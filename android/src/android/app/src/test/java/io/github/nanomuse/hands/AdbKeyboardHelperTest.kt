package io.github.nanomuse.hands

import com.openminis.app.offload.ShizukuManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

class AdbKeyboardHelperTest {

    private val originalRunner = AdbKeyboardHelper.processRunner
    private val originalReady = AdbKeyboardHelper.isPrivilegedReady
    private val originalSleep = AdbKeyboardHelper.sleepRunner

    @Before
    fun setUp() {
        AdbKeyboardHelper.isPrivilegedReady = { true }
        AdbKeyboardHelper.sleepRunner = { /* no-op in tests */ }
    }

    @After
    fun tearDown() {
        AdbKeyboardHelper.processRunner = originalRunner
        AdbKeyboardHelper.isPrivilegedReady = originalReady
        AdbKeyboardHelper.sleepRunner = originalSleep
    }

    @Test
    fun `typeText switches IME, sends b64 broadcast, and unconditionally restores original IME`() {
        val commands = mutableListOf<List<String>>()
        val originalIme = "com.sohu.inputmethod.sogou/.SogouIME"

        AdbKeyboardHelper.processRunner = { argv ->
            commands.add(argv.toList())
            when {
                argv.contentEquals(arrayOf("settings", "get", "secure", "default_input_method")) ->
                    ShizukuManager.ProcessResult(0, originalIme, "")
                else ->
                    ShizukuManager.ProcessResult(0, "", "")
            }
        }

        val text = "北京到上海"
        val ok = AdbKeyboardHelper.typeText(text)
        assertTrue(ok)

        assertEquals("settings", commands[0][0])
        assertEquals("ime", commands[1][0])
        assertEquals("enable", commands[1][1])
        assertEquals(AdbKeyboardHelper.ADB_KEYBOARD_IME, commands[1][2])

        assertEquals("ime", commands[2][0])
        assertEquals("set", commands[2][1])
        assertEquals(AdbKeyboardHelper.ADB_KEYBOARD_IME, commands[2][2])

        assertEquals("am", commands[3][0])
        assertEquals("broadcast", commands[3][1])
        assertEquals("ADB_CLEAR_TEXT", commands[3][3])

        assertEquals("am", commands[4][0])
        assertEquals("broadcast", commands[4][1])
        assertEquals("ADB_INPUT_B64", commands[4][3])

        // Verify Base64 payload
        val expectedB64 = java.util.Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))
        assertEquals(expectedB64, commands[4][6])

        // Verify restoration in finally
        assertEquals("ime", commands[5][0])
        assertEquals("set", commands[5][1])
        assertEquals(originalIme, commands[5][2])
    }

    @Test
    fun `typeText restores original IME even when broadcast fails`() {
        val commands = mutableListOf<List<String>>()
        val originalIme = "com.baidu.input_vivo/.ImeVivoService"

        AdbKeyboardHelper.processRunner = { argv ->
            commands.add(argv.toList())
            when {
                argv.contentEquals(arrayOf("settings", "get", "secure", "default_input_method")) ->
                    ShizukuManager.ProcessResult(0, originalIme, "")
                argv.contains("ADB_INPUT_B64") ->
                    ShizukuManager.ProcessResult(1, "", "broadcast failed")
                else ->
                    ShizukuManager.ProcessResult(0, "", "")
            }
        }

        val ok = AdbKeyboardHelper.typeText("测试失败恢复")
        assertFalse(ok)

        // The last command executed must be restoring the original IME
        val lastCmd = commands.last()
        assertEquals(listOf("ime", "set", originalIme), lastCmd)
    }

    @Test
    fun `typeText skips switching when already on ADBKeyBoard`() {
        val commands = mutableListOf<List<String>>()

        AdbKeyboardHelper.processRunner = { argv ->
            commands.add(argv.toList())
            when {
                argv.contentEquals(arrayOf("settings", "get", "secure", "default_input_method")) ->
                    ShizukuManager.ProcessResult(0, AdbKeyboardHelper.ADB_KEYBOARD_IME, "")
                else ->
                    ShizukuManager.ProcessResult(0, "", "")
            }
        }

        val ok = AdbKeyboardHelper.typeText("already adb")
        assertTrue(ok)

        // Shouldn't have executed 'ime set'
        assertFalse(commands.any { it.size >= 2 && it[0] == "ime" && it[1] == "set" })
    }

    @Test
    fun `returns false when privileged runner is not ready`() {
        AdbKeyboardHelper.isPrivilegedReady = { false }
        val ok = AdbKeyboardHelper.typeText("should not run")
        assertFalse(ok)
    }
}
