package io.github.nanomuse.hands

import android.view.KeyEvent
import io.github.nanomuse.display.AgentDisplay
import io.github.nanomuse.shell.INanoMuseShell
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify

class VirtualDisplayPipInputTest {

    private lateinit var shell: INanoMuseShell

    private fun target(): VirtualDisplayTarget {
        shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        `when`(display.width).thenReturn(1080)
        `when`(display.height).thenReturn(2400)
        return VirtualDisplayTarget(display, shell)
    }

    @Test
    fun `a tap is scaled from the preview to the display`() {
        val target = target()
        target.pipInput.tap(0.5f, 0.25f)
        verify(shell, timeout(2_000)).injectTap(42, 540, 600)
    }

    @Test
    fun `a swipe sends every point in order with a growing timeline`() {
        val target = target()
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
        target.pipInput.swipe(listOf(0.5f to 0.5f), durationMs = 300L)
        verify(shell, timeout(2_000)).injectTap(42, 540, 1200)
    }

    @Test
    fun `back injects the Back key into the display`() {
        val target = target()
        target.pipInput.back()
        verify(shell, timeout(2_000)).injectKey(42, KeyEvent.KEYCODE_BACK)
    }

    @Test
    fun `a tap is queued on a worker thread, not the caller's`() {
        val target = target()
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
}
