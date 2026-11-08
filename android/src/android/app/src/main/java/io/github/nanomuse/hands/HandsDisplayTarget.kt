package io.github.nanomuse.hands

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.display.AgentDisplay
import io.github.nanomuse.shell.AppsAfterTask
import io.github.nanomuse.shell.INanoMuseShell
import io.github.nanomuse.shell.MainScreenSpill
import io.github.nanomuse.shell.MainScreenWatch
import io.github.nanomuse.shell.SystemPickers
import kotlinx.coroutines.flow.StateFlow

/**
 * 屏幕操作目标抽象：支持主屏 (MainDisplayTarget) 与虚拟屏 (VirtualDisplayTarget)。
 */
interface HandsDisplayTarget {
    val displayId: Int
    val isVirtual: Boolean
    val width: Int
    val height: Int

    fun captureScreenshot(): Bitmap?
    fun tap(x: Int, y: Int): Boolean
    fun doubleTap(x: Int, y: Int): Boolean
    fun longPress(x: Int, y: Int): Boolean
    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean
    fun openApp(packageName: String): Int
    fun restartApp(packageName: String): Int = openApp(packageName)
    fun pressBack(): Boolean
    fun pressHome(): Boolean
    fun injectKey(keyCode: Int): Boolean
    fun foregroundPackage(svc: MinisAccessibilityService): String?
    fun hasTask(): Boolean = true
    val previewFrames: StateFlow<Bitmap?>?

    fun injectText(text: String): Boolean = false
    fun setClipText(text: String): Boolean = false
    fun restoreClip() {}
    fun injectCtrlKey(keyCode: Int): Boolean = false

    fun onTakeoverStart(): Boolean = true
    fun onTakeoverEnd(): Boolean = true
    fun release() {}

    data class SpillWatch(val since: Long, val topBefore: Int)

    fun armSpillWatch(windowMs: Int = SPILL_WINDOW_MS.toInt()): SpillWatch? = null
    fun checkSpill(
        watch: SpillWatch?,
        excluded: Set<String> = emptySet(),
        userTaps: List<MainScreenSpill.Tap> = emptyList(),
    ): MainScreenSpill.Verdict? = null

    companion object {
        const val SPILL_WINDOW_MS = 6_000L
    }
}

/**
 * 原有的主屏操作目标，依赖 MinisAccessibilityService。
 */
class MainDisplayTarget(
    private val context: Context,
    private val svc: MinisAccessibilityService,
    override val width: Int,
    override val height: Int,
) : HandsDisplayTarget {
    override val displayId: Int = Display.DEFAULT_DISPLAY
    override val isVirtual: Boolean = false
    override val previewFrames: StateFlow<Bitmap?>? = null

    override fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val shot = svc.captureScreenshot(displayId = displayId, timeoutMs = 6_000)
            return shot.bitmap
        }
        return null
    }

    override fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return svc.dispatchSimpleGesture(path, 0, 60L)
    }

    override fun doubleTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        if (!svc.dispatchSimpleGesture(path, 0, 50L)) return false
        Thread.sleep(80)
        return svc.dispatchSimpleGesture(path, 0, 50L)
    }

    override fun longPress(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return svc.dispatchSimpleGesture(path, 0, 900L)
    }

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        return svc.dispatchSimpleGesture(path, 0, durationMs)
    }

    override fun openApp(packageName: String): Int {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return 2
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            context.startActivity(intent)
            0
        }.getOrDefault(3)
    }

    override fun pressBack(): Boolean =
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun pressHome(): Boolean =
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    override fun injectKey(keyCode: Int): Boolean = false

    override fun foregroundPackage(svc: MinisAccessibilityService): String? =
        svc.foregroundPackage().first
            ?: ScreenWords.rootsOn(svc, Display.DEFAULT_DISPLAY).firstNotNullOfOrNull { it.packageName?.toString() }
}

/**
 * Mika 风格的后台虚拟屏幕目标，依赖 AgentDisplay 与 INanoMuseShell。
 */
