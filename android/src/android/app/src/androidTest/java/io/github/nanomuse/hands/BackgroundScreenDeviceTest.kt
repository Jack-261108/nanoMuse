package io.github.nanomuse.hands

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.UserManager
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nanomuse.display.AgentDisplay
import io.github.nanomuse.shell.INanoMuseShell
import io.github.nanomuse.shell.SharedShell
import io.github.nanomuse.shell.setup.BuiltinShellManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit physical-device validation. Runs only against the local fixture in the test APK.
 *
 * adb shell am instrument -w -e class io.github.nanomuse.hands.BackgroundScreenDeviceTest \
 *   io.github.nanomuse.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * This starts and stops the privileged helper. The app is left with the helper stopped.
 * It never starts HandsOperator.run, contacts a model, or opens an installed user app.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundScreenDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val automation: UiAutomation
        get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test
    fun realHelperDisplayInputTakeoverReuseAndStop() {
        assumeTrue("Background display requires Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        assumeTrue("Unlock the device before running this test", context.getSystemService(UserManager::class.java).isUserUnlocked)
        assertFalse("Do not run device validation during an active Hands task", Hands.active.value)
        val manager = BuiltinShellManager.instance(context)
        val ui = automation
        val oldInfo = ui.serviceInfo
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val displays = mutableListOf<AgentDisplay>()
        var cleanupShell: INanoMuseShell? = null
        var taskId = -1
        try {
            runBlocking { manager.stop() }
            // A previous helper can still be completing Binder-death cleanup.
            eventually("previous helper exits", 15_000) { helperProcesses().isEmpty() }
            val command = SharedShell.getAdbCommand(context)
            exec(command)
            eventually("helper Binder arrives", 20_000) { SharedShell.isReady() }
            val shell = SharedShell.shell.value!!
            cleanupShell = shell
            val helperPid = shell.identity()[1]
            checkpoint("handoff", "PASS: live shell Binder received")

            val beforeMain = shell.topTaskId(Display.DEFAULT_DISPLAY)
            val originalAudio = shell.getAudioMode(fixturePackage)
            assertNotNull("Fixture audio mode must be readable", originalAudio)
            val metrics = context.resources.displayMetrics
            fun newDisplay(): AgentDisplay = AgentDisplay(
                shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            ).also { displays += it }
            val display = newDisplay()
            val target = VirtualDisplayTarget(display, shell, context)
            assertTrue("Virtual display must not be the main screen", display.displayId > 0)
            assertEquals("New display must start empty", -1, shell.topTaskId(display.displayId))
            assertEquals("Fixture launch should succeed", 0, target.openApp(fixturePackage))
            eventually("fixture task on virtual display") {
                val id = shell.topTaskId(display.displayId)
                if (id >= 0 && shell.packageOfTask(id) == fixturePackage) {
                    taskId = id
                    true
                } else {
                    false
                }
            }
            awaitNode(display.displayId, "fixture-input")
            eventually("a real nonblack fixture frame arrives") {
                display.latestFrameBitmap()?.let { bitmap ->
                    try {
                        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        pixel and 0x00ffffff != 0
                    } finally { bitmap.recycle() }
                } ?: false
            }
            assertEquals("Background launch changed the main task", beforeMain, shell.topTaskId(Display.DEFAULT_DISPLAY))
            assertEquals("Background fixture must be muted", "ignore", shell.getAudioMode(fixturePackage))
            checkpoint("display", "PASS: empty display, fixture task, real frame, main task unchanged")

            tap(target, "fixture-input")
            eventually("fixture field is focused") { findNode(display.displayId, "fixture-input")?.isFocused == true }
            assertTrue("Text injection was rejected", target.injectText("nanomuse42"))
            eventually("text appears on virtual display") { fieldText(display.displayId) == "nanomuse42" }
            assertTrue("Enter injection was rejected", target.injectKey(KeyEvent.KEYCODE_ENTER))
            eventually("Enter reaches only the fixture") { state(display.displayId).contains("enter=1;") }
            tap(target, "fixture-click")
            eventually("one tap reaches the fixture") { state(display.displayId).contains("clicks=1;") }
            assertEquals("Virtual input changed the main task", beforeMain, shell.topTaskId(Display.DEFAULT_DISPLAY))
            checkpoint("input", "PASS: tap, text and Enter delivered to fixture")

            tap(target, "fixture-input")
            if (shell.setClipText("fixture-paste") == 0) {
                try {
                    assertTrue(target.injectCtrlKey(KeyEvent.KEYCODE_A))
                    assertTrue(target.injectCtrlKey(KeyEvent.KEYCODE_V))
                    eventually("paste is read back before clipboard restoration") { fieldText(display.displayId) == "fixture-paste" }
                    checkpoint("clipboard", "PASS: protected paste and field readback")
                } finally {
                    shell.restoreClip()
                }
            } else {
                shell.restoreClip()
                checkpoint("clipboard", "SKIP: clipboard transaction could not be established")
            }

            // Simulate Continue without doing any payment or tapping the main screen.
            val operator = HandsOperator(context)
            val gate = operator.manualPayment(target, "fixture-only payment label") {
                assertEquals("Takeover did not move the same task to main", Display.DEFAULT_DISPLAY, shell.displayOfTask(taskId))
                true
            }
            assertTrue("Continue must consume the old action", gate.reobserve)
            assertEquals("Takeover did not return the same task", display.displayId, shell.displayOfTask(taskId))
            eventually("fixture state remains readable after takeover") { state(display.displayId).contains("clicks=1;") }
            checkpoint("takeover", "PASS: same task moved out and back; no old tap replayed")

            shell.finishDisplay(display.displayId, arrayOf(fixturePackage), emptyArray())
            display.release()
            displays.remove(display)
            assertEquals("Retained task should be on main display", Display.DEFAULT_DISPLAY, shell.displayOfTask(taskId))
            assertEquals("Finished display must restore the previous audio mode", originalAudio, shell.getAudioMode(fixturePackage))
            assertEquals("Retained fixture must remain behind the user's task", beforeMain, shell.topTaskId(Display.DEFAULT_DISPLAY))
            val reused = newDisplay()
            assertEquals("Retained task should reopen", 0, shell.startApp(reused.displayId, fixturePackage))
            eventually("retained task reused on a new display") { shell.displayOfTask(taskId) == reused.displayId }
            assertEquals("Reused task must be muted again", "ignore", shell.getAudioMode(fixturePackage))
            shell.finishDisplay(reused.displayId, emptyArray(), arrayOf(fixturePackage))
            reused.release()
            displays.remove(reused)
            eventually("closed fixture task is removed") { shell.displayOfTask(taskId) == -1 }
            assertEquals(originalAudio, shell.getAudioMode(fixturePackage))
            shell.releaseDisplay(reused.displayId)
            shell.releaseDisplay(Display.DEFAULT_DISPLAY)
            assertEquals("Repeated cleanup must leave the main task intact", beforeMain, shell.topTaskId(Display.DEFAULT_DISPLAY))
            assertEquals(null, context.getSystemService(DisplayManager::class.java).getDisplay(reused.displayId))
            checkpoint("cleanup", "PASS: retained task remuted; display and task closed; audio restored")

            runBlocking { manager.stop() }
            eventually("helper exits after Stop") { !processExists(helperPid) }
            assertFalse(SharedShell.isReady())
            // Replay the already-consumed command. The app must reject the stale nonce,
            // and the temporary helper must exit rather than keep a privileged orphan.
            exec(command)
            SystemClock.sleep(1000)
            eventually("stale helper is rejected and exits", 15_000) {
                !SharedShell.isReady() && helperProcesses().isEmpty()
            }
            checkpoint("stop", "PASS: process exited and stale nonce could not restart it")
        } finally {
            // If an assertion failed between retaining and reopening the fixture, put only
            // our test task on a disposable display so cleanup also removes that task.
            val remainingShell = cleanupShell
            if (remainingShell != null && SharedShell.isReady() && taskId >= 0) {
                runCatching {
                    val on = remainingShell.displayOfTask(taskId)
                    if (on == Display.DEFAULT_DISPLAY &&
                        remainingShell.packageOfTask(taskId) == fixturePackage &&
                        displays.none { it.displayId == on }
                    ) {
                        val metrics = context.resources.displayMetrics
                        val cleanup = AgentDisplay(remainingShell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
                        displays += cleanup
                        remainingShell.moveTask(taskId, cleanup.displayId)
                    }
                }
            }
            for (display in displays.reversed()) {
                runCatching { cleanupShell?.finishDisplay(display.displayId, emptyArray(), arrayOf(fixturePackage)) }
                runCatching { display.release() }
            }
            runCatching { cleanupShell?.restoreClip() }
            runCatching { runBlocking { manager.stop() } }
            ui.serviceInfo = oldInfo
        }
    }

    private fun tap(target: VirtualDisplayTarget, description: String) {
        val node = awaitNode(target.displayId, description)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        assertTrue("Fixture target must have nonempty bounds", !bounds.isEmpty)
        assertTrue("Tap injection was rejected", target.tap(bounds.centerX(), bounds.centerY()))
    }

    private fun awaitNode(displayId: Int, description: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        eventually("fixture node $description on display $displayId") {
            result = findNode(displayId, description)
            result != null
        }
        return result!!
    }

    private fun findNode(displayId: Int, description: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
            if (node.packageName?.toString() != fixturePackage || depth > 30) return null
            if (node.contentDescription?.toString() == description) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { find(it, depth + 1)?.let { found -> return found } }
            }
            return null
        }
        return automation.windowsOnAllDisplays[displayId].orEmpty().firstNotNullOfOrNull { window ->
            window.root?.let { find(it) }
        }
    }

    private fun fieldText(displayId: Int) = findNode(displayId, "fixture-input")?.text?.toString()
    private fun state(displayId: Int) = findNode(displayId, "fixture-state")?.text?.toString().orEmpty()

    private fun eventually(message: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            if (check()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, check())
    }

    private fun exec(command: String): String {
        // UiAutomation tokenizes its command like Runtime.exec, so send shell syntax over
        // stdin instead of relying on quoting around "sh -c".
        val pipes = automation.executeShellCommandRw("/system/bin/sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter().use {
            it.write(command)
            it.write("\nexit\n")
        }
        return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
    }

    private fun helperProcesses(): String = exec("pidof ${context.packageName}:helper").trim()
    private fun processExists(pid: Int): Boolean = exec("if [ -d /proc/$pid ]; then echo alive; fi").trim() == "alive"
    private fun checkpoint(name: String, result: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n[$name] $result\n") })
    }
}
