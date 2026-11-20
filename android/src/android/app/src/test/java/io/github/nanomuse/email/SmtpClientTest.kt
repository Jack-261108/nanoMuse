package io.github.nanomuse.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

class SmtpClientTest {
    private class FakeServer(
        private val script: (BufferedReader, BufferedWriter) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val failure = arrayOfNulls<Throwable>(1)
        val port: Int get() = server.localPort
        private val worker = thread(name = "fake-smtp") {
            try {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1))
                    script(reader, writer)
                }
            } catch (t: Throwable) {
                failure[0] = t
            }
        }

        fun await() {
            worker.join(3_000)
            failure[0]?.let { throw AssertionError("fake SMTP server failed", it) }
        }

        override fun close() {
            server.close()
        }
    }

    private fun client(port: Int, starttls: Boolean) = SmtpClient(
        host = "127.0.0.1",
        port = port,
        starttls = starttls,
        connect = { _, _ ->
            Socket().apply {
                connect(InetSocketAddress("127.0.0.1", port), 2_000)
                soTimeout = 2_000
            }
        },
        tls = { raw, _, _ -> raw },
    )

    @Test(timeout = 5_000)
    fun `sends a message over implicit tls and stuffs dots`() {
        var received = ""
        val server = FakeServer { reader, writer ->
            writer.write("220 ready\r\n"); writer.flush()
            assertEquals("EHLO nanomuse", reader.readLine())
            writer.write("250-fake\r\n250-AUTH LOGIN PLAIN\r\n250 OK\r\n"); writer.flush()
            assertEquals("AUTH LOGIN", reader.readLine())
            writer.write("334 VXNlcm5hbWU6\r\n"); writer.flush()
            assertEquals(base64("me@example.com"), reader.readLine())
            writer.write("334 UGFzc3dvcmQ6\r\n"); writer.flush()
            assertEquals(base64("code-123"), reader.readLine())
            writer.write("235 authenticated\r\n"); writer.flush()
            assertEquals("MAIL FROM:<me@example.com>", reader.readLine())
            writer.write("250 ok\r\n"); writer.flush()
            assertEquals("RCPT TO:<bob@example.com>", reader.readLine())
            writer.write("250 ok\r\n"); writer.flush()
            assertEquals("DATA", reader.readLine())
            writer.write("354 go\r\n"); writer.flush()
            val lines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine()
                if (line == ".") break
                lines += line
            }
            received = lines.joinToString("\r\n")
            writer.write("250 queued\r\n"); writer.flush()
            assertEquals("QUIT", reader.readLine())
            writer.write("221 bye\r\n"); writer.flush()
        }
        client(server.port, starttls = false).use { smtp ->
            smtp.open()
            smtp.authenticate("me@example.com", "code-123")
            smtp.sendMessage(
                from = "me@example.com",
                recipients = listOf("bob@example.com"),
                data = "Subject: x\r\n\r\n.line\r\n".toByteArray(Charsets.US_ASCII),
            )
            smtp.quit()
        }
        server.await()
        assertTrue(received.contains("..line"))
        assertTrue(received.contains("Subject: x"))
    }

    @Test(timeout = 5_000)
    fun `upgrades with starttls and authenticates`() {
        val server = FakeServer { reader, writer ->
            writer.write("220 ready\r\n"); writer.flush()
            assertEquals("EHLO nanomuse", reader.readLine())
            writer.write("250-fake\r\n250-STARTTLS\r\n250-AUTH LOGIN PLAIN\r\n250 OK\r\n"); writer.flush()
            assertEquals("STARTTLS", reader.readLine())
            writer.write("220 go ahead\r\n"); writer.flush()
            assertEquals("EHLO nanomuse", reader.readLine())
            writer.write("250-fake\r\n250-AUTH LOGIN PLAIN\r\n250 OK\r\n"); writer.flush()
            assertEquals("AUTH LOGIN", reader.readLine())
            writer.write("334 VXNlcm5hbWU6\r\n"); writer.flush()
            assertEquals(base64("me@example.com"), reader.readLine())
            writer.write("334 UGFzc3dvcmQ6\r\n"); writer.flush()
            assertEquals(base64("code-123"), reader.readLine())
            writer.write("235 authenticated\r\n"); writer.flush()
            assertEquals("QUIT", reader.readLine())
            writer.write("221 bye\r\n"); writer.flush()
        }
        client(server.port, starttls = true).use { smtp ->
            smtp.open()
            smtp.authenticate("me@example.com", "code-123")
            smtp.quit()
        }
        server.await()
    }

    @Test(timeout = 5_000)
    fun `refuses a server that does not offer starttls`() {
        val server = FakeServer { reader, writer ->
            writer.write("220 ready\r\n"); writer.flush()
            assertEquals("EHLO nanomuse", reader.readLine())
            writer.write("250-fake\r\n250-AUTH LOGIN PLAIN\r\n250 OK\r\n"); writer.flush()
        }
        try {
            client(server.port, starttls = true).use { smtp -> smtp.open() }
            fail("expected a refusal")
        } catch (error: EmailException) {
            assertTrue(error.message!!.contains("STARTTLS"))
        }
        server.await()
    }

    @Test(timeout = 5_000)
    fun `authenticates with xoauth2`() {
        val server = FakeServer { reader, writer ->
            writer.write("220 ready\r\n"); writer.flush()
            assertEquals("EHLO nanomuse", reader.readLine())
            writer.write("250-fake\r\n250-AUTH XOAUTH2\r\n250 OK\r\n"); writer.flush()
            val line = reader.readLine()
            assertTrue(line.startsWith("AUTH XOAUTH2 "))
            val token = line.removePrefix("AUTH XOAUTH2 ")
            assertEquals(
                "user=me@example.com\u0001auth=Bearer token-1\u0001\u0001",
                String(Base64.getDecoder().decode(token), Charsets.UTF_8),
            )
            writer.write("235 authenticated\r\n"); writer.flush()
            assertEquals("QUIT", reader.readLine())
            writer.write("221 bye\r\n"); writer.flush()
        }
        client(server.port, starttls = false).use { smtp ->
            smtp.open()
            smtp.authenticateXoauth2("me@example.com", "token-1")
            smtp.quit()
        }
        server.await()
    }

    @Test(timeout = 5_000)
    fun `xoauth2 completes a 334 challenge with an empty line`() {
        val server = FakeServer { reader, writer ->
            writer.write("220 ready\r\n"); writer.flush()
            assertEquals("EHLO nanomuse", reader.readLine())
            writer.write("250-fake\r\n250-AUTH XOAUTH2\r\n250 OK\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("AUTH XOAUTH2 "))
            writer.write("334 \r\n"); writer.flush()
            assertEquals("", reader.readLine())
            writer.write("235 authenticated\r\n"); writer.flush()
            assertEquals("QUIT", reader.readLine())
            writer.write("221 bye\r\n"); writer.flush()
        }
        client(server.port, starttls = false).use { smtp ->
            smtp.open()
            smtp.authenticateXoauth2("me@example.com", "token-1")
            smtp.quit()
        }
        server.await()
    }

    @Test(timeout = 5_000)
    fun `falls back to auth plain when auth login is unsupported`() {
        val server = FakeServer { reader, writer ->
            writer.write("220 ready\r\n"); writer.flush()
            assertEquals("EHLO nanomuse", reader.readLine())
            writer.write("250-fake\r\n250-AUTH LOGIN PLAIN\r\n250 OK\r\n"); writer.flush()
            assertEquals("AUTH LOGIN", reader.readLine())
            writer.write("504 mechanism not supported\r\n"); writer.flush()
            val line = reader.readLine()
            assertTrue(line.startsWith("AUTH PLAIN "))
            val token = line.removePrefix("AUTH PLAIN ")
            assertEquals("\u0000me@example.com\u0000code-123", String(Base64.getDecoder().decode(token), Charsets.UTF_8))
            writer.write("235 authenticated\r\n"); writer.flush()
            assertEquals("QUIT", reader.readLine())
            writer.write("221 bye\r\n"); writer.flush()
        }
        client(server.port, starttls = false).use { smtp ->
            smtp.open()
            smtp.authenticate("me@example.com", "code-123")
            smtp.quit()
        }
        server.await()
    }

    @Test(timeout = 5_000)
    fun `accepts auth equals plain syntax and stops immediately on rejected password`() {
        val plainOnlyServer = FakeServer { reader, writer ->
            writer.write("220 ready\r\n"); writer.flush()
            assertEquals("EHLO nanomuse", reader.readLine())
            writer.write("250-fake\r\n250 AUTH=PLAIN\r\n"); writer.flush()
            val line = reader.readLine()
            assertTrue(line.startsWith("AUTH PLAIN "))
            writer.write("235 authenticated\r\n"); writer.flush()
            assertEquals("QUIT", reader.readLine())
            writer.write("221 bye\r\n"); writer.flush()
        }
        client(plainOnlyServer.port, starttls = false).use { smtp ->
            smtp.open()
            smtp.authenticate("me@example.com", "code-123")
            smtp.quit()
        }
        plainOnlyServer.await()

        val wrongPasswordServer = FakeServer { reader, writer ->
            writer.write("220 ready\r\n"); writer.flush()
            assertEquals("EHLO nanomuse", reader.readLine())
            writer.write("250-fake\r\n250-AUTH LOGIN PLAIN\r\n250 OK\r\n"); writer.flush()
            assertEquals("AUTH LOGIN", reader.readLine())
            writer.write("334 VXNlcm5hbWU6\r\n"); writer.flush()
            reader.readLine()
            writer.write("334 UGFzc3dvcmQ6\r\n"); writer.flush()
            reader.readLine()
            writer.write("535 bad credentials\r\n"); writer.flush()
        }
        try {
            client(wrongPasswordServer.port, starttls = false).use { smtp ->
                smtp.open()
                smtp.authenticate("me@example.com", "wrong")
            }
            fail("expected authentication refusal")
        } catch (e: EmailException) {
            assertTrue(e.message!!.contains("refused"))
        }
        wrongPasswordServer.await()
    }

    private fun base64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
