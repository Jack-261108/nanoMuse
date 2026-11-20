package io.github.nanomuse.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import kotlin.concurrent.thread

class ImapClientTest {
    private class FakeServer(
        private val script: (BufferedReader, BufferedWriter, Socket) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val failure = arrayOfNulls<Throwable>(1)
        val port: Int get() = server.localPort
        private val worker = thread(name = "fake-imap") {
            try {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.ISO_8859_1))
                    script(reader, writer, socket)
                }
            } catch (t: Throwable) {
                failure[0] = t
            }
        }

        fun await() {
            worker.join(3_000)
            failure[0]?.let { throw AssertionError("fake IMAP server failed", it) }
        }

        override fun close() {
            server.close()
        }
    }

    private fun client(port: Int, starttls: Boolean = false) = ImapClient(
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
    fun `logs in, searches across multiple lines, fetches a literal and logs out`() {
        val raw = "Subject: x\r\n\r\nhi"
        val server = FakeServer { reader, writer, socket ->
            writer.write("* OK ready\r\n"); writer.flush()
            assertEquals("A1 LOGIN \"user@example.com\" \"code-123\"", reader.readLine())
            writer.write("A1 OK LOGIN completed\r\n"); writer.flush()
            assertEquals("A2 ID (\"name\" \"nanomuse\" \"version\" \"1.0\" \"vendor\" \"nanomuse\")", reader.readLine())
            writer.write("* ID (\"name\" \"nanomuse\")\r\nA2 OK ID completed\r\n"); writer.flush()
            assertEquals("A3 EXAMINE \"INBOX\"", reader.readLine())
            writer.write("* 2 EXISTS\r\nA3 OK EXAMINE completed\r\n"); writer.flush()
            assertEquals("A4 UID SEARCH ALL", reader.readLine())
            writer.write("* SEARCH 2 4\r\n* SEARCH 6\r\nA4 OK SEARCH completed\r\n"); writer.flush()
            assertEquals("A5 UID FETCH 6 (RFC822.SIZE)", reader.readLine())
            writer.write("* 1 FETCH (UID 6 RFC822.SIZE ${raw.length})\r\nA5 OK FETCH completed\r\n"); writer.flush()
            assertEquals("A6 UID FETCH 6 (BODY.PEEK[])", reader.readLine())
            writer.write("* 1 FETCH (UID 6 BODY[] {${raw.length}}\r\n"); writer.flush()
            socket.getOutputStream().write(raw.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            writer.write(")\r\nA6 OK FETCH completed\r\n"); writer.flush()
            assertEquals("A7 LOGOUT", reader.readLine())
            writer.write("* BYE\r\nA7 OK LOGOUT completed\r\n"); writer.flush()
        }
        client(server.port).use { imap ->
            imap.open()
            imap.login("user@example.com", "code-123")
            imap.examine("INBOX")
            assertEquals(listOf(2, 4, 6), imap.search(false, null))
            assertEquals(raw.length.toLong(), imap.size(6))
            assertEquals(raw, imap.fetch(6).toString(Charsets.ISO_8859_1))
            imap.logout()
        }
        server.await()
    }

    @Test(timeout = 5_000)
    fun `upgrades with starttls on port 143 and refuses preauth before starttls`() {
        val server = FakeServer { reader, writer, _ ->
            writer.write("* OK ready\r\n"); writer.flush()
            assertEquals("A1 STARTTLS", reader.readLine())
            writer.write("A1 OK Begin TLS\r\n"); writer.flush()
            assertEquals("A2 LOGIN \"u\" \"p\"", reader.readLine())
            writer.write("A2 OK\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("A3 ID ("))
            writer.write("A3 OK\r\n"); writer.flush()
            assertEquals("A4 LOGOUT", reader.readLine())
            writer.write("A4 OK\r\n"); writer.flush()
        }
        client(server.port, starttls = true).use { imap ->
            imap.open()
            imap.login("u", "p")
            imap.logout()
        }
        server.await()

        val preauthServer = FakeServer { _, writer, _ ->
            writer.write("* PREAUTH sneaky\r\n"); writer.flush()
        }
        try {
            client(preauthServer.port, starttls = true).use { it.open() }
            fail("expected PREAUTH before STARTTLS to fail")
        } catch (e: EmailException) {
            assertTrue(e.message!!.contains("STARTTLS"))
        }
        preauthServer.await()
    }

    @Test(timeout = 5_000)
    fun `lists modified utf7 folders and resolves common aliases`() {
        val encodedSent = ModifiedUtf7.encode("已发送")
        val encodedDrafts = ModifiedUtf7.encode("草稿箱")
        val server = FakeServer { reader, writer, _ ->
            writer.write("* OK ready\r\n"); writer.flush()
            assertEquals("A1 LIST \"\" \"*\"", reader.readLine())
            writer.write("* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n")
            writer.write("* LIST (\\Noselect) \"/\" \"[Gmail]\"\r\n")
            writer.write("* LIST (\\HasNoChildren \\Sent) \"/\" \"$encodedSent\"\r\n")
            writer.write("* LIST (\\HasNoChildren \\Drafts) \"/\" \"$encodedDrafts\"\r\n")
            writer.write("A1 OK LIST completed\r\n")
            writer.flush()
            assertEquals("A2 LOGOUT", reader.readLine())
            writer.write("A2 OK\r\n"); writer.flush()
        }
        val folders = client(server.port).use { imap ->
            imap.open()
            val list = imap.listFolders()
            imap.logout()
            list
        }
        server.await()
        assertEquals(listOf("INBOX", "已发送", "草稿箱"), folders.map { it.name })
        assertEquals(encodedSent, FolderMatcher.resolve("Sent", folders)?.rawName)
        assertEquals(encodedSent, FolderMatcher.resolve("已发送", folders)?.rawName)
        assertEquals(encodedDrafts, FolderMatcher.resolve("Drafts", folders)?.rawName)
        assertNull(FolderMatcher.resolve("NonExistentFolder", folders))
    }

    @Test(timeout = 5_000)
    fun `sends a non-ascii search term as a utf 8 literal`() {
        val seen = StringBuilder()
        val server = FakeServer { reader, writer, _ ->
            writer.write("* OK ready\r\n"); writer.flush()
            assertEquals("A1 LOGIN \"u\" \"p\"", reader.readLine())
            writer.write("A1 OK\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("A2 ID ("))
            writer.write("A2 OK\r\n"); writer.flush()
            assertEquals("A3 EXAMINE \"INBOX\"", reader.readLine())
            writer.write("A3 OK\r\n"); writer.flush()
            val line = reader.readLine()
            assertTrue(line.startsWith("A4 UID SEARCH CHARSET UTF-8 UNSEEN TEXT {"))
            val length = line.substringAfterLast('{').substringBefore('}').toInt()
            writer.write("+ go ahead\r\n"); writer.flush()
            val buffer = CharArray(length)
            var read = 0
            while (read < length) {
                val count = reader.read(buffer, read, length - read)
                if (count < 0) break
                read += count
            }
            seen.append(String(buffer, 0, read))
            reader.readLine()
            writer.write("* SEARCH 9\r\nA4 OK\r\n"); writer.flush()
            assertEquals("A5 LOGOUT", reader.readLine())
            writer.write("A5 OK\r\n"); writer.flush()
        }
        client(server.port).use { imap ->
            imap.open()
            imap.login("u", "p")
            imap.examine("INBOX")
            assertEquals(listOf(9), imap.search(true, "发票"))
            imap.logout()
        }
        server.await()
        assertEquals("发票", String(seen.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
    }

    @Test(timeout = 5_000)
    fun `authenticates with xoauth2`() {
        val server = FakeServer { reader, writer, _ ->
            writer.write("* OK ready\r\n"); writer.flush()
            assertEquals("A1 AUTHENTICATE XOAUTH2", reader.readLine())
            writer.write("+ \r\n"); writer.flush()
            val payload = reader.readLine()
            assertEquals(
                "user=me@example.com\u0001auth=Bearer token-1\u0001\u0001",
                String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_8),
            )
            writer.write("A1 OK AUTHENTICATE completed\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("A2 ID ("))
            writer.write("A2 OK\r\n"); writer.flush()
            assertEquals("A3 LOGOUT", reader.readLine())
            writer.write("A3 OK\r\n"); writer.flush()
        }
        client(server.port).use { imap ->
            imap.open()
            imap.loginXoauth2("me@example.com", "token-1")
            imap.logout()
        }
        server.await()
    }

    @Test(timeout = 5_000)
    fun `preauth skips login`() {
        val server = FakeServer { reader, writer, _ ->
            writer.write("* PREAUTH ready\r\n"); writer.flush()
            assertTrue(reader.readLine().startsWith("A1 ID ("))
            writer.write("A1 OK\r\n"); writer.flush()
            assertEquals("A2 EXAMINE \"INBOX\"", reader.readLine())
            writer.write("A2 OK\r\n"); writer.flush()
            assertEquals("A3 LOGOUT", reader.readLine())
            writer.write("A3 OK\r\n"); writer.flush()
        }
        client(server.port).use { imap ->
            imap.open()
            imap.login("u", "p")
            imap.examine("INBOX")
            imap.logout()
        }
        server.await()
    }
}
