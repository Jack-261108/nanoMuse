package io.github.nanomuse.hands

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.hands.HandsPipLayout.Bounds
import io.github.nanomuse.hands.HandsPipLayout.Corner
import io.github.nanomuse.hands.HandsPipLayout.IntSize
import io.github.nanomuse.hands.HandsPipLayout.Motion
import io.github.nanomuse.hands.HandsPipLayout.ScreenArea
import io.github.nanomuse.hands.HandsPipLayout.Spring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Mika 风格的后台虚拟屏幕独立画中画（Picture-in-Picture）小窗。
 *
 * - 比例：通过 [HandsPipLayout] 严格保持虚拟屏真实长宽比，横竖屏均完整展示、无裁切失真
 * - 质感：黑曜石磨砂底色、玻璃外框留白、1.5dp 高光微光边框与动态圆角同步
 * - 状态：工作时泛一圈深邃青蓝微光，需要用户时呼吸橙色光晕
 * - 手势：1:1 跟手拖拽，松手按速度投射落点并以临界阻尼弹簧平滑吸附到最近的角
 * - 缩放：点击在迷你悬浮窗与沉浸大窗之间丝滑切换，再次点击稳定缩回原样；
 *   开启「直接操作小画面」时在顶部展示接管状态（← / 接管或交回 / ✕）
 */
