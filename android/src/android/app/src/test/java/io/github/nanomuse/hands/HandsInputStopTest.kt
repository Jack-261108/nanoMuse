package io.github.nanomuse.hands

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.offload.ShizukuManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class HandsInputStopTest {
    private val context = mock(Context::class.java)
    private val op = HandsOperator(context)
    private val svc = mock(MinisAccessibilityService::class.java)
    private val node = mock(AccessibilityNodeInfo::class.java)
    private val action = HandsAction.InputText("hello", "search")

    private fun focusedField() {
        `when`(svc.rootInActiveWindow).thenReturn(node)
        `when`(node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)).thenReturn(node)
    }

    private fun virtualTarget(): HandsDisplayTarget = mock(HandsDisplayTarget::class.java).also {
        // The mock uses the default display tree so this test needs no Android window manager.
        `when`(it.isVirtual).thenReturn(true)
        focusedField()
    }

    @Test
    fun `ADB keyboard cancellation restores IME and prevents accessibility fallback`() {
        focusedField()
        val packages = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(packages)
        @Suppress("DEPRECATION")
        `when`(packages.getPackageInfo(AdbKeyboardHelper.ADB_KEYBOARD_PACKAGE, 0)).thenReturn(PackageInfo())
        val previousRunner = AdbKeyboardHelper.processRunner
        val previousReady = AdbKeyboardHelper.isPrivilegedReady
        val previousSleep = AdbKeyboardHelper.sleepRunner
        val commands = mutableListOf<List<String>>()
        val originalIme = "com.example.keyboard/.Ime"
        try {
            AdbKeyboardHelper.isPrivilegedReady = { true }
            AdbKeyboardHelper.processRunner = { argv ->
                commands += argv.toList()
                ShizukuManager.ProcessResult(0, if (argv.first() == "settings") originalIme else "", "")
            }
            AdbKeyboardHelper.sleepRunner = {
                op.requestStop()
                throw InterruptedException("cancelled while switching IME")
            }

            assertThrows(InterruptedException::class.java) { op.typeText(svc, action) }

            assertEquals(listOf("ime", "set", originalIme), commands.last())
            verify(svc, never()).setNodeText(node, action.text)
            verify(node, never()).performAction(anyInt())
            verify(context, never()).getSystemService(Context.CLIPBOARD_SERVICE)
        } finally {
            AdbKeyboardHelper.processRunner = previousRunner
            AdbKeyboardHelper.isPrivilegedReady = previousReady
            AdbKeyboardHelper.sleepRunner = previousSleep
            Thread.interrupted()
        }
    }

    @Test
    fun `virtual set text cancellation prevents clipboard and shell fallbacks`() {
        val target = virtualTarget()
        `when`(svc.setNodeText(node, action.text)).thenAnswer { op.requestStop(); false }

        assertThrows(InterruptedException::class.java) { op.typeText(svc, action, target) }

        verify(target, never()).setClipText(action.text)
        verify(target, never()).injectText(action.text)
        verify(node, never()).performAction(anyInt())
    }

    @Test
    fun `stop after setting virtual clipboard restores it without pasting`() {
        val target = virtualTarget()
        `when`(target.setClipText(action.text)).thenAnswer { op.requestStop(); true }

        assertThrows(InterruptedException::class.java) { op.typeText(svc, action, target) }

        verify(target).restoreClip()
        verify(node, never()).performAction(anyInt())
        verify(target, never()).injectCtrlKey(anyInt())
        verify(target, never()).injectText(action.text)
    }

    @Test
    fun `stop after select all prevents paste and restores virtual clipboard`() {
        val target = virtualTarget()
        `when`(target.setClipText(action.text)).thenReturn(true)
        `when`(target.injectCtrlKey(KeyEvent.KEYCODE_A)).thenAnswer { op.requestStop(); true }

        assertThrows(InterruptedException::class.java) { op.typeText(svc, action, target) }

        verify(target).restoreClip()
        verify(target, never()).injectCtrlKey(KeyEvent.KEYCODE_V)
        verify(target, never()).injectText(action.text)
    }

    @Test
    fun `virtual enter cancellation prevents accessibility fallback`() {
        val target = mock(HandsDisplayTarget::class.java)
        `when`(target.isVirtual).thenReturn(true)
        `when`(target.injectKey(KeyEvent.KEYCODE_ENTER)).thenAnswer { op.requestStop(); false }

        assertThrows(InterruptedException::class.java) { op.pressEnter(svc, target) }

        verify(svc, never()).rootInActiveWindow
        verify(svc, never()).windows
    }

    @Test
    fun `an interrupted worker does not begin text input`() {
        val target = virtualTarget()
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) { op.typeText(svc, action, target) }

            verify(svc, never()).setNodeText(node, action.text)
            verify(target, never()).setClipText(action.text)
            verify(target, never()).injectText(action.text)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `virtual clipboard fallback still completes and restores clipboard when running`() {
        val target = virtualTarget()
        `when`(target.setClipText(action.text)).thenReturn(true)
        `when`(target.injectCtrlKey(KeyEvent.KEYCODE_A)).thenReturn(true)
        `when`(target.injectCtrlKey(KeyEvent.KEYCODE_V)).thenReturn(true)
        `when`(node.refresh()).thenReturn(true)
        `when`(node.text).thenReturn(null, action.text)

        assertTrue(op.typeText(svc, action, target).startsWith("typed"))

        verify(target).injectCtrlKey(KeyEvent.KEYCODE_A)
        verify(target).injectCtrlKey(KeyEvent.KEYCODE_V)
        verify(target).restoreClip()
        verify(target, never()).injectText(action.text)
    }

    @Test
    fun `accepted paste is read back before restoring the clipboard`() {
        val target = virtualTarget()
        `when`(target.setClipText(action.text)).thenReturn(true)
        `when`(node.performAction(AccessibilityNodeInfo.ACTION_PASTE)).thenReturn(true)
        `when`(node.refresh()).thenReturn(true)
        `when`(node.text).thenReturn(null, action.text)

        assertTrue(op.typeText(svc, action, target).startsWith("typed"))

        inOrder(node, target).apply {
            verify(node).performAction(AccessibilityNodeInfo.ACTION_PASTE)
            verify(node).refresh()
            verify(node).text
            verify(target).restoreClip()
        }
        verify(target, never()).injectCtrlKey(anyInt())
        verify(target, never()).injectText(action.text)
    }

    @Test
    fun `paste whose text cannot be confirmed is not repeated through shell`() {
        val target = virtualTarget()
        `when`(target.setClipText(action.text)).thenReturn(true)
        `when`(node.performAction(AccessibilityNodeInfo.ACTION_PASTE)).thenReturn(true)
        `when`(node.refresh()).thenReturn(false)

        assertTrue(op.typeText(svc, action, target).contains("did not confirm"))

        verify(target).restoreClip()
        verify(target, never()).injectCtrlKey(anyInt())
        verify(target, never()).injectText(action.text)
    }

    @Test
    fun `text confirmation waits for the requested value instead of an unrelated edit`() {
        `when`(node.refresh()).thenReturn(true)
        `when`(node.text).thenReturn("different", action.text)

        assertTrue(op.awaitText(node, action.text, attempts = 2, pause = {}))
    }

    @Test
    fun `text confirmation times out without treating stale node text as success`() {
        `when`(node.refresh()).thenReturn(false)
        `when`(node.text).thenReturn(action.text)

        assertFalse(op.awaitText(node, action.text, attempts = 2, pause = {}))
    }

    @Test
    fun `stop while awaiting paste restores clipboard and does not retry`() {
        val target = virtualTarget()
        `when`(target.setClipText(action.text)).thenReturn(true)
        `when`(node.performAction(AccessibilityNodeInfo.ACTION_PASTE)).thenReturn(true)
        `when`(node.refresh()).thenAnswer { op.requestStop(); false }

        assertThrows(InterruptedException::class.java) { op.typeText(svc, action, target) }

        verify(target).restoreClip()
        verify(target, never()).injectCtrlKey(anyInt())
        verify(target, never()).injectText(action.text)
    }
}
