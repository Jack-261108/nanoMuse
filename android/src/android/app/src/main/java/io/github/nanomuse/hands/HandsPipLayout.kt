package io.github.nanomuse.hands

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 悬浮画中画小窗的纯逻辑几何与手势物理（参考 Mika [PipSizing] 与 [PipPhysics]），
 * 脱离 Android View 依赖以便在 JVM 上直接单测。
 */
internal object HandsPipLayout {
    enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

    data class FloatSize(val width: Float, val height: Float)
    data class IntSize(val width: Int, val height: Int)

    data class ScreenArea(
        val width: Int,
        val height: Int,
        val insetLeft: Int = 0,
        val insetTop: Int = 0,
        val insetRight: Int = 0,
        val insetBottom: Int = 0,
    )

    private const val DEFAULT_ASPECT = 2.16f
    private const val DECELERATION = 0.998f
    private const val MAX_DT = 0.032f
    private const val REST_DISTANCE = 0.5f
    private const val REST_VELOCITY = 5f

    /**
     * 按画面长宽比（高 / 宽）拟合外框内容尺寸：
     * 画面总能完整放进可用区域；超出任一方向时等比缩小，不裁剪也不拉伸。
     */
    fun fit(
        baseWidth: Float,
        aspect: Float,
        availableWidth: Float,
        availableHeight: Float,
        inset: Float,
    ): FloatSize {
        val ratio = aspect.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_ASPECT
        val width = availableWidth.coerceAtLeast(1f)
        val height = availableHeight.coerceAtLeast(1f)
        val padding = min(inset.coerceAtLeast(0f), min(width / 2f, height / 2f))
        val content = min(
            (baseWidth - padding).coerceAtLeast(0.1f),
            min(width - padding, (height - padding) / ratio),
        )
        return FloatSize(content + padding, content * ratio + padding)
    }

    /** 计算整个悬浮窗的目标宽高（含外框内边距与顶部操控条高度）。 */
    fun windowSize(
        expanded: Boolean,
        landscapeFrame: Boolean,
        aspect: Float,
        area: ScreenArea,
        marginPx: Float,
        insetTotalPx: Float,
        chromeHeightPx: Float,
        collapsedPortraitWidthPx: Float,
        expandedPortraitWidthPx: Float,
        collapsedLandscapeWidthPx: Float,
        expandedLandscapeWidthPx: Float,
    ): IntSize {
        val safeW = (area.width - area.insetLeft - area.insetRight - marginPx * 2f).coerceAtLeast(1f)
        val safeH = (area.height - area.insetTop - area.insetBottom - marginPx * 2f).coerceAtLeast(1f)
        val chrome = chromeHeightPx.coerceAtLeast(0f).coerceAtMost((safeH - 1f).coerceAtLeast(0f))
        val contentAvailableH = (safeH - chrome).coerceAtLeast(1f)
        val base = when {
            landscapeFrame -> if (expanded) expandedLandscapeWidthPx else collapsedLandscapeWidthPx
            else -> if (expanded) expandedPortraitWidthPx else collapsedPortraitWidthPx
        }
        val fitted = fit(base, aspect, safeW, contentAvailableH, insetTotalPx)
        return IntSize(
            width = fitted.width.roundToInt().coerceAtLeast(1),
            height = (fitted.height + chrome).roundToInt().coerceAtLeast(1),
        )
    }

    /** 计算实际显示区域内、严格匹配画面长宽比的图像框尺寸（不裁切、无多余黑边）。 */
    fun fittedFrameSize(frameWidth: Int, frameHeight: Int, stageWidth: Int, stageHeight: Int): IntSize {
        if (stageWidth <= 0 || stageHeight <= 0) return IntSize(maxOf(0, stageWidth), maxOf(0, stageHeight))
        if (frameWidth <= 0 || frameHeight <= 0) return IntSize(stageWidth, stageHeight)
        val a = frameWidth.toFloat() / frameHeight
        val w = if (stageWidth / a <= stageHeight) stageWidth.toFloat() else stageHeight * a
        val h = w / a
        return IntSize(
            width = w.roundToInt().coerceIn(1, stageWidth),
            height = h.roundToInt().coerceIn(1, stageHeight),
        )
    }

    /** 小窗在屏幕上的可停靠安全区边界；空间过小时优先保住左、上边界，不钻进系统栏。 */
    data class Bounds(
        val areaWidth: Float,
        val areaHeight: Float,
        val pipWidth: Float,
        val pipHeight: Float,
        val margin: Float,
        val insetLeft: Float = 0f,
        val insetTop: Float = 0f,
        val insetRight: Float = 0f,
        val insetBottom: Float = 0f,
    ) {
        val left: Float get() = insetLeft + margin
        val right: Float get() = maxOf(left, areaWidth - insetRight - pipWidth - margin)
        val top: Float get() = insetTop + margin
        val bottom: Float get() = maxOf(top, areaHeight - insetBottom - pipHeight - margin)

        fun position(corner: Corner): Pair<Float, Float> = when (corner) {
            Corner.TopStart -> left to top
            Corner.TopEnd -> right to top
            Corner.BottomStart -> left to bottom
            Corner.BottomEnd -> right to bottom
        }

        fun clamp(x: Float, y: Float): Pair<Float, Float> = x.coerceIn(left, right) to y.coerceIn(top, bottom)

        fun snapCorner(x: Float, y: Float, vx: Float, vy: Float): Corner {
            val px = x + project(vx)
            val py = y + project(vy)
            return Corner.entries.minBy { c ->
                val (cx, cy) = position(c)
                hypot(cx - px, cy - py)
            }
        }
    }

