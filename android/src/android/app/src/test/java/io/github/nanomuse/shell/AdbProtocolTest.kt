package io.github.nanomuse.shell

import io.github.nanomuse.shell.adb.AdbMessage
import io.github.nanomuse.shell.setup.BuiltinShellFailure
import io.github.nanomuse.shell.setup.BuiltinShellStatus
import io.github.nanomuse.shell.setup.PairingCode
import io.github.nanomuse.shell.setup.PairingScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class AdbProtocolTest {

    @Test
    fun adbMessageEncodeAndDecode() {
        val payload = "shell:ls -la\u0000".toByteArray(Charsets.UTF_8)
        val msg = AdbMessage(AdbMessage.OPEN, 1, 0, payload)
        val encoded = msg.encode()
        assertEquals(AdbMessage.HEADER_SIZE + payload.size, encoded.size)

        val decoded = AdbMessage.read(DataInputStream(ByteArrayInputStream(encoded)), verifyChecksum = true)
        assertNotNull(decoded)
        assertEquals(AdbMessage.OPEN, decoded!!.command)
        assertEquals(1, decoded.arg0)
        assertEquals(0, decoded.arg1)
        assertEquals("shell:ls -la\u0000", decoded.data.toString(Charsets.UTF_8))
    }

    @Test
    fun pairingCodeNormalization() {
        assertEquals("123456", PairingCode.normalize("123456"))
        assertEquals("123456", PairingCode.normalize("123 456"))
        assertEquals("123456", PairingCode.normalize(" 123-456 "))
        assertEquals("123456", PairingCode.normalize("１２３４５６")) // 全角数字
        assertNull(PairingCode.normalize("12345"))
        assertNull(PairingCode.normalize("1234567"))
        assertNull(PairingCode.normalize("abcdef"))
    }

    @Test
    fun pairingScreenReadingFromSettingsText() {
        val texts = listOf(
            "配对设备",
            "Wi-Fi 配对码",
            "849 201",
            "IP 地址和端口",
            "192.168.31.23:42109",
            "取消",
        )
        val reading = PairingScreen.read(texts)
        assertEquals("849201", reading.code)
        assertEquals(42109, reading.port)
    }

    @Test
    fun pairingScreenRejectsAmbiguousCodesOrPorts() {
        val textsWithTwoCodes = listOf("123456", "654321", "192.168.1.10:38291")
        val reading = PairingScreen.read(textsWithTwoCodes)
        assertNull(reading.code)
        assertEquals(38291, reading.port)
    }

    @Test
    fun pairingAttemptLogic() {
        val reading = PairingScreen.read(listOf("849 201", "192.168.31.23:42109"))
        val attempt = PairingScreen.attempt(reading, portFound = true, status = BuiltinShellStatus.NotPaired, tried = emptySet())
        assertNotNull(attempt)
        assertEquals("849201", attempt!!.code)
        assertEquals(42109, attempt.port)

        // 已经试过的码不再尝试
        val retried = PairingScreen.attempt(reading, portFound = true, status = BuiltinShellStatus.NotPaired, tried = setOf("849201"))
        assertNull(retried)

        // 正在运行或连接中不尝试
        val running = PairingScreen.attempt(reading, portFound = true, status = BuiltinShellStatus.Running, tried = emptySet())
        assertNull(running)

        // 授权失效 (AuthorizationRevoked) 应当允许再次配对尝试
        val revoked = PairingScreen.attempt(
            reading,
            portFound = true,
            status = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked),
            tried = emptySet(),
        )
        assertNotNull(revoked)
        assertEquals("849201", revoked!!.code)
    }

    @Test
    fun repairModeTreatsStatusAsNotPaired() {
        val failed = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)
        assertEquals(BuiltinShellStatus.NotPaired, PairingScreen.effectiveStatus(failed, repair = true))
        assertEquals(failed, PairingScreen.effectiveStatus(failed, repair = false))
    }
}
