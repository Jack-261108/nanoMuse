package io.github.nanomuse.hands

import android.view.KeyEvent
import io.github.nanomuse.display.AgentDisplay
import io.github.nanomuse.shell.INanoMuseShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class VirtualDisplayInputTest {

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
}
