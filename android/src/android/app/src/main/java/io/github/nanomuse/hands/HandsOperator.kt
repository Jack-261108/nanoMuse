package io.github.nanomuse.hands

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
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
import io.github.nanomuse.shell.MainScreenSpill
import io.github.nanomuse.shell.SharedShell
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

    /** The session this run's approval cards are filed under; see [ownPendingRequest]. */
    @Volatile private var gateSession: String? = null
    /** The chat the run came from, for the record's events (null for a run without one). */
    @Volatile private var eventSession: String? = null

    private val notSpills: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = runCatching {
            context.packageManager.queryIntentActivities(home, 0).mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList())
        (launchers + context.packageName + SystemPickers.resolve(context)).toSet()
    }

    /**
     * The approval card waiting on this run, if the one on top is ours: a tap or an Enter the
     * hands asked about, not a shell or browser request of some other conversation.
     */
    private fun ownPendingRequest(): RiskRequest? =
        RiskGate.pending.value?.takeIf { it.kind == GuardKind.SCREEN && it.sessionId == gateSession }

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
                    val agentDisplay = AgentDisplay(shell, dm.widthPixels, dm.heightPixels, dm.densityDpi)
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
                capsule.setPreviewFlow(target.previewFrames)
                Hands.setLiveFrames(target.previewFrames?.value)
                target.previewFrames?.let { flow ->
                    CoroutineScope(Dispatchers.Default).launch {
                        flow.collect { Hands.setLiveFrames(it) }
                    }
                }
            } else {
                capsule.setPreviewFlow(null)
                Hands.setLiveFrames(null)
                null
            }

            // The app the caller named comes first, before the model looks.
            opts.appHint?.takeIf { it.isNotBlank() }?.let { hint ->
                if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                val app = HandsApps.resolve(context, hint)
                val spillWatch = target.armSpillWatch()
                lastResult = if (app != null) {
                    val res = target.openApp(app.packageName)
                    if (!sleepOrStop(OPEN_APP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    val spill = target.checkSpill(spillWatch, notSpills, userTaps.toList())
                    if (spill is MainScreenSpill.PullBack) {
                        memoryApp = app.packageName
                        memoryTicket = HandsRunMemory.shared.begin(opts.sessionId, app.packageName)
                        if (!sleepOrStop(OPEN_APP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        "opened ${app.label} (restored to virtual screen)."
                    } else if (spill is MainScreenSpill.InUse) {
                        "app ${app.label} is currently in use on the main screen; left on main screen."
                    } else if (res == 0) {
                        memoryApp = app.packageName
                        memoryTicket = HandsRunMemory.shared.begin(opts.sessionId, app.packageName)
                        if (!sleepOrStop(OPEN_APP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        "opened ${app.label}."
                    } else if (res == 1) {
                        "user is currently using ${app.label} on the main screen; waiting or continue on main screen."
                    } else {
                        "could not open ${app.label} (code $res)."
                    }
                } else "no installed app matches “$hint”; open the one you need yourself."
                log += "open $hint → $lastResult"
            }

            while (opts.maxSteps <= 0 || steps < opts.maxSteps) {
                if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                if (System.currentTimeMillis() - started > MAX_RUN_MS) {
                    return finish(Outcome.Stopped("the run hit its time limit"), steps, runId, lastScreen, traceDir, log, model.label)
                }
                steps++
                KeepAwake.a11yTouched()
                capsule.working(steps, context.getString(com.openminis.app.R.string.nm_hands_looking))

                // ── look ──
                val shot = screenshot(svc, target)
                if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
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
                    frameWaits++
                    if (frameWaits >= MAX_FRAME_WAITS) return finish(Outcome.Failed("the app did not provide a readable screen frame"), steps, runId, lastScreen, traceDir, log, model.label)
                    steps--
                    if (!sleepOrStop(500)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    continue
                }
                frameWaits = 0
                if (jpeg != null && screenState != HandsScreenState.EMPTY) lastScreen = save(traceDir, steps, jpeg, runId)
                if (screenState == HandsScreenState.PROTECTED) {
                    blackScreens++
                    if (blackScreens > 2) return finish(Outcome.Infeasible("the screen stays protected (black screenshot); the app does not allow it to be read"), steps, runId, lastScreen, traceDir, log, model.label)
                    log += "step $steps: protected screen — handed to the user"
                    val cont = takeOver(target, context.getString(com.openminis.app.R.string.nm_hands_protected_screen))
                    if (!cont || stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
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
                if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                if (!screenState.allows(action)) {
                    lastResult = "no action was performed: the virtual screen has no app open. Use open_app, ask_user or status; do not act on an earlier screenshot."
                    trace(traceDir, steps, parsed.thought, reply, lastResult, null)
                    step(runId, steps, actionWord(action), "refused", thoughtMs)
                    continue
                }
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
                            lastResult = gate.notice
                            trace(traceDir, steps, parsed.thought, reply, lastResult.orEmpty(), lastScreen)
                            step(runId, steps, actionWord(action), "handed_over", thoughtMs)
                            continue
                        }
                        val spillWatch = target.armSpillWatch()
                        val ok = if (target.isVirtual) {
                            when (action) {
                                is HandsAction.LongPress -> target.longPress(px, py)
                                is HandsAction.DoubleTap -> doubleTap({ target.tap(px, py) })
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
                        if (!sleepOrStop(TAP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        val spill = target.checkSpill(spillWatch, notSpills, userTaps.toList())
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
                    is HandsAction.Swipe -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        val (x1, y1) = action.from.toPixels(screenW, screenH)
                        val (x2, y2) = action.to.toPixels(screenW, screenH)
                        val ok = if (target.isVirtual) {
                            target.swipe(x1, y1, x2, y2, SWIPE_MS)
                        } else {
                            sweep(svc, x1, y1, x2, y2, SWIPE_MS, fxLabel(action))
                        }
                        lastResult = if (ok) "swiped from ($x1, $y1) to ($x2, $y2)." else "the swipe was rejected by the system."
                        if (!sleepOrStop(SWIPE_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    }
                    is HandsAction.Scroll -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        val (from, to) = HandsScroll.gesture(action.direction, action.distance, screenW, screenH)
                        val ok = if (target.isVirtual) {
                            target.swipe(from.first, from.second, to.first, to.second, SCROLL_MS)
                        } else {
                            sweep(svc, from.first, from.second, to.first, to.second, SCROLL_MS, fxLabel(action))
                        }
                        lastResult = if (ok) "scrolled ${action.direction}." else "the scroll was rejected by the system."
                        if (!sleepOrStop(SWIPE_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    }
                    is HandsAction.InputText -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (!target.isVirtual && !TapWords.looksSecret(action.field)) capsule.stage.say(fxLabel(action))
                        lastResult = typeText(svc, action, target)
                        if (lastResult == HANDOFF) {
                            val cont = takeOver(target, context.getString(com.openminis.app.R.string.nm_hands_secret_field))
                            if (!cont || stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                            lastResult = "that field is a password or code field, which you never type; the user filled it in and says to continue."
                        }
                        if (!sleepOrStop(TAP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
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
                        if (!target.isVirtual) capsule.stage.say(fxLabel(action))
                        lastResult = pressEnter(svc, target)
                        if (!sleepOrStop(TAP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    }
                    is HandsAction.OpenApp -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        val app = HandsApps.resolve(context, action.appName)
                        val spillWatch = target.armSpillWatch()
                        lastResult = if (app != null) {
                            val res = if (action.cold) target.restartApp(app.packageName) else target.openApp(app.packageName)
                            if (!sleepOrStop(OPEN_APP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                            val spill = target.checkSpill(spillWatch, notSpills, userTaps.toList())
                            if (spill is MainScreenSpill.PullBack) {
                                // A note from a multi-app run must not be reused as one app's history.
                                if (app.packageName != memoryApp) memoryTicket = null
                                if (!target.isVirtual) capsule.stage.say(context.getString(com.openminis.app.R.string.nm_hands_fx_open, app.label))
                                if (!sleepOrStop(OPEN_APP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                                "opened ${app.label} (restored to virtual screen)."
                            } else if (spill is MainScreenSpill.InUse) {
                                "cannot open ${app.label}: user is currently using it on the main screen."
                            } else if (res == 0) {
                                // A note from a multi-app run must not be reused as one app's history.
                                if (app.packageName != memoryApp) memoryTicket = null
                                if (!target.isVirtual) capsule.stage.say(context.getString(com.openminis.app.R.string.nm_hands_fx_open, app.label))
                                if (!sleepOrStop(OPEN_APP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                                "opened ${app.label}."
                            } else if (res == 1) {
                                "cannot open ${app.label}: user is currently using it on the main screen."
                            } else {
                                "could not open ${app.label} (code $res)."
                            }
                        } else {
                            "no installed app matches “${action.appName}”. Installed: " + apps.take(40).joinToString(", ") { it.label } + ". Open it from the home screen if you must."
                        }
                    }
                    HandsAction.Back -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (!target.isVirtual) capsule.stage.say(fxLabel(action))
                        target.pressBack()
                        lastResult = "pressed Back."
                        if (!sleepOrStop(TAP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    }
                    HandsAction.Home -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (!target.isVirtual) capsule.stage.say(fxLabel(action))
                        target.pressHome()
                        lastResult = "went to the home screen."
                        if (!sleepOrStop(TAP_SETTLE_MS)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                    }
                    is HandsAction.Wait -> {
                        if (stop.get()) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        if (!target.isVirtual) capsule.stage.say(fxLabel(action))
                        if (!sleepOrStop(action.seconds * 1000L)) return finish(Outcome.Stopped(stopReason.get() ?: "stopped"), steps, runId, lastScreen, traceDir, log, model.label)
                        lastResult = "waited ${action.seconds}s."
                    }
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
        check(target.onTakeoverStart()) { context.getString(R.string.nm_hands_takeover_move_failed) }
        val continued = try {
            ensureRunning()
            awaitUser(reason)
        } finally {
            // Stop interrupts the worker, but moving the user's app back is still required cleanup.
            val interrupted = Thread.interrupted()
            try {
                check(target.onTakeoverEnd()) { context.getString(R.string.nm_hands_takeover_restore_failed) }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        ensureRunning()
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
    internal fun typeText(svc: MinisAccessibilityService, action: HandsAction.InputText, target: HandsDisplayTarget? = null): String {
        ensureRunning()
        if (TapWords.looksSecret(action.field)) return HANDOFF
        val node = focusedField(svc, target)
        if (node?.isPassword == true) return HANDOFF
        val hint = listOfNotNull(node?.hintText?.toString(), node?.contentDescription?.toString()).joinToString(" ")
        if (hint.isNotBlank() && TapWords.looksSecret(hint)) return HANDOFF

        // 1. Try ADB Keyboard if available (Open-AutoGLM pattern: triggers TextWatcher & commitText, restores on finally).
        // Note: ADB Keyboard operates on the global system IME and expects main display IME focus.
        // It is strictly reserved for the physical main screen (target?.isVirtual != true) to avoid hijacking
        // the user's foreground keyboard or failing to bind to the background virtual display.
        if (target?.isVirtual != true && AdbKeyboardHelper.isAvailable(context)) {
            val typed = attemptInput { AdbKeyboardHelper.typeText(action.text) }
            if (typed) return "typed “${action.text.take(60)}” into the field."
            AppLogger.warning(TAG, "ADBKeyBoard typing failed, falling back to setNodeText")
        }

        if (target?.isVirtual == true) {
            val roots = ScreenWords.rootsOn(svc, target.displayId)
            if (roots.isEmpty()) {
                return "cannot read screen nodes on virtual display; tap the field first or take over if manual input is needed."
            }
            if (node == null) {
                return "no text field has the cursor on virtual display; tap the field first, then type in the next turn."
            }

            // When on a virtual display and ADB keyboard isn't enabled, avoid displaying soft keyboard (IME) on the main screen:
            // a. First try direct accessibility ACTION_SET_TEXT if node is editable (sets text without IME)
            if (attemptInput { svc.setNodeText(node, action.text) }) {
                return confirmedInput(node, action.text)
            }

            // b. Try clipboard injection via INanoMuseShell (overlay suppressed, sensitive marked)
            ensureRunning()
            val clipSet = target.setClipText(action.text)
            if (clipSet) {
                try {
                    ensureRunning()
                    val before = node.text?.toString().orEmpty()
                    if (before.isNotEmpty()) {
                        val range = Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before.length)
                        }
                        val selected = attemptInput { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range) }
                        if (!selected && !attemptInput { target.injectCtrlKey(KeyEvent.KEYCODE_A) }) {
                            return "the existing text could not be selected; no paste was attempted."
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
                        return confirmedInput(node, action.text)
                    }
                    return "the virtual screen rejected the paste; no additional text was injected."
                } finally {
                    target.restoreClip()
                }
            }

            // c. Direct shell text injection to the virtual display
            if (attemptInput { target.injectText(action.text) }) {
                return confirmedInput(node, action.text)
            }

            return "text injection did not confirm success on the virtual display; inspect the next screen before attempting more input."
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

    private fun confirmedInput(node: AccessibilityNodeInfo, text: String): String =
        if (awaitText(node, text)) "typed “${text.take(60)}” into the field."
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

    internal fun pressEnter(svc: MinisAccessibilityService, target: HandsDisplayTarget? = null): String {
        ensureRunning()
        if (target?.isVirtual == true) {
            val ok = attemptInput { target.injectKey(KeyEvent.KEYCODE_ENTER) }
            if (ok) return "pressed Enter."
            val node = focusedField(svc, target)
            if (node != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (attemptInput { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }) {
                    return "pressed Enter."
                }
            }
            return "failed to inject Enter key."
        }
        val node = focusedField(svc, target) ?: return "no text field has the cursor, so there is nothing to submit; tap the search or send button instead."
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attemptInput { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }
        } else false
        return if (ok) "pressed Enter." else "Enter is not available for this field; tap the search or send button on the screen instead."
    }

    // ── the eyes ───────────────────────────────────────────────────────────

    private fun screenshot(svc: MinisAccessibilityService, target: HandsDisplayTarget): Bitmap? {
        if (stop.get()) return null
        if (target.isVirtual) {
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
        private const val TAKE_OVER_TIMEOUT_MS = 5 * 60_000L
        private const val MAX_RUN_MS = 30 * 60_000L
        private const val TAP_SETTLE_MS = 900L
        private const val SWIPE_SETTLE_MS = 1100L
        private const val OPEN_APP_SETTLE_MS = 1800L
        /** The ring shows this long before the finger lands, so the eye gets there first. */
        private const val AIM_MS = 260L
        private const val LONG_PRESS_MS = 900L
        private const val SWIPE_MS = 350L
        private const val SCROLL_MS = 400L
        private const val HANDOFF = "\u0000handoff"
    }
}
