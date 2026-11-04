package io.github.nanomuse.shell

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 守护进程侧主屏监视器：在 Agent 动作前后一小段时间内记录主屏顶层任务变动 ([MainScreenLog])。
 * 当应用从后台启动或弹窗默认放置于主屏时，协助客户端识别并拉回。
 *
 * 监听 ITaskStackListener 回调作为任务栈变化信号；若无法注册则降级为固定周期轮询 ([POLL_MS])。
 */
class MainScreenWatch(
    private val taskManager: () -> Any,
    /** 此刻所有屏幕上的任务，按最近使用排序。 */
    private val tasks: () -> List<MainScreenLog.Task>,
) {
    private val log = MainScreenLog()
    private val thread = HandlerThread("nanomuse-main-watch").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var until = 0L
    @Volatile private var listening: Boolean? = null
    private val queued = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)
    private val signal = StackSignal()

    /** 从当前起监视 [windowMs]；返回当前主屏顶层任务 ID (无任务为 -1)。 */
    fun watch(windowMs: Int): Int {
        val now = SystemClock.uptimeMillis()
        val top = synchronized(log) {
            scanLocked(now, record = now < until)
            until = maxOf(until, now + windowMs.coerceIn(0, MAX_WINDOW_MS))
            log.topTaskId
        }
        if (listening == null) listening = register()
        if (listening == false && polling.compareAndSet(false, true)) handler.post(::poll)
        return top
    }

    /**
     * 获取 [since] 之后主屏冒到顶层的任务记录，带上最新屏幕与监听模式。
     */
    fun arrivals(since: Long): Bundle = synchronized(log) {
        val now = SystemClock.uptimeMillis()
        if (now < until + GRACE_MS) scanLocked(now, record = true)
        toBundle(log.since(since), log::displayOf, listening == true)
    }

    fun close() {
        if (listening == true) runCatching {
            taskManager().javaClass.methods.first { it.name == "unregisterTaskStackListener" }.invoke(taskManager(), listener())
        }
        listening = null
        thread.quitSafely()
    }

    private fun scanLocked(now: Long, record: Boolean) {
        val list = try { tasks() } catch (t: Throwable) { Log.w(TAG, "cannot list tasks", t); return }
        log.scan(list, now, record)
    }

    private fun scan() {
        val now = SystemClock.uptimeMillis()
        if (now >= until) return
        synchronized(log) { scanLocked(now, record = true) }
    }

    private fun onSignal() {
        if (SystemClock.uptimeMillis() >= until) return
        if (queued.compareAndSet(false, true)) handler.postDelayed({ queued.set(false); scan() }, COALESCE_MS)
    }

    private fun poll() {
        if (SystemClock.uptimeMillis() >= until) { polling.set(false); return }
        scan()
        handler.postDelayed(::poll, POLL_MS)
    }

    @SuppressLint("PrivateApi")
    private fun listener(): Any =
        Class.forName("android.app.ITaskStackListener\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, signal)!!

    private fun register(): Boolean = try {
        val am = taskManager()
        am.javaClass.methods.first { it.name == "registerTaskStackListener" && it.parameterTypes.size == 1 }.invoke(am, listener())
        Log.i(TAG, "task stack listener registered")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "task stack listener unavailable, polling instead", t)
        false
    }

    private inner class StackSignal : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                onSignal()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        private const val TAG = "NanoMuseShell"
        private const val DESCRIPTOR = "android.app.ITaskStackListener"
        const val POLL_MS = 100L
        private const val COALESCE_MS = 30L
        private const val GRACE_MS = 2_000L
        private const val MAX_WINDOW_MS = 30_000

        const val KEY_TASK = "task"
        const val KEY_PACKAGE = "package"
        const val KEY_USER = "user"
        const val KEY_AT = "at"
        const val KEY_FROM = "from"
        const val KEY_SAME_TASK = "sameTask"
        const val KEY_STANDARD = "standard"
        const val KEY_ACTIVITY = "activity"
        const val KEY_NOW = "now"
        const val KEY_LISTENING = "listening"

        fun toBundle(list: List<MainScreenLog.Arrival>, now: (Int) -> Int, listening: Boolean): Bundle = Bundle().apply {
            putIntArray(KEY_TASK, list.map { it.taskId }.toIntArray())
            putStringArray(KEY_PACKAGE, list.map { it.packageName }.toTypedArray())
            putIntArray(KEY_USER, list.map { it.userId }.toIntArray())
            putLongArray(KEY_AT, list.map { it.at }.toLongArray())
            putIntArray(KEY_FROM, list.map { it.from }.toIntArray())
            putBooleanArray(KEY_SAME_TASK, list.map { it.sameTask }.toBooleanArray())
            putBooleanArray(KEY_STANDARD, list.map { it.standard }.toBooleanArray())
            putStringArray(KEY_ACTIVITY, list.map { it.topActivity }.toTypedArray())
            putIntArray(KEY_NOW, list.map { now(it.taskId) }.toIntArray())
            putBoolean(KEY_LISTENING, listening)
        }

        fun parseArrivals(b: Bundle): List<MainScreenSpill.Arrival> {
            val tasks = b.getIntArray(KEY_TASK) ?: return emptyList()
            val packages = b.getStringArray(KEY_PACKAGE)
            val users = b.getIntArray(KEY_USER)
            val at = b.getLongArray(KEY_AT)
            val from = b.getIntArray(KEY_FROM)
            val same = b.getBooleanArray(KEY_SAME_TASK)
            val standard = b.getBooleanArray(KEY_STANDARD)
            val activity = b.getStringArray(KEY_ACTIVITY)
            val now = b.getIntArray(KEY_NOW)
            if (packages == null || users == null || at == null || from == null || same == null || standard == null || now == null) return emptyList()
            return tasks.indices.map { i ->
                MainScreenSpill.Arrival(tasks[i], packages[i], users[i], at[i], from[i], same[i], standard[i], now[i], activity?.getOrNull(i))
            }
        }
    }
}
