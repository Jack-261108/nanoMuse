package io.github.nanomuse.hands

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlin.math.hypot

/**
 * Mika 风格的后台虚拟屏幕独立画中画（Picture-in-Picture）小窗。
 *
 * 纯粹的画面呈现，不遮挡、不拼贴文字：
 * - 比例：严格保持虚拟屏真实长宽比，整屏完整展示，无裁切失真
 * - 质感：黑曜石磨砂底色、0.75dp 高光微光边框、平滑深色阴影
 * - 状态：工作时泛一圈深邃青蓝微光，需要用户时呼吸橙色光晕
 * - 手势：1:1 跟手拖拽，松手平滑吸附到最近的角（左上/右上/左下/右下）
 * - 缩放：轻点在迷你悬浮窗 (宽 104dp) 与沉浸大窗 (宽 216dp) 之间丝滑切换
 */
class HandsPipWindow(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var imageView: ImageView? = null
    private var liveBadge: TextView? = null
    private var closeBtn: TextView? = null
    private var glowBorder: PipGlowView? = null

    private var currentFrame: Bitmap? = null
    private var isExpanded: Boolean = false
    private var isNeedsYou: Boolean = false
    private var isVisible: Boolean = true

    var onDismiss: (() -> Unit)? = null

    enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }
    private var currentCorner: Corner = Corner.BottomEnd

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun screenMetrics(): DisplayMetrics = context.resources.displayMetrics

    private fun defaultAspect(): Float {
        val dm = screenMetrics()
        return if (dm.widthPixels > 0 && dm.heightPixels > 0) dm.heightPixels.toFloat() / dm.widthPixels else 2.16f
    }

    private fun currentAspect(): Float =
        currentFrame?.let { if (it.width > 0) it.height.toFloat() / it.width else null } ?: defaultAspect()

    private fun targetWidth(): Int = if (isExpanded) dp(EXPANDED_WIDTH_DP) else dp(COLLAPSED_WIDTH_DP)

    private fun targetHeight(): Int {
        val w = targetWidth()
        val h = (w * currentAspect()).toInt()
        val dm = screenMetrics()
        val maxH = (dm.heightPixels * 0.72f).toInt()
        return h.coerceAtMost(maxH)
    }

    fun show() {
        if (root != null) return
        val dm = screenMetrics()
        val w = targetWidth()
        val h = targetHeight()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val p = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val (targetX, targetY) = cornerPosition(currentCorner, w, h)
        p.x = targetX
        p.y = targetY
        this.params = p

        val rootLayout = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        // 外层卡片容器（圆角、黑曜石亚克力底色、描边）
        val cardRadius = if (isExpanded) dp(22).toFloat() else dp(16).toFloat()
        val card = FrameLayout(context).apply {
            elevation = dp(10).toFloat()
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = if (isExpanded) dp(22).toFloat() else dp(16).toFloat()
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            background = GradientDrawable().apply {
                cornerRadius = cardRadius
                setColor(CARD_INK)
                setStroke(dp(1), CARD_EDGE)
            }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // 内部视频/屏幕帧
        val img = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            currentFrame?.let { setImageBitmap(it) }
        }

        // 左下角微标 "● 实时"
        val badge = TextView(context).apply {
            text = "● " + context.getString(R.string.nm_hands_preview_live)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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

        // 展开时右上角的极简收起按钮 "✕"
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            val s = dp(26)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(6)
                rightMargin = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x80000000.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            visibility = if (isExpanded) View.VISIBLE else View.GONE
            setOnClickListener {
                toggleExpand()
            }
        }

        // 边框微光 / 呼吸光晕
        val glow = PipGlowView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isNeedsYou = this@HandsPipWindow.isNeedsYou
        }

        card.addView(img)
        card.addView(badge)
        card.addView(close)
        card.addView(glow)
        rootLayout.addView(card)

        setupGestures(rootLayout)

        try {
            wm.addView(rootLayout, p)
            root = rootLayout
            imageView = img
            liveBadge = badge
            closeBtn = close
            glowBorder = glow

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
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "pip addView failed: ${t.message}")
        }
    }

    fun updateFrame(frame: Bitmap) {
        currentFrame = frame
        imageView?.setImageBitmap(frame)
        updateWindowSizeIfNecessary()
    }

    fun setNeedsYou(needsYou: Boolean) {
        if (isNeedsYou == needsYou) return
        isNeedsYou = needsYou
        glowBorder?.setNeedsYou(needsYou)
        if (needsYou && !isExpanded) {
            // 接管时自动平滑展开，让用户清楚看到正在发生什么
            toggleExpand()
        }
    }

    fun setVisible(visible: Boolean) {
        isVisible = visible
        root?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun hide() {
        val r = root ?: return
        root = null
        params = null
        imageView = null
        liveBadge = null
        closeBtn = null
        glowBorder?.stop()
        glowBorder = null

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

    private fun toggleExpand() {
        isExpanded = !isExpanded
        closeBtn?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        animateSizeTransition()
    }

    private fun updateWindowSizeIfNecessary() {
        val p = params ?: return
        val r = root ?: return
        val targetW = targetWidth()
        val targetH = targetHeight()
        if (p.width != targetW || p.height != targetH) {
            p.width = targetW
            p.height = targetH
            val (snapX, snapY) = cornerPosition(currentCorner, targetW, targetH)
            p.x = snapX
            p.y = snapY
            runCatching { wm.updateViewLayout(r, p) }
        }
    }

    private fun animateSizeTransition() {
        val p = params ?: return
        val r = root ?: return
        val startW = p.width
        val startH = p.height
        val endW = targetWidth()
        val endH = targetHeight()

        val startX = p.x
        val startY = p.y
        val (endX, endY) = cornerPosition(currentCorner, endW, endH)

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val frac = va.animatedFraction
                p.width = (startW + (endW - startW) * frac).toInt()
                p.height = (startH + (endH - startH) * frac).toInt()
                p.x = (startX + (endX - startX) * frac).toInt()
                p.y = (startY + (endY - startY) * frac).toInt()
                runCatching { wm.updateViewLayout(r, p) }
            }
        }
        anim.start()
    }

    private fun cornerPosition(corner: Corner, width: Int, height: Int): Pair<Int, Int> {
        val dm = screenMetrics()
        val margin = dp(12)
        val statusH = statusBarHeight()
        val navH = navigationBarHeight()

        val left = margin
        val right = dm.widthPixels - width - margin
        val top = statusH + margin
        val bottom = dm.heightPixels - height - navH - margin

        return when (corner) {
            Corner.TopStart -> left to top
            Corner.TopEnd -> right to top
            Corner.BottomStart -> left to bottom
            Corner.BottomEnd -> right to bottom
        }
    }

    private fun snapToNearestCorner(currentX: Int, currentY: Int, vx: Float, vy: Float) {
        val p = params ?: return
        val r = root ?: return
        val w = p.width
        val h = p.height

        // 结合滑动速度投射落点
        val projX = currentX + (vx * 0.12f).toInt()
        val projY = currentY + (vy * 0.12f).toInt()

        val dm = screenMetrics()
        val midX = dm.widthPixels / 2
        val midY = dm.heightPixels / 2

        val bestCorner = when {
            projX < midX && projY < midY -> Corner.TopStart
            projX >= midX && projY < midY -> Corner.TopEnd
            projX < midX && projY >= midY -> Corner.BottomStart
            else -> Corner.BottomEnd
        }
        currentCorner = bestCorner
        val (targetX, targetY) = cornerPosition(bestCorner, w, h)

        val startX = p.x
        val startY = p.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { va ->
                val f = va.animatedFraction
                p.x = (startX + (targetX - startX) * f).toInt()
                p.y = (startY + (targetY - startY) * f).toInt()
                runCatching { wm.updateViewLayout(r, p) }
            }
        }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures(view: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var initX = 0
        var initY = 0
        var isDragging = false
        var lastMoveTime = 0L
        var lastX = 0f
        var lastY = 0f
        var vx = 0f
        var vy = 0f

        view.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            val r = root ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastX = event.rawX
                    lastY = event.rawY
                    initX = p.x
                    initY = p.y
                    isDragging = false
                    lastMoveTime = System.currentTimeMillis()
                    vx = 0f
                    vy = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    val now = System.currentTimeMillis()
                    val dt = (now - lastMoveTime).coerceAtLeast(1)
                    vx = (event.rawX - lastX) / (dt / 1000f)
                    vy = (event.rawY - lastY) / (dt / 1000f)
                    lastX = event.rawX
                    lastY = event.rawY
                    lastMoveTime = now

                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        p.x = initX + dx.toInt()
                        p.y = initY + dy.toInt()
                        runCatching { wm.updateViewLayout(r, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        snapToNearestCorner(p.x, p.y, vx, vy)
                    } else {
                        // 轻点切换展开/收起
                        toggleExpand()
                    }
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        snapToNearestCorner(p.x, p.y, 0f, 0f)
                    }
                    isDragging = false
                    true
                }
                else -> false
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
            strokeWidth = dp(1.5f.toInt().coerceAtLeast(1)).toFloat()
        }
        private val rect = RectF()
        private var isBreathing = false
        private var breathAlpha = 1f
        private var breathAnimator: ValueAnimator? = null

        fun setNeedsYou(needsYou: Boolean) {
            isBreathing = needsYou
            breathAnimator?.cancel()
            if (needsYou) {
                paint.color = COLOR_WARN
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
            val r = (if (isExpanded) dp(22) else dp(16)).toFloat()
            paint.alpha = (255 * (if (isBreathing) breathAlpha else 0.8f)).toInt()
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    companion object {
        private const val TAG = "HandsPipWindow"
        private const val COLLAPSED_WIDTH_DP = 104
        private const val EXPANDED_WIDTH_DP = 216

        private const val CARD_INK = 0xF20F1117.toInt()
        private const val CARD_EDGE = 0x26FFFFFF

        private const val COLOR_ACTIVE = 0xCC06B6D4.toInt() // 优雅青蓝微光 (Cyan)
        private const val COLOR_WARN = 0xFFFF8C00.toInt()   // 接管提示橙光 (Warning Orange)
    }
}
