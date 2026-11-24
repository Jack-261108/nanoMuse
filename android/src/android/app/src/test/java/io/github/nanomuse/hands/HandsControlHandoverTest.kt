package io.github.nanomuse.hands

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.offload.ShizukuManager
import io.github.nanomuse.display.AgentDisplay
import io.github.nanomuse.display.FrameFreshness
import io.github.nanomuse.shell.INanoMuseShell
import io.github.nanomuse.shell.MainScreenSpill
import io.github.nanomuse.shell.MainScreenWatch
import io.github.nanomuse.shell.ShellService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Verifies HandsOperator honours HandsControl across typing, Enter, double tap and takeover. */
class HandsControlHandoverTest {

    @Rule
    @JvmField
    val testTimeout: Timeout = Timeout.seconds(15)

    private val context = mock(Context::class.java)
    private val op = HandsOperator(context)
    private val svc = mock(MinisAccessibilityService::class.java)
    private val node = mock(AccessibilityNodeInfo::class.java)
    private val action = HandsAction.InputText("hello", "search")

    private val previousRunner = AdbKeyboardHelper.processRunner
    private val previousReady = AdbKeyboardHelper.isPrivilegedReady
    private val previousSleep = AdbKeyboardHelper.sleepRunner
    private val previousSupported = AdbKeyboardHelper.virtualScreenImeSupported
    private val createdTargets = mutableListOf<VirtualDisplayTarget>()

    private var adbBroadcasts = 0

