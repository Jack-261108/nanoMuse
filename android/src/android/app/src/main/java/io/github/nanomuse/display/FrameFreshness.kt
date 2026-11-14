package io.github.nanomuse.display

/**
 * 启动一个 App 之后交给模型的画面必须来自该次启动之后的渲染帧。
 * 虚拟屏生产者的帧时间戳（[AgentDisplay.lastFrameAt]）与启动时记下的 `System.nanoTime()` 同源，
 * 两者比较即可排除启动前已入队、启动后才读取的旧帧。
 * 等待预算另用一只毫秒时钟：两只时钟各自单调，只在本函数内分别使用，不需要同源。
 * 时钟与等待由调用方注入，便于在 JVM 上测。
 */
internal object FrameFreshness {
    /** 等这次启动之后第一帧的上限：冷启动首帧与启动页动画绝大多数在这之内。 */
    const val FRAME_WAIT_MS = 1_500L
    const val POLL_MS = 60L

    /**
     * 等到比 [since] 晚的帧返回 true；到 [timeoutMs] 仍没有（或一直没有过帧）返回 false。
     * [nowMs] 是毫秒预算时钟（`SystemClock.uptimeMillis`），[frameAt] 与 [since] 是纳秒的生产者时钟。
     * [sleep] 返回 false 表示被打断（例如用户按了 Stop），立即结束。
     */
    fun awaitNewFrame(
        nowMs: () -> Long,
        sleep: (Long) -> Boolean,
        frameAt: () -> Long?,
        since: Long,
        timeoutMs: Long = FRAME_WAIT_MS,
    ): Boolean {
        val deadline = nowMs() + timeoutMs
        while (true) {
            // 没有帧时按 since 本身算，不用 0：nanoTime 的零点任意，0 未必就比 since 早。
            if ((frameAt() ?: since) > since) return true
            val left = deadline - nowMs()
            if (left <= 0) return false
            if (!sleep(minOf(POLL_MS, left))) return false
        }
    }
}
