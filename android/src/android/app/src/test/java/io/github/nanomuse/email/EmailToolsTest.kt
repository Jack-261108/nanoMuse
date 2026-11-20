package io.github.nanomuse.email

import io.github.nanomuse.guard.RiskClass
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

class EmailToolsTest {
    @After
    fun resetFactories() {
        EmailConnector.imapFactory = { config -> ImapClient(config.imapHost, config.imapPort) }
        EmailConnector.smtpFactory = { config -> SmtpClient(config.smtpHost, config.smtpPort, config.smtpStarttls) }
    }

    @Test
    fun `limit is clamped to the runtime range`() {
        assertEquals(1, EmailTools.clampLimit(0))
        assertEquals(1, EmailTools.clampLimit(-4))
        assertEquals(10, EmailTools.clampLimit(10))
        assertEquals(30, EmailTools.clampLimit(99))
    }

    @Test
    fun `recipients survive display names, semicolons, arrays and bcc`() {
        val list = EmailTools.parseRecipients("Alice <a@x.com>, b@y.com; c@z.com", null, "d@w.com")
        assertEquals(listOf("a@x.com", "b@y.com", "c@z.com", "d@w.com"), list.map { it.address })
        val array = JSONArray().put("a@x.com").put("b@y.com")
        assertEquals(listOf("a@x.com", "b@y.com"), EmailTools.parseRecipients(array, null).map { it.address })
    }

    @Test
    fun `a mailbox is found by id, name or address`() {
        val work = EmailConfig(id = "id-work", label = "work", address = "work@example.com")
        val personal = EmailConfig(id = "id-home", label = "personal", address = "home@example.com")
        val accounts = listOf(work, personal)
        assertEquals(work, EmailTools.findAccount(accounts, "work"))
        assertEquals(work, EmailTools.findAccount(accounts, "WORK@example.com"))
        assertEquals(personal, EmailTools.findAccount(accounts, "id-home"))
        assertNull(EmailTools.findAccount(accounts, "missing"))
        assertNull(EmailTools.findAccount(accounts, ""))
    }

    @Test
    fun `send assessment is outbound, bound to all recipients including bcc and names the sender`() {
        val account = EmailConfig(label = "work", address = "work@example.com")
        val args = JSONObject(
            """{"to":"Bob <Bob@Example.com>, alice@example.com","cc":"carol@example.com","bcc":"dave@example.com","subject":"hi","body":"text"}""",
        )
        val assessment = EmailTools.assessSend(args, account)
        assertEquals(RiskClass.OUTBOUND, assessment.riskClass)
        assertEquals("alice@example.com,bob@example.com,carol@example.com,dave@example.com", assessment.target)
        assertTrue(assessment.reason.contains("work"))
        assertTrue(assessment.needsApproval)
    }

    @Test
    fun `definitions carry tool_title, account, uid, list_folders, bcc and reply_to_uid`() {
        val definitions = EmailTools.definitions()
        assertEquals(listOf(EmailTools.READ, EmailTools.SEND), definitions.map { it.name })
        for (definition in definitions) {
            assertTrue(definition.parameters.containsKey("tool_title"))
            assertTrue(definition.parameters.containsKey("account"))
            assertTrue(definition.required.contains("tool_title"))
        }
        assertTrue(definitions[0].parameters.containsKey("uid"))
        assertTrue(definitions[0].parameters.containsKey("list_folders"))
        assertTrue(definitions[1].parameters.containsKey("bcc"))
        assertTrue(definitions[1].parameters.containsKey("reply_to_uid"))
        assertTrue(definitions[1].required.containsAll(listOf("to", "subject", "body")))
    }

    @Test
    fun `preview names sender, cc, bcc, reply target and shows text`() {
        val account = EmailConfig(label = "work", address = "work@example.com")
        val args = JSONObject(
            """{"to":"a@x.com","cc":"c@x.com","bcc":"d@x.com","reply_to_uid":42,"subject":"Report","body":"First line"}""",
        )
        val preview = EmailTools.previewFor(args, account)
        assertTrue(preview.contains("work"))
        assertTrue(preview.contains("work@example.com"))
        assertTrue(preview.contains("To: a@x.com"))
        assertTrue(preview.contains("Cc: c@x.com"))
        assertTrue(preview.contains("Bcc: d@x.com"))
        assertTrue(preview.contains("Replying to: #42"))
        assertTrue(preview.contains("Subject: Report"))
        assertTrue(preview.contains("First line"))
    }

    @Test
    fun `normalizes grouped gmail app passwords without touching regular passwords`() {
        assertEquals("abcdefghijklmnop", EmailConnector.normalizePassword("imap.gmail.com", "abcd efgh ijkl mnop"))
        assertEquals("abcd efgh ijkl mnop", EmailConnector.normalizePassword("imap.example.com", "abcd efgh ijkl mnop"))
        assertEquals("my pass phrase", EmailConnector.normalizePassword("imap.gmail.com", "my pass phrase"))
    }