class HandsPipWindow(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: PipRootLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var cardView: FrameLayout? = null
    private var cardBackground: GradientDrawable? = null
    private var contentColumn: LinearLayout? = null
    private var controlBar: LinearLayout? = null
    private var barBackBtn: TextView? = null
    private var barHintView: TextView? = null
    private var barCloseBtn: TextView? = null
    private var frameStage: AspectFrameStage? = null
    private var frameBox: FrameLayout? = null
    private var imageView: ImageView? = null
    private var liveBadge: TextView? = null
    private var closeWrap: FrameLayout? = null
    private var glowBorder: PipGlowView? = null

    private var currentFrame: Bitmap? = null
    private var isExpanded: Boolean = false
    private var isNeedsYou: Boolean = false
    private var isVisible: Boolean = true
    private var pipInput: PipInput? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var settingJob: Job? = null
    private var controlJob: Job? = null

    private var currentCorner: Corner = Corner.BottomEnd
    private var currentOuterRadiusPx: Float = 0f
    private var currentInnerRadiusPx: Float = 0f
    private var sizeAnimator: ValueAnimator? = null
    private var settleCallback: Choreographer.FrameCallback? = null
    private var isDragging: Boolean = false

    var onDismiss: (() -> Unit)? = null

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun dpF(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    private fun stillMotion(): Boolean =
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

    private fun screenArea(): ScreenArea {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val area = runCatching {
                val m = wm.currentWindowMetrics
                val i = m.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                ScreenArea(
                    width = m.bounds.width(),
                    height = m.bounds.height(),
                    insetLeft = i.left,
                    insetTop = i.top,
                    insetRight = i.right,
                    insetBottom = i.bottom,
                )
            }.getOrNull()
            if (area != null && area.width > 0 && area.height > 0) return area
        }
        val dm: DisplayMetrics = context.resources.displayMetrics
        return ScreenArea(
            width = dm.widthPixels.coerceAtLeast(1),
            height = dm.heightPixels.coerceAtLeast(1),
            insetTop = statusBarHeight(),
            insetBottom = navigationBarHeight(),
        )
    }

    private fun defaultAspect(): Float {
        val dm = context.resources.displayMetrics
        return if (dm.widthPixels > 0 && dm.heightPixels > 0) dm.heightPixels.toFloat() / dm.widthPixels else 2.16f
    }

    private fun currentAspect(): Float =
        currentFrame?.let { if (it.width > 0 && it.height > 0) it.height.toFloat() / it.width else null } ?: defaultAspect()

    private fun isLandscapeFrame(): Boolean =
        currentFrame?.let { it.width > it.height } == true

    private fun insetPerSidePx(expanded: Boolean = isExpanded): Int =
        dp(if (expanded) EXPANDED_INSET_DP else COLLAPSED_INSET_DP)

    private fun chromeHeightPx(operate: Boolean = operateNow()): Int =
        if (operate) dp(CONTROL_BAR_HEIGHT_DP) else 0

    private fun computeTargetSize(area: ScreenArea = screenArea()): IntSize {
        val insetTotal = insetPerSidePx() * 2f
        val chrome = chromeHeightPx().toFloat()
        return HandsPipLayout.windowSize(
            expanded = isExpanded,
            landscapeFrame = isLandscapeFrame(),
            aspect = currentAspect(),
            area = area,
            marginPx = dpF(SCREEN_MARGIN_DP.toFloat()),
            insetTotalPx = insetTotal,
            chromeHeightPx = chrome,
            collapsedPortraitWidthPx = dpF(COLLAPSED_WIDTH_DP.toFloat()),
            expandedPortraitWidthPx = dpF(EXPANDED_WIDTH_DP.toFloat()),
            collapsedLandscapeWidthPx = dpF(COLLAPSED_LANDSCAPE_WIDTH_DP.toFloat()),
            expandedLandscapeWidthPx = dpF(EXPANDED_LANDSCAPE_WIDTH_DP.toFloat()),
        )
    }

    private fun computeBounds(pipWidth: Int, pipHeight: Int, area: ScreenArea = screenArea()): Bounds =
        Bounds(
            areaWidth = area.width.toFloat(),
            areaHeight = area.height.toFloat(),
            pipWidth = pipWidth.toFloat(),
            pipHeight = pipHeight.toFloat(),
            margin = dpF(SCREEN_MARGIN_DP.toFloat()),
            insetLeft = area.insetLeft.toFloat(),
            insetTop = area.insetTop.toFloat(),
            insetRight = area.insetRight.toFloat(),
            insetBottom = area.insetBottom.toFloat(),
        )

    private fun targetRadii(expanded: Boolean = isExpanded): Pair<Float, Float> {
        val outer = dpF(if (expanded) EXPANDED_OUTER_RADIUS_DP else COLLAPSED_OUTER_RADIUS_DP)
        val inner = dpF(if (expanded) EXPANDED_INNER_RADIUS_DP else COLLAPSED_INNER_RADIUS_DP)
        return outer to inner
    }

    fun show() {
        if (root != null) return
        val area = screenArea()
        val size = computeTargetSize(area)
        val bounds = computeBounds(size.width, size.height, area)
        val (targetX, targetY) = bounds.position(currentCorner)
        val (outerR, innerR) = targetRadii(isExpanded)
        currentOuterRadiusPx = outerR
        currentInnerRadiusPx = innerR

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val p = WindowManager.LayoutParams(
            size.width, size.height, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = targetX.roundToInt()
            y = targetY.roundToInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        this.params = p

        val rootLayout = PipRootLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            visibility = if (isVisible) View.VISIBLE else View.GONE
        }

        val inset = insetPerSidePx()
        val cardBg = GradientDrawable().apply {
            cornerRadius = outerR
            setColor(CARD_INK)
            setStroke(dp(1), CARD_EDGE)
        }
        val card = FrameLayout(context).apply {
            elevation = dp(10).toFloat()
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, currentOuterRadiusPx)
                }
            }
            background = cardBg
            setPadding(inset, inset, inset, inset)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, currentInnerRadiusPx)
                }
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CONTROL_BAR_INK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(CONTROL_BAR_HEIGHT_DP),
            )
            visibility = if (operateNow()) View.VISIBLE else View.GONE
        }

        val backBtn = TextView(context).apply {
            text = "←"
            contentDescription = context.getString(R.string.nm_hands_pip_back)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener {
                pipInput?.back()
            }
        }

        val hintView = TextView(context).apply {
            text = context.getString(R.string.nm_hands_pip_control_take)
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), 0, dp(2), 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutoSizeTextTypeUniformWithConfiguration(9, 11, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                val input = pipInput ?: return@setOnClickListener
                when (HandsPipLayout.controlHintTapAction(input.control.current)) {
                    HandsPipLayout.ControlHintTapAction.REQUEST_CONTROL -> input.requestControl()
                    HandsPipLayout.ControlHintTapAction.HAND_BACK -> input.handBack()
                    HandsPipLayout.ControlHintTapAction.NONE -> Unit
                }
            }
        }

        val barClose = TextView(context).apply {
            text = "✕"
            contentDescription = context.getString(R.string.nm_hands_preview_collapse)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener {
                setExpanded(false)
            }
        }

        bar.addView(backBtn)
        bar.addView(hintView)
        bar.addView(barClose)

        val stage = AspectFrameStage(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val fBox = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }

        val img = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            currentFrame?.let {
                setImageBitmap(it)
                stage.setFrameSize(it.width, it.height)
            }
        }
        fBox.addView(img)
        setupInputGesture(fBox)
        stage.addView(fBox)

        column.addView(bar)
        column.addView(stage)

        // 左下角微标 "● 实时画面"（非交互，不拦截任何触摸）
        val badge = TextView(context).apply {
            text = "● " + context.getString(R.string.nm_hands_preview_live)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isClickable = false
            isFocusable = false
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(0x99000000.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply {
                leftMargin = dp(6)
                bottomMargin = dp(6)
            }
        }

        // 非直接操作展开态右上角极简收起按钮，外包 40dp 透明热区防止点偏
        val closeCircle = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            val s = dp(26)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(4)
                rightMargin = dp(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x80000000.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        val closeHit = FrameLayout(context).apply {
            contentDescription = context.getString(R.string.nm_hands_preview_collapse)
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(2)
                rightMargin = dp(2)
            }
            visibility = if (isExpanded && !operateNow()) View.VISIBLE else View.GONE
            setOnClickListener {
                setExpanded(false)
            }
            addView(closeCircle)
        }

        // 外沿微光 / 呼吸光晕（位于 card 外部同级，不被内边距挤入）
        val glow = PipGlowView(context).apply {
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        card.addView(column)
        card.addView(badge)
        card.addView(closeHit)
        rootLayout.addView(card)
        rootLayout.addView(glow)

        try {
            wm.addView(rootLayout, p)
            root = rootLayout
            cardView = card
            cardBackground = cardBg
            contentColumn = column
            controlBar = bar
            barBackBtn = backBtn
            barHintView = hintView
            barCloseBtn = barClose
            frameStage = stage
            frameBox = fBox
            imageView = img
            liveBadge = badge
            closeWrap = closeHit
            glowBorder = glow

            glow.setNeedsYou(isNeedsYou)
            refreshChrome(syncRadii = false)

            if (!stillMotion()) {
                rootLayout.alpha = 0f
                rootLayout.scaleX = 0.85f
                rootLayout.scaleY = 0.85f
                rootLayout.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260L)
                    .setInterpolator(OvershootInterpolator(1.1f))
                    .start()
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "pip addView failed: ${t.message}")
        }
    }

    fun updateFrame(frame: Bitmap) {
        val hadFrame = currentFrame != null
        val prevW = currentFrame?.width ?: 0
        val prevH = currentFrame?.height ?: 0
        currentFrame = frame
        imageView?.setImageBitmap(frame)
        if (frame.width != prevW || frame.height != prevH) {
            frameStage?.setFrameSize(frame.width, frame.height)
        }
        if (!hadFrame) refreshChrome()
        updateWindowSizeIfNecessary()
    }

    fun setNeedsYou(needsYou: Boolean) {
        if (isNeedsYou == needsYou) return
        isNeedsYou = needsYou
        glowBorder?.setNeedsYou(needsYou)
        if (needsYou && !isExpanded) {
            // 接管时自动平滑展开，让用户清楚看到正在发生什么
            setExpanded(true)
        }
    }

    fun setVisible(visible: Boolean) {
        isVisible = visible
        root?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun hide() {
        val oldInput = pipInput
        if (oldInput != null && HandsPipLayout.shouldHandBack(oldInput.control.current)) {
            oldInput.handBack()
        }
        pipInput = null
        currentFrame = null
        settingJob?.cancel()
        settingJob = null
        controlJob?.cancel()
        controlJob = null
        cancelSizeAnimator()
        cancelSettle()
        isDragging = false
        val r = root ?: return
        root = null
        params = null
        cardView = null
        cardBackground = null
        contentColumn = null
        controlBar = null
        barBackBtn = null
        barHintView = null
        barCloseBtn = null
        frameStage = null
        frameBox = null
        imageView = null
        liveBadge = null
        closeWrap = null
        glowBorder?.stop()
        glowBorder = null

        r.animate().cancel()
        if (stillMotion()) {
            runCatching { wm.removeView(r) }
        } else {
            r.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(200L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    runCatching { wm.removeView(r) }
                }.start()
        }
    }

    /** 这次运行能不能让小窗直接操作：有口子、已展开、有画面、设置里也开着。 */
    private fun operateNow(): Boolean {
        val input = pipInput ?: return false
        return input.control.current != HandsControl.State.Closed &&
            isExpanded && currentFrame != null && Hands.pipInputEnabled(context)
    }

    /** 小窗的手势注入口；null 关掉直接操作，画面退回拖窗口与轻点缩放。 */
    fun setPipInput(input: PipInput?) {
        val previous = pipInput
        if (previous !== input && previous != null && HandsPipLayout.shouldHandBack(previous.control.current)) {
            previous.handBack()
        }
        pipInput = input
        controlJob?.cancel()
        controlJob = null
        if (input != null) {
            controlJob = scope.launch {
                input.control.state.collect {
                    refreshChrome()
                    updateWindowSizeIfNecessary()
                }
            }
        }
        // 设置页改开关时画面可能一直不动，所以订阅而不是等下一帧。
        settingJob?.cancel()
        settingJob = if (input == null) null else scope.launch {
            Hands.pipInputEnabledFlow(context).collect {
                if (!it && HandsPipLayout.shouldHandBack(input.control.current)) {
                    input.handBack()
                }
                refreshChrome()
                updateWindowSizeIfNecessary()
            }
        }
        refreshChrome()
        updateWindowSizeIfNecessary()
    }

    private fun refreshChrome(syncRadii: Boolean = sizeAnimator == null) {
        val operate = operateNow()
        val inset = insetPerSidePx(isExpanded)
        val control = pipInput?.control?.current ?: HandsControl.State.Automatic
        val chrome = HandsPipLayout.controlBarChrome(operate, control)
        cardView?.setPadding(inset, inset, inset, inset)
        controlBar?.visibility = if (chrome.visible) View.VISIBLE else View.GONE
        closeWrap?.visibility = if (isExpanded && !operate) View.VISIBLE else View.GONE
        liveBadge?.visibility = if (operate) View.GONE else View.VISIBLE
        barBackBtn?.isEnabled = chrome.backEnabled
        barBackBtn?.alpha = if (chrome.backEnabled) 1f else 0.4f
        barHintView?.text = context.getString(when (control) {
            HandsControl.State.Automatic -> R.string.nm_hands_pip_control_take
            HandsControl.State.Requesting -> R.string.nm_hands_pip_control_wait
            HandsControl.State.Manual -> R.string.nm_hands_pip_control_manual
            HandsControl.State.Closed -> R.string.nm_hands_pip_control_wait
        })
        barHintView?.isClickable = chrome.hintClickable
        barHintView?.isEnabled = chrome.hintEnabled
        barHintView?.alpha = if (chrome.hintEnabled) 1f else 0.6f
        if (syncRadii) {
            val (outerR, innerR) = targetRadii(isExpanded)
            applyRadii(outerR, innerR)
        }
    }

    private fun applyRadii(outerRadius: Float, innerRadius: Float) {
        currentOuterRadiusPx = outerRadius
        currentInnerRadiusPx = innerRadius
        cardBackground?.cornerRadius = outerRadius
        cardView?.invalidateOutline()
        contentColumn?.invalidateOutline()
        glowBorder?.invalidate()
    }

    private fun toggleExpand() {
        setExpanded(!isExpanded)
    }

    private fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        if (!expanded) pipInput?.handBack()
        isExpanded = expanded
        cancelSizeAnimator()
        cancelSettle()
        refreshChrome(syncRadii = false)
        animateSizeTransition()
    }

    private fun cancelSizeAnimator() {
        sizeAnimator?.cancel()
        sizeAnimator = null
    }

    private fun cancelSettle() {
        settleCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        settleCallback = null
    }

    private fun updateWindowLayoutSafe() {
        val r = root ?: return
        val p = params ?: return
        if (!r.isAttachedToWindow) return
        runCatching { wm.updateViewLayout(r, p) }
    }

    private fun updateWindowSizeIfNecessary() {
        val p = params ?: return
        if (root == null) return
        val area = screenArea()
        val target = computeTargetSize(area)
        val bounds = computeBounds(target.width, target.height, area)
        val (clampedX, clampedY) = bounds.clamp(p.x.toFloat(), p.y.toFloat())
        val shouldSync = HandsPipLayout.shouldSyncFrameResize(
            isDragging = isDragging,
            isAnimatingSize = sizeAnimator?.isRunning == true,
            isSettling = settleCallback != null,
            currentWidth = p.width,
            currentHeight = p.height,
            targetWidth = target.width,
            targetHeight = target.height,
            currentX = p.x,
            currentY = p.y,
            clampedX = clampedX.roundToInt(),
            clampedY = clampedY.roundToInt(),
        )
        if (!shouldSync) return

        p.width = target.width
        p.height = target.height
        val (snapX, snapY) = bounds.position(currentCorner)
        p.x = snapX.roundToInt()
        p.y = snapY.roundToInt()
        updateWindowLayoutSafe()
    }

    private fun animateSizeTransition() {
        val p = params ?: return
        if (root == null) return
        val area = screenArea()
        val endSize = computeTargetSize(area)
        val endBounds = computeBounds(endSize.width, endSize.height, area)
        val (endXFloat, endYFloat) = endBounds.position(currentCorner)
        val endX = endXFloat.roundToInt()
        val endY = endYFloat.roundToInt()
        val (endOuterR, endInnerR) = targetRadii(isExpanded)

        if (stillMotion()) {
            applyRadii(endOuterR, endInnerR)
            p.width = endSize.width
            p.height = endSize.height
            p.x = endX
            p.y = endY
            updateWindowLayoutSafe()
            return
        }

        val startW = p.width
        val startH = p.height
        val startX = p.x
        val startY = p.y
        val startOuterR = currentOuterRadiusPx
        val startInnerR = currentInnerRadiusPx

        var cancelled = false
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SIZE_ANIM_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                if (cancelled || sizeAnimator !== va) return@addUpdateListener
                val f = va.animatedFraction
                p.width = (startW + (endSize.width - startW) * f).roundToInt()
                p.height = (startH + (endSize.height - startH) * f).roundToInt()
                p.x = (startX + (endX - startX) * f).roundToInt()
                p.y = (startY + (endY - startY) * f).roundToInt()
                applyRadii(
                    outerRadius = startOuterR + (endOuterR - startOuterR) * f,
                    innerRadius = startInnerR + (endInnerR - startInnerR) * f,
                )
                updateWindowLayoutSafe()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || sizeAnimator !== animation) return
                    sizeAnimator = null
                    applyRadii(endOuterR, endInnerR)
                    p.width = endSize.width
                    p.height = endSize.height
                    p.x = endX
                    p.y = endY
                    updateWindowLayoutSafe()
                    updateWindowSizeIfNecessary()
                }
            })
        }
        sizeAnimator = anim
        anim.start()
    }

    private fun snapToNearestCorner(currentX: Int, currentY: Int, vx: Float, vy: Float) {
        val p = params ?: return
        if (root == null) return
        cancelSettle()

        val bounds = computeBounds(p.width, p.height)
        val bestCorner = bounds.snapCorner(currentX.toFloat(), currentY.toFloat(), vx, vy)
        currentCorner = bestCorner
        val (targetX, targetY) = bounds.position(bestCorner)

        if (stillMotion()) {
            p.x = targetX.roundToInt()
            p.y = targetY.roundToInt()
            updateWindowLayoutSafe()
            updateWindowSizeIfNecessary()
            return
        }

        val spring = Spring()
        var mx = Motion(currentX.toFloat(), vx)
        var my = Motion(currentY.toFloat(), vy)
        var lastNanos = 0L

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (settleCallback !== this || params !== p || root == null) return
                val dt = if (lastNanos == 0L) 0.016f else (frameTimeNanos - lastNanos) / 1_000_000_000f
                lastNanos = frameTimeNanos

                val liveBounds = computeBounds(p.width, p.height)
                val (tx, ty) = liveBounds.position(bestCorner)
                mx = spring.step(mx, tx, dt)
                my = spring.step(my, ty, dt)

                p.x = mx.position.roundToInt()
                p.y = my.position.roundToInt()
                updateWindowLayoutSafe()

                if (spring.settled(mx, tx) && spring.settled(my, ty)) {
                    settleCallback = null
                    p.x = tx.roundToInt()
                    p.y = ty.roundToInt()
                    updateWindowLayoutSafe()
                    updateWindowSizeIfNecessary()
                } else {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        settleCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /**
     * 直接操作：展开态把画框内的触摸注入虚拟屏。
     * 画框尺寸由 [AspectFrameStage] 严格按真实长宽比计算，无黑边偏移，0..1 归一化精准对应屏幕坐标。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupInputGesture(view: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val points = ArrayList<Pair<Float, Float>>(64)
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var lastAt = 0L
        var moved = false
        var operating = false
        var requesting = false

        view.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val input = pipInput
                val canOperate = operateNow()
                val action = HandsPipLayout.frameTouchAction(canOperate, input?.control?.current)
                operating = action == HandsPipLayout.FrameTouchAction.DISPATCH_MANUAL
                requesting = action == HandsPipLayout.FrameTouchAction.REQUEST_CONTROL_AND_CONSUME ||
                    action == HandsPipLayout.FrameTouchAction.CONSUME_WAITING
                if (action == HandsPipLayout.FrameTouchAction.REQUEST_CONTROL_AND_CONSUME) {
                    input?.requestControl()
                }
            }
            if (requesting) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) requesting = false
                return@setOnTouchListener true
            }
            if (!operating) return@setOnTouchListener false
            if (currentFrame == null) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    points.clear()
                    moved = false
                    downX = event.x
                    downY = event.y
                    downAt = event.eventTime
                    lastAt = event.eventTime
                    points += normalize(event.x, event.y, v)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    points += normalize(event.x, event.y, v)
                    lastAt = event.eventTime
                    if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > slop) moved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    lastAt = event.eventTime
                    val input = pipInput
                    if (input != null) {
                        if (!moved) {
                            points.lastOrNull()?.let { (fx, fy) -> input.tap(fx, fy) }
                        } else {
                            val duration = (lastAt - downAt).coerceIn(80L, 600L)
                            input.swipe(points.toList(), duration)
                        }
                    }
                    points.clear()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    points.clear()
                    moved = false
                    true
                }
                else -> false
            }
        }
    }

    private fun normalize(x: Float, y: Float, view: View): Pair<Float, Float> {
        val w = view.width.toFloat().coerceAtLeast(1f)
        val h = view.height.toFloat().coerceAtLeast(1f)
        return (x / w).coerceIn(0f, 1f) to (y / h).coerceIn(0f, 1f)
    }

    /**
     * 居中并按画面真实长宽比精确测量 [frameBox] 的舞台容器，保证动画期间也不裁切、不变形。
     */
    private class AspectFrameStage(context: Context) : FrameLayout(context) {
        private var frameW = 0
        private var frameH = 0

        fun setFrameSize(width: Int, height: Int) {
            if (frameW == width && frameH == height) return
            frameW = width
            frameH = height
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val stageW = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
            val stageH = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
            val child = getChildAt(0)
            if (child != null && child.visibility != View.GONE) {
                val fitted = HandsPipLayout.fittedFrameSize(frameW, frameH, stageW, stageH)
                child.measure(
                    MeasureSpec.makeMeasureSpec(fitted.width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(fitted.height, MeasureSpec.EXACTLY),
                )
            }
            setMeasuredDimension(stageW, stageH)
        }
    }

    /**
     * 协调子控件点击、直接操作画框注入、整窗 1:1 拖拽与轻点展开/收起的根容器（参考 Mika [OverlayFrame]）。
     */
    private inner class PipRootLayout(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val tmpRect = Rect()
        private var downRawX = 0f
        private var downRawY = 0f
        private var initX = 0
        private var initY = 0
        private var tracking = false
        private var passthrough = false
        private var velocityTracker: VelocityTracker? = null

        private fun isInsideFrame(x: Float, y: Float): Boolean {
            val box = frameBox ?: return false
            if (box.visibility != View.VISIBLE || box.width <= 0 || box.height <= 0) return false
            tmpRect.set(0, 0, box.width, box.height)
            offsetDescendantRectToMyCoords(box, tmpRect)
            return tmpRect.contains(x.roundToInt(), y.roundToInt())
        }

        private fun addVelocitySample(ev: MotionEvent) {
            val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
            val copy = MotionEvent.obtain(ev)
            copy.setLocation(ev.rawX, ev.rawY)
            tracker.addMovement(copy)
            copy.recycle()
        }

        private fun recycleVelocityTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }

        private fun beginDragFromCurrent() {
            isDragging = true
            // 若在尺寸过渡动画中途起拖，先同步到最终目标尺寸并对齐抓手坐标，避免宽高半截卡住或位置瞬跳
            if (sizeAnimator != null) {
                cancelSizeAnimator()
                val p = params
                if (p != null) {
                    val target = computeTargetSize()
                    val (outerR, innerR) = targetRadii(isExpanded)
                    applyRadii(outerR, innerR)
                    p.width = target.width
                    p.height = target.height
                    updateWindowLayoutSafe()
                    initX = p.x
                    initY = p.y
                }
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            val p = params ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    recycleVelocityTracker()
                    tracking = false
                    isDragging = false
                    passthrough = HandsPipLayout.shouldPassTouchToFrame(
                        operateNow = operateNow(),
                        insideFrame = isInsideFrame(ev.x, ev.y),
                    )
                    if (passthrough) return false

                    cancelSettle()
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    initX = p.x
                    initY = p.y
                    tracking = true
                    addVelocitySample(ev)
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (passthrough || !tracking) return false
                    addVelocitySample(ev)
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        beginDragFromCurrent()
                        return true
                    }
                    return isDragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val intercept = isDragging
                    if (!intercept) {
                        tracking = false
                        passthrough = false
                        recycleVelocityTracker()
                    }
                    return intercept
                }
                else -> return false
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val p = params ?: return false
            if (passthrough) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!tracking) {
                        recycleVelocityTracker()
                        cancelSettle()
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        initX = p.x
                        initY = p.y
                        tracking = true
                        isDragging = false
                        addVelocitySample(ev)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return false
                    addVelocitySample(ev)
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        beginDragFromCurrent()
                    }
                    if (isDragging) {
                        val nextDx = ev.rawX - downRawX
                        val nextDy = ev.rawY - downRawY
                        p.x = initX + nextDx.roundToInt()
                        p.y = initY + nextDy.roundToInt()
                        updateWindowLayoutSafe()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!tracking) return false
                    addVelocitySample(ev)
                    var vx = 0f
                    var vy = 0f
                    velocityTracker?.let { vt ->
                        vt.computeCurrentVelocity(1000)
                        vx = vt.xVelocity
                        vy = vt.yVelocity
                    }
                    recycleVelocityTracker()
                    val wasDragging = isDragging
                    tracking = false
                    isDragging = false
                    passthrough = false

                    if (wasDragging) {
                        snapToNearestCorner(p.x, p.y, vx, vy)
                    } else {
                        // 轻点在放大与缩回之间切换
                        toggleExpand()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    recycleVelocityTracker()
                    val wasDragging = isDragging
                    tracking = false
                    isDragging = false
                    passthrough = false
                    if (wasDragging) {
                        snapToNearestCorner(p.x, p.y, 0f, 0f)
                    }
                    return true
                }
                else -> return false
            }
        }
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun navigationBarHeight(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(48)
    }

    /**
     * 绘制外边缘的微光边框与呼吸动画。
     */
    private inner class PipGlowView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpF(1.5f).coerceAtLeast(1f)
        }
        private val rect = RectF()
        private var isBreathing = false
        private var breathAlpha = 1f
        private var breathAnimator: ValueAnimator? = null

        fun setNeedsYou(needsYou: Boolean) {
            isBreathing = needsYou
            breathAnimator?.cancel()
            breathAnimator = null
            if (needsYou) {
                paint.color = COLOR_WARN
                if (stillMotion()) {
                    breathAlpha = 1f
                    invalidate()
                } else {
                    breathAnimator = ValueAnimator.ofFloat(0.45f, 1f).apply {
                        duration = 900L
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        addUpdateListener {
                            breathAlpha = it.animatedValue as Float
                            invalidate()
                        }
                    }
                    breathAnimator?.start()
                }
            } else {
                paint.color = COLOR_ACTIVE
                breathAlpha = 1f
                invalidate()
            }
        }

        fun stop() {
            breathAnimator?.cancel()
            breathAnimator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val stroke = paint.strokeWidth
            val half = stroke / 2f
            rect.set(half, half, width - half, height - half)
            val r = currentOuterRadiusPx.takeIf { it > 0f }
                ?: dpF(if (isExpanded) EXPANDED_OUTER_RADIUS_DP else COLLAPSED_OUTER_RADIUS_DP)
            paint.alpha = (255 * (if (isBreathing) breathAlpha else 0.8f)).toInt()
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    companion object {
        private const val TAG = "HandsPipWindow"
        private const val COLLAPSED_WIDTH_DP = 104
        private const val EXPANDED_WIDTH_DP = 216
        private const val COLLAPSED_LANDSCAPE_WIDTH_DP = 176
        private const val EXPANDED_LANDSCAPE_WIDTH_DP = 300

        private const val COLLAPSED_INSET_DP = 3
        private const val EXPANDED_INSET_DP = 4
        private const val COLLAPSED_OUTER_RADIUS_DP = 16f
        private const val EXPANDED_OUTER_RADIUS_DP = 22f
        private const val COLLAPSED_INNER_RADIUS_DP = 13f
        private const val EXPANDED_INNER_RADIUS_DP = 18f

        private const val SCREEN_MARGIN_DP = 12
        private const val CONTROL_BAR_HEIGHT_DP = 32
        private const val SIZE_ANIM_MS = 280L

        private const val CARD_INK = 0xF20F1117.toInt()
        private const val CONTROL_BAR_INK = 0xFF151821.toInt()
        private const val CARD_EDGE = 0x26FFFFFF

        private const val COLOR_ACTIVE = 0xCC06B6D4.toInt()
        private const val COLOR_WARN = 0xFFFF8C00.toInt()
    }
}
