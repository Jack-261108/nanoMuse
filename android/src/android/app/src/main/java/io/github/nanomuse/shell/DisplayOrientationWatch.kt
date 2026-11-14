package io.github.nanomuse.shell

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 只在有虚拟屏时监听任务方向与增删；不读应用内容，不改变任务焦点。
 * 注册不上任务监听时退回每 [POLL_MS] 扫一次，只在 [active]（还有我们的屏）为真时扫；
 * 屏放完后由 [close] 停下。同一批回调只排一次扫描。
 */
@SuppressLint("PrivateApi")
internal class DisplayOrientationWatch(
    private val taskManager: Any,
    private val active: () -> Boolean,
    private val onRequestedOrientation: (taskId: Int, orientation: Int) -> Unit = { _, _ -> },
    private val onTaskRemoved: (taskId: Int) -> Unit = {},
    private val sync: () -> Unit,
) {
    private val thread = HandlerThread("nanomuse-display-orientation").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    private val queued = AtomicBoolean(false)
    private val work = Runnable {
        queued.set(false)
        if (closed) return@Runnable
        runCatching(sync).onFailure { Log.w(TAG, "display orientation scan failed", it) }
    }
    private val requestedOrientationTransactions = setOfNotNull(
        transactionCode("onActivityRequestedOrientationChanged"),
        transactionCode("onTaskRequestedOrientationChanged"),
    )
    private val taskRemovedTransaction = transactionCode("onTaskRemoved")
    private val signal = object : Binder() {
        init { attachInterface(null, TASK_LISTENER) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in requestedOrientationTransactions) {
                runCatching {
                    data.enforceInterface(TASK_LISTENER)
                    onRequestedOrientation(data.readInt(), data.readInt())
                }.onFailure { Log.w(TAG, "display orientation callback rejected", it) }
                request()
                return true
            }
            if (code == taskRemovedTransaction) {
                runCatching {
                    data.enforceInterface(TASK_LISTENER)
                    onTaskRemoved(data.readInt())
                }.onFailure { Log.w(TAG, "task removal callback rejected", it) }
                request()
                return true
            }
            if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                request()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }
    private val listener = runCatching {
        Class.forName("android.app.ITaskStackListener\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, signal)
    }.getOrNull()
    private val listening = listener != null && runCatching {
        taskManager.javaClass.methods.first { it.name == "registerTaskStackListener" }.invoke(taskManager, listener)
    }.isSuccess
    private val poll = object : Runnable {
        override fun run() {
            if (closed || !active()) return
            request()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init { if (listening) request() else handler.post(poll) }

    /** 一批事件只排一次扫描；后来的事件不推迟已排好的扫描。 */
    private fun request() {
        if (closed) return
        if (queued.compareAndSet(false, true)) handler.postDelayed(work, COALESCE_MS)
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (listening) runCatching {
            taskManager.javaClass.methods.first { it.name == "unregisterTaskStackListener" }.invoke(taskManager, listener)
        }.onFailure { Log.w(TAG, "unregisterTaskStackListener failed", it) }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "NanoMuseShell"
        const val POLL_MS = 500L
        const val COALESCE_MS = 50L
        const val TASK_LISTENER = "android.app.ITaskStackListener"

        fun transactionCode(callback: String): Int? = runCatching {
            Class.forName("android.app.ITaskStackListener\$Stub")
                .getDeclaredField("TRANSACTION_$callback").apply { isAccessible = true }.getInt(null)
        }.getOrNull()
    }
}
