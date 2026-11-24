package io.github.nanomuse.hands

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.service.SessionActivityTracker
import io.github.nanomuse.cloud.ClientEvents
import io.github.nanomuse.cloud.Provenance
import io.github.nanomuse.display.AgentDisplay
import io.github.nanomuse.display.FrameFreshness
import io.github.nanomuse.shell.MainScreenSpill
import io.github.nanomuse.shell.SharedShell
import io.github.nanomuse.shell.ShellService
import io.github.nanomuse.shell.SystemPickers
import io.github.nanomuse.guard.GateOutcome
import io.github.nanomuse.guard.GuardKind
import io.github.nanomuse.guard.RiskAssessment
import io.github.nanomuse.guard.RiskClass
import io.github.nanomuse.guard.RiskDecision
import io.github.nanomuse.guard.RiskGate
import io.github.nanomuse.guard.RiskRequest
import io.github.nanomuse.guard.TapWords
import io.github.nanomuse.status.KeepAwake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One task on the phone's screen, start to finish: screenshot → screen model → one action →
 * again, until the model says it is done, the user says stop, or the step budget runs out.
 *
 * Perception is the screenshot alone. The accessibility service performs the gestures and
 * takes the screenshot; the only node it ever touches is the field that has the cursor, to
 * type into it — and to refuse when that field is a password field.
 *
 * Runs on the sandbox worker thread that carries the `nanomuse-hands` call, so everything here
 * blocks; the capsule's buttons arrive from the main thread through flags and latches.
 */
class HandsOperator(private val context: Context) {

    data class Options(
        val task: String,
        val appHint: String? = null,
        /** A cap on steps for callers that want one; 0 (the default) means the run goes on until the task is done, the person stops it, or the time limit. */
        val maxSteps: Int = DEFAULT_MAX_STEPS,
        val sessionId: String? = null,
        val agentName: String = "nanoMuse",
        val language: String = "the user's language",
    )

    sealed class Outcome(val code: String) {
        data class Done(val answer: String) : Outcome("done")
        data class Infeasible(val message: String) : Outcome("infeasible")
        data class Stopped(val message: String) : Outcome("stopped")
        data class NeedsUser(val question: String) : Outcome("needs_user")
        data class Failed(val message: String) : Outcome("failed")
    }

    data class Result(
        val outcome: Outcome,
        val steps: Int,
        val runId: String,
        /** `minis://attachments/hands/<run>/step-N.jpg` of the last screen, if any. */
        val lastScreen: String?,
        val tracePath: String?,
        /** One line per step, for the tool result. */
        val log: List<String>,
        val model: String?,
    )

    private class Turn(val userText: String, val image: ByteArray?, var assistant: String)

    private val stop = AtomicBoolean(false)
    private val stopReason = AtomicReference<String?>(null)
    private val userSignal = AtomicReference<CountDownLatch?>(null)
    private val capsule = HandsCapsule(context)
    private var memoryTicket: HandsRunMemory.Ticket? = null
    private var memoryTask = ""
    private var memoryApp: String? = null
    private val stopLock = Any()
    @Volatile private var workerThread: Thread? = null
    @Volatile private var currentJob: Job? = null

    /**
     * 最近一次把 App 启动或挪回虚拟屏的时刻（System.nanoTime，与帧时间戳同源）。
     * 设着时只认这之后的帧：启动前已入队、启动后才读出来的旧帧不能交给模型。
     */
    @Volatile private var frameGuardSince: Long? = null

    /** The session this run's approval cards are filed under; see [ownPendingRequest]. */
    @Volatile private var gateSession: String? = null
    /** The chat the run came from, for the record's events (null for a run without one). */
    @Volatile private var eventSession: String? = null
    /** This task's answer to the keyboard-consent question; null until it is asked. */
    private var imeConsent: Boolean? = null
    /** Consecutive IME typing failures; two stop trying it for the rest of the task. */
    private var imeFailures = 0
    /** Total time spent paused waiting for preview control hand-back; excluded from [MAX_RUN_MS]. */
    @Volatile private var pausedHandoverMs = 0L