    @Test
    fun `send refuses a header break before touching the network`() = runBlocking {
        val (store, account) = readyStore()
        val result = EmailConnector.send(
            store,
            account.id,
            to = "bob@example.com\nBcc: eve@example.com",
            subject = "hi",
            body = "body",
            cc = null,
        )
        assertFalse(result.ok)
        assertTrue(result.message.contains("line break"))
    }

    @Test
    fun `send requires a recipient`() = runBlocking {
        val (store, account) = readyStore()
        val result = EmailConnector.send(store, account.id, to = "", subject = "hi", body = "body", cc = null)
        assertFalse(result.ok)
        assertTrue(result.message.contains("`to` is required"))
    }

    @Test(timeout = 5_000)
    fun `connector resolves sent folder alias, falls back on chinese search and reads full uid`() = runBlocking {
        val (store, account) = readyStore()
        val longBody = "报销发票明细：" + "A".repeat(3_500)
        val rawMail = Mime.build(
            from = "finance@example.com",
            to = listOf(Mailbox(null, "me@example.com")),
            cc = emptyList(),
            subject = "十月电子发票",
            body = longBody,
            messageId = "<inv-10@example.com>",
        )
        val encodedSent = ModifiedUtf7.encode("已发送")

        val serverSocket = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val worker = thread(name = "fake-imap-connector") {
            // Session 1: search fallback + Sent folder resolution
            serverSocket.accept().use { s ->
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.ISO_8859_1))
                w.write("* OK ready\r\n"); w.flush()
                r.readLine(); w.write("A1 OK\r\n"); w.flush() // LOGIN
                r.readLine(); w.write("A2 OK\r\n"); w.flush() // ID
                assertEquals("A3 EXAMINE \"Sent\"", r.readLine())
                w.write("A3 NO no such mailbox\r\n"); w.flush()
                assertEquals("A4 LIST \"\" \"*\"", r.readLine())
                w.write("* LIST (\\Sent) \"/\" \"$encodedSent\"\r\nA4 OK\r\n"); w.flush()
                assertEquals("A5 EXAMINE \"$encodedSent\"", r.readLine())
                w.write("A5 OK\r\n"); w.flush()
                // Reject CHARSET UTF-8 search to exercise client fallback scan
                val searchLine = r.readLine()
                assertTrue(searchLine.contains("CHARSET UTF-8"))
                w.write("A6 NO [BADCHARSET]\r\n"); w.flush()
                assertEquals("A7 UID SEARCH ALL", r.readLine())
                w.write("* SEARCH 12\r\nA7 OK\r\n"); w.flush()
                assertEquals("A8 UID FETCH 12 (RFC822.SIZE)", r.readLine())
                w.write("* 1 FETCH (UID 12 RFC822.SIZE ${rawMail.size})\r\nA8 OK\r\n"); w.flush()
                assertEquals("A9 UID FETCH 12 (BODY.PEEK[])", r.readLine())
                w.write("* 1 FETCH (UID 12 BODY[] {${rawMail.size}}\r\n"); w.flush()
                s.getOutputStream().write(rawMail); s.getOutputStream().flush()
                w.write(")\r\nA9 OK\r\n"); w.flush()
                r.readLine(); w.write("A10 OK\r\n"); w.flush() // LOGOUT
            }
            // Session 2: read single UID in full
            serverSocket.accept().use { s ->
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.ISO_8859_1))
                w.write("* OK ready\r\n"); w.flush()
                r.readLine(); w.write("A1 OK\r\n"); w.flush()
                r.readLine(); w.write("A2 OK\r\n"); w.flush()
                r.readLine(); w.write("A3 OK\r\n"); w.flush() // EXAMINE INBOX
                r.readLine(); w.write("* 1 FETCH (UID 12 RFC822.SIZE ${rawMail.size})\r\nA4 OK\r\n"); w.flush()
                r.readLine()
                w.write("* 1 FETCH (UID 12 BODY[] {${rawMail.size}}\r\n"); w.flush()
                s.getOutputStream().write(rawMail); s.getOutputStream().flush()
                w.write(")\r\nA5 OK\r\n"); w.flush()
                r.readLine(); w.write("A6 OK\r\n"); w.flush()
            }
            serverSocket.close()
        }

        EmailConnector.imapFactory = {
            ImapClient("127.0.0.1", serverSocket.localPort) { _, p ->
                Socket().apply {
                    connect(InetSocketAddress("127.0.0.1", p), 2_000)
                    soTimeout = 2_000
                }
            }
        }

        val summary = EmailConnector.read(
            store = store,
            accountId = account.id,
            folder = "Sent",
            limit = 5,
            unreadOnly = false,
            search = "发票",
        )
        assertTrue(summary.ok)
        assertTrue(summary.message.contains("十月电子发票"))
        assertTrue(summary.message.contains("uid=12"))

        val full = EmailConnector.read(
            store = store,
            accountId = account.id,
            folder = "INBOX",
            limit = 1,
            unreadOnly = false,
            search = null,
            uid = 12,
        )
        assertTrue(full.ok)
        assertTrue(full.message.contains("A".repeat(3_400)))
        worker.join(3_000)
    }

    @Test(timeout = 5_000)
    fun `connector threads replies and sends bcc in envelope only`() = runBlocking {
        val (store, account) = readyStore()
        val origMail = Mime.build(
            from = "alice@example.com",
            to = listOf(Mailbox(null, "me@example.com")),
            cc = emptyList(),
            subject = "Project sync",
            body = "Are we ready?",
            messageId = "<msg-99@example.com>",
            references = listOf("<msg-98@example.com>"),
        )
        val imapSocket = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val imapThread = thread(name = "fake-imap-reply") {
            imapSocket.accept().use { s ->
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.ISO_8859_1))
                w.write("* OK ready\r\n"); w.flush()
                r.readLine(); w.write("A1 OK\r\n"); w.flush()
                r.readLine(); w.write("A2 OK\r\n"); w.flush()
                r.readLine(); w.write("A3 OK\r\n"); w.flush()
                r.readLine(); w.write("* 1 FETCH (UID 99 RFC822.SIZE ${origMail.size})\r\nA4 OK\r\n"); w.flush()
                r.readLine()
                w.write("* 1 FETCH (UID 99 BODY[] {${origMail.size}}\r\n"); w.flush()
                s.getOutputStream().write(origMail); s.getOutputStream().flush()
                w.write(")\r\nA5 OK\r\n"); w.flush()
                r.readLine(); w.write("A6 OK\r\n"); w.flush()
            }
            imapSocket.close()
        }

        val rcptSeen = mutableListOf<String>()
        var wireData = ""
        val smtpSocket = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val smtpThread = thread(name = "fake-smtp-reply") {
            smtpSocket.accept().use { s ->
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.ISO_8859_1))
                w.write("220 ready\r\n"); w.flush()
                r.readLine(); w.write("250-fake\r\n250 AUTH PLAIN\r\n"); w.flush()
                r.readLine(); w.write("235 ok\r\n"); w.flush()
                r.readLine(); w.write("250 ok\r\n"); w.flush() // MAIL FROM
                while (true) {
                    val cmd = r.readLine()
                    if (cmd.startsWith("RCPT TO:")) {
                        rcptSeen += cmd
                        w.write("250 ok\r\n"); w.flush()
                    } else if (cmd == "DATA") {
                        w.write("354 go\r\n"); w.flush()
                        break
                    }
                }
                val lines = mutableListOf<String>()
                while (true) {
                    val line = r.readLine()
                    if (line == ".") break
                    lines += line
                }
                wireData = lines.joinToString("\r\n")
                w.write("250 queued\r\n"); w.flush()
                r.readLine(); w.write("221 bye\r\n"); w.flush()
            }
            smtpSocket.close()
        }

        EmailConnector.imapFactory = {
            ImapClient("127.0.0.1", imapSocket.localPort) { _, p ->
                Socket().apply {
                    connect(InetSocketAddress("127.0.0.1", p), 2_000)
                    soTimeout = 2_000
                }
            }
        }
        EmailConnector.smtpFactory = {
            SmtpClient(
                host = "127.0.0.1",
                port = smtpSocket.localPort,
                starttls = false,
                connect = { _, p ->
                    Socket().apply {
                        connect(InetSocketAddress("127.0.0.1", p), 2_000)
                        soTimeout = 2_000
                    }
                },
                tls = { raw, _, _ -> raw },
            )
        }

        val reply = EmailConnector.resolveReply(store, account.id, 99, "INBOX", "").getOrThrow()
        assertEquals("Re: Project sync", reply.subject)
        assertEquals("<msg-99@example.com>", reply.inReplyTo)
        assertEquals(listOf("<msg-98@example.com>", "<msg-99@example.com>"), reply.references)

        val sent = EmailConnector.send(
            store = store,
            accountId = account.id,
            to = "alice@example.com",
            subject = reply.subject,
            body = "Yes, ready.",
            cc = null,
            bcc = "audit@example.com",
            inReplyTo = reply.inReplyTo,
            references = reply.references,
        )
        assertTrue(sent.ok)
        assertEquals(
            listOf("RCPT TO:<alice@example.com>", "RCPT TO:<audit@example.com>"),
            rcptSeen,
        )
        assertTrue(wireData.contains("In-Reply-To: <msg-99@example.com>"))
        assertTrue(wireData.contains("References: <msg-98@example.com> <msg-99@example.com>"))
        assertFalse(wireData.contains("audit@example.com"))
        imapThread.join(3_000)
        smtpThread.join(3_000)
    }

    private fun readyStore(): Pair<EmailStore, EmailConfig> {
        val store = EmailStore(FakePrefs(), FakePrefs())
        store.save(
            EmailConfig(
                enabled = true,
                verified = true,
                address = "me@example.com",
                imapHost = "imap.example.com",
                smtpHost = "smtp.example.com",
            ),
            "code-123",
        )
        val account = store.accounts().single()
        store.setVerified(account.id, true)
        return store to store.account(account.id)!!
    }
}
