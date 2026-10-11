package io.github.nanomuse.hands

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import java.util.Base64

/**
 * Open-AutoGLM style text input using ADBKeyBoard and Shizuku privileged execution.
 *
 * Temporarily switches the active IME to ADBKeyBoard (com.android.adbkeyboard/.AdbIME),
 * dispatches the text via broadcast (Base64 UTF-8), and unconditionally restores the user's
 * original IME in a finally block so their personal keyboard is never hijacked.
 */
object AdbKeyboardHelper {
    private const val TAG = "AdbKeyboardHelper"

    const val ADB_KEYBOARD_PACKAGE = "com.android.adbkeyboard"
    const val ADB_KEYBOARD_IME = "com.android.adbkeyboard/.AdbIME"
    const val DOWNLOAD_URL = "https://github.com/senzhk/ADBKeyBoard/releases"

    const val ACTION_CLEAR_TEXT = "ADB_CLEAR_TEXT"
    const val ACTION_INPUT_B64 = "ADB_INPUT_B64"
    const val EXTRA_B64_MSG = "msg"

    /**
     * Injectable shell process runner for unit tests. Defaults to ShizukuManager.runProcess.
     */
    var processRunner: (Array<String>) -> ShizukuManager.ProcessResult = { argv ->
        ShizukuManager.runProcess(argv)
    }

    /**
     * Injectable readiness check for unit tests. Defaults to ShizukuManager.isReady.
     */
    var isPrivilegedReady: () -> Boolean = {
        ShizukuManager.isReady()
    }

    /**
     * Injectable sleep runner for unit tests. Defaults to Thread.sleep.
     */
    var sleepRunner: (Long) -> Unit = { ms ->
        Thread.sleep(ms)
    }

    /**
     * Checks if ADBKeyBoard is installed on the device.
     */
    fun isInstalled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imm?.inputMethodList?.any { it.packageName == ADB_KEYBOARD_PACKAGE } == true) {
            return true
        }
        return runCatching {
            context.packageManager.getPackageInfo(ADB_KEYBOARD_PACKAGE, 0)
        }.isSuccess
    }

    /**
     * True when privileged execution is ready and ADBKeyBoard is installed.
     */
    fun isAvailable(context: Context): Boolean =
        isPrivilegedReady() && isInstalled(context)

    /**
     * Guides the user on what is missing:
     * - Shizuku not ready: guide to Shizuku app / install page.
     * - ADBKeyBoard not installed: open download page.
     * - Installed: open IME settings.
     */
    fun guideSetup(context: Context) {
        if (!isPrivilegedReady()) {
            when (ShizukuManager.snapshot.value.state) {
                ShizukuManager.State.NOT_INSTALLED -> ShizukuManager.openInstallPage(context)
                ShizukuManager.State.NOT_RUNNING -> ShizukuManager.openShizukuApp(context)
                ShizukuManager.State.NEED_PERMISSION -> {
                    if (!ShizukuManager.requestPermission()) {
                        ShizukuManager.openShizukuApp(context)
                    }
                }
                ShizukuManager.State.READY -> Unit
            }
            return
        }
        if (!isInstalled(context)) {
            openDownloadPage(context)
            return
        }
        openInputMethodSettings(context)
    }

    fun openDownloadPage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { AppLogger.warning(TAG, "Failed to open ADBKeyBoard download URL: ${it.message}") }
    }

    fun openInputMethodSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { AppLogger.warning(TAG, "Failed to open input method settings: ${it.message}") }
    }

    /**
     * Types text using the Open-AutoGLM ADBKeyBoard broadcast protocol.
     * Always restores the original IME in a finally block.
     */
    fun typeText(text: String, clearBefore: Boolean = true): Boolean {
        if (!isPrivilegedReady()) return false

        // 1. Detect current IME
        val getRes = processRunner(arrayOf("settings", "get", "secure", "default_input_method"))
        val originalIme = getRes.stdout.trim()
        val hasOriginal = originalIme.isNotBlank() && originalIme != "null"

        var switched = false
        try {
            // 2. Ensure ADBKeyBoard is enabled in the IME list
            processRunner(arrayOf("ime", "enable", ADB_KEYBOARD_IME))

            // 3. Switch to ADBKeyBoard
            if (originalIme.isNotBlank() && !originalIme.contains("adbkeyboard", ignoreCase = true)) {
                val setRes = processRunner(arrayOf("ime", "set", ADB_KEYBOARD_IME))
                switched = setRes.exitCode == 0
                if (!switched) {
                    AppLogger.warning(TAG, "ime set $ADB_KEYBOARD_IME failed: ${setRes.combined}")
                    return false
                }
                sleepRunner(60L)
            } else {
                switched = true
            }

            // 4. Clear existing text if requested
            if (clearBefore) {
                processRunner(arrayOf("am", "broadcast", "-a", ACTION_CLEAR_TEXT))
                sleepRunner(30L)
            }

            // 5. Send Base64-encoded UTF-8 text via broadcast
            val b64 = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
            val inputRes = processRunner(
                arrayOf("am", "broadcast", "-a", ACTION_INPUT_B64, "--es", EXTRA_B64_MSG, b64)
            )
            if (inputRes.exitCode != 0) {
                AppLogger.warning(TAG, "broadcast $ACTION_INPUT_B64 failed: ${inputRes.combined}")
                return false
            }

            sleepRunner(50L)
            return true
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "typeText encountered exception: ${t.message}")
            return false
        } finally {
            // 6. ALWAYS restore original IME
            if (switched && hasOriginal && !originalIme.contains("adbkeyboard", ignoreCase = true)) {
                val restoreRes = processRunner(arrayOf("ime", "set", originalIme))
                if (restoreRes.exitCode != 0) {
                    AppLogger.warning(TAG, "Failed to restore IME $originalIme: ${restoreRes.combined}")
                }
            }
        }
    }
}
