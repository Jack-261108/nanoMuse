package io.github.nanomuse.hands

import android.view.KeyEvent
import android.view.Display
import android.os.Bundle
import io.github.nanomuse.display.AgentDisplay
import io.github.nanomuse.shell.INanoMuseShell
import io.github.nanomuse.shell.MainScreenSpill
import io.github.nanomuse.shell.MainScreenWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString

class VirtualDisplayInputTest {

    @Rule
    @JvmField
    val testTimeout: Timeout = Timeout.seconds(15)

    @Test
    fun testVirtualDisplayInputDelegation() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        `when`(shell.injectKey(42, KeyEvent.KEYCODE_ENTER)).thenReturn(true)
        `when`(shell.injectText(42, "hello")).thenReturn(true)
        `when`(shell.setClipText("clipboard text")).thenReturn(0)
        `when`(shell.injectCtrlKey(42, KeyEvent.KEYCODE_A)).thenReturn(true)
        `when`(shell.injectCtrlKey(42, KeyEvent.KEYCODE_V)).thenReturn(true)

        val target = VirtualDisplayTarget(display, shell)

        assertTrue(target.isVirtual)
        assertEquals(42, target.displayId)
        assertTrue(target.injectKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(target.injectText("hello"))
        assertTrue(target.setClipText("clipboard text"))
        target.restoreClip()
        verify(shell).restoreClip()
        assertTrue(target.injectCtrlKey(KeyEvent.KEYCODE_A))
        assertTrue(target.injectCtrlKey(KeyEvent.KEYCODE_V))
    }

    @Test
    fun testForegroundPackageDelegation() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        val svc = mock(com.openminis.app.accessibility.MinisAccessibilityService::class.java)
        `when`(display.displayId).thenReturn(42)
        `when`(shell.topTaskId(42)).thenReturn(101)
        `when`(shell.packageOfTask(101)).thenReturn("com.example.app")

