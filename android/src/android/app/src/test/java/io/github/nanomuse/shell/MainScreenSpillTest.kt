package io.github.nanomuse.shell

import io.github.nanomuse.shell.MainScreenSpill.InUse
import io.github.nanomuse.shell.MainScreenSpill.PullBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenSpillTest {
    private val since = 10_000L
    private val window = 6_000L
    private val home = setOf("com.home", "io.github.nanomuse")

    private fun arrival(
        task: Int = 7,
        pkg: String? = "com.chat",
        at: Long = since + 500,
        from: Int = MainScreenSpill.NEW,
        sameTask: Boolean = false,
        standard: Boolean = true,
        now: Int = MainScreenSpill.MAIN,
        user: Int = 0,
    ) = MainScreenSpill.Arrival(task, pkg, user, at, from, sameTask, standard, now)

    private fun decide(
        vararg a: MainScreenSpill.Arrival,
        topBefore: Int = 3,
        taps: List<MainScreenSpill.Tap> = emptyList(),
    ) = MainScreenSpill.decide(a.toList(), since, window, topBefore, home, taps)

    @Test
    fun newTaskOnTheMainScreenRightAfterTheActionIsPulledBack() {
        assertEquals(listOf(PullBack(arrival())), decide(arrival()))
    }

    @Test
    fun backgroundTaskOnTheMainScreenBroughtToFrontIsPulledBackToo() {
        val a = arrival(from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(a)), decide(a))
    }

    @Test
    fun outsideTheWindowIsNotThisAction() {
        assertTrue(decide(arrival(at = since - 1)).isEmpty())
        assertTrue(decide(arrival(at = since + window + 1)).isEmpty())
    }

    @Test
    fun launcherNanoMuseAndNonStandardPagesAreNotSpills() {
        assertTrue(decide(arrival(pkg = "com.home")).isEmpty())
        assertTrue(decide(arrival(pkg = "io.github.nanomuse")).isEmpty())
        assertTrue(decide(arrival(standard = false)).isEmpty())
        assertTrue(decide(arrival(pkg = null)).isEmpty())
    }

    @Test
    fun taskMovedFromAnotherDisplayIsATakeoverNotASpill() {
        assertTrue(decide(arrival(from = 5)).isEmpty())
    }

    @Test
    fun taskNoLongerOnTheMainScreenIsLeftAlone() {
        assertTrue(decide(arrival(now = 5)).isEmpty())
        assertTrue(decide(arrival(now = -1)).isEmpty())
    }

    @Test
    fun userTappingTheMainScreenMeansTheUserOpenedIt() {
        val other = MainScreenSpill.Tap(since + 400, "com.other")
        assertTrue(decide(arrival(at = since + 900), taps = listOf(other)).isEmpty())
        assertEquals(1, decide(arrival(at = since + 900), taps = listOf(MainScreenSpill.Tap(since - 100, "com.other"))).size)
    }

    @Test
    fun userTappingThePopupItselfLeavesItThere() {
        val a = arrival(at = since + 900)
        assertEquals(listOf(InUse(a)), decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.chat"))))
        assertEquals(1, decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.other"))).size)
    }

    @Test
    fun previousTopComingBackIsNotASpill() {
        assertTrue(decide(arrival(task = 3, from = MainScreenSpill.MAIN), topBefore = 3).isEmpty())
    }

    @Test
    fun newPageInTheAppTheUserIsUsingIsReportedNotMoved() {
        val a = arrival(task = 3, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(InUse(a)), decide(a, topBefore = 3))
    }

    @Test
    fun systemQuestionIsLeftForTheUserAndNothingElseIs() {
        val leave = MainScreenSpill.Handling.LeaveForUser
        val move = MainScreenSpill.Handling.Move
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = true, optionsMatch = false))
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = true))
        assertEquals(move, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = false))
        assertEquals(move, MainScreenSpill.handling(systemPackage = false, systemPicker = false, optionsMatch = true))
    }

    @Test
    fun appPickedByTheUserIsWhatCameUpAfterTheQuestionClosed() {
        val gone = since + 20_000
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val picked = arrival(task = 9, at = gone - 200)
        val pick = { list: List<MainScreenSpill.Arrival> -> MainScreenSpill.picked(list, 8, gone, 1_000, 3_000, 3, home) }
        assertEquals(picked, pick(listOf(chooser, picked)))
        assertEquals(picked, MainScreenSpill.picked(listOf(picked), 8, gone, 1_000, 3_000, 3, home))
        assertEquals(null, pick(listOf(chooser, arrival(task = 3, at = gone, from = MainScreenSpill.MAIN))))
        assertEquals(null, pick(listOf(arrival(task = 9, at = gone - 5_000))))
        val existing = arrival(task = 12, at = gone + 400, from = MainScreenSpill.MAIN, user = 128)
        assertEquals(existing, pick(listOf(chooser, existing)))
    }

    @Test
    fun pickedAppNeedsTheUserForAPasswordOrASecureUnreadablePage() {
        assertTrue(MainScreenSpill.needsUser(passwordField = true, readable = true, secureWindow = false))
        assertTrue(MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = true))
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = false))
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = true, secureWindow = false))
    }

    @Test
    fun accountServiceWithoutLauncherIsAUserStep() {
        val auth = setOf("com.example.accounts", "com.example.chat")
        assertTrue(MainScreenSpill.authenticatorUi("com.example.accounts", auth, hasLauncher = false))
    }

    @Test
    fun authenticatorAppWithLauncherIsMovedBackAsBefore() {
        val auth = setOf("com.example.accounts", "com.example.chat")
        assertFalse(MainScreenSpill.authenticatorUi("com.example.chat", auth, hasLauncher = true))
        assertFalse(MainScreenSpill.authenticatorUi("com.example.helper", auth, hasLauncher = false))
    }

    @Test
    fun oneVerdictPerTaskInOrder() {
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val first = arrival(task = 7, at = since + 200)
        val again = arrival(task = 7, at = since + 600, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(chooser), PullBack(again)), decide(again, first, chooser))
    }

    @Test
    fun mainScreenLogRecordsArrivalsAndDetectsSameTask() {
        val log = MainScreenLog()
        val t1 = MainScreenLog.Task(1, 0, "com.app", 0, "Act1", true)
        val t2 = MainScreenLog.Task(2, 0, "com.other", 0, "Act2", true)
        log.scan(listOf(t1), 100, record = false)
        log.scan(listOf(t2, t1), 200, record = true)
        val arrs = log.since(0)
        assertEquals(1, arrs.size)
        assertEquals(2, arrs[0].taskId)
        assertEquals(MainScreenLog.NEW, arrs[0].from)

        // same task topActivity change
        val t2Updated = MainScreenLog.Task(2, 0, "com.other", 0, "Act2Sub", true)
        log.scan(listOf(t2Updated, t1), 300, record = true)
        val arrs2 = log.since(0)
        assertEquals(2, arrs2.size)
        assertTrue(arrs2[1].sameTask)
    }
}