    private val notSpills: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = runCatching {
            context.packageManager.queryIntentActivities(home, 0).mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList())
        (launchers + context.packageName + SystemPickers.resolve(context)).toSet()
    }

    /**
     * The approval card waiting on this run, if the one on top is ours: a tap or an Enter the
     * hands asked about, or the keyboard consent, not a shell or browser request of some other
     * conversation.
     */
    private fun ownPendingRequest(): RiskRequest? =
        RiskGate.pending.value?.takeIf {
            (it.kind == GuardKind.SCREEN || it.kind == GuardKind.IME) && it.sessionId == gateSession
        }

    private fun sleepOrStop(ms: Long): Boolean {
        if (stop.get()) return false
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            return false
        }
        return !stop.get()
    }

    /**
     * Stops scheduling actions from the capsule, the chat or the settings page.
     * Cancels the model call, wakes waits and denies pending approvals. A system gesture that
     * has already started may finish.
     */
    fun requestStop(reason: String = "the user tapped Stop") {
        synchronized(stopLock) {
            if (stop.get()) return
            // Publish the reason before the stop flag, and do not interrupt a reused worker.
            stopReason.set(reason)
            stop.set(true)
            currentJob?.cancel()
            workerThread?.interrupt()
        }
        capsule.stopping()
        ownPendingRequest()?.let { RiskGate.decide(it.id, RiskDecision.DENY) }
        userSignal.get()?.countDown()
    }

    /** A model timeout is an error to retry, not evidence that the user pressed Stop. */
    internal fun isStopFailure(error: Throwable): Boolean =
        stop.get() || error is InterruptedException

    private fun ensureRunning() {
        if (stop.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("screen operation stopped")
        }
    }

    /** A helper may swallow interruption and return false; never treat that as permission to fall back. */
    private fun attemptInput(input: () -> Boolean): Boolean {
        ensureRunning()
        val accepted = input()
        ensureRunning()
        return accepted
    }

    internal fun <T> callModel(
        calling: CoroutineContext = EmptyCoroutineContext,
        timeoutMs: Long = MODEL_TIMEOUT_MS,
        request: suspend () -> T,
    ): T = runBlocking(calling) {
        val job = coroutineContext[Job]!!
        synchronized(stopLock) {
            if (stop.get()) throw CancellationException("screen operation stopped")
            currentJob = job
        }
        try {
            withTimeout(timeoutMs) { request() }
        } finally {
            synchronized(stopLock) {
                if (currentJob === job) currentJob = null
            }
        }
    }

    /** Both main-screen and virtual-screen double taps share the same cancellation boundary. */
    internal fun doubleTap(tap: () -> Boolean, between: () -> Unit = {}): Boolean {
        if (stop.get() || !tap()) return false
        between()
        return sleepOrStop(90) && !stop.get() && tap()
    }

    /** Runs a virtual-screen double tap inside one [HandsControl.automatic] scope while keeping Stop responsive. */
    internal fun virtualDoubleTap(
        target: HandsDisplayTarget,
        x: Int,
        y: Int,
        between: () -> Unit = {},
    ): Boolean = runAutomatic(target) {
        doubleTap({ target.tap(x, y) }, between)
    } ?: false

    /** Waits while the person owns the preview; a Stop ends the wait at once. */
    private fun awaitScreenControl(target: HandsDisplayTarget): Boolean =
        awaitScreenControlVersion(target) != null

    /**
     * Waits while the person owns the preview and returns [HandsControl.version] from the same
     * lock acquisition that confirmed Automatic; returns 0L when [target] has no control, or null
     * when stopped or closed. Time spent paused in handover is accumulated in [pausedHandoverMs].
     */
    internal fun awaitScreenControlVersion(
        target: HandsDisplayTarget,
        nowNanos: () -> Long = System::nanoTime,
    ): Long? {
        val control = target.control ?: return 0L
        val stateBefore = control.current
        val versionBefore = control.version
        val waitStart = nowNanos()
        val version = control.awaitAutomaticVersion { stop.get() }
        if (version != null && (stateBefore != HandsControl.State.Automatic || version != versionBefore)) {
            val waitedMs = ((nowNanos() - waitStart) / 1_000_000L).coerceAtLeast(0L)
            pausedHandoverMs += waitedMs
        }
        return version
    }

    internal fun isRunTimeLimitExceeded(
        nowMs: Long,
        startedMs: Long,
        pausedMs: Long = pausedHandoverMs,
        limitMs: Long = MAX_RUN_MS,
    ): Boolean = (nowMs - startedMs - pausedMs) > limitMs

    internal sealed interface SpillCheck {
        data class Checked(val verdict: MainScreenSpill.Verdict?) : SpillCheck
        data object Skipped : SpillCheck
    }

    /**
     * Checks main-screen spills inside [HandsControl.automatic] so the caller can tell a clean
     * check ([SpillCheck.Checked] with null) apart from one skipped because the user took control
     * ([SpillCheck.Skipped]).
     */
    internal fun checkSpillOrSkip(
        target: HandsDisplayTarget,
        watch: HandsDisplayTarget.SpillWatch?,
        excluded: Set<String>,
        userTaps: List<MainScreenSpill.Tap>,
    ): SpillCheck {
        if (watch == null) return SpillCheck.Checked(null)
        return runAutomatic(target) {
            SpillCheck.Checked(target.checkSpill(watch, excluded, userTaps))
        } ?: SpillCheck.Skipped
    }

    internal data class HandoverSync(
        val controlVersion: Long,
        val lastResult: String?,
        val loopNote: String?,
        val pendingSpillWatch: HandsDisplayTarget.SpillWatch?,
        val handoverOccurred: Boolean,
        val spillRecovered: Boolean = false,
    )

    /**
     * Waits for Automatic control at the top of a step, settles after any user handover, updates
     * [lastResult] so the model knows the screen may have been changed by the user, and replays a
     * deferred main-screen spill check if one was skipped during takeover and is still within its
     * fresh observation window.
     */
    internal fun syncControlBeforeStep(
        target: HandsDisplayTarget,
        lastControlVersion: Long?,
        watch: HandsLoopWatch,
        lastResult: String?,
        loopNote: String?,
        pendingSpillWatch: HandsDisplayTarget.SpillWatch?,
        handoverAlreadyNoted: Boolean,
        excluded: Set<String>,
        userTaps: () -> List<MainScreenSpill.Tap>,
        onHandover: (String) -> Unit = {},
        settle: (Long) -> Boolean = ::sleepOrStop,
        awaitFrame: (HandsDisplayTarget) -> Unit = ::awaitFreshFrame,
        nowNanos: () -> Long = System::nanoTime,
        uptimeMs: () -> Long = SystemClock::uptimeMillis,
    ): HandoverSync? {
        var controlVersion = awaitScreenControlVersion(target, nowNanos) ?: return null
        if (lastControlVersion == null) {
            return HandoverSync(
                controlVersion = controlVersion,
                lastResult = lastResult,
                loopNote = loopNote,
                pendingSpillWatch = null,
                handoverOccurred = false,
            )
        }
        var prevVersion = lastControlVersion
        var updatedLastResult = lastResult
        var updatedLoopNote = loopNote
        var pendingSpill = pendingSpillWatch
        var handoverOccurred = false
        var spillRecovered = false
        var noted = handoverAlreadyNoted

        while (true) {
            while (controlVersion != prevVersion) {
                handoverOccurred = true
                watch.reset()
                updatedLoopNote = null
                if (!noted) {
                    updatedLastResult = HANDED_OVER_RESULT
                    onHandover("the user took control; waiting for a fresh look")
                    noted = true
                }
                // Let the virtual display publish the post-input frame past AgentDisplay's 100 ms throttle.
                if (!settle(HANDOVER_SETTLE_MS)) return null
                prevVersion = controlVersion
                controlVersion = awaitScreenControlVersion(target, nowNanos) ?: return null
            }

            val spillWatch = pendingSpill ?: break
            if (uptimeMs() - spillWatch.since > HandsDisplayTarget.SPILL_WINDOW_MS) {
                pendingSpill = null
                clearFrameGuard()
                updatedLastResult = HANDED_OVER_SPILL_SKIPPED_RESULT
                break
            }
            val taps = userTaps()
            val beforeCheckNanos = nowNanos()
            when (val check = checkSpillOrSkip(target, spillWatch, excluded, taps)) {
                SpillCheck.Skipped -> {
                    // Another takeover started before the deferred spill check ran; wait again.
                    controlVersion = awaitScreenControlVersion(target, nowNanos) ?: return null
                    continue
                }
                is SpillCheck.Checked -> {
                    pendingSpill = null
                    when (val verdict = check.verdict) {
                        is MainScreenSpill.PullBack -> {
                            guardFramesFromNow(beforeCheckNanos)
                            spillRecovered = true
                            val pkg = verdict.arrival.packageName ?: "app"
                            updatedLastResult =
                                "$HANDED_OVER_RESULT $pkg had opened on the main screen and was restored to the virtual screen."
                            if (!settle(OPEN_APP_SETTLE_MS)) return null
                            controlVersion = awaitScreenControlVersion(target, nowNanos) ?: return null
                        }
                        is MainScreenSpill.InUse -> {
                            clearFrameGuard()
                            val pkg = verdict.arrival.packageName ?: "app"
                            updatedLastResult =
                                "$HANDED_OVER_RESULT $pkg had opened on the main screen where it is currently in use; left on main screen."
                        }
                        null -> Unit
                    }
                    if (controlVersion == prevVersion) break
                }
            }
        }

        if ((handoverOccurred || spillRecovered) && frameGuardSince != null) {
            awaitFrame(target)
            if (stop.get()) return null
        }

        return HandoverSync(
            controlVersion = controlVersion,
            lastResult = updatedLastResult,
            loopNote = updatedLoopNote,
            pendingSpillWatch = pendingSpill,
            handoverOccurred = handoverOccurred,
            spillRecovered = spillRecovered,
        )
    }

    internal data class AppHintOutcome(
        val lastControlVersion: Long?,
        val lastResult: String,
        val pendingSpillWatch: HandsDisplayTarget.SpillWatch?,
        val handoverNoted: Boolean,
    )

    /**
     * Opens the caller-named [app] before the first model step, syncing [lastControlVersion] to
     * the version observed when opening began so an earlier takeover is not misattributed to after
     * the launch. Returns null if stopped or closed while waiting.
     */
    internal fun openAppHint(
        target: HandsDisplayTarget,
        hint: String,
        app: HandsApps.App?,
        excluded: Set<String>,
        userTaps: () -> List<MainScreenSpill.Tap>,
        onAppStarted: (packageName: String, unmuted: Boolean) -> Unit = { _, _ -> },
        settle: (Long) -> Boolean = ::sleepOrStop,
        awaitFrame: (HandsDisplayTarget) -> Unit = ::awaitFreshFrame,
        nowNanos: () -> Long = System::nanoTime,
    ): AppHintOutcome? {
        val openVersion = awaitScreenControlVersion(target, nowNanos) ?: return null
        val syncedControlVersion = if (target.control != null) openVersion else null
        if (stop.get()) return null
        if (app == null) {
            return AppHintOutcome(
                lastControlVersion = syncedControlVersion,
                lastResult = "no installed app matches “$hint”; open the one you need yourself.",
                pendingSpillWatch = null,
                handoverNoted = false,
            )
        }
        val spillWatch = target.armSpillWatch()
        guardFramesFromNow(nowNanos())
        val res = target.openApp(app.packageName)
        if (res == ShellService.START_ERROR && isControlChanged(target, openVersion)) {
            clearFrameGuard()
            return AppHintOutcome(
                lastControlVersion = syncedControlVersion,
                lastResult = "the user took control before ${app.label} was opened; look at the screen before acting.",
                pendingSpillWatch = null,
                handoverNoted = true,
            )
        }
        if (!settle(OPEN_APP_SETTLE_MS)) return null
        val spillCheck = checkSpillOrSkip(target, spillWatch, excluded, userTaps())
        val unmuted = res == ShellService.START_OK_UNMUTED
        if (spillCheck is SpillCheck.Skipped) {
            resolveFrameGuardAfterSkippedLaunchSpill(res)
            val pending = if (res != ShellService.START_IN_USE_ON_MAIN) spillWatch else null
            if (res == ShellService.START_OK || unmuted) {
                onAppStarted(app.packageName, unmuted)
            }
            return AppHintOutcome(
                lastControlVersion = syncedControlVersion,
                lastResult = HANDED_OVER_RESULT,
                pendingSpillWatch = pending,
                handoverNoted = true,
            )
        }
        val spill = (spillCheck as SpillCheck.Checked).verdict
        val result = if (spill is MainScreenSpill.PullBack) {
            onAppStarted(app.packageName, unmuted)
            if (!settle(OPEN_APP_SETTLE_MS)) return null
            awaitFrame(target)
            if (unmuted) "opened ${app.label} (restored to virtual screen; audio muting unavailable, sound may play on the phone)."
            else "opened ${app.label} (restored to virtual screen)."
        } else if (spill is MainScreenSpill.InUse) {
            clearFrameGuard()
            "app ${app.label} is currently in use on the main screen; left on main screen."
        } else if (res == ShellService.START_OK || unmuted) {
            onAppStarted(app.packageName, unmuted)
            if (!settle(OPEN_APP_SETTLE_MS)) return null
            awaitFrame(target)
            if (unmuted) "opened ${app.label} (audio muting unavailable; sound may play on the phone)."
            else "opened ${app.label}."
        } else if (res == ShellService.START_IN_USE_ON_MAIN) {
            clearFrameGuard()
            "user is currently using ${app.label} on the main screen; waiting or continue on main screen."
        } else {
            clearFrameGuard()
            "could not open ${app.label} (code $res)."
        }
        return AppHintOutcome(
            lastControlVersion = syncedControlVersion,
            lastResult = result,
            pendingSpillWatch = null,
            handoverNoted = false,
        )
    }

    internal data class OpenAppStepOutcome(
        val lastResult: String?,
        val pendingSpillWatch: HandsDisplayTarget.SpillWatch?,
        val handedOverStep: Boolean,
    )

    /**
     * Runs one [HandsAction.OpenApp] step for a resolved [app]. Returns null when stopped during
     * settle so the caller finishes with [Outcome.Stopped].
     */
    internal fun executeOpenAppStep(
        target: HandsDisplayTarget,
        action: HandsAction.OpenApp,
        app: HandsApps.App,
        controlVersion: Long,
        excluded: Set<String>,
        userTaps: () -> List<MainScreenSpill.Tap>,
        onAppStarted: (packageName: String, unmuted: Boolean) -> Unit = { _, _ -> },
        settle: (Long) -> Boolean = ::sleepOrStop,
        awaitFrame: (HandsDisplayTarget) -> Unit = ::awaitFreshFrame,
        nowNanos: () -> Long = System::nanoTime,
    ): OpenAppStepOutcome? {
        val spillWatch = target.armSpillWatch()
        guardFramesFromNow(nowNanos())
        val res = if (action.cold) target.restartApp(app.packageName) else target.openApp(app.packageName)
        if (res == ShellService.START_ERROR && isControlChanged(target, controlVersion)) {
            clearFrameGuard()
            return OpenAppStepOutcome(
                lastResult = null,
                pendingSpillWatch = null,
                handedOverStep = true,
            )
        }
        if (!settle(OPEN_APP_SETTLE_MS)) return null
        val spillCheck = checkSpillOrSkip(target, spillWatch, excluded, userTaps())
        val unmuted = res == ShellService.START_OK_UNMUTED
        if (spillCheck is SpillCheck.Skipped) {
            resolveFrameGuardAfterSkippedLaunchSpill(res)
            val pending = if (res != ShellService.START_IN_USE_ON_MAIN) spillWatch else null
            val result = if (res == ShellService.START_OK || unmuted) {
                onAppStarted(app.packageName, unmuted)
                if (unmuted) "opened ${app.label} (audio muting unavailable; sound may play on the phone)."
                else "opened ${app.label}."
            } else if (res == ShellService.START_IN_USE_ON_MAIN) {
                "cannot open ${app.label}: user is currently using it on the main screen."
            } else {
                "could not open ${app.label} (code $res)."
            }
            return OpenAppStepOutcome(
                lastResult = result,
                pendingSpillWatch = pending,
                handedOverStep = false,
            )
        }
        val spill = (spillCheck as SpillCheck.Checked).verdict
        val result = if (spill is MainScreenSpill.PullBack) {
            onAppStarted(app.packageName, unmuted)
            if (!settle(OPEN_APP_SETTLE_MS)) return null
            awaitFrame(target)
            if (unmuted) "opened ${app.label} (restored to virtual screen; audio muting unavailable, sound may play on the phone)."
            else "opened ${app.label} (restored to virtual screen)."
        } else if (spill is MainScreenSpill.InUse) {
            clearFrameGuard()
            "cannot open ${app.label}: user is currently using it on the main screen."
        } else if (res == ShellService.START_OK || unmuted) {
            onAppStarted(app.packageName, unmuted)
            if (!settle(OPEN_APP_SETTLE_MS)) return null
            awaitFrame(target)
            if (unmuted) "opened ${app.label} (audio muting unavailable; sound may play on the phone)."
            else "opened ${app.label}."
        } else if (res == ShellService.START_IN_USE_ON_MAIN) {
            clearFrameGuard()
            "cannot open ${app.label}: user is currently using it on the main screen."
        } else {
            clearFrameGuard()
            "could not open ${app.label} (code $res)."
        }
        return OpenAppStepOutcome(
            lastResult = result,
            pendingSpillWatch = null,
            handedOverStep = false,
        )
    }

    private fun isControlChanged(target: HandsDisplayTarget, controlVersion: Long?): Boolean {
        val control = target.control ?: return false
        return (controlVersion != null && control.version != controlVersion) ||
            control.current != HandsControl.State.Automatic
    }

    private fun <T> runAutomatic(target: HandsDisplayTarget, block: () -> T): T? {
        val control = target.control ?: return block()
        return control.automatic(block)
    }

    fun run(opts: Options): Result {
        memoryTicket = null
        memoryTask = opts.task
        memoryApp = null
        val runId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val log = mutableListOf<String>()
        // where each model call sits, for the relay (relay 0.31): this run as the trajectory, the
        // step being thought about, and the chat the task came from; one `hands.step` event per
        // step and one at the end go to the account's record as codes (ClientEvents)
        val where = Provenance.Hands(runId)
        val calling = where + (opts.sessionId?.let { Provenance.Session(it) } ?: EmptyCoroutineContext)
        eventSession = opts.sessionId
        val traceDir = traceDir(opts.sessionId, runId)
        var lastScreen: String? = null
        var steps = 0
        val model = Hands.screenModel(context)
        Hands.setActive(true)
        Hands.current = this
        gateSession = opts.sessionId ?: "hands"
        imeConsent = null
        imeFailures = 0
        pausedHandoverMs = 0L
        SessionActivityTracker.setCameraSuppressActive(true) // one capsule at a time: ours, not OpenMinis'
        capsule.onStop = { requestStop() }
        capsule.onContinue = { userSignal.get()?.countDown() }
        capsule.onOpenApp = { capsule.bringAppToFront(opts.sessionId) }
        // the approval answered where the person is: the same request the chat card shows,
        // and only ours (a shell or browser card of another conversation is not the capsule's to answer)
        capsule.onAllow = { ownPendingRequest()?.let { RiskGate.decide(it.id, RiskDecision.ALLOW_ONCE) } }
        capsule.onDeny = { ownPendingRequest()?.let { RiskGate.decide(it.id, RiskDecision.DENY) } }
        val started = System.currentTimeMillis()
        var displayTarget: HandsDisplayTarget? = null
        var frameForwardJob: Job? = null
        var tapListener: ((MinisAccessibilityService.RecordedEvent) -> Unit)? = null
        var a11ySvc: MinisAccessibilityService? = null
        val userTaps = ConcurrentLinkedDeque<MainScreenSpill.Tap>()
        try {
            synchronized(stopLock) { workerThread = Thread.currentThread() }
            if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model?.label)
            val svc = MinisAccessibilityService.getInstance()
                ?: return finish(Outcome.Failed("the accessibility service is not running"), steps, runId, lastScreen, traceDir, log, model?.label)
            a11ySvc = svc
            val listener: (MinisAccessibilityService.RecordedEvent) -> Unit = { rec ->
                if (rec.type == "TYPE_VIEW_CLICKED" || rec.type == "TYPE_VIEW_LONG_CLICKED") {
                    if (rec.packageName != context.packageName && rec.displayId == Display.DEFAULT_DISPLAY) {
                        userTaps.addLast(MainScreenSpill.Tap(rec.eventTime, rec.packageName))
                        while (userTaps.size > 50) userTaps.pollFirst()
                    }
                }
            }
            svc.addEventListener(listener)
            tapListener = listener
            if (Build.VERSION.SDK_INT < Hands.MIN_SDK) {
                return finish(Outcome.Failed("screenshots need Android 11"), steps, runId, lastScreen, traceDir, log, model?.label)
            }
            if (model == null) return finish(Outcome.Failed("no model that can see pictures is configured"), steps, runId, lastScreen, traceDir, log, null)
            val provider = ProviderFactory.create(model.instance, model.apiKey, model.entry.model, context)
            val apps = HandsApps.launchable(context)
            val system = HandsPrompt.system(opts.task, apps.map { it.label }.take(MAX_APPS_IN_PROMPT), opts.agentName, opts.language)
            val turns = mutableListOf<Turn>()
            var lastResult: String? = null
            var formatErrors = 0
            var blackScreens = 0
            var frameWaits = 0
            var modelErrors = 0
            // the same action on an unchanged screen: a note at the third, a stop at the sixth
            val watch = HandsLoopWatch()
            var loopNote: String? = null

            val dm = context.resources.displayMetrics
            val target: HandsDisplayTarget = if (Hands.virtualScreenEnabled(context)) {
                if (!SharedShell.isReady()) {
                    return finish(
                        Outcome.Failed(context.getString(R.string.nm_hands_virtual_screen_unavailable)),
                        steps, runId, lastScreen, traceDir, log, model.label,
                    )
                }
                try {
                    val shell = SharedShell.shell.value!!
                    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val agentDisplay = AgentDisplay(shell, dm.widthPixels, dm.heightPixels, dm.densityDpi, displayManager)
                    VirtualDisplayTarget(agentDisplay, shell, context)
                } catch (t: Throwable) {
                    AppLogger.warning(TAG, "failed to create virtual display target: ${t.message}")
                    return finish(
                        Outcome.Failed(context.getString(R.string.nm_hands_virtual_screen_init_failed, t.message ?: "unknown")),
                        steps, runId, lastScreen, traceDir, log, model.label,
                    )
                }
            } else {
                MainDisplayTarget(context, svc, dm.widthPixels, dm.heightPixels)
            }
            displayTarget = target
            frameForwardJob = if (target.isVirtual) {
                capsule.setPipInput(target.pipInput)
                capsule.setPreviewFlow(target.previewFrames)
                Hands.setLiveFrames(target.previewFrames?.value)
                target.previewFrames?.let { flow ->
                    CoroutineScope(Dispatchers.Default).launch {
                        flow.collect { Hands.setLiveFrames(it) }
                    }
                }
            } else {
                capsule.setPipInput(null)
                capsule.setPreviewFlow(null)
                Hands.setLiveFrames(null)
                null
            }

            var lastControlVersion: Long? = target.control?.version
            var pendingSpillWatch: HandsDisplayTarget.SpillWatch? = null
            var handoverNoted = false

            // The app the caller named comes first, before the model looks.
            opts.appHint?.takeIf { it.isNotBlank() }?.let { hint ->
                val outcome = openAppHint(
                    target = target,
                    hint = hint,
                    app = HandsApps.resolve(context, hint),
                    excluded = notSpills,
                    userTaps = { userTaps.toList() },
                    onAppStarted = { pkg, unmuted ->
                        memoryApp = pkg
                        memoryTicket = HandsRunMemory.shared.begin(opts.sessionId, pkg)
                        if (unmuted) capsule.setAudioNotice(context.getString(R.string.nm_hands_audio_unmuted_note))
                    },
                ) ?: return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                lastControlVersion = outcome.lastControlVersion
                lastResult = outcome.lastResult
                pendingSpillWatch = outcome.pendingSpillWatch
                handoverNoted = outcome.handoverNoted
                log += "open $hint → $lastResult"
            }

            while (opts.maxSteps <= 0 || steps < opts.maxSteps) {
                if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                val sync = syncControlBeforeStep(
                    target = target,
                    lastControlVersion = lastControlVersion,
                    watch = watch,
                    lastResult = lastResult,
                    loopNote = loopNote,
                    pendingSpillWatch = pendingSpillWatch,
                    handoverAlreadyNoted = handoverNoted,
                    excluded = notSpills,
                    userTaps = { userTaps.toList() },
                    onHandover = { msg -> log += if (steps > 0) "after step $steps: $msg" else msg },
                ) ?: return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                val controlVersion = sync.controlVersion
                lastControlVersion = if (target.control != null) controlVersion else null
                lastResult = sync.lastResult
                loopNote = sync.loopNote
                pendingSpillWatch = sync.pendingSpillWatch
                handoverNoted = false
                if (isRunTimeLimitExceeded(System.currentTimeMillis(), started)) {
                    return finish(Outcome.Stopped("the run hit its time limit"), steps, runId, lastScreen, traceDir, log, model.label)
                }
                steps++
                KeepAwake.a11yTouched()
                capsule.working(steps, context.getString(com.openminis.app.R.string.nm_hands_looking))

                // ── look ──
                val shot = screenshot(svc, target)
                if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                if (isControlChanged(target, controlVersion)) {
                    shot?.recycle()
                    steps--
                    continue
                }
                val screenW = shot?.width ?: target.width
                val screenH = shot?.height ?: target.height
                val jpeg = shot?.let { try { encode(it) } finally { it.recycle() } }
                val screenState = HandsScreenState.classify(
                    virtual = target.isVirtual,
                    hasTask = target.hasTask(),
                    hasAppWindow = ScreenWords.windowsOn(svc, target.displayId).any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION },
                    hasFrame = jpeg != null,
                    black = jpeg?.black == true,
                )
                if (screenState == HandsScreenState.WAITING_FOR_FRAME) {
                    if (isControlChanged(target, controlVersion)) {
                        steps--
                        continue
                    }
                    frameWaits++
                    if (frameWaits >= MAX_FRAME_WAITS) return finish(Outcome.Failed("the app did not provide a readable screen frame"), steps, runId, lastScreen, traceDir, log, model.label)
                    steps--
                    if (!sleepOrStop(500)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    continue
                }
                frameWaits = 0
                if (isControlChanged(target, controlVersion)) {
                    steps--
                    continue
                }
                if (jpeg != null && screenState != HandsScreenState.EMPTY) lastScreen = save(traceDir, steps, jpeg, runId)
                if (screenState == HandsScreenState.PROTECTED) {
                    blackScreens++
                    if (blackScreens > 2) return finish(Outcome.Infeasible("the screen stays protected (black screenshot); the app does not allow it to be read"), steps, runId, lastScreen, traceDir, log, model.label)
                    log += "step $steps: protected screen — handed to the user"
                    val cont = takeOver(target, context.getString(com.openminis.app.R.string.nm_hands_protected_screen))
                    if (!cont || stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    lastControlVersion = target.control?.version
                    watch.reset()
                    loopNote = null
                    lastResult = "the screen was protected (payment, authorization or banking); the user did that part and says to continue."
                    continue
                }
                blackScreens = 0

                // ── think ──
                val emptyScreen = screenState == HandsScreenState.EMPTY
                val turnText = HandsPrompt.turnText(
                    steps,
                    lastResult,
                    previousRun = memoryTicket?.previous.takeIf { turns.isEmpty() },
                    emptyScreen = emptyScreen,
                )
                val currentImage = jpeg?.bytes?.takeUnless { emptyScreen }
                val messages = buildMessages(turns, turnText, currentImage)
                where.step = steps
                val thinkingFrom = System.currentTimeMillis()
                val reply = try {
                    if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    callModel(calling) {
                        provider.sendMessage(messages, system, maxTokens = 1024, temperature = 0.0, thinkingLevel = ThinkingLevel.OFF).text
                    }
                } catch (t: Throwable) {
                    if (isStopFailure(t)) {
                        return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    }
                    modelErrors++
                    AppLogger.warning(TAG, "model call failed: ${t.message}")
                    if (modelErrors >= 3) return finish(Outcome.Failed("the screen model failed three times: ${t.message}"), steps, runId, lastScreen, traceDir, log, model.label)
                    steps--
                    if (!sleepOrStop(1500)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    continue
                }
                val thoughtMs = System.currentTimeMillis() - thinkingFrom
                if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                val turn = Turn(turnText, currentImage, reply)
                turns += turn
                val parsed = try {
                    HandsAction.parse(reply)
                } catch (e: HandsAction.Companion.FormatError) {
                    formatErrors++
                    if (formatErrors >= 3) return finish(Outcome.Failed("the screen model did not answer in the action format (${e.message})"), steps, runId, lastScreen, traceDir, log, model.label)
                    lastResult = "your reply could not be read (${e.message}); answer with `Thought:` and one JSON `Action:` only."
                    log += "step $steps: unreadable reply"
                    trace(traceDir, steps, "", reply, lastResult, lastScreen)
                    step(runId, steps, "unreadable", "failed", thoughtMs)
                    continue
                }
                formatErrors = 0
                val action = parsed.action

                // ── check handover before updating UI or loop watch ──
                if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                if (!awaitScreenControl(target)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                if (isControlChanged(target, controlVersion)) {
                    watch.reset()
                    loopNote = null
                    lastResult = HANDED_OVER_RESULT
                    handoverNoted = true
                    log += "step $steps: the user took control; waiting for a fresh look"
                    trace(traceDir, steps, parsed.thought, reply, lastResult.orEmpty(), lastScreen)
                    step(runId, steps, actionWord(action), "handed_over", thoughtMs)
                    continue
                }

                capsule.working(steps, parsed.thought.ifBlank { action.describe() }.take(140))
                log += "step $steps: ${action.describe()}" + (parsed.thought.takeIf { it.isNotBlank() }?.let { " — ${it.take(100)}" } ?: "")

                // ── the same thing again on the same screen? ──
                when (watch.step(if (emptyScreen) intArrayOf(0) else jpeg!!.print, action)) {
                    HandsLoopWatch.Verdict.STOP -> {
                        val why = HandsLoopWatch.stopReason(action)
                        log += "step $steps: $why"
                        trace(traceDir, steps, parsed.thought, reply, why, lastScreen)
                        step(runId, steps, actionWord(action), "stopped", thoughtMs)
                        return finish(Outcome.Infeasible(why), steps, runId, lastScreen, traceDir, log, model.label)
                    }
                    HandsLoopWatch.Verdict.NOTE -> {
                        log += "step $steps: the same action on an unchanged screen, ${watch.streak} times running"
                        loopNote = HandsLoopWatch.NOTE
                    }
                    HandsLoopWatch.Verdict.FINE -> loopNote = null
                }

                // ── act ──
                if (!screenState.allows(action)) {
                    lastResult = "no action was performed: the virtual screen has no app open. Use open_app, ask_user or status; do not act on an earlier screenshot."
                    trace(traceDir, steps, parsed.thought, reply, lastResult, null)
                    step(runId, steps, actionWord(action), "refused", thoughtMs)
                    continue
                }
                var handedOverStep = false
                when (action) {
                    is HandsAction.Status -> {
                        trace(traceDir, steps, parsed.thought, reply, "finished", lastScreen)
                        return finish(
                            if (action.complete) Outcome.Done(action.answer.ifBlank { parsed.thought }) else Outcome.Infeasible(action.answer.ifBlank { parsed.thought }),
                            steps, runId, lastScreen, traceDir, log, model.label,
                        )
                    }
                    is HandsAction.AskUser -> {
                        trace(traceDir, steps, parsed.thought, reply, "asked the user", lastScreen)
                        return finish(Outcome.NeedsUser(action.question.ifBlank { parsed.thought }), steps, runId, lastScreen, traceDir, log, model.label)
                    }
                    is HandsAction.TakeOver -> {
                        val cont = takeOver(target, action.reason.ifBlank { parsed.thought })
                        step(runId, steps, "take_over", if (cont) "handed_over" else "stopped", thoughtMs)
                        if (!cont || stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        lastControlVersion = target.control?.version
                        watch.reset()
                        loopNote = null
                        lastResult = "the user did it themselves and says to continue; look at the screen as it is now."
                    }
                    is HandsAction.Click, is HandsAction.DoubleTap, is HandsAction.LongPress -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        val (px, py) = when (action) {
                            is HandsAction.Click -> action.at.toPixels(screenW, screenH)
                            is HandsAction.DoubleTap -> action.at.toPixels(screenW, screenH)
                            is HandsAction.LongPress -> action.at.toPixels(screenW, screenH)
                            else -> 0 to 0
                        }
                        // the label the model gave, read next to what the screen itself says is there
                        val targetLabel = action.tapTarget.orEmpty()
                        val onScreen = ScreenWords.at(svc, target.displayId, px, py)
                        val gate = approveTap(svc, opts.sessionId, targetLabel, onScreen, target)
                        if (gate.denied != null) {
                            trace(traceDir, steps, parsed.thought, reply, "refused: ${gate.denied}", lastScreen)
                            step(runId, steps, actionWord(action), "refused", thoughtMs)
                            return finish(Outcome.Infeasible("the user did not allow the tap “${gate.shown.take(40)}” — ${gate.denied}"), steps, runId, lastScreen, traceDir, log, model.label)
                        }
                        // A remembered approval, or a payment that ran on one: the chat model
                        // sees it in the log and tells the user where it lives.
                        gate.notice?.let { log += it }
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (gate.reobserve) {
                            lastControlVersion = target.control?.version
                            watch.reset()
                            loopNote = null
                            lastResult = gate.notice
                            trace(traceDir, steps, parsed.thought, reply, lastResult.orEmpty(), lastScreen)
                            step(runId, steps, actionWord(action), "handed_over", thoughtMs)
                            continue
                        }
                        if (!awaitScreenControl(target)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (isControlChanged(target, controlVersion)) {
                            handedOverStep = true
                        } else {
                            val spillWatch = target.armSpillWatch()
                            val ok = if (target.isVirtual) {
                                when (action) {
                                    is HandsAction.LongPress -> target.longPress(px, py)
                                    is HandsAction.DoubleTap -> virtualDoubleTap(target, px, py)
                                    else -> target.tap(px, py)
                                }
                            } else {
                                val tapOk = capsule.withGesture(px, py, px, py) {
                                    capsule.stage.aim(px, py, fxLabel(action))
                                    if (!sleepOrStop(AIM_MS)) return@withGesture false
                                    when (action) {
                                        is HandsAction.LongPress -> { capsule.stage.hold(LONG_PRESS_MS); tap(svc, px, py, LONG_PRESS_MS) }
                                        is HandsAction.DoubleTap -> doubleTap({ tap(svc, px, py, 50) }) { capsule.stage.ripple(px, py) }
                                        else -> tap(svc, px, py, 60)
                                    }
                                }
                                if (!stop.get()) capsule.stage.ripple(px, py)
                                tapOk
                            }
                            if (!ok && isControlChanged(target, controlVersion)) {
                                handedOverStep = true
                            } else {
                                if (!sleepOrStop(TAP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                                val spillCheck = checkSpillOrSkip(target, spillWatch, notSpills, userTaps.toList())
                                if (spillCheck is SpillCheck.Skipped) {
                                    if (ok) pendingSpillWatch = spillWatch
                                    lastResult = if (ok) {
                                        "${action.describe()} at ($px, $py) was performed."
                                    } else {
                                        "the gesture at ($px, $py) was rejected by the system; try again or another way."
                                    }
                                } else {
                                    val spill = (spillCheck as SpillCheck.Checked).verdict
                                    lastResult = when {
                                        spill is MainScreenSpill.PullBack ->
                                            "${action.describe()} at ($px, $py) opened ${spill.arrival.packageName ?: "app"} on main screen, restored to virtual screen."
                                        spill is MainScreenSpill.InUse ->
                                            "${action.describe()} at ($px, $py) opened ${spill.arrival.packageName ?: "app"} on main screen where it is currently in use; left on main screen."
                                        ok ->
                                            "${action.describe()} at ($px, $py) was performed."
                                        else ->
                                            "the gesture at ($px, $py) was rejected by the system; try again or another way."
                                    }
                                }
                            }
                        }
                    }
                    is HandsAction.Swipe -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        val (x1, y1) = action.from.toPixels(screenW, screenH)
                        val (x2, y2) = action.to.toPixels(screenW, screenH)
                        val ok = if (target.isVirtual) {
                            target.swipe(x1, y1, x2, y2, SWIPE_MS)
                        } else {
                            sweep(svc, x1, y1, x2, y2, SWIPE_MS, fxLabel(action))
                        }
                        if (!ok && isControlChanged(target, controlVersion)) {
                            handedOverStep = true
                        } else {
                            lastResult = if (ok) "swiped from ($x1, $y1) to ($x2, $y2)." else "the swipe was rejected by the system."
                            if (!sleepOrStop(SWIPE_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        }
                    }
                    is HandsAction.Scroll -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        val (from, to) = HandsScroll.gesture(action.direction, action.distance, screenW, screenH)
                        val ok = if (target.isVirtual) {
                            target.swipe(from.first, from.second, to.first, to.second, SCROLL_MS)
                        } else {
                            sweep(svc, from.first, from.second, to.first, to.second, SCROLL_MS, fxLabel(action))
                        }
                        if (!ok && isControlChanged(target, controlVersion)) {
                            handedOverStep = true
                        } else {
                            lastResult = if (ok) "scrolled ${action.direction}." else "the scroll was rejected by the system."
                            if (!sleepOrStop(SWIPE_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        }
                    }
                    is HandsAction.InputText -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (!target.isVirtual && !TapWords.looksSecret(action.field)) capsule.stage.say(fxLabel(action))
                        val typedResult = typeText(svc, action, target, controlVersion = controlVersion)
                        if (typedResult == HANDED_OVER) {
                            handedOverStep = true
                        } else if (typedResult == HANDOFF) {
                            val cont = takeOver(target, context.getString(com.openminis.app.R.string.nm_hands_secret_field))
                            if (!cont || stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                            lastControlVersion = target.control?.version
                            watch.reset()
                            loopNote = null
                            lastResult = "that field is a password or code field, which you never type; the user filled it in and says to continue."
                            if (!sleepOrStop(TAP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        } else {
                            lastResult = typedResult
                            if (!sleepOrStop(TAP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        }
                    }
                    HandsAction.KeyboardEnter -> {
                        // Enter in a messenger's field *is* the send button: the same card a tap on “Send” gets.
                        val gate = approveEnter(svc, opts.sessionId, target)
                        if (gate.denied != null) {
                            trace(traceDir, steps, parsed.thought, reply, "refused: ${gate.denied}", lastScreen)
                            step(runId, steps, "enter", "refused", thoughtMs)
                            return finish(Outcome.Infeasible("Enter was not pressed: ${gate.denied}"), steps, runId, lastScreen, traceDir, log, model.label)
                        }
                        gate.notice?.let { log += it }
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (!awaitScreenControl(target)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (isControlChanged(target, controlVersion)) {
                            handedOverStep = true
                        } else {
                            if (!target.isVirtual) capsule.stage.say(fxLabel(action))
                            val enterResult = pressEnter(svc, target, controlVersion)
                            if (enterResult == HANDED_OVER || (enterResult != "pressed Enter." && isControlChanged(target, controlVersion))) {
                                handedOverStep = true
                            } else {
                                lastResult = enterResult
                                if (!sleepOrStop(TAP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                            }
                        }
                    }
                    is HandsAction.OpenApp -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        val app = HandsApps.resolve(context, action.appName)
                        if (app != null) {
                            val outcome = executeOpenAppStep(
                                target = target,
                                action = action,
                                app = app,
                                controlVersion = controlVersion,
                                excluded = notSpills,
                                userTaps = { userTaps.toList() },
                                onAppStarted = { pkg, unmuted ->
                                    // A note from a multi-app run must not be reused as one app's history.
                                    if (pkg != memoryApp) memoryTicket = null
                                    if (unmuted) capsule.setAudioNotice(context.getString(R.string.nm_hands_audio_unmuted_note))
                                    if (!target.isVirtual) capsule.stage.say(context.getString(com.openminis.app.R.string.nm_hands_fx_open, app.label))
                                },
                            ) ?: return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                            if (outcome.handedOverStep) {
                                handedOverStep = true
                            } else {
                                lastResult = outcome.lastResult
                                pendingSpillWatch = outcome.pendingSpillWatch
                            }
                        } else {
                            lastResult = "no installed app matches “${action.appName}”. Installed: " + apps.take(40).joinToString(", ") { it.label } + ". Open it from the home screen if you must."
                        }
                    }
                    HandsAction.Back -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (!target.isVirtual) capsule.stage.say(fxLabel(action))
                        val ok = target.pressBack()
                        if (!ok && isControlChanged(target, controlVersion)) {
                            handedOverStep = true
                        } else {
                            lastResult = if (ok) "pressed Back." else "the Back action was rejected by the system."
                            if (!sleepOrStop(TAP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        }
                    }
                    HandsAction.Home -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (!target.isVirtual) capsule.stage.say(fxLabel(action))
                        val ok = target.pressHome()
                        if (!ok && isControlChanged(target, controlVersion)) {
                            handedOverStep = true
                        } else {
                            lastResult = if (ok) "went to the home screen." else "the Home action was rejected by the system."
                            if (!sleepOrStop(TAP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        }
                    }
                    is HandsAction.Wait -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (!target.isVirtual) capsule.stage.say(fxLabel(action))
                        if (!sleepOrStop(action.seconds * 1000L)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        lastResult = "waited ${action.seconds}s."
                    }
                }
                if (handedOverStep) {
                    if (!awaitScreenControl(target)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    watch.reset()
                    loopNote = null
                    lastResult = HANDED_OVER_RESULT
                    handoverNoted = true
                    log += "step $steps: the user took control; waiting for a fresh look"
                    trace(traceDir, steps, parsed.thought, reply, lastResult.orEmpty(), lastScreen)
                    step(runId, steps, actionWord(action), "handed_over", thoughtMs)
                    continue
                }
                loopNote?.let { note -> lastResult = listOfNotNull(lastResult, note).joinToString(" ") }
                trace(traceDir, steps, parsed.thought, reply, lastResult ?: "", lastScreen)
                if (action !is HandsAction.TakeOver) step(runId, steps, actionWord(action), if (lastResult?.contains("rejected by the system") == true) "failed" else "ok", thoughtMs)
            }
            return finish(Outcome.Infeasible("the step limit (${opts.maxSteps}) was reached before the task was done"), steps, runId, lastScreen, traceDir, log, model.label, end = "max_steps")
        } catch (t: Throwable) {
            if (isStopFailure(t)) {
                return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model?.label)
            }
            AppLogger.warning(TAG, "run failed: ${t.javaClass.simpleName} ${t.message}")
            return finish(Outcome.Failed("${t.javaClass.simpleName}: ${t.message}"), steps, runId, lastScreen, traceDir, log, model?.label)
        } finally {
            synchronized(stopLock) {
                workerThread = null
                currentJob = null
                // This is a shared sandbox worker; the next task must not inherit our interrupt.
                if (stop.get()) Thread.interrupted()
            }
            tapListener?.let { a11ySvc?.removeEventListener(it) }
            frameForwardJob?.cancel()
            capsule.setPipInput(null)
            capsule.setPreviewFlow(null)
            Hands.setLiveFrames(null)
            displayTarget?.release()
            capsule.hide()
            Hands.current = null
            Hands.setActive(false)
            SessionActivityTracker.setCameraSuppressActive(false)
            if (displayTarget?.isVirtual != true) {
                capsule.bringAppToFront(opts.sessionId)
            }
        }
    }

    private fun finish(outcome: Outcome, steps: Int, runId: String, lastScreen: String?, traceDir: File?, log: List<String>, model: String?, end: String? = null): Result {
        // the run's end for the record: `done`, `ask`, `blocked`, `stopped`, `failed`, `max_steps` (docs/cloud.md "Client events")
        val word = end ?: when (outcome) {
            is Outcome.Done -> "done"
            is Outcome.NeedsUser -> "ask"
            is Outcome.Infeasible -> "blocked"
            is Outcome.Stopped -> "stopped"
            is Outcome.Failed -> "failed"
        }
        step(runId, steps, "end", word, null)
        val summary = when (outcome) {
            is Outcome.Done -> outcome.answer
            is Outcome.NeedsUser -> outcome.question
            is Outcome.Infeasible -> outcome.message
            is Outcome.Stopped -> outcome.message
            is Outcome.Failed -> outcome.message
        }
        HandsRunMemory.shared.finish(memoryTicket, memoryTask, outcome.code, summary, log)
        return Result(outcome, steps, runId, lastScreen, traceDir?.let { "/var/minis/attachments/hands/$runId/trace.jsonl" }, log, model)
    }

    /** One `hands.step` to the record: the trajectory, the step, the action's kind and how it went; never the screen or the words. */
    private fun step(runId: String, step: Int, action: String, outcome: String?, latencyMs: Long?) {
        runCatching { ClientEvents.handsStep(eventSession, runId, step, action, outcome, latencyMs) }
    }

    /** The action's kind, as the record names it. */
    private fun actionWord(a: HandsAction): String = when (a) {
        is HandsAction.Click -> "tap"
        is HandsAction.DoubleTap -> "double_tap"
        is HandsAction.LongPress -> "long_press"
        is HandsAction.Swipe -> "swipe"
        is HandsAction.Scroll -> "scroll"
        is HandsAction.InputText -> "type"
        HandsAction.KeyboardEnter -> "enter"
        is HandsAction.OpenApp -> "open_app"
        HandsAction.Back -> "back"
        HandsAction.Home -> "home"
        is HandsAction.Wait -> "wait"
        is HandsAction.TakeOver -> "take_over"
        is HandsAction.AskUser -> "ask"
        is HandsAction.Status -> "status"
    }

    // ── the user's turn ────────────────────────────────────────────────────

    /** A successful Continue only resumes automation after the same task is back on its display. */
    internal fun takeOver(
        target: HandsDisplayTarget,
        reason: String,
        awaitUser: (String) -> Boolean = ::waitForUser,
    ): Boolean {
        ensureRunning()
        target.control?.handBack()
        if (target.isVirtual) capsule.setPipInput(null)
        var moved = false
        val continued = try {
            check(target.onTakeoverStart()) { context.getString(R.string.nm_hands_takeover_move_failed) }
            moved = true
            ensureRunning()
            awaitUser(reason)
        } finally {
            // Stop interrupts the worker, but moving the user's app back is still required cleanup.
            val interrupted = Thread.interrupted()
            try {
                target.control?.handBack()
                if (moved) {
                    // 挪回虚拟屏后画面会重画一遍，之后的第一帧才是这块屏现在的样子。
                    if (target.isVirtual) guardFramesFromNow()
                    check(target.onTakeoverEnd()) { context.getString(R.string.nm_hands_takeover_restore_failed) }
                }
            } finally {
                if (target.isVirtual) capsule.setPipInput(target.pipInput)
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        ensureRunning()
        awaitFreshFrame(target)
        return continued
    }

    /** Shows the take-over card and waits for Continue; false when Stop was tapped or nobody came. */
    private fun waitForUser(reason: String): Boolean {
        val latch = CountDownLatch(1)
        userSignal.set(latch)
        capsule.takeOver(reason)
        val came = try {
            latch.await(TAKE_OVER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            userSignal.compareAndSet(latch, null)
        }
        if (!came) requestStop("nobody continued within ${TAKE_OVER_TIMEOUT_MS / 60_000} minutes")
        if (stop.get()) return false
        capsule.working(0, context.getString(com.openminis.app.R.string.nm_hands_looking))
        if (!sleepOrStop(600)) return false
        return true
    }

    /** A manual step consumes the old coordinates; only a new observation may choose another action. */
    internal data class TapGate(
        val denied: String? = null,
        val notice: String? = null,
        val shown: String = "",
        val reobserve: Boolean = false,
    )

    internal fun manualPayment(
        target: HandsDisplayTarget,
        shown: String,
        awaitUser: (String) -> Boolean = ::waitForUser,
    ): TapGate {
        val continued = takeOver(target, context.getString(R.string.nm_hands_payment_takeover), awaitUser)
        return if (continued) {
            TapGate(
                shown = shown,
                reobserve = true,
                notice = "the user continued after handling the payment step on the main screen; no tap was replayed. Read the new screen to verify the result.",
            )
        } else TapGate(denied = "payment cancelled by user", shown = shown)
    }

    /**
     * The approval for a tap whose label says pay, send, post or delete. The person is in the
     * operated app, so the capsule takes the answer itself (Allow once / Deny); the card in the
     * chat and the notification show the same request. Money needs the screen lock, which only
     * the card can ask for: the capsule then offers Open.
     */
    private fun approveTap(
        svc: MinisAccessibilityService,
        sessionId: String?,
        targetLabel: String,
        onScreen: String?,
        target: HandsDisplayTarget? = null,
    ): TapGate {
        val cls = TapWords.classify(targetLabel, onScreen) ?: RiskClass.SAFE
        val shown = if (!onScreen.isNullOrBlank() && TapWords.classify(onScreen) == cls) onScreen
        else TapWords.shown(targetLabel, onScreen)
        val pkg = if (target != null) target.foregroundPackage(svc) else svc.foregroundPackage().first
        val appLabel = HandsApps.labelOf(context, pkg)
        val short = shown.take(40)
        val reason = if (cls == RiskClass.SAFE) "taps “$short”" else TapWords.reason(cls, short, appLabel)
        val assessment = RiskAssessment(cls, reason, appLabel?.let { "app:$it" })
        val decidable = cls != RiskClass.MONEY

        // Money on virtual screen requires user takeover on physical screen
        if (target?.isVirtual == true && !decidable) {
            capsule.working(0, context.getString(com.openminis.app.R.string.nm_hands_approval_title))
            return manualPayment(target, shown)
        }

        if (assessment.needsApproval) {
            capsule.approval(context.getString(if (decidable) com.openminis.app.R.string.nm_hands_approval_here else com.openminis.app.R.string.nm_hands_approval_detail, short), decidable)
        }
        val outcome = runBlocking {
            RiskGate.check(sessionId ?: "hands", GuardKind.SCREEN, assessment, preview = "Tap “$short”", pageUrl = appLabel, elementText = short)
        }
        ensureRunning()
        // The user may have opened nanoMuse to answer; put the operated app back in front on physical screen.
        if (target?.isVirtual != true) {
            val (nowPkg, _) = svc.foregroundPackage()
            if (nowPkg == context.packageName && pkg != null && pkg != context.packageName) {
                HandsApps.launchable(context).firstOrNull { it.packageName == pkg }?.let {
                    ensureRunning()
                    HandsApps.open(context, it)
                }
                if (!sleepOrStop(OPEN_APP_SETTLE_MS)) return TapGate(denied = "stopped", shown = shown)
            }
        }
        capsule.working(0, context.getString(com.openminis.app.R.string.nm_hands_looking))
        return when (outcome) {
            is GateOutcome.Allowed -> TapGate(notice = outcome.notice, shown = shown)
            is GateOutcome.Denied -> TapGate(denied = outcome.message, shown = shown)
        }
    }

    /**
     * Enter with the cursor in a message field sends the message (0.1.31): in a messenger, or
     * when the field's hint says so, it takes the outbound card a tap on “Send” would. Enter in
     * a search box, a form or a terminal is an ordinary key.
     */
    private fun approveEnter(svc: MinisAccessibilityService, sessionId: String?, target: HandsDisplayTarget? = null): TapGate {
        val node = focusedField(svc, target)
        val hint = listOfNotNull(node?.hintText?.toString(), node?.contentDescription?.toString()).joinToString(" ")
        val pkg = if (target != null) target.foregroundPackage(svc) ?: node?.packageName?.toString()
        else node?.packageName?.toString() ?: svc.foregroundPackage().first
        if (target?.isVirtual == true && node == null) {
            return TapGate(denied = "cannot identify the focused field on the virtual screen; Enter was not pressed", shown = "Enter")
        }
        val sends = TapWords.entersSend(pkg, hint)
        val appLabel = HandsApps.labelOf(context, pkg)
        val where = appLabel ?: context.getString(com.openminis.app.R.string.nm_hands_this_phone)
        val assessment = RiskAssessment(
            if (sends) RiskClass.OUTBOUND else RiskClass.SAFE,
            if (sends) "presses Enter in a message field, which sends it ($where)" else "presses Enter in a text field ($where)",
            appLabel?.let { "app:$it" },
        )
        if (assessment.needsApproval) {
            capsule.approval(context.getString(com.openminis.app.R.string.nm_hands_approval_here, context.getString(com.openminis.app.R.string.nm_hands_fx_enter)), decidable = true)
        }
        val outcome = runBlocking {
            RiskGate.check(sessionId ?: "hands", GuardKind.SCREEN, assessment, preview = if (sends) "Enter → send" else "Enter", pageUrl = appLabel, elementText = context.getString(com.openminis.app.R.string.nm_hands_fx_enter))
        }
        ensureRunning()
        if (target?.isVirtual != true) {
            val (nowPkg, _) = svc.foregroundPackage()
            if (nowPkg == context.packageName && pkg != null && pkg != context.packageName) {
                HandsApps.launchable(context).firstOrNull { it.packageName == pkg }?.let {
                    ensureRunning()
                    HandsApps.open(context, it)
                }
                if (!sleepOrStop(OPEN_APP_SETTLE_MS)) return TapGate(denied = "stopped", shown = "Enter")
            }
        }
        capsule.working(0, context.getString(com.openminis.app.R.string.nm_hands_looking))
        return when (outcome) {
            is GateOutcome.Allowed -> TapGate(notice = outcome.notice, shown = "Enter")
            is GateOutcome.Denied -> TapGate(denied = outcome.message, shown = "Enter")
        }
    }

    // ── the hand ───────────────────────────────────────────────────────────

    private fun tap(svc: MinisAccessibilityService, x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x + 0.5f, y + 0.5f) }
        return HandsGestureDispatcher.dispatch(svc, path, durationMs, isStopped = { stop.get() }, canDispatch = capsule::canDispatchGesture)
    }

    private fun swipe(svc: MinisAccessibilityService, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return HandsGestureDispatcher.dispatch(svc, path, durationMs, isStopped = { stop.get() }, canDispatch = capsule::canDispatchGesture)
    }

    /** A swipe with its picture: the ring waits [AIM_MS] at the start, then travels with the finger. */
    private fun sweep(svc: MinisAccessibilityService, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, label: String): Boolean {
        if (stop.get()) return false
        return capsule.withGesture(x1, y1, x2, y2) {
            capsule.stage.sweep(x1, y1, x2, y2, AIM_MS, durationMs, label)
            sleepOrStop(AIM_MS) && swipe(svc, x1, y1, x2, y2, durationMs)
        }
    }

    /** The action's name for the ring's label, in the user's language. */
    private fun fxLabel(action: HandsAction): String {
        fun target(plain: Int, withTarget: Int, t: String) =
            if (t.isBlank()) context.getString(plain) else context.getString(withTarget, t.take(24))
        return when (action) {
            is HandsAction.Click -> target(R.string.nm_hands_fx_tap, R.string.nm_hands_fx_tap_target, action.target)
            is HandsAction.DoubleTap -> target(R.string.nm_hands_fx_double_tap, R.string.nm_hands_fx_double_tap_target, action.target)
            is HandsAction.LongPress -> target(R.string.nm_hands_fx_hold, R.string.nm_hands_fx_hold_target, action.target)
            is HandsAction.Swipe -> context.getString(R.string.nm_hands_fx_swipe)
            is HandsAction.Scroll -> context.getString(
                when (action.direction) {
                    "up" -> R.string.nm_hands_fx_scroll_up
                    "left" -> R.string.nm_hands_fx_scroll_left
                    "right" -> R.string.nm_hands_fx_scroll_right
                    else -> R.string.nm_hands_fx_scroll_down
                },
            )
            is HandsAction.InputText -> context.getString(R.string.nm_hands_fx_type, action.text.take(24))
            HandsAction.KeyboardEnter -> context.getString(R.string.nm_hands_fx_enter)
            HandsAction.Back -> context.getString(R.string.nm_hands_fx_back)
            HandsAction.Home -> context.getString(R.string.nm_hands_fx_home)
            is HandsAction.OpenApp -> context.getString(R.string.nm_hands_fx_open, action.appName)
            is HandsAction.Wait -> context.getString(R.string.nm_hands_fx_wait, action.seconds)
            else -> ""
        }
    }

    private fun focusedField(svc: MinisAccessibilityService, target: HandsDisplayTarget? = null): AccessibilityNodeInfo? {
        val displayId = target?.displayId ?: Display.DEFAULT_DISPLAY
        val roots = ScreenWords.rootsOn(svc, displayId)
        return roots.firstNotNullOfOrNull { root -> runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() }
    }

    /** Types into the field with the cursor; [HANDOFF] when that field is a secret. */
    internal fun typeText(
        svc: MinisAccessibilityService,
        action: HandsAction.InputText,
        target: HandsDisplayTarget? = null,
        askImeConsent: () -> Boolean = { promptForImeConsent() },
    ): String = typeText(svc, action, target, target?.control?.version, askImeConsent)

    internal fun typeText(
        svc: MinisAccessibilityService,
        action: HandsAction.InputText,
        target: HandsDisplayTarget?,
        controlVersion: Long?,
        askImeConsent: () -> Boolean = { promptForImeConsent() },
    ): String {
        ensureRunning()
        if (TapWords.looksSecret(action.field)) return HANDOFF
        val node = focusedField(svc, target)
        if (node?.isPassword == true) return HANDOFF
        val hint = listOfNotNull(node?.hintText?.toString(), node?.contentDescription?.toString()).joinToString(" ")
        if (hint.isNotBlank() && TapWords.looksSecret(hint)) return HANDOFF

        // 1. On the main screen, try ADB Keyboard directly if available (Open-AutoGLM pattern:
        // triggers TextWatcher & commitText, restores on finally). The background display has its
        // own consent-gated attempt below, because switching the global IME affects the main screen.
        if (target?.isVirtual != true && AdbKeyboardHelper.isAvailable(context)) {
            val typed = attemptInput { AdbKeyboardHelper.typeText(action.text) }
            if (typed) return "typed “${action.text.take(60)}” into the field."
            AppLogger.warning(TAG, "ADBKeyBoard typing failed, falling back to setNodeText")
        }

        if (target?.isVirtual == true) {
            if (isControlChanged(target, controlVersion)) return HANDED_OVER
            val roots = ScreenWords.rootsOn(svc, target.displayId)
            if (roots.isEmpty()) {
                return "cannot read screen nodes on virtual display; tap the field first or take over if manual input is needed."
            }
            if (node == null) {
                return "no text field has the cursor on virtual display; tap the field first, then type in the next turn."
            }

            // A display that holds its own focus (Android 14+) can take the keyboard, which is a
            // stronger input path than ACTION_SET_TEXT. Switching the IME is global, so ask once
            // per task outside HandsControl.automatic; re-verify control once the user answers.
            var tryIme = false
            if (AdbKeyboardHelper.isAvailable(context) &&
                AdbKeyboardHelper.virtualScreenImeSupported() &&
                imeConsent != false
            ) {
                val neededConsent = imeConsent == null
                val allowed = imeConsent ?: askImeConsent()
                ensureRunning()
                imeConsent = allowed
                if (neededConsent && target.control != null) {
                    if (!awaitScreenControl(target)) {
                        ensureRunning()
                        return HANDED_OVER
                    }
                    if (isControlChanged(target, controlVersion)) return HANDED_OVER
                }
                tryIme = allowed
            }

            var clipSet = false
            val typed = try {
                runAutomatic(target) {
                    if (controlVersion != null && target.control != null && target.control?.version != controlVersion) {
                        return@runAutomatic HANDED_OVER
                    }
                    if (tryIme) {
                        // The person may have taken a while to answer; never type into a field that
                        // lost focus, or into a password field that gained it, while waiting.
                        val live = focusedField(svc, target)
                        if (live == null) {
                            return@runAutomatic "no text field has the cursor on virtual display; tap the field first, then type in the next turn."
                        }
                        val liveHint = listOfNotNull(live.hintText?.toString(), live.contentDescription?.toString()).joinToString(" ")
                        if (live.isPassword || (liveHint.isNotBlank() && TapWords.looksSecret(liveHint))) {
                            return@runAutomatic HANDOFF
                        }
                        if (attemptInput { AdbKeyboardHelper.typeText(action.text, settleMs = VIRTUAL_IME_SETTLE_MS) }) {
                            val confirmed = awaitText(live, action.text)
                            if (confirmed) {
                                imeFailures = 0
                            } else {
                                imeFailures++
                                if (imeFailures >= IME_FAILURE_LIMIT) imeConsent = false
                            }
                            return@runAutomatic confirmedInput(confirmed, action.text)
                        }
                        imeFailures++
                        if (imeFailures >= IME_FAILURE_LIMIT) imeConsent = false
                        AppLogger.warning(TAG, "ADBKeyBoard typing failed on the virtual display, falling back to accessibility")
                        if (target.control != null && target.control?.current != HandsControl.State.Automatic) {
                            return@runAutomatic HANDED_OVER
                        }
                    }

                    // When the keyboard is unavailable, declined or failed on a virtual display, avoid
                    // showing a soft keyboard (IME) on the main screen:
                    // a. First try direct accessibility ACTION_SET_TEXT if node is editable (sets text without IME)
                    if (attemptInput { svc.setNodeText(node, action.text) }) {
                        return@runAutomatic confirmedInput(node, action.text)
                    }
                    if (target.control != null && target.control?.current != HandsControl.State.Automatic) {
                        return@runAutomatic HANDED_OVER
                    }

                    // b. Try clipboard injection via INanoMuseShell (overlay suppressed, sensitive marked)
                    ensureRunning()
                    clipSet = target.setClipText(action.text)
                    if (clipSet) {
                        ensureRunning()
                        val before = node.text?.toString().orEmpty()
                        if (before.isNotEmpty()) {
                            val range = Bundle().apply {
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before.length)
                            }
                            val selected = attemptInput { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range) }
                            if (!selected && !attemptInput { target.injectCtrlKey(KeyEvent.KEYCODE_A) }) {
                                return@runAutomatic "the existing text could not be selected; no paste was attempted."
                            }
                        }
                        val pasted = if (attemptInput { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }) {
                            true
                        } else {
                            attemptInput { target.injectCtrlKey(KeyEvent.KEYCODE_A) } &&
                                attemptInput { target.injectCtrlKey(KeyEvent.KEYCODE_V) }
                        }
                        if (pasted) {
                            // Paste is asynchronous. Keep our clipboard until the app reads it, and
                            // never inject again when an accepted paste has an uncertain outcome.
                            return@runAutomatic confirmedInput(node, action.text)
                        }
                        return@runAutomatic "the virtual screen rejected the paste; no additional text was injected."
                    }
                    // If setClipText was rejected because control is no longer Automatic, do not fall back.
                    if (target.control != null && target.control?.current != HandsControl.State.Automatic) {
                        return@runAutomatic HANDED_OVER
                    }

                    // c. Direct shell text injection to the virtual display
                    if (attemptInput { target.injectText(action.text) }) {
                        return@runAutomatic confirmedInput(node, action.text)
                    }

                    "text injection did not confirm success on the virtual display; inspect the next screen before attempting more input."
                }
            } finally {
                if (clipSet) target.restoreClip()
            }
            return typed ?: HANDED_OVER
        }

        if (node == null) return "no text field has the cursor; tap the field first, then type in the next turn."

        // 2. Standard Accessibility ACTION_SET_TEXT
        val ok = attemptInput { svc.setNodeText(node, action.text) }
        if (ok) return "typed “${action.text.take(60)}” into the field."

        // 3. Fallback: clipboard paste
        val pasted = pasteViaClipboard(node, action.text)
        if (pasted) return "typed “${action.text.take(60)}” into the field."

        return "the field did not accept text (ACTION_SET_TEXT failed); tap it and try again."
    }

    /**
     * Asks once per task before the hands switch the phone's keyboard to type on the background
     * screen. Waits until the person answers; Stop withdraws the request. Only an allow is a yes.
     */
    private fun promptForImeConsent(): Boolean {
        ensureRunning()
        val request = RiskRequest(
            sessionId = gateSession ?: "hands",
            kind = GuardKind.IME,
            assessment = RiskAssessment(RiskClass.SAFE, "switch the keyboard temporarily for text input"),
            preview = context.getString(R.string.nm_hands_ime_action),
            timeoutMs = 0,
        )
        capsule.approval(context.getString(R.string.nm_hands_ime_ask), decidable = true)
        val decision = try {
            runBlocking { RiskGate.ask(request) }
        } catch (t: Throwable) {
            // An interrupted runBlocking can leave the request queued; without a timeout it
            // would otherwise live on as a card nobody asked for.
            RiskGate.withdraw(request.id)
            throw t
        }
        ensureRunning()
        capsule.working(0, context.getString(R.string.nm_hands_looking))
        return decision == RiskDecision.ALLOW_ONCE ||
            decision == RiskDecision.ALLOW_SESSION ||
            decision == RiskDecision.ALLOW_ALWAYS
    }

    private fun confirmedInput(node: AccessibilityNodeInfo, text: String): String =
        confirmedInput(awaitText(node, text), text)

    private fun confirmedInput(confirmed: Boolean, text: String): String =
        if (confirmed) "typed “${text.take(60)}” into the field."
        else "text input was accepted, but the field did not confirm the requested text. No retry was sent; inspect the next screen before continuing."

    internal fun awaitText(
        node: AccessibilityNodeInfo,
        expected: String,
        attempts: Int = TEXT_CONFIRM_ATTEMPTS,
        pause: () -> Unit = { Thread.sleep(TEXT_CONFIRM_POLL_MS) },
    ): Boolean {
        repeat(attempts) { index ->
            ensureRunning()
            val matches = runCatching { node.refresh() && node.text?.toString().orEmpty() == expected }.getOrDefault(false)
            ensureRunning()
            if (matches) return true
            if (index + 1 < attempts) pause()
        }
        ensureRunning()
        return false
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        ensureRunning()
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
            val clip = ClipData.newPlainText("hands_input", text)
            val latch = CountDownLatch(1)
            val written = AtomicBoolean(false)
            val pending = AtomicBoolean(true)
            val worker = Thread.currentThread()
            val main = Handler(Looper.getMainLooper())
            val writeClip = Runnable {
                try {
                    if (pending.get() && !stop.get() && !worker.isInterrupted) {
                        cm.setPrimaryClip(clip)
                        written.set(true)
                    }
                } catch (_: Exception) {
                    // A rejected clipboard write must not paste an older clipboard value.
                } finally {
                    latch.countDown()
                }
            }
            try {
                ensureRunning()
                if (!main.post(writeClip)) return false
                val completed = latch.await(500, TimeUnit.MILLISECONDS)
                ensureRunning()
                completed && written.get() && attemptInput { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
            } finally {
                pending.set(false)
                main.removeCallbacks(writeClip)
            }
        } catch (error: InterruptedException) {
            throw error
        } catch (_: Exception) {
            ensureRunning()
            false
        }
    }

    internal fun pressEnter(
        svc: MinisAccessibilityService,
        target: HandsDisplayTarget? = null,
        controlVersion: Long? = target?.control?.version,
    ): String {
        ensureRunning()
        if (target?.isVirtual == true) {
            if (isControlChanged(target, controlVersion)) return HANDED_OVER
            return runAutomatic(target) {
                if (controlVersion != null && target.control != null && target.control?.version != controlVersion) {
                    return@runAutomatic HANDED_OVER
                }
                val ok = attemptInput { target.injectKey(KeyEvent.KEYCODE_ENTER) }
                if (ok) return@runAutomatic "pressed Enter."
                // Do not fall back to ACTION_IME_ENTER when control is no longer Automatic.
                if (target.control != null && target.control?.current != HandsControl.State.Automatic) {
                    return@runAutomatic HANDED_OVER
                }
                val node = focusedField(svc, target)
                if (node != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (attemptInput { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }) {
                        return@runAutomatic "pressed Enter."
                    }
                }
                "failed to inject Enter key."
            } ?: HANDED_OVER
        }
        val node = focusedField(svc, target) ?: return "no text field has the cursor, so there is nothing to submit; tap the search or send button instead."
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attemptInput { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }
        } else false
        return if (ok) "pressed Enter." else "Enter is not available for this field; tap the search or send button on the screen instead."
    }

    // ── the eyes ───────────────────────────────────────────────────────────

    internal fun currentFrameGuardSince(): Long? = frameGuardSince

    /** 记下启动时刻：之后的第一张画面必须是这之后渲染出来的。 */
    internal fun guardFramesFromNow(sinceNanos: Long = System.nanoTime()) {
        frameGuardSince = sinceNanos
    }

    /** 启动没成，没有新画面可等，撤掉保护。 */
    private fun clearFrameGuard() {
        frameGuardSince = null
    }

    /**
     * 溢出检查因用户接管被跳过时，仅在 App 并未成功启动的情况下撤掉帧保护；
     * 启动成功（START_OK / START_OK_UNMUTED）则保留保护，交回后继续等待启动后的新帧。
     */
    internal fun resolveFrameGuardAfterSkippedLaunchSpill(startResult: Int) {
        if (startResult != ShellService.START_OK && startResult != ShellService.START_OK_UNMUTED) {
            clearFrameGuard()
        }
    }

    /**
     * 启动或挪回之后等一次这次的第一帧（最多 [FrameFreshness.FRAME_WAIT_MS]）。
     * 等不到就保持保护：之后的画面一律不给，直到新帧到达，由调用方的等待循环兜底。
     */
    internal fun awaitFreshFrame(
        target: HandsDisplayTarget,
        nowNanos: () -> Long = System::nanoTime,
        sleep: (Long) -> Boolean = ::sleepOrStop,
        timeoutMs: Long = FrameFreshness.FRAME_WAIT_MS,
    ) {
        if (!target.isVirtual) return
        val since = frameGuardSince ?: return
        val fresh = FrameFreshness.awaitNewFrame(
            nowMs = { nowNanos() / 1_000_000L },
            sleep = sleep,
            frameAt = target::lastFrameAt,
            since = since,
            timeoutMs = timeoutMs,
        )
        if (fresh) frameGuardSince = null
    }

    internal fun screenshot(svc: MinisAccessibilityService, target: HandsDisplayTarget): Bitmap? {
        if (stop.get()) return null
        if (target.isVirtual) {
            // 启动之后还没有新帧时宁可不给画面，也不给启动前的旧帧。
            val since = frameGuardSince
            if (since != null) {
                if ((target.lastFrameAt() ?: since) <= since) return null
                frameGuardSince = null
            }
            return target.captureScreenshot()
        }
        capsule.hideForCapture()
        try {
            repeat(2) { attempt ->
                if (stop.get()) return null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val shot = svc.captureScreenshot(displayId = target.displayId, timeoutMs = 6_000)
                    shot.bitmap?.let { return it }
                    AppLogger.warning(TAG, "screenshot failed (${shot.errorCode}): ${shot.errorMessage}")
                }
                if (attempt == 0 && !sleepOrStop(400)) return null
            }
            return null
        } finally {
            capsule.restore()
        }
    }

    /** [print]: the grey level of each sampled cell, the fingerprint [HandsLoopWatch] compares. */
    private class Jpeg(val bytes: ByteArray, val black: Boolean, val print: IntArray)

    /** Down to [SHOT_WIDTH] px wide (the grid is relative, so nothing is lost for tapping) and JPEG. */
    private fun encode(shot: Bitmap): Jpeg {
        val scale = SHOT_WIDTH.toFloat() / shot.width
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(shot, SHOT_WIDTH, (shot.height * scale).toInt().coerceAtLeast(1), true) else shot
        val print = sample(scaled)
        val black = looksBlack(print)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== shot) scaled.recycle()
        return Jpeg(out.toByteArray(), black, print)
    }

    /** A grid of 12 × 20 pixels, each as a grey level 0–255: enough to tell a black frame and an unchanged screen. */
    private fun sample(b: Bitmap): IntArray {
        val out = ArrayList<Int>(240)
        val stepX = (b.width / 12).coerceAtLeast(1)
        val stepY = (b.height / 20).coerceAtLeast(1)
        var y = stepY / 2
        while (y < b.height) {
            var x = stepX / 2
            while (x < b.width) {
                val c = b.getPixel(x, y)
                out += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                x += stepX
            }
            y += stepY
        }
        return out.toIntArray()
    }

    /** A FLAG_SECURE page comes back as a black frame: nearly every sampled cell is dark. */
    private fun looksBlack(print: IntArray): Boolean {
        if (print.isEmpty()) return false
        val dark = print.count { it < 12 }
        return dark * 100 / print.size >= 98
    }

    private fun buildMessages(turns: List<Turn>, currentText: String, current: ByteArray?): List<LLMMessage> {
        val out = mutableListOf<LLMMessage>()
        val withImage = turns.size - HISTORY_IMAGES
        turns.forEachIndexed { i, t ->
            val parts = mutableListOf<AgentContentPart>(AgentContentPart.Text(t.userText))
            if (i >= withImage && t.image != null) parts += AgentContentPart.ImageData(t.image, "image/jpeg")
            else if (t.image != null) parts += AgentContentPart.Text(HandsPrompt.OLDER_SCREEN)
            out += LLMMessage(LLMMessage.Role.USER, content = t.userText, contentParts = parts)
            out += LLMMessage(LLMMessage.Role.ASSISTANT, content = t.assistant)
        }
        out += LLMMessage(
            LLMMessage.Role.USER,
            content = currentText,
            contentParts = buildList {
                add(AgentContentPart.Text(currentText))
                if (current != null) add(AgentContentPart.ImageData(current, "image/jpeg"))
            },
        )
        return out
    }

    // ── the trace ──────────────────────────────────────────────────────────

    private fun traceDir(sessionId: String?, runId: String): File? {
        val base = if (sessionId != null) File(context.filesDir, "minis-sessions/$sessionId/attachments")
        else PRootKernel.resolveHostPath("/var/minis/attachments")
        val hands = base?.let { File(it, "hands") } ?: return null
        // the person's screen is not kept without bound: older runs keep their trace and last screen
        runCatching { HandsTraces.prune(hands) }
        return File(hands, runId).apply { mkdirs() }
    }

    private fun save(dir: File?, step: Int, jpeg: Jpeg, runId: String): String? {
        dir ?: return null
        return runCatching {
            File(dir, "step-$step.jpg").writeBytes(jpeg.bytes)
            "minis://attachments/hands/$runId/step-$step.jpg"
        }.getOrNull()
    }

    private fun trace(dir: File?, step: Int, thought: String, reply: String, result: String, screen: String?) {
        dir ?: return
        runCatching {
            val line = JSONObject()
                .put("step", step).put("at", System.currentTimeMillis())
                .put("thought", thought).put("reply", reply).put("result", result).put("screen", screen ?: JSONObject.NULL)
            File(dir, "trace.jsonl").appendText(line.toString() + "\n")
        }
    }

    companion object {
        private const val TAG = "Hands"
        /** No step cap: a task takes as many steps as it takes (the time limit and the Stop button remain). */
        const val DEFAULT_MAX_STEPS = 0
        private const val MAX_APPS_IN_PROMPT = 120
        private const val SHOT_WIDTH = 720
        private const val JPEG_QUALITY = 78
        private const val HISTORY_IMAGES = 1
        private const val MODEL_TIMEOUT_MS = 90_000L
        private const val MAX_FRAME_WAITS = 5
        private const val TEXT_CONFIRM_ATTEMPTS = 16
        private const val TEXT_CONFIRM_POLL_MS = 100L
        /** Longer than the main screen: the keyboard has to bind on the background display. */
        private const val VIRTUAL_IME_SETTLE_MS = 300L
        /** Consecutive keyboard failures before this task stops trying it and asks no more. */
        private const val IME_FAILURE_LIMIT = 2
        private const val TAKE_OVER_TIMEOUT_MS = 5 * 60_000L
        private const val MAX_RUN_MS = 30 * 60_000L
        private const val TAP_SETTLE_MS = 900L
        private const val SWIPE_SETTLE_MS = 1100L
        private const val OPEN_APP_SETTLE_MS = 1800L
        /** Brief settle after user hand-back so the virtual display publishes the post-input frame. */
        private const val HANDOVER_SETTLE_MS = 300L
        /** The ring shows this long before the finger lands, so the eye gets there first. */
        private const val AIM_MS = 260L
        private const val LONG_PRESS_MS = 900L
        private const val SWIPE_MS = 350L
        private const val SCROLL_MS = 400L
        private const val HANDOFF = "\u0000handoff"
        internal const val HANDED_OVER = "\u0000handed_over"
        internal const val HANDED_OVER_RESULT = "the user took control; look at the screen again before acting."
        internal const val HANDED_OVER_SPILL_SKIPPED_RESULT =
            "the user took control; an earlier main-screen spill check could not run during takeover, so look at the screen again before acting."
    }
}
