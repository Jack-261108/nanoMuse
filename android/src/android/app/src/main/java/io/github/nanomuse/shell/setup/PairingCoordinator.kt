package io.github.nanomuse.shell.setup

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 滑动时限器: 从最后一次 extend() 起算 lengthMs。
 */
internal class SlidingDeadline(private val lengthMs: Long, private val now: () -> Long) {
    @Volatile private var until = 0L

    fun extend() {
        until = now() + lengthMs
    }

    suspend fun await(job: Job): Boolean {
        while (true) {
            val left = until - now()
            if (left <= 0) return false
            if (withTimeoutOrNull(left) { job.join() } != null) return true
        }
    }
}

/**
 * 配对流程总协调器:
 * 调度 mDNS 配对端口探测、无障碍自动读码 (PairingCodeReader) 与前台 RemoteInput 通知栏输入。
 */
class PairingCoordinator(context: Context, private val control: BuiltinShellControl) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: Job? = null
    private var pairingJob: Job? = null
    private val session = PairingSession()
    private val deadline = SlidingDeadline(WINDOW_MS) { SystemClock.elapsedRealtime() }

    @Volatile private var shown: PairingNotice? = null
    private val _repairing = MutableStateFlow(false)
    val repairing: StateFlow<Boolean> = _repairing
    @Volatile private var submitted = false

    fun startRepair() { _repairing.value = true }
    fun cancelRepair() { _repairing.value = false }

    suspend fun pair(code: String, port: Int?, sessionId: Long) = coroutineScope {
        currentCoroutineContext().ensureActive()
        val job = synchronized(this@PairingCoordinator) {
            if (!session.accepts(sessionId)) return@coroutineScope
            pairingJob?.cancel()
            launch(start = CoroutineStart.LAZY) {
                currentCoroutineContext().ensureActive()
                control.startPairing(code, port)
            }.also {
                pairingJob = it
                _repairing.value = false
                submitted = false
                it.start()
            }
        }
        job.join()
    }

    @Synchronized
    fun begin() {
        deadline.extend()
        control.startDiscovery()
        if (watch?.isActive == true) return
        val token = session.begin()
        watch = scope.launch {
            val reader = launch { PairingCodeReader.run(app, control, this@PairingCoordinator, token) }
            val notices = launch {
                combine(control.status, control.pairingDiscovery, _repairing) { _, _, _ ->
                    val d = control.pairingDiscovery.value
                    val pending = submitted
                    val r = _repairing.value
                    PairingNotice.follow(control.status.value, d, r, pending)
                }
                    .distinctUntilChanged()
                    .transformWhile { emit(it.first); !it.second }
                    .collectLatest { notice -> notice?.let { post(it, token) } }
            }
            if (!deadline.await(notices)) {
                notices.cancel()
                synchronized(this@PairingCoordinator) {
                    if (session.accepts(token)) cancelNotice()
                }
            }
            reader.cancel()
            synchronized(this@PairingCoordinator) {
                if (session.accepts(token)) {
                    session.end()
                    control.stopDiscovery()
                }
            }
        }
    }

    private suspend fun post(notice: PairingNotice, token: Long) {
        delay(PairingNotice.delayBefore(notice, autoRead = MinisAccessibilityService.getInstance() != null))
        show(notice, token)
        var sent = 0
        while (true) {
            delay(PairingNotice.realertAfter(notice, sent) ?: return)
            if (shown != notice) return
            show(notice, token)
            sent++
        }
    }

    @Synchronized
    private fun show(notice: PairingNotice, token: Long) {
        if (!session.accepts(token)) return
        shown = notice
        PairingNotifier.show(app, notice, token)
        if (notice is PairingNotice.Done) {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    Toast.makeText(app, R.string.nm_hands_virtual_screen_pair_success_toast, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun cancelNotice() {
        shown = null
        PairingNotifier.cancel(app)
    }

    @Synchronized
    fun end() {
        session.end()
        watch?.cancel()
        watch = null
        pairingJob?.cancel()
        pairingJob = null
        submitted = false
        _repairing.value = false
        control.stopDiscovery()
        cancelNotice()
    }

    @Synchronized
    fun submit(raw: String, port: Int?, token: Long): Boolean {
        if (!session.accepts(token)) return false
        val code = PairingCode.normalize(raw)
        if (code == null) {
            show(PairingNotice.EnterCode(port, "Please enter a valid 6-digit number."), token)
            return false
        }
        submitted = true
        deadline.extend()
        show(PairingNotice.Working, token)
        pairingJob?.cancel()
        pairingJob = scope.launch(start = CoroutineStart.LAZY) {
            currentCoroutineContext().ensureActive()
            if (synchronized(this@PairingCoordinator) {
                if (!session.accepts(token)) false else {
                    _repairing.value = false
                    submitted = false
                    true
                }
            }) control.startPairing(code, port)
        }.also { it.start() }
        return true
    }

    private companion object {
        const val WINDOW_MS = 10 * 60_000L
    }
}