        val target = VirtualDisplayTarget(display, shell)
        assertEquals("com.example.app", target.foregroundPackage(svc))
    }

    @Test
    fun `agent input is rejected while the person controls the preview`() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        val target = VirtualDisplayTarget(display, shell)
        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin())

        assertNull(target.captureScreenshot())
        assertFalse(target.tap(10, 20))
        assertFalse(target.doubleTap(10, 20))
        assertFalse(target.longPress(10, 20))
        assertFalse(target.swipe(10, 20, 30, 40, 200L))
        assertEquals(io.github.nanomuse.shell.ShellService.START_ERROR, target.openApp("com.example.app"))
        assertEquals(io.github.nanomuse.shell.ShellService.START_ERROR, target.restartApp("com.example.app"))
        assertFalse(target.pressBack())
        assertFalse(target.pressHome())
        assertFalse(target.injectKey(KeyEvent.KEYCODE_ENTER))
        assertFalse(target.injectText("hello"))
        assertFalse(target.setClipText("clip"))
        assertFalse(target.injectCtrlKey(KeyEvent.KEYCODE_V))

        verify(display, never()).latestFrameBitmap()
        verify(shell, never()).injectTap(anyInt(), anyInt(), anyInt())
        verify(shell, never()).injectStroke(anyInt(), any(), any(), any())
        verify(shell, never()).startApp(anyInt(), anyString())
        verify(shell, never()).restartApp(anyInt(), anyString())
        verify(shell, never()).injectKey(anyInt(), anyInt())
        verify(shell, never()).injectText(anyInt(), anyString())
        verify(shell, never()).setClipText(anyString())
        verify(shell, never()).injectCtrlKey(anyInt(), anyInt())
        target.release()
    }

    @Test
    fun `release closes HandsControl before tearing down display`() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        val target = VirtualDisplayTarget(display, shell)
        assertEquals(HandsControl.State.Automatic, target.control.current)

        target.release()

        assertEquals(HandsControl.State.Closed, target.control.current)
        assertFalse(target.tap(10, 20))
        verify(shell, never()).injectTap(anyInt(), anyInt(), anyInt())
        verify(display).release()
    }

    @Test
    fun `clipboard restore still runs while the person controls the preview`() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        val target = VirtualDisplayTarget(display, shell)
        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin())

        target.restoreClip()

        verify(shell).restoreClip()
        target.release()
    }

    @Test
    fun `spill recovery does not move a task while the person controls the preview`() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        val target = VirtualDisplayTarget(display, shell)
        assertEquals(HandsControl.BeginResult.Acquired, target.control.begin())

        assertNull(target.checkSpill(HandsDisplayTarget.SpillWatch(100, -1)))

        verify(shell, never()).mainArrivals(anyLong())
        verify(shell, never()).moveTask(anyInt(), anyInt())
        target.release()
    }

    @Test
    fun `takeover requires a task and a successful move to main`() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        `when`(shell.topTaskId(42)).thenReturn(-1)
        val target = VirtualDisplayTarget(display, shell)

        assertFalse(target.hasTask())
        assertFalse(target.onTakeoverStart())
        assertFalse(target.onTakeoverEnd())

        `when`(shell.topTaskId(42)).thenReturn(101)
        assertTrue(target.hasTask())
        assertFalse(target.onTakeoverStart())
        assertFalse(target.onTakeoverEnd())
        verify(shell, never()).moveTask(101, 42)
    }

    @Test
    fun `takeover returns only the task actually handed to the user`() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        `when`(shell.topTaskId(42)).thenReturn(101)
        `when`(shell.moveTask(101, Display.DEFAULT_DISPLAY)).thenReturn(true)
        `when`(shell.moveTask(101, 42)).thenReturn(true)
        val target = VirtualDisplayTarget(display, shell)

        assertTrue(target.onTakeoverStart())
        `when`(shell.topTaskId(42)).thenReturn(202)
        assertTrue(target.onTakeoverEnd())
        assertFalse(target.onTakeoverEnd())

        verify(shell).moveTask(101, 42)
        verify(shell, never()).moveTask(202, 42)
    }

    @Test
    fun `opening an app does not pull main screen tasks before user taps can be considered`() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        val target = VirtualDisplayTarget(display, shell)

        target.openApp("com.example.app")
        target.restartApp("com.example.app")

        verify(shell, never()).watchMainScreen(anyInt())
        verify(shell, never()).mainArrivals(anyLong())
        verify(shell, never()).moveTask(anyInt(), anyInt())
    }

    @Test
    fun `unmuted fallback status from shell is preserved by openApp and restartApp`() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        `when`(shell.startApp(42, "com.example.app")).thenReturn(io.github.nanomuse.shell.ShellService.START_OK_UNMUTED)
        `when`(shell.restartApp(42, "com.example.app")).thenReturn(io.github.nanomuse.shell.ShellService.START_OK_UNMUTED)
        val target = VirtualDisplayTarget(display, shell)

        assertEquals(io.github.nanomuse.shell.ShellService.START_OK_UNMUTED, target.openApp("com.example.app"))
        assertEquals(io.github.nanomuse.shell.ShellService.START_OK_UNMUTED, target.restartApp("com.example.app"))
    }

    private fun arrival(): Bundle = mock(Bundle::class.java).also {
        `when`(it.getIntArray(MainScreenWatch.KEY_TASK)).thenReturn(intArrayOf(101))
        `when`(it.getStringArray(MainScreenWatch.KEY_PACKAGE)).thenReturn(arrayOf("com.example.app"))
        `when`(it.getIntArray(MainScreenWatch.KEY_USER)).thenReturn(intArrayOf(0))
        `when`(it.getLongArray(MainScreenWatch.KEY_AT)).thenReturn(longArrayOf(200))
        `when`(it.getIntArray(MainScreenWatch.KEY_FROM)).thenReturn(intArrayOf(MainScreenSpill.NEW))
        `when`(it.getBooleanArray(MainScreenWatch.KEY_SAME_TASK)).thenReturn(booleanArrayOf(false))
        `when`(it.getBooleanArray(MainScreenWatch.KEY_STANDARD)).thenReturn(booleanArrayOf(true))
        `when`(it.getIntArray(MainScreenWatch.KEY_NOW)).thenReturn(intArrayOf(Display.DEFAULT_DISPLAY))
    }

    @Test
    fun `user tap before the arrival keeps the newly opened app on main`() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        val arrived = arrival()
        `when`(shell.mainArrivals(100)).thenReturn(arrived)
        val target = VirtualDisplayTarget(display, shell)

        val verdict = target.checkSpill(
            HandsDisplayTarget.SpillWatch(100, -1),
            userTaps = listOf(MainScreenSpill.Tap(150, "com.example.other")),
        )

        assertNull(verdict)
        verify(shell, never()).moveTask(anyInt(), anyInt())
    }

    @Test
    fun `failed spill recovery never reports that the task was returned`() {
        val shell = mock(INanoMuseShell::class.java)
        val display = mock(AgentDisplay::class.java)
        `when`(display.displayId).thenReturn(42)
        val arrived = arrival()
        `when`(shell.mainArrivals(100)).thenReturn(arrived)
        val target = VirtualDisplayTarget(display, shell)

        assertThrows(IllegalStateException::class.java) {
            target.checkSpill(HandsDisplayTarget.SpillWatch(100, -1))
        }

        verify(shell).moveTask(101, 42)
    }
}