    @Before
    fun setUp() {
        `when`(svc.rootInActiveWindow).thenReturn(node)
        `when`(node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)).thenReturn(node)
        val packages = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(packages)
        @Suppress("DEPRECATION")
        `when`(packages.getPackageInfo(AdbKeyboardHelper.ADB_KEYBOARD_PACKAGE, 0)).thenReturn(PackageInfo())
        AdbKeyboardHelper.isPrivilegedReady = { true }
        AdbKeyboardHelper.virtualScreenImeSupported = { true }
        AdbKeyboardHelper.sleepRunner = {}
        AdbKeyboardHelper.processRunner = { argv ->
            if (argv.getOrNull(3) == AdbKeyboardHelper.ACTION_INPUT_B64) adbBroadcasts++
            ShizukuManager.ProcessResult(0, if (argv.first() == "settings") "com.example/.Ime" else "", "")
        }
    }

    @After
    fun tearDown() {
        op.requestStop("test teardown")
        createdTargets.forEach { it.release() }
        createdTargets.clear()
        AdbKeyboardHelper.processRunner = previousRunner
        AdbKeyboardHelper.isPrivilegedReady = previousReady
        AdbKeyboardHelper.sleepRunner = previousSleep
        AdbKeyboardHelper.virtualScreenImeSupported = previousSupported
    }

    private fun virtualTarget(
        shell: INanoMuseShell = mock(INanoMuseShell::class.java),
        frameTimestampNanos: AtomicLong = AtomicLong(0L),
        display: AgentDisplay = mock(AgentDisplay::class.java),
    ): Pair<VirtualDisplayTarget, INanoMuseShell> {
        `when`(display.displayId).thenReturn(0)
        `when`(display.lastFrameAt()).thenAnswer { frameTimestampNanos.get().takeIf { it > 0L } }
        val target = VirtualDisplayTarget(display, shell)
        createdTargets += target
        return target to shell
    }

    @Test
    fun `pressEnter on virtual display does not fall back to accessibility when person controls screen`() {
        val (target, shell) = virtualTarget()
        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))

        val result = op.pressEnter(svc, target)

        assertEquals(HandsOperator.HANDED_OVER, result)
        verify(shell, never()).injectKey(anyInt(), anyInt())
        verify(node, never()).performAction(anyInt())
    }

    @Test
    fun `pressEnter does not fall back to ACTION_IME_ENTER when control becomes non-Automatic during injectKey`() {
        val control = HandsControl()
        val target = mock(HandsDisplayTarget::class.java)
        `when`(target.isVirtual).thenReturn(true)
        `when`(target.control).thenReturn(control)
        val versionBefore = control.version
        val requester = AtomicReference<Thread?>()
        `when`(target.injectKey(KeyEvent.KEYCODE_ENTER)).thenAnswer {
            // Simulate a takeover request arriving while injectKey fails.
            val t = Thread { control.begin(timeoutMs = 2_000L) }
            requester.set(t)
            t.start()
            val deadline = System.currentTimeMillis() + 1_000
            while (control.current == HandsControl.State.Automatic && System.currentTimeMillis() < deadline) {
                Thread.yield()
            }
            false
        }

        try {
            val result = op.pressEnter(svc, target, versionBefore)
            assertEquals(HandsOperator.HANDED_OVER, result)
            verify(node, never()).performAction(anyInt())
        } finally {
            control.close()
            requester.get()?.join(1_000)
        }
    }

    @Test
    fun `typeText on virtual display skips all input paths while person controls screen`() {
        val (target, shell) = virtualTarget()
        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
        var asked = 0

        val result = op.typeText(svc, action, target, askImeConsent = { asked++; true })

        assertEquals(HandsOperator.HANDED_OVER, result)
        assertEquals(0, asked)
        assertEquals(0, adbBroadcasts)
        verify(svc, never()).setNodeText(node, action.text)
        verify(shell, never()).setClipText(anyString())
        verify(shell, never()).restoreClip()
        verify(shell, never()).injectText(anyInt(), anyString())
        verify(node, never()).performAction(anyInt())
    }

    @Test
    fun `typeText aborts and asks to reobserve if user takes over during askImeConsent`() {
        val (target, shell) = virtualTarget()
        val versionBefore = target.control.version

        val result = op.typeText(
            svc,
            action,
            target,
            controlVersion = versionBefore,
            askImeConsent = {
                // User takes control and hands back while consent prompt is open.
                assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
                assertTrue(target.control.handBack())
                true
            },
        )

        assertEquals(HandsOperator.HANDED_OVER, result)
        assertEquals(0, adbBroadcasts)
        verify(svc, never()).setNodeText(node, action.text)
        verify(shell, never()).setClipText(anyString())
        verify(shell, never()).injectText(anyInt(), anyString())
    }

    @Test
    fun `typeText does not fall back to injectText when setClipText fails under non-Automatic control`() {
        AdbKeyboardHelper.virtualScreenImeSupported = { false }
        val control = HandsControl()
        val target = mock(HandsDisplayTarget::class.java)
        `when`(target.isVirtual).thenReturn(true)
        `when`(target.control).thenReturn(control)
        val versionBefore = control.version
        val requester = AtomicReference<Thread?>()
        `when`(svc.setNodeText(node, action.text)).thenReturn(false)
        `when`(target.setClipText(action.text)).thenAnswer {
            val t = Thread { control.begin(timeoutMs = 2_000L) }
            requester.set(t)
            t.start()
            val deadline = System.currentTimeMillis() + 1_000
            while (control.current == HandsControl.State.Automatic && System.currentTimeMillis() < deadline) {
                Thread.yield()
            }
            false
        }

        try {
            val result = op.typeText(svc, action, target, controlVersion = versionBefore)
            assertEquals(HandsOperator.HANDED_OVER, result)
            verify(target, never()).injectText(anyString())
            verify(target, never()).restoreClip()
        } finally {
            control.close()
            requester.get()?.join(1_000)
        }
    }

    @Test
    fun `virtualDoubleTap finishes both taps inside one automatic scope when takeover arrives between taps`() {
        val (target, shell) = virtualTarget()
        `when`(shell.injectTap(0, 100, 200)).thenReturn(true)
        val beginResult = AtomicReference<HandsControl.BeginResult?>()
        val beginThread = AtomicReference<Thread?>()

        try {
            val completed = op.virtualDoubleTap(target, 100, 200) {
                val t = Thread { beginResult.set(target.control.begin(timeoutMs = 2_000L)) }
                beginThread.set(t)
                t.start()
                val deadline = System.currentTimeMillis() + 1_000
                while (target.control.current != HandsControl.State.Requesting && System.currentTimeMillis() < deadline) {
                    Thread.yield()
                }
                assertEquals(HandsControl.State.Requesting, target.control.current)
            }

            assertTrue("double tap must finish both taps before control transfers", completed)
            verify(shell, times(2)).injectTap(0, 100, 200)
            beginThread.get()!!.join(1_000)
            assertEquals(HandsControl.BeginResult.Acquired, beginResult.get())
            assertEquals(HandsControl.State.Manual, target.control.current)
        } finally {
            target.release()
            beginThread.get()?.join(1_000)
        }
    }

    @Test
    fun `virtualDoubleTap still cancels the second tap on Stop`() {
        val (target, shell) = virtualTarget()
        `when`(shell.injectTap(0, 100, 200)).thenReturn(true)

        val completed = op.virtualDoubleTap(target, 100, 200) { op.requestStop() }

        assertFalse(completed)
        verify(shell, times(1)).injectTap(0, 100, 200)
    }

    @Test
    fun `main screen takeOver hands back PiP control and does not return stuck in Manual`() {
        val frameClock = AtomicLong(System.nanoTime())
        val (target, shell) = virtualTarget(frameTimestampNanos = frameClock)
        `when`(shell.topTaskId(0)).thenReturn(55)
        `when`(shell.moveTask(55, Display.DEFAULT_DISPLAY)).thenReturn(true)
        // Drive a fresh frame timestamp when the task returns to the virtual display.
        `when`(shell.moveTask(55, 0)).thenAnswer {
            frameClock.set(System.nanoTime() + 1_000_000L)
            true
        }
        val movedToMain = CountDownLatch(1)

        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
        assertEquals(HandsControl.State.Manual, target.control.current)

        val continued = op.takeOver(target, "Handle payment") {
            movedToMain.countDown()
            // Even if PiP requests control while on the main screen, it must stay Automatic.
            target.pipInput.requestControl()
            assertEquals(HandsControl.State.Automatic, target.control.current)
            true
        }

        assertTrue(movedToMain.await(1, TimeUnit.SECONDS))
        assertTrue(continued)
        assertEquals(HandsControl.State.Automatic, target.control.current)
    }

    private fun spillArrival(
        taskId: Int = 101,
        packageName: String = "com.example.spilled",
        at: Long = 200L,
    ): Bundle = mock(Bundle::class.java).also {
        `when`(it.getIntArray(MainScreenWatch.KEY_TASK)).thenReturn(intArrayOf(taskId))
        `when`(it.getStringArray(MainScreenWatch.KEY_PACKAGE)).thenReturn(arrayOf(packageName))
        `when`(it.getIntArray(MainScreenWatch.KEY_USER)).thenReturn(intArrayOf(0))
        `when`(it.getLongArray(MainScreenWatch.KEY_AT)).thenReturn(longArrayOf(at))
        `when`(it.getIntArray(MainScreenWatch.KEY_FROM)).thenReturn(intArrayOf(MainScreenSpill.NEW))
        `when`(it.getBooleanArray(MainScreenWatch.KEY_SAME_TASK)).thenReturn(booleanArrayOf(false))
        `when`(it.getBooleanArray(MainScreenWatch.KEY_STANDARD)).thenReturn(booleanArrayOf(true))
        `when`(it.getIntArray(MainScreenWatch.KEY_NOW)).thenReturn(intArrayOf(Display.DEFAULT_DISPLAY))
    }

    @Test
    fun `loop-top handover updates lastResult, resets loop watch and clears loop note`() {
        val (target, _) = virtualTarget()
        val versionBefore = target.control.version
        val watch = HandsLoopWatch()
        val tap = HandsAction.Click(HandsAction.Point(500, 500), "Next")
        watch.step(intArrayOf(10, 20, 30), tap)
        watch.step(intArrayOf(10, 20, 30), tap)
        assertEquals(2, watch.streak)

        // Simulate user takeover and hand-back happening between steps (e.g. during settle).
        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
        assertTrue(target.control.handBack())

        val handoverLogs = mutableListOf<String>()
        val settleDelays = mutableListOf<Long>()
        val sync = op.syncControlBeforeStep(
            target = target,
            lastControlVersion = versionBefore,
            watch = watch,
            lastResult = "tap “Next” at (540, 1200) was performed.",
            loopNote = HandsLoopWatch.NOTE,
            pendingSpillWatch = null,
            handoverAlreadyNoted = false,
            excluded = emptySet(),
            userTaps = { emptyList() },
            onHandover = { handoverLogs += it },
            settle = { ms -> settleDelays += ms; true },
        )

        assertNotNull(sync)
        assertTrue(sync!!.handoverOccurred)
        assertEquals(target.control.version, sync.controlVersion)
        assertEquals(HandsOperator.HANDED_OVER_RESULT, sync.lastResult)
        assertNull(sync.loopNote)
        assertEquals(0, watch.streak)
        assertEquals(1, handoverLogs.size)
        assertEquals(1, settleDelays.size)
    }

    @Test
    fun `loop-top handover preserves specific result and avoids duplicate log when already noted`() {
        val (target, _) = virtualTarget()
        val versionBefore = target.control.version
        val watch = HandsLoopWatch()
        watch.step(intArrayOf(1, 2, 3), HandsAction.Back)
        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
        assertTrue(target.control.handBack())

        val specific = "the user took control before Mail was opened; look at the screen before acting."
        val handoverLogs = mutableListOf<String>()
        val sync = op.syncControlBeforeStep(
            target = target,
            lastControlVersion = versionBefore,
            watch = watch,
            lastResult = specific,
            loopNote = HandsLoopWatch.NOTE,
            pendingSpillWatch = null,
            handoverAlreadyNoted = true,
            excluded = emptySet(),
            userTaps = { emptyList() },
            onHandover = { handoverLogs += it },
            settle = { true },
        )

        assertNotNull(sync)
        assertTrue(sync!!.handoverOccurred)
        assertEquals(specific, sync.lastResult)
        assertNull(sync.loopNote)
        assertEquals(0, watch.streak)
        assertTrue(handoverLogs.isEmpty())
    }

    @Test
    fun `loop-top handover re-waits when user takes control again during settle`() {
        val (target, _) = virtualTarget()
        val versionBefore = target.control.version
        val watch = HandsLoopWatch()
        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
        assertTrue(target.control.handBack())

        val settles = AtomicInteger(0)
        val handoverLogs = mutableListOf<String>()
        val sync = op.syncControlBeforeStep(
            target = target,
            lastControlVersion = versionBefore,
            watch = watch,
            lastResult = "scrolled down.",
            loopNote = null,
            pendingSpillWatch = null,
            handoverAlreadyNoted = false,
            excluded = emptySet(),
            userTaps = { emptyList() },
            onHandover = { handoverLogs += it },
            settle = {
                if (settles.incrementAndGet() == 1) {
                    assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
                    assertTrue(target.control.handBack())
                }
                true
            },
        )

        assertNotNull(sync)
        assertEquals(2, settles.get())
        assertEquals(1, handoverLogs.size)
        assertEquals(target.control.version, sync!!.controlVersion)
        assertEquals(HandsOperator.HANDED_OVER_RESULT, sync.lastResult)
    }

    @Test
    fun `checkSpillOrSkip distinguishes skipped handover from clean check`() {
        val (target, shell) = virtualTarget()
        val spillWatch = HandsDisplayTarget.SpillWatch(100L, -1)

        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
        assertEquals(
            HandsOperator.SpillCheck.Skipped,
            op.checkSpillOrSkip(target, spillWatch, emptySet(), emptyList()),
        )
        verify(shell, never()).mainArrivals(anyLong())
        verify(shell, never()).moveTask(anyInt(), anyInt())

        assertTrue(target.control.handBack())
        val checked = op.checkSpillOrSkip(target, spillWatch, emptySet(), emptyList())
        assertEquals(HandsOperator.SpillCheck.Checked(null), checked)
        verify(shell, times(1)).mainArrivals(100L)
    }

    @Test
    fun `loop-top handover replays skipped spill check and pulls back spilled app within window`() {
        val frameClock = AtomicLong(500L)
        val (target, shell) = virtualTarget(frameTimestampNanos = frameClock)
        val versionBefore = target.control.version
        val spillWatch = HandsDisplayTarget.SpillWatch(100L, -1)
        val arrived = spillArrival(taskId = 101, packageName = "com.example.spilled", at = 200L)
        `when`(shell.mainArrivals(100L)).thenReturn(arrived)
        `when`(shell.moveTask(101, 0)).thenAnswer {
            frameClock.set(System.nanoTime() + 1_000_000L)
            true
        }

        // Takeover starts during settle so the immediate spill check is skipped.
        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
        assertEquals(
            HandsOperator.SpillCheck.Skipped,
            op.checkSpillOrSkip(target, spillWatch, emptySet(), emptyList()),
        )
        verify(shell, never()).moveTask(anyInt(), anyInt())

        // User hands back within SPILL_WINDOW_MS; loop top replays checkSpill and restores the app.
        assertTrue(target.control.handBack())
        val sync = op.syncControlBeforeStep(
            target = target,
            lastControlVersion = versionBefore,
            watch = HandsLoopWatch(),
            lastResult = "tap “Open” at (500, 500) was performed.",
            loopNote = null,
            pendingSpillWatch = spillWatch,
            handoverAlreadyNoted = false,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            uptimeMs = { 2_000L },
        )

        assertNotNull(sync)
        assertTrue(sync!!.spillRecovered)
        assertNull(sync.pendingSpillWatch)
        assertNull(op.currentFrameGuardSince())
        assertTrue(sync.lastResult.orEmpty().contains(HandsOperator.HANDED_OVER_RESULT))
        assertTrue(sync.lastResult.orEmpty().contains("com.example.spilled"))
        assertTrue(sync.lastResult.orEmpty().contains("restored to the virtual screen"))
        verify(shell, times(1)).moveTask(101, 0)
    }

    @Test
    fun `loop-top handover leaves spilled app on main screen when user tapped it`() {
        val (target, shell) = virtualTarget()
        val versionBefore = target.control.version
        val spillWatch = HandsDisplayTarget.SpillWatch(100L, -1)
        val arrived = spillArrival(taskId = 101, packageName = "com.example.spilled", at = 200L)
        `when`(shell.mainArrivals(100L)).thenReturn(arrived)

        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
        assertTrue(target.control.handBack())

        val sync = op.syncControlBeforeStep(
            target = target,
            lastControlVersion = versionBefore,
            watch = HandsLoopWatch(),
            lastResult = "tap was performed.",
            loopNote = null,
            pendingSpillWatch = spillWatch,
            handoverAlreadyNoted = false,
            excluded = emptySet(),
            userTaps = { listOf(MainScreenSpill.Tap(350L, "com.example.spilled")) },
            settle = { true },
            uptimeMs = { 2_000L },
        )

        assertNotNull(sync)
        assertFalse(sync!!.spillRecovered)
        assertNull(sync.pendingSpillWatch)
        assertTrue(sync.lastResult.orEmpty().contains("currently in use"))
        verify(shell, never()).moveTask(anyInt(), anyInt())
    }

    @Test
    fun `loop-top handover drops stale spill check after watch window expires and warns model`() {
        val (target, shell) = virtualTarget()
        val versionBefore = target.control.version
        val spillWatch = HandsDisplayTarget.SpillWatch(100L, -1)
        val arrived = spillArrival(taskId = 101, packageName = "com.example.spilled", at = 200L)
        `when`(shell.mainArrivals(100L)).thenReturn(arrived)

        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
        assertTrue(target.control.handBack())

        val sync = op.syncControlBeforeStep(
            target = target,
            lastControlVersion = versionBefore,
            watch = HandsLoopWatch(),
            lastResult = "tap was performed.",
            loopNote = null,
            pendingSpillWatch = spillWatch,
            handoverAlreadyNoted = false,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            uptimeMs = { 100L + HandsDisplayTarget.SPILL_WINDOW_MS + 1L },
        )

        assertNotNull(sync)
        assertFalse(sync!!.spillRecovered)
        assertNull(sync.pendingSpillWatch)
        assertEquals(HandsOperator.HANDED_OVER_SPILL_SKIPPED_RESULT, sync.lastResult)
        verify(shell, never()).mainArrivals(anyLong())
        verify(shell, never()).moveTask(anyInt(), anyInt())
    }

    @Test
    fun `handover wait duration is excluded from the run time limit`() {
        val (target, _) = virtualTarget()
        val startedMs = 1_000_000L
        val tenMinutesMs = 10 * 60_000L
        val fakeNanos = AtomicLong(0L)

        // When no handover is active, clock advance does not count as paused handover time.
        assertEquals(target.control.version, op.awaitScreenControlVersion(target) { fakeNanos.addAndGet(5_000_000_000L) })
        assertTrue(op.isRunTimeLimitExceeded(nowMs = startedMs + 31 * 60_000L, startedMs = startedMs))

        // During a 10-minute handover pause, that wait time is deducted from the 30-minute run cap.
        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
        val handBack = Thread {
            Thread.sleep(40)
            fakeNanos.addAndGet(tenMinutesMs * 1_000_000L)
            target.control.handBack()
        }
        handBack.start()
        try {
            val resumedVersion = op.awaitScreenControlVersion(target) { fakeNanos.get() }
            assertEquals(target.control.version, resumedVersion)
        } finally {
            target.control.handBack()
            handBack.join(1_000)
        }

        // 35 minutes of wall time minus 10 minutes paused = 25 minutes active -> still within 30-minute limit.
        assertFalse(op.isRunTimeLimitExceeded(nowMs = startedMs + 35 * 60_000L, startedMs = startedMs))
        // 41 minutes of wall time minus 10 minutes paused = 31 minutes active -> exceeds 30-minute limit.
        assertTrue(op.isRunTimeLimitExceeded(nowMs = startedMs + 41 * 60_000L, startedMs = startedMs))
    }

    @Test
    fun `appHint syncs lastControlVersion after pre-launch takeover and avoids duplicate handover log`() {
        val app = HandsApps.App(label = "Mail", packageName = "com.example.mail")

        // 1. Pre-launch takeover before openAppHint: openAppHint syncs lastControlVersion so the
        // first loop step does not falsely treat it as a post-launch handover.
        val frameClockA = AtomicLong(500L)
        val (targetA, shellA) = virtualTarget(frameTimestampNanos = frameClockA)
        `when`(shellA.startApp(0, app.packageName)).thenAnswer {
            frameClockA.set(15_000L)
            ShellService.START_OK
        }
        assertEquals(HandsControl.BeginResult.Acquired, targetA.control.begin(timeoutMs = 2_000L))
        assertTrue(targetA.control.handBack())

        val outcomeA = op.openAppHint(
            target = targetA,
            hint = "Mail",
            app = app,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            nowNanos = { 10_000L },
        )
        assertNotNull(outcomeA)
        assertEquals(targetA.control.version, outcomeA!!.lastControlVersion)
        assertFalse(outcomeA.handoverNoted)
        assertNull(outcomeA.pendingSpillWatch)
        assertEquals("opened Mail.", outcomeA.lastResult)
        assertNull(op.currentFrameGuardSince())

        val logsA = mutableListOf<String>()
        val syncA = op.syncControlBeforeStep(
            target = targetA,
            lastControlVersion = outcomeA.lastControlVersion,
            watch = HandsLoopWatch(),
            lastResult = outcomeA.lastResult,
            loopNote = null,
            pendingSpillWatch = outcomeA.pendingSpillWatch,
            handoverAlreadyNoted = outcomeA.handoverNoted,
            excluded = emptySet(),
            userTaps = { emptyList() },
            onHandover = { logsA += it },
            settle = { true },
        )
        assertNotNull(syncA)
        assertFalse(syncA!!.handoverOccurred)
        assertEquals("opened Mail.", syncA.lastResult)
        assertTrue(logsA.isEmpty())

        // 2. Takeover right before openApp executes: appHint notes handover and clears frame guard;
        // the following loop-top sync preserves the specific message and does not log twice.
        val (targetB, shellB) = virtualTarget()
        `when`(shellB.watchMainScreen(anyInt())).thenAnswer {
            assertEquals(HandsControl.BeginResult.Acquired, targetB.control.begin(timeoutMs = 2_000L))
            -1
        }
        val outcomeB = op.openAppHint(
            target = targetB,
            hint = "Mail",
            app = app,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            nowNanos = { 10_000L },
        )
        assertNotNull(outcomeB)
        assertTrue(outcomeB!!.handoverNoted)
        assertNull(outcomeB.pendingSpillWatch)
        assertNull(op.currentFrameGuardSince())
        assertTrue(outcomeB.lastResult.contains("the user took control before Mail was opened"))

        assertTrue(targetB.control.handBack())
        val logsB = mutableListOf<String>()
        val syncB = op.syncControlBeforeStep(
            target = targetB,
            lastControlVersion = outcomeB.lastControlVersion,
            watch = HandsLoopWatch(),
            lastResult = outcomeB.lastResult,
            loopNote = null,
            pendingSpillWatch = outcomeB.pendingSpillWatch,
            handoverAlreadyNoted = outcomeB.handoverNoted,
            excluded = emptySet(),
            userTaps = { emptyList() },
            onHandover = { logsB += it },
            settle = { true },
        )
        assertNotNull(syncB)
        assertTrue(syncB!!.handoverOccurred)
        assertEquals(outcomeB.lastResult, syncB.lastResult)
        assertTrue(logsB.isEmpty())

        // 3. Takeover during appHint settle after START_OK: keeps frame guard, marks handoverNoted
        // and defers spill check; loop-top sync avoids duplicate log and clears guard on fresh frame.
        val frameClockC = AtomicLong(500L)
        val (targetC, shellC) = virtualTarget(frameTimestampNanos = frameClockC)
        `when`(shellC.startApp(0, app.packageName)).thenReturn(ShellService.START_OK)
        val outcomeC = op.openAppHint(
            target = targetC,
            hint = "Mail",
            app = app,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = {
                assertEquals(HandsControl.BeginResult.Acquired, targetC.control.begin(timeoutMs = 2_000L))
                true
            },
            nowNanos = { 10_000L },
        )
        assertNotNull(outcomeC)
        assertTrue(outcomeC!!.handoverNoted)
        assertNotNull(outcomeC.pendingSpillWatch)
        assertEquals(HandsOperator.HANDED_OVER_RESULT, outcomeC.lastResult)
        assertEquals(10_000L, op.currentFrameGuardSince())

        frameClockC.set(15_000L)
        assertTrue(targetC.control.handBack())
        val logsC = mutableListOf<String>()
        val syncC = op.syncControlBeforeStep(
            target = targetC,
            lastControlVersion = outcomeC.lastControlVersion,
            watch = HandsLoopWatch(),
            lastResult = outcomeC.lastResult,
            loopNote = null,
            pendingSpillWatch = outcomeC.pendingSpillWatch,
            handoverAlreadyNoted = outcomeC.handoverNoted,
            excluded = emptySet(),
            userTaps = { emptyList() },
            onHandover = { logsC += it },
            settle = { true },
            uptimeMs = { 500L },
        )
        assertNotNull(syncC)
        assertTrue(syncC!!.handoverOccurred)
        assertTrue(logsC.isEmpty())
        assertNull(op.currentFrameGuardSince())
    }

    @Test
    fun `loop-top handover waits another round when deferred spill check is skipped by a second takeover`() {
        val frameClock = AtomicLong(500L)
        val (target, shell) = virtualTarget(frameTimestampNanos = frameClock)
        val versionBefore = target.control.version
        val spillWatch = HandsDisplayTarget.SpillWatch(100L, -1)
        val arrived = spillArrival(taskId = 101, packageName = "com.example.spilled", at = 200L)
        `when`(shell.mainArrivals(100L)).thenReturn(arrived)
        `when`(shell.moveTask(101, 0)).thenAnswer {
            frameClock.set(System.nanoTime() + 1_000_000L)
            true
        }

        // First takeover completes before syncControlBeforeStep starts.
        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
        assertTrue(target.control.handBack())

        val worker = Thread.currentThread()
        val userTapsCalls = AtomicInteger(0)
        val settleDelays = mutableListOf<Long>()
        val secondHandBack = AtomicReference<Thread?>()

        try {
            val sync = op.syncControlBeforeStep(
                target = target,
                lastControlVersion = versionBefore,
                watch = HandsLoopWatch(),
                lastResult = "tap was performed.",
                loopNote = null,
                pendingSpillWatch = spillWatch,
                handoverAlreadyNoted = false,
                excluded = emptySet(),
                userTaps = {
                    if (userTapsCalls.incrementAndGet() == 1) {
                        // Second takeover starts right before checkSpillOrSkip so it returns Skipped.
                        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin(timeoutMs = 2_000L))
                        val t = Thread {
                            val deadline = System.currentTimeMillis() + 2_000
                            while (worker.state != Thread.State.WAITING &&
                                worker.state != Thread.State.TIMED_WAITING &&
                                System.currentTimeMillis() < deadline
                            ) {
                                Thread.yield()
                            }
                            target.control.handBack()
                        }
                        secondHandBack.set(t)
                        t.start()
                    }
                    emptyList()
                },
                settle = { ms -> settleDelays += ms; true },
                uptimeMs = { 2_000L },
            )

            assertNotNull(sync)
            assertTrue(sync!!.spillRecovered)
            assertEquals(target.control.version, sync.controlVersion)
            assertEquals(2, userTapsCalls.get())
            // Two handover settles (300ms each) plus one PullBack settle (1800ms).
            assertEquals(listOf(300L, 300L, 1800L), settleDelays)
            assertNull(op.currentFrameGuardSince())
            verify(shell, times(1)).moveTask(101, 0)
        } finally {
            target.control.handBack()
            secondHandBack.get()?.join(1_000)
        }
    }

    @Test
    fun `resolveFrameGuardAfterSkippedLaunchSpill keeps guard on successful launch and clears on failure`() {
        op.guardFramesFromNow(10_000L)
        op.resolveFrameGuardAfterSkippedLaunchSpill(ShellService.START_OK)
        assertEquals(10_000L, op.currentFrameGuardSince())

        op.resolveFrameGuardAfterSkippedLaunchSpill(ShellService.START_OK_UNMUTED)
        assertEquals(10_000L, op.currentFrameGuardSince())

        op.resolveFrameGuardAfterSkippedLaunchSpill(ShellService.START_IN_USE_ON_MAIN)
        assertNull(op.currentFrameGuardSince())

        op.guardFramesFromNow(20_000L)
        op.resolveFrameGuardAfterSkippedLaunchSpill(ShellService.START_ERROR)
        assertNull(op.currentFrameGuardSince())
    }

    @Test
    fun `loop-top handover keeps frame guard when post-launch or PullBack frame is stale and clears on InUse or expiry`() {
        // 1. START_OK_UNMUTED + Skipped -> Checked(null) with still-stale frame keeps guard armed.
        val frameClockA = AtomicLong(500L)
        val (targetA, shellA) = virtualTarget(frameTimestampNanos = frameClockA)
        val app = HandsApps.App(label = "Mail", packageName = "com.example.mail")
        `when`(shellA.startApp(0, app.packageName)).thenReturn(ShellService.START_OK_UNMUTED)

        val outcomeA = op.openAppHint(
            target = targetA,
            hint = "Mail",
            app = app,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = {
                assertEquals(HandsControl.BeginResult.Acquired, targetA.control.begin(timeoutMs = 2_000L))
                true
            },
            nowNanos = { 10_000L },
        )
        assertNotNull(outcomeA)
        assertEquals(10_000L, op.currentFrameGuardSince())

        assertTrue(targetA.control.handBack())
        val syncA = op.syncControlBeforeStep(
            target = targetA,
            lastControlVersion = outcomeA!!.lastControlVersion,
            watch = HandsLoopWatch(),
            lastResult = outcomeA.lastResult,
            loopNote = null,
            pendingSpillWatch = outcomeA.pendingSpillWatch,
            handoverAlreadyNoted = outcomeA.handoverNoted,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            awaitFrame = { t -> op.awaitFreshFrame(t, nowNanos = { 10_000L }, sleep = { true }, timeoutMs = 0L) },
            uptimeMs = { 500L },
        )
        assertNotNull(syncA)
        assertEquals(10_000L, op.currentFrameGuardSince())

        frameClockA.set(12_000L)
        op.awaitFreshFrame(targetA, nowNanos = { 12_000L }, sleep = { true }, timeoutMs = 0L)
        assertNull(op.currentFrameGuardSince())

        // 2. Deferred PullBack re-arms frame guard at beforeCheckNanos when no post-move frame arrived yet.
        val frameClockB = AtomicLong(2_000L)
        val (targetB, shellB) = virtualTarget(frameTimestampNanos = frameClockB)
        val versionBeforeB = targetB.control.version
        val arrivedB = spillArrival(taskId = 101, packageName = "com.example.spilled", at = 200L)
        `when`(shellB.mainArrivals(100L)).thenReturn(arrivedB)
        `when`(shellB.moveTask(101, 0)).thenReturn(true)
        assertEquals(HandsControl.BeginResult.Acquired, targetB.control.begin(timeoutMs = 2_000L))
        assertTrue(targetB.control.handBack())

        val syncB = op.syncControlBeforeStep(
            target = targetB,
            lastControlVersion = versionBeforeB,
            watch = HandsLoopWatch(),
            lastResult = "tap was performed.",
            loopNote = null,
            pendingSpillWatch = HandsDisplayTarget.SpillWatch(100L, -1),
            handoverAlreadyNoted = false,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            awaitFrame = { t -> op.awaitFreshFrame(t, nowNanos = { 3_000L }, sleep = { true }, timeoutMs = 0L) },
            nowNanos = { 3_000L },
            uptimeMs = { 2_000L },
        )
        assertNotNull(syncB)
        assertTrue(syncB!!.spillRecovered)
        assertEquals(3_000L, op.currentFrameGuardSince())

        frameClockB.set(3_500L)
        op.awaitFreshFrame(targetB, nowNanos = { 3_500L }, sleep = { true }, timeoutMs = 0L)
        assertNull(op.currentFrameGuardSince())

        // 3. Deferred InUse and expired watch clear an existing launch frame guard so screenshot does not stall.
        val (targetC, shellC) = virtualTarget()
        val versionBeforeC = targetC.control.version
        val arrivedC = spillArrival(taskId = 101, packageName = "com.example.spilled", at = 200L)
        `when`(shellC.mainArrivals(100L)).thenReturn(arrivedC)
        op.guardFramesFromNow(10_000L)
        assertEquals(HandsControl.BeginResult.Acquired, targetC.control.begin(timeoutMs = 2_000L))
        assertTrue(targetC.control.handBack())

        val syncInUse = op.syncControlBeforeStep(
            target = targetC,
            lastControlVersion = versionBeforeC,
            watch = HandsLoopWatch(),
            lastResult = "opened Mail.",
            loopNote = null,
            pendingSpillWatch = HandsDisplayTarget.SpillWatch(100L, -1),
            handoverAlreadyNoted = false,
            excluded = emptySet(),
            userTaps = { listOf(MainScreenSpill.Tap(350L, "com.example.spilled")) },
            settle = { true },
            uptimeMs = { 2_000L },
        )
        assertNotNull(syncInUse)
        assertNull(op.currentFrameGuardSince())

        val versionBeforeExpired = targetC.control.version
        op.guardFramesFromNow(20_000L)
        assertEquals(HandsControl.BeginResult.Acquired, targetC.control.begin(timeoutMs = 2_000L))
        assertTrue(targetC.control.handBack())

        val syncExpired = op.syncControlBeforeStep(
            target = targetC,
            lastControlVersion = versionBeforeExpired,
            watch = HandsLoopWatch(),
            lastResult = "opened Mail.",
            loopNote = null,
            pendingSpillWatch = HandsDisplayTarget.SpillWatch(100L, -1),
            handoverAlreadyNoted = false,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            uptimeMs = { 100L + HandsDisplayTarget.SPILL_WINDOW_MS + 1L },
        )
        assertNotNull(syncExpired)
        assertNull(op.currentFrameGuardSince())
    }

    @Test
    fun `openAppHint handles missing app and Checked PullBack or InUse outcomes`() {
        val app = HandsApps.App(label = "Mail", packageName = "com.example.mail")

        // 1. app == null: returns not-found message without arming spill watch or starting an app.
        val (targetMissing, shellMissing) = virtualTarget()
        val missing = op.openAppHint(
            target = targetMissing,
            hint = "NoSuchApp",
            app = null,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
        )
        assertNotNull(missing)
        assertFalse(missing!!.handoverNoted)
        assertNull(missing.pendingSpillWatch)
        assertEquals("no installed app matches “NoSuchApp”; open the one you need yourself.", missing.lastResult)
        assertNull(op.currentFrameGuardSince())
        verify(shellMissing, never()).watchMainScreen(anyInt())
        verify(shellMissing, never()).startApp(anyInt(), anyString())

        // 2. Checked PullBack with START_OK_UNMUTED: pulls back, notifies onAppStarted(unmuted = true), awaits fresh frame.
        val frameClockPullBack = AtomicLong(500L)
        val (targetPullBack, shellPullBack) = virtualTarget(frameTimestampNanos = frameClockPullBack)
        `when`(shellPullBack.startApp(0, app.packageName)).thenReturn(ShellService.START_OK_UNMUTED)
        val arrivedPullBack = spillArrival(taskId = 101, packageName = app.packageName, at = 0L)
        `when`(shellPullBack.mainArrivals(anyLong())).thenReturn(arrivedPullBack)
        `when`(shellPullBack.moveTask(101, 0)).thenAnswer {
            frameClockPullBack.set(15_000L)
            true
        }
        val startedPullBack = mutableListOf<Pair<String, Boolean>>()
        val awaitedPullBack = AtomicInteger(0)
        val pullBack = op.openAppHint(
            target = targetPullBack,
            hint = "Mail",
            app = app,
            excluded = emptySet(),
            userTaps = { emptyList() },
            onAppStarted = { pkg, unmuted -> startedPullBack += pkg to unmuted },
            settle = { true },
            awaitFrame = { t ->
                awaitedPullBack.incrementAndGet()
                op.awaitFreshFrame(t, nowNanos = { 15_000L }, sleep = { true }, timeoutMs = 0L)
            },
            nowNanos = { 10_000L },
        )
        assertNotNull(pullBack)
        assertFalse(pullBack!!.handoverNoted)
        assertNull(pullBack.pendingSpillWatch)
        assertEquals(listOf(app.packageName to true), startedPullBack)
        assertEquals(1, awaitedPullBack.get())
        assertNull(op.currentFrameGuardSince())
        assertEquals(
            "opened Mail (restored to virtual screen; audio muting unavailable, sound may play on the phone).",
            pullBack.lastResult,
        )
        verify(shellPullBack, times(1)).moveTask(101, 0)

        // 3. Checked InUse: does not move task or call onAppStarted, clears frame guard.
        val (targetInUse, shellInUse) = virtualTarget()
        `when`(shellInUse.startApp(0, app.packageName)).thenReturn(ShellService.START_OK)
        val arrivedInUse = spillArrival(taskId = 101, packageName = app.packageName, at = 0L)
        `when`(shellInUse.mainArrivals(anyLong())).thenReturn(arrivedInUse)
        val startedInUse = AtomicInteger(0)
        val inUse = op.openAppHint(
            target = targetInUse,
            hint = "Mail",
            app = app,
            excluded = emptySet(),
            userTaps = { listOf(MainScreenSpill.Tap(50L, app.packageName)) },
            onAppStarted = { _, _ -> startedInUse.incrementAndGet() },
            settle = { true },
            nowNanos = { 10_000L },
        )
        assertNotNull(inUse)
        assertFalse(inUse!!.handoverNoted)
        assertNull(inUse.pendingSpillWatch)
        assertEquals(0, startedInUse.get())
        assertNull(op.currentFrameGuardSince())
        assertEquals("app Mail is currently in use on the main screen; left on main screen.", inUse.lastResult)
        verify(shellInUse, never()).moveTask(anyInt(), anyInt())
    }

    @Test
    fun `executeOpenAppStep preserves or clears frame guard across Skipped, handover and cold restart paths`() {
        val app = HandsApps.App(label = "Mail", packageName = "com.example.mail")

        // 1. START_OK + Skipped keeps frameGuardSince and saves pendingSpillWatch.
        val (targetOk, shellOk) = virtualTarget()
        `when`(shellOk.startApp(0, app.packageName)).thenReturn(ShellService.START_OK)
        val startedOk = mutableListOf<Pair<String, Boolean>>()
        val okOutcome = op.executeOpenAppStep(
            target = targetOk,
            action = HandsAction.OpenApp("Mail"),
            app = app,
            controlVersion = targetOk.control.version,
            excluded = emptySet(),
            userTaps = { emptyList() },
            onAppStarted = { pkg, unmuted -> startedOk += pkg to unmuted },
            settle = {
                assertEquals(HandsControl.BeginResult.Acquired, targetOk.control.begin(timeoutMs = 2_000L))
                true
            },
            nowNanos = { 10_000L },
        )
        assertNotNull(okOutcome)
        assertFalse(okOutcome!!.handedOverStep)
        assertNotNull(okOutcome.pendingSpillWatch)
        assertEquals("opened Mail.", okOutcome.lastResult)
        assertEquals(listOf(app.packageName to false), startedOk)
        assertEquals(10_000L, op.currentFrameGuardSince())

        // 2. Cold restart + START_OK_UNMUTED + Skipped calls restartApp and keeps frameGuardSince.
        val (targetCold, shellCold) = virtualTarget()
        `when`(shellCold.restartApp(0, app.packageName)).thenReturn(ShellService.START_OK_UNMUTED)
        val startedCold = mutableListOf<Pair<String, Boolean>>()
        val coldOutcome = op.executeOpenAppStep(
            target = targetCold,
            action = HandsAction.OpenApp("Mail", cold = true),
            app = app,
            controlVersion = targetCold.control.version,
            excluded = emptySet(),
            userTaps = { emptyList() },
            onAppStarted = { pkg, unmuted -> startedCold += pkg to unmuted },
            settle = {
                assertEquals(HandsControl.BeginResult.Acquired, targetCold.control.begin(timeoutMs = 2_000L))
                true
            },
            nowNanos = { 20_000L },
        )
        assertNotNull(coldOutcome)
        assertFalse(coldOutcome!!.handedOverStep)
        assertNotNull(coldOutcome.pendingSpillWatch)
        assertEquals("opened Mail (audio muting unavailable; sound may play on the phone).", coldOutcome.lastResult)
        assertEquals(listOf(app.packageName to true), startedCold)
        assertEquals(20_000L, op.currentFrameGuardSince())
        verify(shellCold, times(1)).restartApp(0, app.packageName)
        verify(shellCold, never()).startApp(anyInt(), anyString())

        // 3. START_IN_USE_ON_MAIN + Skipped clears frameGuardSince and does not queue pendingSpillWatch.
        val (targetInUse, shellInUse) = virtualTarget()
        `when`(shellInUse.startApp(0, app.packageName)).thenReturn(ShellService.START_IN_USE_ON_MAIN)
        val inUseOutcome = op.executeOpenAppStep(
            target = targetInUse,
            action = HandsAction.OpenApp("Mail"),
            app = app,
            controlVersion = targetInUse.control.version,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = {
                assertEquals(HandsControl.BeginResult.Acquired, targetInUse.control.begin(timeoutMs = 2_000L))
                true
            },
            nowNanos = { 30_000L },
        )
        assertNotNull(inUseOutcome)
        assertFalse(inUseOutcome!!.handedOverStep)
        assertNull(inUseOutcome.pendingSpillWatch)
        assertNull(op.currentFrameGuardSince())
        assertEquals("cannot open Mail: user is currently using it on the main screen.", inUseOutcome.lastResult)

        // 4. Non-handover START_ERROR + Skipped clears frameGuardSince but keeps pendingSpillWatch.
        val (targetErr, shellErr) = virtualTarget()
        `when`(shellErr.startApp(0, app.packageName)).thenReturn(ShellService.START_ERROR)
        val errOutcome = op.executeOpenAppStep(
            target = targetErr,
            action = HandsAction.OpenApp("Mail"),
            app = app,
            controlVersion = targetErr.control.version,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = {
                assertEquals(HandsControl.BeginResult.Acquired, targetErr.control.begin(timeoutMs = 2_000L))
                true
            },
            nowNanos = { 40_000L },
        )
        assertNotNull(errOutcome)
        assertFalse(errOutcome!!.handedOverStep)
        assertNotNull(errOutcome.pendingSpillWatch)
        assertNull(op.currentFrameGuardSince())
        assertEquals("could not open Mail (code ${ShellService.START_ERROR}).", errOutcome.lastResult)

        // 5. START_ERROR caused by takeover before openApp clears frameGuardSince and flags handedOverStep.
        val (targetPreHandover, shellPreHandover) = virtualTarget()
        val versionBefore = targetPreHandover.control.version
        `when`(shellPreHandover.watchMainScreen(anyInt())).thenAnswer {
            assertEquals(HandsControl.BeginResult.Acquired, targetPreHandover.control.begin(timeoutMs = 2_000L))
            -1
        }
        val handoverOutcome = op.executeOpenAppStep(
            target = targetPreHandover,
            action = HandsAction.OpenApp("Mail"),
            app = app,
            controlVersion = versionBefore,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            nowNanos = { 50_000L },
        )
        assertNotNull(handoverOutcome)
        assertTrue(handoverOutcome!!.handedOverStep)
        assertNull(handoverOutcome.pendingSpillWatch)
        assertNull(op.currentFrameGuardSince())
    }

    @Test
    fun `WAITING_FOR_FRAME retries do not repeat syncControlBeforeStep awaitFrame and guard blocks stale screenshots until a fresh frame arrives`() {
        val app = HandsApps.App(label = "Mail", packageName = "com.example.mail")

        // 1. No handover: openAppHint waits once for a fresh frame and times out with guard kept.
        // Five subsequent WAITING_FOR_FRAME retries must NOT invoke syncControlBeforeStep's awaitFrame
        // again, and screenshot() must block stale frames without reading latestFrameBitmap().
        val frameClockNoHandover = AtomicLong(500L)
        val displayNoHandover = mock(AgentDisplay::class.java)
        val (targetNoHandover, shellNoHandover) = virtualTarget(
            frameTimestampNanos = frameClockNoHandover,
            display = displayNoHandover,
        )
        `when`(shellNoHandover.startApp(0, app.packageName)).thenReturn(ShellService.START_OK)
        `when`(shellNoHandover.topTaskId(0)).thenReturn(77)

        val actionClockMs = AtomicLong(1_000L)
        val actionWaitedMs = AtomicLong(0L)
        val actionAwaitCalls = AtomicInteger(0)
        val hintOutcome = op.openAppHint(
            target = targetNoHandover,
            hint = "Mail",
            app = app,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            awaitFrame = { t ->
                actionAwaitCalls.incrementAndGet()
                op.awaitFreshFrame(
                    target = t,
                    nowNanos = { actionClockMs.get() * 1_000_000L },
                    sleep = { ms ->
                        actionWaitedMs.addAndGet(ms)
                        actionClockMs.addAndGet(ms)
                        true
                    },
                )
            },
            nowNanos = { 10_000L },
        )
        assertNotNull(hintOutcome)
        assertEquals(1, actionAwaitCalls.get())
        assertEquals(FrameFreshness.FRAME_WAIT_MS, actionWaitedMs.get())
        assertEquals(10_000L, op.currentFrameGuardSince())

        val syncAwaitCallsNoHandover = AtomicInteger(0)
        val syncWaitedMsNoHandover = AtomicLong(0L)
        var lastVersion = hintOutcome!!.lastControlVersion
        var frameWaits = 0
        repeat(5) {
            val sync = op.syncControlBeforeStep(
                target = targetNoHandover,
                lastControlVersion = lastVersion,
                watch = HandsLoopWatch(),
                lastResult = hintOutcome.lastResult,
                loopNote = null,
                pendingSpillWatch = null,
                handoverAlreadyNoted = false,
                excluded = emptySet(),
                userTaps = { emptyList() },
                settle = { true },
                awaitFrame = {
                    syncAwaitCallsNoHandover.incrementAndGet()
                    syncWaitedMsNoHandover.addAndGet(FrameFreshness.FRAME_WAIT_MS)
                },
            )
            assertNotNull(sync)
            assertFalse(sync!!.handoverOccurred)
            assertFalse(sync.spillRecovered)
            lastVersion = sync.controlVersion

            val shot = op.screenshot(svc, targetNoHandover)
            assertNull(shot)
            val state = HandsScreenState.classify(
                virtual = targetNoHandover.isVirtual,
                hasTask = targetNoHandover.hasTask(),
                hasAppWindow = true,
                hasFrame = shot != null,
                black = false,
            )
            assertEquals(HandsScreenState.WAITING_FOR_FRAME, state)
            frameWaits++
        }
        assertEquals(5, frameWaits)
        assertEquals(0, syncAwaitCallsNoHandover.get())
        assertEquals(0L, syncWaitedMsNoHandover.get())
        assertEquals(10_000L, op.currentFrameGuardSince())
        verify(displayNoHandover, never()).latestFrameBitmap()

        // 2. Handover skipped spill check after START_OK: syncControlBeforeStep waits ONCE on the
        // handover iteration (full 1.5s budget), then 0 times on subsequent WAITING_FOR_FRAME retries,
        // and screenshot() succeeds as soon as a post-launch frame arrives.
        val frameClockHandover = AtomicLong(500L)
        val displayHandover = mock(AgentDisplay::class.java)
        val freshBitmap = mock(Bitmap::class.java)
        `when`(displayHandover.latestFrameBitmap()).thenReturn(freshBitmap)
        val (targetHandover, shellHandover) = virtualTarget(
            frameTimestampNanos = frameClockHandover,
            display = displayHandover,
        )
        `when`(shellHandover.startApp(0, app.packageName)).thenReturn(ShellService.START_OK)
        `when`(shellHandover.topTaskId(0)).thenReturn(88)
        val openVersion = targetHandover.control.version

        val openStep = op.executeOpenAppStep(
            target = targetHandover,
            action = HandsAction.OpenApp("Mail"),
            app = app,
            controlVersion = openVersion,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = {
                assertEquals(HandsControl.BeginResult.Acquired, targetHandover.control.begin(timeoutMs = 2_000L))
                true
            },
            nowNanos = { 10_000L },
        )
        assertNotNull(openStep)
        assertEquals(10_000L, op.currentFrameGuardSince())
        assertTrue(targetHandover.control.handBack())

        val syncClockMs = AtomicLong(2_000L)
        val syncAwaitCallsHandover = AtomicInteger(0)
        val syncWaitedMsHandover = AtomicLong(0L)
        var trackedVersion: Long? = openVersion
        var pendingSpill = openStep!!.pendingSpillWatch
        var trackedResult = openStep.lastResult

        // Retry 1 (handover iteration) + Retries 2 & 3 (no further handover) with stale frame.
        repeat(3) { index ->
            val sync = op.syncControlBeforeStep(
                target = targetHandover,
                lastControlVersion = trackedVersion,
                watch = HandsLoopWatch(),
                lastResult = trackedResult,
                loopNote = null,
                pendingSpillWatch = pendingSpill,
                handoverAlreadyNoted = false,
                excluded = emptySet(),
                userTaps = { emptyList() },
                settle = { true },
                awaitFrame = { t ->
                    syncAwaitCallsHandover.incrementAndGet()
                    op.awaitFreshFrame(
                        target = t,
                        nowNanos = { syncClockMs.get() * 1_000_000L },
                        sleep = { ms ->
                            syncWaitedMsHandover.addAndGet(ms)
                            syncClockMs.addAndGet(ms)
                            true
                        },
                    )
                },
                uptimeMs = { 500L },
            )
            assertNotNull(sync)
            assertEquals(index == 0, sync!!.handoverOccurred)
            trackedVersion = sync.controlVersion
            pendingSpill = sync.pendingSpillWatch
            trackedResult = sync.lastResult

            val shot = op.screenshot(svc, targetHandover)
            assertNull(shot)
            assertEquals(
                HandsScreenState.WAITING_FOR_FRAME,
                HandsScreenState.classify(
                    virtual = true,
                    hasTask = targetHandover.hasTask(),
                    hasAppWindow = true,
                    hasFrame = false,
                    black = false,
                ),
            )
        }
        assertEquals(1, syncAwaitCallsHandover.get())
        assertEquals(FrameFreshness.FRAME_WAIT_MS, syncWaitedMsHandover.get())
        verify(displayHandover, never()).latestFrameBitmap()

        // Retry 4: a fresh frame arrives -> screenshot() clears the guard and returns READY.
        frameClockHandover.set(15_000L)
        val finalSync = op.syncControlBeforeStep(
            target = targetHandover,
            lastControlVersion = trackedVersion,
            watch = HandsLoopWatch(),
            lastResult = trackedResult,
            loopNote = null,
            pendingSpillWatch = pendingSpill,
            handoverAlreadyNoted = false,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            awaitFrame = { syncAwaitCallsHandover.incrementAndGet() },
            uptimeMs = { 500L },
        )
        assertNotNull(finalSync)
        assertEquals(1, syncAwaitCallsHandover.get())
        val recoveredShot = op.screenshot(svc, targetHandover)
        assertEquals(freshBitmap, recoveredShot)
        assertNull(op.currentFrameGuardSince())
        assertEquals(
            HandsScreenState.READY,
            HandsScreenState.classify(
                virtual = true,
                hasTask = targetHandover.hasTask(),
                hasAppWindow = true,
                hasFrame = recoveredShot != null,
                black = false,
            ),
        )

        // 3. Deferred PullBack waits once on the spillRecovered iteration, not on subsequent retries.
        val frameClockPullBack = AtomicLong(2_000L)
        val displayPullBack = mock(AgentDisplay::class.java)
        val (targetPullBack, shellPullBack) = virtualTarget(
            frameTimestampNanos = frameClockPullBack,
            display = displayPullBack,
        )
        val arrivedPullBack = spillArrival(taskId = 101, packageName = "com.example.spilled", at = 200L)
        `when`(shellPullBack.mainArrivals(100L)).thenReturn(arrivedPullBack)
        `when`(shellPullBack.moveTask(101, 0)).thenReturn(true)
        val pullBackStartVersion = targetPullBack.control.version
        assertEquals(HandsControl.BeginResult.Acquired, targetPullBack.control.begin(timeoutMs = 2_000L))
        assertTrue(targetPullBack.control.handBack())

        val pullBackAwaitCalls = AtomicInteger(0)
        val pullBackSync1 = op.syncControlBeforeStep(
            target = targetPullBack,
            lastControlVersion = pullBackStartVersion,
            watch = HandsLoopWatch(),
            lastResult = "tap was performed.",
            loopNote = null,
            pendingSpillWatch = HandsDisplayTarget.SpillWatch(100L, -1),
            handoverAlreadyNoted = false,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            awaitFrame = { t ->
                pullBackAwaitCalls.incrementAndGet()
                op.awaitFreshFrame(t, nowNanos = { 3_000L }, sleep = { true }, timeoutMs = 0L)
            },
            nowNanos = { 3_000L },
            uptimeMs = { 2_000L },
        )
        assertNotNull(pullBackSync1)
        assertTrue(pullBackSync1!!.spillRecovered)
        assertEquals(1, pullBackAwaitCalls.get())
        assertEquals(3_000L, op.currentFrameGuardSince())
        assertNull(op.screenshot(svc, targetPullBack))

        val pullBackSync2 = op.syncControlBeforeStep(
            target = targetPullBack,
            lastControlVersion = pullBackSync1.controlVersion,
            watch = HandsLoopWatch(),
            lastResult = pullBackSync1.lastResult,
            loopNote = null,
            pendingSpillWatch = null,
            handoverAlreadyNoted = false,
            excluded = emptySet(),
            userTaps = { emptyList() },
            settle = { true },
            awaitFrame = { pullBackAwaitCalls.incrementAndGet() },
            nowNanos = { 3_000L },
            uptimeMs = { 2_500L },
        )
        assertNotNull(pullBackSync2)
        assertFalse(pullBackSync2!!.handoverOccurred)
        assertFalse(pullBackSync2.spillRecovered)
        assertEquals(1, pullBackAwaitCalls.get())
        assertNull(op.screenshot(svc, targetPullBack))
        verify(displayPullBack, never()).latestFrameBitmap()
    }
}
