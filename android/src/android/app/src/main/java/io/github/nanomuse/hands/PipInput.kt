package io.github.nanomuse.hands

/**
 * 小窗直接操作的口子：坐标是画面上 0..1 的归一化值，实现负责换算成虚拟屏的逻辑坐标并注入。
 * 调用方负责切到后台线程：注入一条笔迹会按点数逐段等待。
 */
interface PipInput {
    /** 点一下。 */
    fun tap(fx: Float, fy: Float)

    /** 按 [points] 的顺序滑一笔，整体用 [durationMs] 毫秒。 */
    fun swipe(points: List<Pair<Float, Float>>, durationMs: Long)

    /** 在虚拟屏上按返回键。 */
    fun back()
}
