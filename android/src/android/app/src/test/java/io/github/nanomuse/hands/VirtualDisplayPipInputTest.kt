package io.github.nanomuse.hands

import android.view.Display
import android.view.KeyEvent
import io.github.nanomuse.display.AgentDisplay
import io.github.nanomuse.shell.INanoMuseShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VirtualDisplayPipInputTest {

    @Rule
    @JvmField
    val testTimeout: Timeout = Timeout.seconds(15)

    private lateinit var shell: INanoMuseShell
    private val createdTargets = mutableListOf<VirtualDisplayTarget>()

    private fun target(): VirtualDisplayTarget {
        shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        `when`(display.width).thenReturn(1080)
        `when`(display.height).thenReturn(2400)
        return VirtualDisplayTarget(display, shell).also { createdTargets += it }
    }

    @After
    fun tearDown() {
        createdTargets.forEach { it.release() }
        createdTargets.clear()
    }

    @Test
    fun `a tap is scaled from the preview to the display`() {
        val target = target()
        assertEquals(HandsControl.BeginResult.Acquired, target.pipInput.control.begin())
        target.pipInput.tap(0.5f, 0.25f)
        verify(shell, timeout(2_000)).injectTap(42, 540, 600)
    }

    @Test
    fun `a swipe sends every point in order with a growing timeline`() {
        val target = target()
        assertEquals(HandsControl.BeginResult.Acquired, target.pipInput.control.begin())
        target.pipInput.swipe(listOf(0.1f to 0.2f, 0.5f to 0.5f, 0.9f to 0.8f), durationMs = 300L)
        verify(shell, timeout(2_000)).injectStroke(
            org.mockito.ArgumentMatchers.eq(42),
            org.mockito.ArgumentMatchers.eq(intArrayOf(108, 540, 972)),
            org.mockito.ArgumentMatchers.eq(intArrayOf(480, 1200, 1920)),
            org.mockito.ArgumentMatchers.eq(longArrayOf(0L, 150L, 300L)),
        )
    }

    @Test
    fun `a swipe with one point falls back to a tap`() {
        val target = target()
        assertEquals(HandsControl.BeginResult.Acquired, target.pipInput.control.begin())
        target.pipInput.swipe(listOf(0.5f to 0.5f), durationMs = 300L)
        verify(shell, timeout(2_000)).injectTap(42, 540, 1200)
    }

    @Test
    fun `back injects the Back key into the display`() {
        val target = target()
        assertEquals(HandsControl.BeginResult.Acquired, target.pipInput.control.begin())
        target.pipInput.back()
        verify(shell, timeout(2_000)).injectKey(42, KeyEvent.KEYCODE_BACK)
    }

    @Test
    fun `a tap is queued on a worker thread, not the caller's`() {
        val target = target()
        assertEquals(HandsControl.BeginResult.Acquired, target.pipInput.control.begin())
        val before = Thread.currentThread()
        var reachedOn: Thread? = null
        `when`(shell.injectTap(anyInt(), anyInt(), anyInt())).thenAnswer {
            reachedOn = Thread.currentThread()
            true
        }
        target.pipInput.tap(0.1f, 0.1f)
        verify(shell, timeout(2_000)).injectTap(42, 108, 240)
        assertFalse("the injection must not block the caller's thread", reachedOn === before)
    }

    @Test
    fun `tap, swipe and back before takeover do not reach the display`() {
        val target = target()

        target.pipInput.tap(0.5f, 0.5f)
        target.pipInput.swipe(listOf(0.1f to 0.2f, 0.9f to 0.8f), durationMs = 200L)
        target.pipInput.back()
        // Flush the input queue with a real Manual tap check afterward or short barrier via release.
        assertEquals(HandsControl.BeginResult.Acquired, target.pipInput.control.begin())
        target.pipInput.tap(0.2f, 0.2f)
        verify(shell, timeout(2_000)).injectTap(42, 216, 480)

        verify(shell, never()).injectTap(42, 540, 1200)
        verify(shell, never()).injectStroke(anyInt(), any(), any(), any())
        verify(shell, never()).injectKey(anyInt(), anyInt())
    }

    @Test
    fun `requesting control does not replay the first touch`() {
        val target = target()

        target.pipInput.requestControl()
        awaitState(target, HandsControl.State.Manual)

        assertEquals(HandsControl.State.Manual, target.pipInput.control.current)
        verify(shell, times(0)).injectTap(anyInt(), anyInt(), anyInt())
    }

    @Test
    fun `handBack via pipInput stops later tap, swipe and back from reaching the display`() {
        val target = target()
        assertEquals(HandsControl.BeginResult.Acquired, target.pipInput.control.begin())
        target.pipInput.tap(0.5f, 0.5f)
        verify(shell, timeout(2_000)).injectTap(42, 540, 1200)

        target.pipInput.handBack()
        awaitState(target, HandsControl.State.Automatic)

        target.pipInput.tap(0.4f, 0.4f)
        target.pipInput.swipe(listOf(0.1f to 0.2f, 0.8f to 0.8f), durationMs = 200L)
        target.pipInput.back()
        // Re-acquire and send a sentinel tap so the FIFO input queue has drained past the rejected calls.
        assertEquals(HandsControl.BeginResult.Acquired, target.pipInput.control.begin())
        target.pipInput.tap(0.1f, 0.1f)
        verify(shell, timeout(2_000)).injectTap(42, 108, 240)

        verify(shell, times(1)).injectTap(42, 540, 1200)
        verify(shell, never()).injectTap(42, 432, 960)
        verify(shell, never()).injectStroke(anyInt(), any(), any(), any())
        verify(shell, never()).injectKey(anyInt(), anyInt())
    }

    @Test
    fun `handBack cancels a pending requestControl immediately while an agent action is in flight`() {
        val target = target()
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        `when`(shell.injectTap(42, 10, 20)).thenAnswer {
            actionStarted.countDown()
            assertTrue(releaseAction.await(2, TimeUnit.SECONDS))
            true
        }

        val agent = Thread { target.tap(10, 20) }
        agent.start()
        try {
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS))

            target.pipInput.requestControl()
            awaitState(target, HandsControl.State.Requesting)

            // While Requesting, swipe and back must also be rejected.
            target.pipInput.swipe(listOf(0.1f to 0.2f, 0.9f to 0.8f), durationMs = 200L)
            target.pipInput.back()

            // Calling pipInput.handBack() must cancel Requesting immediately without waiting for the agent action.
            target.pipInput.handBack()
            awaitState(target, HandsControl.State.Automatic)

            releaseAction.countDown()
            agent.join(1_000)
            assertEquals(HandsControl.State.Automatic, target.pipInput.control.current)
            verify(shell, never()).injectStroke(anyInt(), any(), any(), any())
            verify(shell, never()).injectKey(anyInt(), anyInt())
        } finally {
            releaseAction.countDown()
            target.release()
            agent.join(1_000)
        }
    }

    @Test
    fun `queued manual tap runs before pipInput handBack and does not block control reads`() {
        val target = target()
        assertEquals(HandsControl.BeginResult.Acquired, target.pipInput.control.begin())
        val tapEntered = CountDownLatch(1)
        val releaseTap = CountDownLatch(1)
        `when`(shell.injectTap(42, 540, 1200)).thenAnswer {
            tapEntered.countDown()
            assertTrue(releaseTap.await(2, TimeUnit.SECONDS))
            true
        }

        try {
            target.pipInput.tap(0.5f, 0.5f)
            assertTrue(tapEntered.await(1, TimeUnit.SECONDS))

            // Main-thread style status read must not block while Binder IPC is running inside userInput.
            assertEquals(HandsControl.State.Manual, target.pipInput.control.current)

            target.pipInput.handBack()
            // Release the tap; handBack executes right after it on inputExecutor.
            releaseTap.countDown()
            awaitState(target, HandsControl.State.Automatic)
            verify(shell, times(1)).injectTap(42, 540, 1200)
        } finally {
            releaseTap.countDown()
            target.release()
        }
    }

    @Test
    fun `main screen takeover hands back PiP control and blocks requestControl until restored`() {
        val target = target()
        `when`(shell.topTaskId(42)).thenReturn(101)
        `when`(shell.moveTask(101, Display.DEFAULT_DISPLAY)).thenReturn(true)
        `when`(shell.moveTask(101, 42)).thenReturn(true)

        assertEquals(HandsControl.BeginResult.Acquired, target.pipInput.control.begin())
        assertEquals(HandsControl.State.Manual, target.control.current)

        assertTrue(target.onTakeoverStart())
        assertEquals(HandsControl.State.Automatic, target.control.current)

        // While the task is on the main screen, requesting PiP control is ignored.
        target.pipInput.requestControl()
        assertEquals(HandsControl.State.Automatic, target.control.current)

        assertTrue(target.onTakeoverEnd())
        assertEquals(HandsControl.State.Automatic, target.control.current)

        // Once restored to the virtual screen, PiP control works again.
        target.pipInput.requestControl()
        awaitState(target, HandsControl.State.Manual)
    }

    private fun awaitState(target: VirtualDisplayTarget, expected: HandsControl.State) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
            target.pipInput.control.current != expected
        ) {
            Thread.yield()
        }
        assertEquals(expected, target.pipInput.control.current)
    }
}
