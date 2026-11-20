package io.github.nanomuse.hands

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.offload.ShizukuManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/** The keyboard consent on a background display: asked once per task, and the fallback on no. */
class HandsImeConsentTest {
    private val context = mock(Context::class.java)
    private val op = HandsOperator(context)
    private val svc = mock(MinisAccessibilityService::class.java)
    private val node = mock(AccessibilityNodeInfo::class.java)
    private val action = HandsAction.InputText("hello", "search")

    private val previousRunner = AdbKeyboardHelper.processRunner
    private val previousReady = AdbKeyboardHelper.isPrivilegedReady
    private val previousSleep = AdbKeyboardHelper.sleepRunner
    private val previousSupported = AdbKeyboardHelper.virtualScreenImeSupported

    private var broadcastAccepted = true
    private var typed = 0

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
            when {
                argv.first() == "settings" ->
                    ShizukuManager.ProcessResult(0, "com.example.keyboard/.Ime", "")
                argv.getOrNull(3) == AdbKeyboardHelper.ACTION_INPUT_B64 -> {
                    typed++
                    ShizukuManager.ProcessResult(if (broadcastAccepted) 0 else 1, "", "")
                }
                else -> ShizukuManager.ProcessResult(0, "", "")
            }
        }
    }

    @After
    fun tearDown() {
        AdbKeyboardHelper.processRunner = previousRunner
        AdbKeyboardHelper.isPrivilegedReady = previousReady
        AdbKeyboardHelper.sleepRunner = previousSleep
        AdbKeyboardHelper.virtualScreenImeSupported = previousSupported
    }

    private fun virtualTarget(): HandsDisplayTarget = mock(HandsDisplayTarget::class.java).also {
        `when`(it.isVirtual).thenReturn(true)
    }

    private fun fieldConfirmsText() {
        `when`(node.refresh()).thenReturn(true)
        `when`(node.text).thenReturn(action.text)
    }

    private fun fieldAcceptsSetText() {
        `when`(svc.setNodeText(node, action.text)).thenReturn(true)
        fieldConfirmsText()
    }

    @Test
    fun `below Android 14 the keyboard is not offered`() {
        AdbKeyboardHelper.virtualScreenImeSupported = { false }
        val target = virtualTarget()
        fieldAcceptsSetText()
        var asked = 0

        assertTrue(op.typeText(svc, action, target) { asked++; true }.startsWith("typed"))

        assertEquals(0, asked)
        assertEquals(0, typed)
        verify(svc).setNodeText(node, action.text)
    }

    @Test
    fun `a refused keyboard keeps the accessibility path and asks nothing again`() {
        val target = virtualTarget()
        fieldAcceptsSetText()
        var asked = 0

        assertTrue(op.typeText(svc, action, target) { asked++; false }.startsWith("typed"))

        assertEquals(1, asked)
        assertEquals(0, typed)
        verify(svc).setNodeText(node, action.text)
    }

    @Test
    fun `an allowed keyboard types and confirms without the accessibility path`() {
        val target = virtualTarget()
        fieldConfirmsText()
        var asked = 0

        assertTrue(op.typeText(svc, action, target) { asked++; true }.startsWith("typed"))

        assertEquals(1, asked)
        assertEquals(1, typed)
        verify(svc, never()).setNodeText(node, action.text)
        verify(target, never()).setClipText(action.text)
        verify(target, never()).injectText(action.text)
    }

    @Test
    fun `a rejected broadcast falls back to setting the text`() {
        broadcastAccepted = false
        val target = virtualTarget()
        fieldAcceptsSetText()
        var asked = 0

        assertTrue(op.typeText(svc, action, target) { asked++; true }.startsWith("typed"))

        assertEquals(1, asked)
        assertEquals(1, typed)
        verify(svc).setNodeText(node, action.text)
    }

    @Test
    fun `an accepted broadcast that cannot be confirmed is not typed twice`() {
        val target = virtualTarget()
        `when`(node.refresh()).thenReturn(false)

        assertTrue(op.typeText(svc, action, target) { true }.contains("did not confirm"))

        assertEquals(1, typed)
        verify(svc, never()).setNodeText(node, action.text)
        verify(target, never()).setClipText(action.text)
        verify(target, never()).injectText(action.text)
    }

    @Test
    fun `the question is asked once per task`() {
        val target = virtualTarget()
        fieldConfirmsText()
        var asked = 0

        op.typeText(svc, action, target) { asked++; true }
        op.typeText(svc, action, target) { asked++; true }

        assertEquals(1, asked)
        assertEquals(2, typed)
    }

    @Test
    fun `stop while waiting for the answer types nothing`() {
        val target = virtualTarget()

        assertThrows(InterruptedException::class.java) {
            op.typeText(svc, action, target) {
                op.requestStop()
                false
            }
        }

        assertEquals(0, typed)
        verify(svc, never()).setNodeText(node, action.text)
        verify(target, never()).setClipText(action.text)
    }

    @Test
    fun `a password field focused while waiting is not typed into`() {
        val target = virtualTarget()
        val password = mock(AccessibilityNodeInfo::class.java)
        `when`(password.isPassword).thenReturn(true)
        `when`(node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)).thenReturn(node, password)
        var asked = 0

        op.typeText(svc, action, target) { asked++; true }

        assertEquals(1, asked)
        assertEquals(0, typed)
        verify(svc, never()).setNodeText(node, action.text)
    }

    @Test
    fun `two failed attempts end the keyboard attempts for the task`() {
        broadcastAccepted = false
        val target = virtualTarget()
        fieldAcceptsSetText()
        var asked = 0
        val consent = { asked++; true }

        repeat(3) { op.typeText(svc, action, target, consent) }

        assertEquals(1, asked)
        assertEquals(2, typed)
        verify(svc, times(3)).setNodeText(node, action.text)
    }
}
