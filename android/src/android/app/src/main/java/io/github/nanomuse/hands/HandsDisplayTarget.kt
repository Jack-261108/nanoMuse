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
import io.github.nanomuse.shell.ShellService
import io.github.nanomuse.shell.SystemPickers
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 屏幕操作目标抽象：支持主屏 (MainDisplayTarget) 与虚拟屏 (VirtualDisplayTarget)。
 */
interface HandsDisplayTarget {
    val displayId: Int
    val isVirtual: Boolean
    val width: Int
    val height: Int
    /** Virtual screens have a handover; the main screen keeps its existing approval flow. */
    val control: HandsControl? get() = null

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

    /** 小窗直接操作的口子；主屏没有小窗，所以默认 null。 */
    val pipInput: PipInput? get() = null

    /** 最新一帧的时刻（与 `System.nanoTime()` 同源）；主屏截图不走这条路径。 */
    fun lastFrameAt(): Long? = null

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
    override val control = HandsControl()

    private var takeoverTaskId: Int = -1
    @Volatile private var mainScreenTakeover: Boolean = false
    private val cancelGen = AtomicLong(0L)

    /**
     * 接管申请可能等待 Agent 当前动作结束（最长 10 秒），单独放在控制线程上，
     * 避免排队堵住小窗输入或取消交回。
     */
    private val controlExecutorLazy = lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "nanomuse-pip-control").apply { isDaemon = true } }
    }
    private val controlExecutor by controlExecutorLazy

    /**
     * 注入会沿笔迹逐点等待，不能占界面线程，所以单开一条线程。
     * 小窗操作是用户的额外手段，失败只记日志：它不决定任务的成败。
     */
    private val inputExecutorLazy = lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "nanomuse-pip-input").apply { isDaemon = true } }
    }
    private val inputExecutor by inputExecutorLazy

    override val pipInput: PipInput by lazy {
        object : PipInput {
            override val control: HandsControl get() = this@VirtualDisplayTarget.control

            override fun requestControl() {
                if (mainScreenTakeover) return
                val cancelEpoch = cancelGen.get()
                runControl {
                    if (mainScreenTakeover || cancelGen.get() != cancelEpoch) return@runControl
                    when (control.begin()) {
                        HandsControl.BeginResult.Acquired -> {
                            if (mainScreenTakeover || cancelGen.get() != cancelEpoch) {
                                control.handBack()
                            }
                        }
                        HandsControl.BeginResult.TimedOut -> {
                            AppLogger.warning(tag, "preview control request timed out waiting for the current action")
                        }
                        HandsControl.BeginResult.Cancelled,
                        HandsControl.BeginResult.BusyOrClosed -> Unit
                    }
                }
            }

            override fun handBack() {
                cancelGen.incrementAndGet()
                // Requesting 态直接非阻塞取消等待中的 begin()；仅 Manual 态经 inputExecutor 与已排队输入保序。
                if (control.cancelRequest()) return
                if (control.current == HandsControl.State.Manual) {
                    runInput { control.handBack() }
                }
            }

            override fun tap(fx: Float, fy: Float) {
                if (control.current != HandsControl.State.Manual) return
                val version = control.version
                runInput {
                    control.userInput(expectedVersion = version) {
                        shell.injectTap(displayId, (fx * width).toInt(), (fy * height).toInt())
                    }
                }
            }

            override fun swipe(points: List<Pair<Float, Float>>, durationMs: Long) {
                if (points.size < 2) {
                    points.firstOrNull()?.let { tap(it.first, it.second) }
                    return
                }
                if (control.current != HandsControl.State.Manual) return
                val version = control.version
                runInput {
                    control.userInput(expectedVersion = version) {
                        val n = points.size
                        shell.injectStroke(
                            displayId,
                            IntArray(n) { (points[it].first * width).toInt() },
                            IntArray(n) { (points[it].second * height).toInt() },
                            LongArray(n) { durationMs * it / (n - 1) },
                        )
                    }
                }
            }

            override fun back() {
                if (control.current != HandsControl.State.Manual) return
                val version = control.version
                runInput {
                    control.userInput(expectedVersion = version) {
                        shell.injectKey(displayId, KeyEvent.KEYCODE_BACK)
                    }
                }
            }

            private fun runControl(block: () -> Unit) {
                runCatching { controlExecutor.execute { runCatching(block) } }
            }

            private fun runInput(block: () -> Unit) {
                runCatching { inputExecutor.execute { runCatching(block) } }
            }
        }
    }

    override fun lastFrameAt(): Long? = display.lastFrameAt()

    override fun hasTask(): Boolean = shell.topTaskId(displayId) >= 0

    override fun captureScreenshot(): Bitmap? = control.automatic {
        // 等待一小会儿确保首帧到达
        repeat(5) {
            if (control.current != HandsControl.State.Automatic) return@automatic null
            val bmp = display.latestFrameBitmap()
            if (bmp != null) return@automatic bmp
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@automatic null
            }
        }
        if (control.current != HandsControl.State.Automatic) null else display.latestFrameBitmap()
    }

    override fun tap(x: Int, y: Int): Boolean = control.automatic {
        shell.injectTap(displayId, x, y)
    } ?: false

    override fun doubleTap(x: Int, y: Int): Boolean = control.automatic {
        if (!shell.injectTap(displayId, x, y)) false
        else {
            try {
                Thread.sleep(80)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@automatic false
            }
            if (control.current == HandsControl.State.Closed) false
            else shell.injectTap(displayId, x, y)
        }
    } ?: false

    override fun longPress(x: Int, y: Int): Boolean = control.automatic {
        val xs = intArrayOf(x, x)
        val ys = intArrayOf(y, y)
        val times = longArrayOf(0, 900)
        shell.injectStroke(displayId, xs, ys, times)
    } ?: false

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean = control.automatic {
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
        shell.injectStroke(displayId, xs, ys, times)
    } ?: false

    // The operator checks for spills after settling, with the user's main-screen tap history.
    override fun openApp(packageName: String): Int = control.automatic {
        shell.startApp(displayId, packageName)
    } ?: ShellService.START_ERROR

    override fun restartApp(packageName: String): Int = control.automatic {
        shell.restartApp(displayId, packageName)
    } ?: ShellService.START_ERROR

    override fun armSpillWatch(windowMs: Int): HandsDisplayTarget.SpillWatch? {
        val since = SystemClock.uptimeMillis()
        val top = runCatching { shell.watchMainScreen(windowMs) }.getOrDefault(-1)
        return HandsDisplayTarget.SpillWatch(since, top)
    }

    override fun checkSpill(
        watch: HandsDisplayTarget.SpillWatch?,
        excluded: Set<String>,
        userTaps: List<MainScreenSpill.Tap>,
    ): MainScreenSpill.Verdict? = control.automatic {
        checkSpillNow(watch, excluded, userTaps)
    }

    private fun checkSpillNow(
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

    override fun pressBack(): Boolean = control.automatic {
        shell.injectKey(displayId, KeyEvent.KEYCODE_BACK)
    } ?: false

    override fun pressHome(): Boolean = control.automatic {
        shell.injectKey(displayId, KeyEvent.KEYCODE_HOME)
    } ?: false

    override fun injectKey(keyCode: Int): Boolean = control.automatic {
        shell.injectKey(displayId, keyCode)
    } ?: false

    override fun injectText(text: String): Boolean = control.automatic {
        shell.injectText(displayId, text)
    } ?: false

    override fun setClipText(text: String): Boolean = control.automatic {
        shell.setClipText(text) == 0
    } ?: false

    /** Restore is cleanup, not a screen action: it must run even if control changed meanwhile. */
    override fun restoreClip() {
        shell.restoreClip()
    }

    override fun injectCtrlKey(keyCode: Int): Boolean = control.automatic {
        shell.injectCtrlKey(displayId, keyCode)
    } ?: false

    override fun foregroundPackage(svc: MinisAccessibilityService): String? =
        ScreenWords.rootsOn(svc, displayId).firstNotNullOfOrNull { it.packageName?.toString() }
            ?: runCatching { shell.packageOfTask(shell.topTaskId(displayId)) }.getOrNull()

    override fun onTakeoverStart(): Boolean {
        if (takeoverTaskId >= 0) return false
        mainScreenTakeover = true
        cancelGen.incrementAndGet()
        control.handBack()
        val top = shell.topTaskId(displayId)
        if (top < 0) {
            mainScreenTakeover = false
            return false
        }
        AppLogger.info(tag, "moving task $top to main screen for user takeover")
        if (!shell.moveTask(top, Display.DEFAULT_DISPLAY)) {
            mainScreenTakeover = false
            return false
        }
        takeoverTaskId = top
        return true
    }

    override fun onTakeoverEnd(): Boolean {
        val taskId = takeoverTaskId
        if (taskId < 0) return false
        control.handBack()
        AppLogger.info(tag, "moving task $taskId back to virtual display $displayId")
        if (!shell.moveTask(taskId, displayId)) {
            mainScreenTakeover = false
            return false
        }
        takeoverTaskId = -1
        mainScreenTakeover = false
        Thread.sleep(500)
        return true
    }

    override fun release() {
        mainScreenTakeover = false
        control.close()
        if (controlExecutorLazy.isInitialized()) runCatching { controlExecutor.shutdownNow() }
        if (inputExecutorLazy.isInitialized()) runCatching { inputExecutor.shutdownNow() }
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
