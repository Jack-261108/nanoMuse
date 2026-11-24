package io.github.nanomuse.hands

/**
 * 小窗直接操作的口子：坐标是画面上 0..1 的归一化值，实现负责换算成虚拟屏的逻辑坐标并注入。
 * 调用方负责切到后台线程：注入一条笔迹会按点数逐段等待。触摸、滑动和返回键只在
 * 用户已经接管时生效；第一次触摸先申请接管，Agent 的当前动作结束后才进入手动状态。
 */
interface PipInput {
    val control: HandsControl

    /** 申请接管；不会把这次触摸当成业务点击。 */
    fun requestControl()

    /** 交回给 Agent；Agent 会重新观察后再继续。 */
    fun handBack()

    /** 点一下。 */
    fun tap(fx: Float, fy: Float)

    /** 按 [points] 的顺序滑一笔，整体用 [durationMs] 毫秒。 */
    fun swipe(points: List<Pair<Float, Float>>, durationMs: Long)

    /** 在虚拟屏上按返回键。 */
    fun back()
}