    /** 惯性投射距离：速度 [velocity] (px/s) 按每毫秒衰减 0.998 倍滑动到停。 */
    fun project(velocity: Float): Float = (velocity / 1000f) * DECELERATION / (1f - DECELERATION)

    data class Motion(val position: Float, val velocity: Float)

    /** 临界阻尼弹簧（无过冲、无回弹，松手继承速度平滑归角）。 */
    class Spring(response: Float = 0.4f, dampingRatio: Float = 1f) {
        private val stiffness = (2 * PI / response).pow(2).toFloat()
        private val damping = (4 * PI * dampingRatio / response).toFloat()

        fun step(m: Motion, target: Float, dt: Float): Motion {
            val t = dt.coerceIn(0f, MAX_DT)
            val v = m.velocity + (-stiffness * (m.position - target) - damping * m.velocity) * t
            return Motion(m.position + v * t, v)
        }

        fun settled(m: Motion, target: Float): Boolean =
            abs(m.position - target) < REST_DISTANCE && abs(m.velocity) < REST_VELOCITY
    }

    /**
     * 帧更新时是否应该立刻同步窗口尺寸/位置：
     * 拖拽、尺寸过渡动画或松手归角弹簧进行期间绝不覆写窗口参数，避免与动画抢写导致闪烁或锁死。
     */
    fun shouldSyncFrameResize(
        isDragging: Boolean,
        isAnimatingSize: Boolean,
        isSettling: Boolean,
        currentWidth: Int,
        currentHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        currentX: Int,
        currentY: Int,
        clampedX: Int,
        clampedY: Int,
    ): Boolean {
        if (isDragging || isAnimatingSize || isSettling) return false
        return currentWidth != targetWidth ||
            currentHeight != targetHeight ||
            currentX != clampedX ||
            currentY != clampedY
    }

    /** 直接操作模式开启且按下点落在画框内部时，才把触摸序列交给虚拟屏注入口。 */
    fun shouldPassTouchToFrame(operateNow: Boolean, insideFrame: Boolean): Boolean =
        operateNow && insideFrame

    enum class FrameTouchAction {
        /** 未开启直接操作或已关闭：交回窗口处理拖拽与展开/收起。 */
        PASS_TO_WINDOW,
        /** 首次触摸画框：申请接管并吞掉本次触摸序列，不当成业务点击。 */
        REQUEST_CONTROL_AND_CONSUME,
        /** 正在等待当前动作结束：吞掉触摸，防止穿透到窗口或误触后台应用。 */
        CONSUME_WAITING,
        /** 已进入手动接管态：把点按与滑动派发到虚拟屏。 */
        DISPATCH_MANUAL,
    }

    /** 根据当前直接操作开关与控制权状态，决定画框上一次触摸序列的流转。 */
    fun frameTouchAction(operateNow: Boolean, controlState: HandsControl.State?): FrameTouchAction {
        if (!operateNow || controlState == null) return FrameTouchAction.PASS_TO_WINDOW
        return when (controlState) {
            HandsControl.State.Automatic -> FrameTouchAction.REQUEST_CONTROL_AND_CONSUME
            HandsControl.State.Requesting -> FrameTouchAction.CONSUME_WAITING
            HandsControl.State.Manual -> FrameTouchAction.DISPATCH_MANUAL
            HandsControl.State.Closed -> FrameTouchAction.PASS_TO_WINDOW
        }
    }

    data class ControlBarChrome(
        val visible: Boolean,
        val backEnabled: Boolean,
        val hintClickable: Boolean,
        val hintEnabled: Boolean,
    )

    /**
     * 顶部控制条的交互状态。
     * 中间提示区始终保持可点击以吞掉触摸，避免等待态误点穿透到根容器触发收起窗口。
     */
    fun controlBarChrome(operateNow: Boolean, controlState: HandsControl.State): ControlBarChrome {
        val hintEnabled = operateNow &&
            (controlState == HandsControl.State.Automatic || controlState == HandsControl.State.Manual)
        return ControlBarChrome(
            visible = operateNow,
            backEnabled = operateNow && controlState == HandsControl.State.Manual,
            hintClickable = true,
            hintEnabled = hintEnabled,
        )
    }

    enum class ControlHintTapAction {
        /** 自动态下点击提示条：申请接管。 */
        REQUEST_CONTROL,
        /** 手动态下点击提示条：交回给智能体。 */
        HAND_BACK,
        /** 等待或已关闭态下点击提示条：吞掉触摸但不改变状态。 */
        NONE,
    }

    /** 根据当前控制权状态决定点击顶部控制条中间提示区时的动作。 */
    fun controlHintTapAction(controlState: HandsControl.State?): ControlHintTapAction = when (controlState) {
        HandsControl.State.Automatic -> ControlHintTapAction.REQUEST_CONTROL
        HandsControl.State.Manual -> ControlHintTapAction.HAND_BACK
        HandsControl.State.Requesting, HandsControl.State.Closed, null -> ControlHintTapAction.NONE
    }

    /** 窗口隐藏、切换注入口或关闭「直接操作小画面」开关时，若仍处于申请/手动接管态则必须交回。 */
    fun shouldHandBack(controlState: HandsControl.State?): Boolean =
        controlState == HandsControl.State.Requesting || controlState == HandsControl.State.Manual
}
