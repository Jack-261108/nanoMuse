package io.github.nanomuse.shell

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.hands.HandsDisplayBridge
import io.github.nanomuse.hands.IHandsDisplay
import io.github.nanomuse.shell.recovery.PermissionCommands
import io.github.nanomuse.shell.setup.BuiltinShellManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One live helper hosting both INanoMuseShell and (under uid 2000 on API 34+) IHandsDisplay. */
object SharedShell {
    private const val TAG = "SharedShell"
    private val _shell = MutableStateFlow<INanoMuseShell?>(null)
    val shell: StateFlow<INanoMuseShell?> = _shell.asStateFlow()

    @Volatile internal var wirelessDebuggingProbe: (() -> Boolean)? = null
    @Volatile internal var shizukuReadyProbe: (() -> Boolean)? = null

    private val store = HelperSessionStore<INanoMuseShell, IHandsDisplay>(
        isShellAlive = { it.asBinder().isBinderAlive },
        pingShell = { it.asBinder().pingBinder() },
        shellIdentity = { runCatching { it.identity() }.getOrNull() },
        destroyShell = { it.destroy() },
        isDisplayAlive = { it.asBinder().isBinderAlive },
        pingDisplay = { it.asBinder().pingBinder() },
        displayIdentity = { runCatching { it.identity() }.getOrNull() },
        destroyDisplay = { it.destroy() },
        linkShellDeath = { s, onDead -> linkDeath(s.asBinder(), onDead) },
        linkDisplayDeath = { d, onDead -> linkDeath(d.asBinder(), onDead) },
        sameBinder = { s, d -> s.asBinder() === d.asBinder() },
        wirelessEnabled = { wirelessDebuggingProbe?.invoke() != false },
        shizukuReady = { shizukuReadyProbe?.invoke() != false },
        onStateChanged = { session, displayReady ->
            _shell.value = session?.shell
            HandsDisplayBridge.updateReady(displayReady)
        },
    )

    private fun linkDeath(binder: IBinder, onDead: () -> Unit): (() -> Unit)? {
        val recipient = IBinder.DeathRecipient {
            AppLogger.warning(TAG, "shell helper died")
            onDead()
        }
        return try {
            binder.linkToDeath(recipient, 0)
            if (!binder.isBinderAlive) {
                runCatching { binder.unlinkToDeath(recipient, 0) }
                null
            } else {
                { runCatching { binder.unlinkToDeath(recipient, 0) } }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isReady(): Boolean = store.isShellReady()

    internal fun currentDisplay(): IHandsDisplay? = store.currentDisplay()

    internal fun hasDisplayBinder(): Boolean = store.hasDisplayBinder()

    internal fun refreshDisplayReadiness() {
        store.refreshReadiness()
    }

    fun prepareStart(nonce: String) {
        store.prepareStart(nonce, ttlMs = null, origin = HelperOrigin.MANUAL)
    }

    internal fun prepareStart(nonce: String, ttlMs: Long?, origin: HelperOrigin) {
        store.prepareStart(nonce, ttlMs = ttlMs, origin = origin)
    }

    fun cancelStart(nonce: String? = null) {
        store.cancelStart(nonce)
    }

    fun setShell(instance: INanoMuseShell?, pid: Int = -1, uid: Int = -1, nonce: String? = null): Boolean {
        if (instance == null) {
            store.detachOnly()
            return true
        }
        val accepted = store.attach(
            shell = instance,
            display = null,
            pid = pid,
            uid = uid,
            nonce = nonce,
            wirelessReported = false,
            verifyIdentity = false,
        )
        if (accepted) AppLogger.info(TAG, "accepted shell helper (pid=$pid, uid=$uid)")
        return accepted
    }

    internal fun attach(
        shell: INanoMuseShell,
        display: IHandsDisplay?,
        pid: Int,
        uid: Int,
        nonce: String?,
        wireless: Boolean,
    ): Boolean {
        val accepted = store.attach(
            shell = shell,
            display = display,
            pid = pid,
            uid = uid,
            nonce = nonce,
            wirelessReported = wireless,
            verifyIdentity = true,
        )
        if (accepted) {
            AppLogger.info(
                TAG,
                "accepted shell helper (pid=$pid, uid=$uid, display=${display != null}, wireless=$wireless)",
            )
        }
        return accepted
    }

    /** All backends share the manager's start, stop and retry state. */
    fun startHelper(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        BuiltinShellManager.instance(context).need()
        return isReady()
    }

    fun getAdbCommand(context: Context): String {
        val nonce = HelperHandoff.generateNonce()
        check(BuiltinShellManager.instance(context).prepareManualStart(nonce)) { "helper startup is unavailable" }
        return command(context, nonce)
    }

    internal fun command(context: Context, nonce: String): String {
        val apk = PermissionCommands.quote(context.applicationInfo.sourceDir)
        val pkg = PermissionCommands.quote(context.packageName)
        val process = PermissionCommands.quote("${context.packageName}:helper")
        return "CLASSPATH=$apk nohup app_process /system/bin --nice-name=$process " +
            "io.github.nanomuse.shell.HelperProcess $pkg ${Process.myUid()} '$nonce' </dev/null >/dev/null 2>&1 &"
    }

    fun release() {
        store.release()
    }
}