class VirtualDisplayTarget(
    private val display: AgentDisplay,
    private val shell: INanoMuseShell,
    private val context: Context? = null,
) : HandsDisplayTarget {
    private val tag = "VirtualDisplayTarget"

    override val displayId: Int get() = display.displayId
    override val isVirtual: Boolean = true
    override val width: Int get() = display.width
    override val height: Int get() = display.height
    override val previewFrames: StateFlow<Bitmap?> get() = display.frames

    private var takeoverTaskId: Int = -1

    override fun hasTask(): Boolean = shell.topTaskId(displayId) >= 0

    override fun captureScreenshot(): Bitmap? {
        // 等待一小会儿确保首帧到达
        repeat(5) {
            val bmp = display.latestFrameBitmap()
            if (bmp != null) return bmp
            Thread.sleep(100)
        }
        return display.latestFrameBitmap()
    }

    override fun tap(x: Int, y: Int): Boolean =
        shell.injectTap(displayId, x, y)

    override fun doubleTap(x: Int, y: Int): Boolean {
        if (!shell.injectTap(displayId, x, y)) return false
        Thread.sleep(80)
        return shell.injectTap(displayId, x, y)
    }

    override fun longPress(x: Int, y: Int): Boolean {
        val xs = intArrayOf(x, x)
        val ys = intArrayOf(y, y)
        val times = longArrayOf(0, 900)
        return shell.injectStroke(displayId, xs, ys, times)
    }

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
        val steps = 10
        val xs = IntArray(steps)
        val ys = IntArray(steps)
        val times = LongArray(steps)
        for (i in 0 until steps) {
            val frac = i.toFloat() / (steps - 1)
            xs[i] = (fromX + (toX - fromX) * frac).toInt()
            ys[i] = (fromY + (toY - fromY) * frac).toInt()
            times[i] = (durationMs * frac).toLong()
        }
        return shell.injectStroke(displayId, xs, ys, times)
    }

    // The operator checks for spills after settling, with the user's main-screen tap history.
    override fun openApp(packageName: String): Int = shell.startApp(displayId, packageName)

    override fun restartApp(packageName: String): Int = shell.restartApp(displayId, packageName)

    override fun armSpillWatch(windowMs: Int): HandsDisplayTarget.SpillWatch? {
        val since = SystemClock.uptimeMillis()
        val top = runCatching { shell.watchMainScreen(windowMs) }.getOrDefault(-1)
        return HandsDisplayTarget.SpillWatch(since, top)
    }

    override fun checkSpill(
        watch: HandsDisplayTarget.SpillWatch?,
        excluded: Set<String>,
        userTaps: List<MainScreenSpill.Tap>,
    ): MainScreenSpill.Verdict? {
        if (watch == null) return null
        val b = runCatching { shell.mainArrivals(watch.since) }.getOrNull() ?: return null
        val arrivals = MainScreenWatch.parseArrivals(b)
        val allExcluded = if (context != null) excluded + SystemPickers.resolve(context) else excluded
        val verdicts = MainScreenSpill.decide(arrivals, watch.since, HandsDisplayTarget.SPILL_WINDOW_MS, watch.topBefore, allExcluded, userTaps)
        val verdict = verdicts.lastOrNull() ?: return null
        if (verdict is MainScreenSpill.PullBack) {
            val taskId = verdict.arrival.taskId
            AppLogger.info(tag, "pulling back spilled task $taskId (${verdict.arrival.packageName}) to virtual display $displayId")
            val moved = runCatching { shell.moveTask(taskId, displayId) }.getOrDefault(false)
            check(moved) {
                context?.getString(R.string.nm_hands_spill_restore_failed)
                    ?: "could not return the app from the main screen to the virtual screen; screen operation stopped"
            }
        }
        return verdict
    }

    override fun pressBack(): Boolean =
        shell.injectKey(displayId, KeyEvent.KEYCODE_BACK)

    override fun pressHome(): Boolean =
        shell.injectKey(displayId, KeyEvent.KEYCODE_HOME)

    override fun injectKey(keyCode: Int): Boolean =
        shell.injectKey(displayId, keyCode)

    override fun injectText(text: String): Boolean =
        shell.injectText(displayId, text)

    override fun setClipText(text: String): Boolean =
        shell.setClipText(text) == 0

    override fun restoreClip() {
        shell.restoreClip()
    }

    override fun injectCtrlKey(keyCode: Int): Boolean =
        shell.injectCtrlKey(displayId, keyCode)

    override fun foregroundPackage(svc: MinisAccessibilityService): String? =
        ScreenWords.rootsOn(svc, displayId).firstNotNullOfOrNull { it.packageName?.toString() }
            ?: runCatching { shell.packageOfTask(shell.topTaskId(displayId)) }.getOrNull()

    override fun onTakeoverStart(): Boolean {
        if (takeoverTaskId >= 0) return false
        val top = shell.topTaskId(displayId)
        if (top < 0) return false
        AppLogger.info(tag, "moving task $top to main screen for user takeover")
        if (!shell.moveTask(top, Display.DEFAULT_DISPLAY)) return false
        takeoverTaskId = top
        return true
    }

    override fun onTakeoverEnd(): Boolean {
        val taskId = takeoverTaskId
        if (taskId < 0) return false
        AppLogger.info(tag, "moving task $taskId back to virtual display $displayId")
        if (!shell.moveTask(taskId, displayId)) return false
        takeoverTaskId = -1
        Thread.sleep(500)
        return true
    }

    override fun release() {
        try {
            val bundle = shell.finishDisplay(displayId, emptyArray(), emptyArray())
            if (bundle != null) {
                val kept = bundle.getStringArray(AppsAfterTask.KEY_KEPT)?.size ?: 0
                val closed = bundle.getStringArray(AppsAfterTask.KEY_CLOSED)?.toList().orEmpty()
                val note = bundle.getString(AppsAfterTask.KEY_NOTE)
                AppLogger.info(tag, "finishDisplay: ${AppsAfterTask.diagnosticsLine(kept, closed, note)}")
            }
        } catch (t: Throwable) {
            AppLogger.warning(tag, "finishDisplay failed: ${t.message}")
        }
        display.release()
    }
}
