package io.github.nanomuse.shell

import io.github.nanomuse.shell.adb.AdbMessage
import io.github.nanomuse.shell.setup.BuiltinShellFailure
import io.github.nanomuse.shell.setup.BuiltinShellStatus
import io.github.nanomuse.shell.setup.PairingCode
import io.github.nanomuse.shell.setup.PairingStatus
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
    fun repairModeTreatsStatusAsNotPaired() {
        val failed = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)
        assertEquals(BuiltinShellStatus.NotPaired, PairingStatus.effective(failed, repair = true))
        assertEquals(failed, PairingStatus.effective(failed, repair = false))
    }
}
