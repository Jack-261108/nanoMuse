package io.github.nanomuse.hands

import android.app.UiAutomation
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.UserManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nanomuse.display.AgentDisplay
import io.github.nanomuse.shell.INanoMuseShell
import io.github.nanomuse.shell.SharedShell
import io.github.nanomuse.shell.ShellService
import io.github.nanomuse.shell.setup.BuiltinShellManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Validates fallback background launch on a real device that cannot mute apps. Uses only the test APK fixture. */
@RunWith(AndroidJUnit4::class)
class BackgroundScreenAudioCompatibilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val automation: UiAutomation
        get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test
    fun unsupportedMutingAllowsBackgroundLaunchAndRestartWithoutChangingAudio() {
        assumeTrue("Background display requires Android 12", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        assumeTrue("Unlock the device before running this test", context.getSystemService(UserManager::class.java).isUserUnlocked)
        assertFalse("Do not run device validation during an active Hands task", Hands.active.value)
        val manager = BuiltinShellManager.instance(context)
        var cleanupShell: INanoMuseShell? = null
        var display: AgentDisplay? = null
        var originalAudio: String? = null
        var audioTouched = false
        var testFailure: Throwable? = null
        try {
            runBlocking { manager.stop() }
            eventually("previous helper exits", 15_000) { helperProcesses().isEmpty() }
            exec(SharedShell.getAdbCommand(context))
            eventually("helper Binder arrives", 20_000) { SharedShell.isReady() }
            val shell = SharedShell.shell.value!!
            cleanupShell = shell
            val helperPid = shell.identity()[1]
            checkpoint("handoff", "PASS: live shell Binder received")

            val beforeMain = shell.topTaskId(Display.DEFAULT_DISPLAY)
            assertFalse("Close the fixture on the main screen before testing", beforeMain >= 0 && shell.packageOfTask(beforeMain) == fixturePackage)
            originalAudio = shell.getAudioMode(fixturePackage)
            assertNotNull("Fixture audio mode must be readable", originalAudio)
            audioTouched = true
            val canMute = shell.setAudioMuted(fixturePackage, true)
            if (canMute) {
                assertEquals("Successful muting must be observable", "ignore", shell.getAudioMode(fixturePackage))
                assertTrue("Restore the fixture audio mode before skipping", shell.setAudioMuted(fixturePackage, false))
                assertEquals("Skipping must preserve the original audio mode", originalAudio, shell.getAudioMode(fixturePackage))
                checkpoint("audio-compatibility", "SKIP: muting works; run the full background screen regression")
            }
            assumeFalse("This fallback test applies only when the device cannot mute apps", canMute)
            assertEquals("Failed muting must preserve the original audio mode", originalAudio, shell.getAudioMode(fixturePackage))
            checkpoint("audio-compatibility", "PASS: unsupported muting reported failure without changing the original mode")

            val metrics = context.resources.displayMetrics
            val background = AgentDisplay(
                shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                context.getSystemService(DisplayManager::class.java),
            )
            display = background
            assertTrue("Virtual display must not be the main screen", background.displayId > 0)
            assertEquals("New display must start empty", -1, shell.topTaskId(background.displayId))

            assertEquals("Start must report unmuted fallback when muting cannot be applied", ShellService.START_OK_UNMUTED, shell.startApp(background.displayId, fixturePackage))
            var taskId = -1
            eventually("fixture task appears on virtual display") {
                val id = shell.topTaskId(background.displayId)
                if (id >= 0 && shell.packageOfTask(id) == fixturePackage) {
                    taskId = id
                    true
                } else false
            }
            eventually("a real nonblack fixture frame arrives") {
                background.latestFrameBitmap()?.let { bitmap ->
                    try {
                        (bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) and 0x00ffffff) != 0
                    } finally { bitmap.recycle() }
                } ?: false
            }
            assertEquals("Unmuted start must preserve the main task", beforeMain, shell.topTaskId(Display.DEFAULT_DISPLAY))
            assertEquals("Unmuted start must preserve the original audio mode", originalAudio, shell.getAudioMode(fixturePackage))
            checkpoint("start-unmuted", "PASS: virtual task started with real frame without changing main task or audio")

            assertTrue("Move to main screen for takeover must succeed", shell.moveTask(taskId, Display.DEFAULT_DISPLAY))
            eventually("fixture task moved to main display") { shell.displayOfTask(taskId) == Display.DEFAULT_DISPLAY }
            assertTrue("Move back to virtual screen without muting must succeed", shell.moveTask(taskId, background.displayId))
            eventually("fixture task returned to virtual display") { shell.displayOfTask(taskId) == background.displayId }
            checkpoint("takeover-move", "PASS: task moved to main and back without muting")

            assertEquals("Restart must report unmuted fallback when muting cannot be applied", ShellService.START_OK_UNMUTED, shell.restartApp(background.displayId, fixturePackage))
            eventually("restarted fixture task appears on virtual display") {
                val id = shell.topTaskId(background.displayId)
                if (id >= 0 && shell.packageOfTask(id) == fixturePackage) {
                    taskId = id
                    true
                } else false
            }
            assertEquals("Unmuted restart must preserve the main task", beforeMain, shell.topTaskId(Display.DEFAULT_DISPLAY))
            assertEquals("Unmuted restart must preserve the original audio mode", originalAudio, shell.getAudioMode(fixturePackage))
            checkpoint("restart-unmuted", "PASS: virtual task restarted without changing main task or audio")

            shell.finishDisplay(background.displayId, emptyArray(), arrayOf(fixturePackage))
            background.release()
            shell.releaseDisplay(background.displayId)
            shell.releaseDisplay(Display.DEFAULT_DISPLAY)
            eventually("virtual display is released") {
                context.getSystemService(DisplayManager::class.java).getDisplay(background.displayId) == null
            }
            if (taskId >= 0) {
                eventually("fixture task is closed after finishDisplay") { shell.displayOfTask(taskId) == -1 }
            }
            assertEquals("Repeated cleanup must preserve the main task", beforeMain, shell.topTaskId(Display.DEFAULT_DISPLAY))
            assertEquals("Cleanup must preserve the original audio mode", originalAudio, shell.getAudioMode(fixturePackage))
            checkpoint("cleanup", "PASS: repeated release left the main task and audio unchanged")

            runBlocking { manager.stop() }
            eventually("helper exits after Stop") { !processExists(helperPid) && helperProcesses().isEmpty() }
            assertFalse("Stopped helper must not remain ready", SharedShell.isReady())
            checkpoint("stop", "PASS: helper process exited")
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            val failures = mutableListOf<Throwable>()
            fun clean(action: () -> Unit) {
                runCatching(action).onFailure { failures += it }
            }
            display?.let { background ->
                if (SharedShell.isReady()) {
                    clean { cleanupShell?.finishDisplay(background.displayId, emptyArray(), arrayOf(fixturePackage)) }
                }
                clean { background.release() }
            }
            if (audioTouched && SharedShell.isReady()) {
                clean {
                    assertTrue("Fixture audio cleanup failed", cleanupShell!!.setAudioMuted(fixturePackage, false))
                    assertEquals("Fixture audio cleanup changed its original mode", originalAudio, cleanupShell!!.getAudioMode(fixturePackage))
                }
            }
            clean { runBlocking { manager.stop() } }
            clean { eventually("helper exits during cleanup", 15_000) { helperProcesses().isEmpty() } }
            if (failures.isNotEmpty()) {
                val failure = AssertionError("Audio compatibility device test cleanup failed")
                testFailure?.let { failure.addSuppressed(it) }
                failures.forEach { failure.addSuppressed(it) }
                throw failure
            }
        }
    }

    private fun eventually(message: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            if (check()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, check())
    }

    private fun exec(command: String): String {
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
