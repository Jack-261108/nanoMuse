package io.github.nanomuse.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MimeTest {
    private fun crlf(vararg lines: String): ByteArray =
        lines.joinToString("\r\n").toByteArray(Charsets.UTF_8)

    @Test
    fun `decodes rfc 2047 b and q words`() {
        assertEquals("你好", Mime.decodeHeader("=?UTF-8?B?5L2g5aW9?="))
        assertEquals("café", Mime.decodeHeader("=?UTF-8?Q?caf=C3=A9?="))
        assertEquals("plain", Mime.decodeHeader("plain"))
    }

    @Test
    fun `picks text plain from multipart alternative and decodes chinese sender names`() {
        val raw = crlf(
            "From: =?UTF-8?B?5byg5LiJ?= <alice@example.com>",
            "To: Bob <bob@example.com>",
            "Cc: Carol <carol@example.com>",
            "Message-ID: <m1@example.com>",
            "References: <m0@example.com>",
            "Subject: =?UTF-8?B?5L2g5aW9?=",
            "MIME-Version: 1.0",
            "Content-Type: multipart/alternative; boundary=\"b1\"",
            "",
            "--b1",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: base64",
            "",
            "5L2g5aW9",
            "--b1",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<p>Hello</p>",
            "--b1--",
        )
        val mail = Mime.parse(raw)
        assertEquals("你好", mail.subject)
        assertEquals("你好", mail.body)
        assertEquals("张三 <alice@example.com>", mail.from)
        assertEquals("Carol <carol@example.com>", mail.cc)
        assertEquals("<m1@example.com>", mail.messageId)
        assertEquals(listOf("<m0@example.com>"), mail.references)
    }

    @Test
    fun `records attachment metadata while keeping inline text parts`() {
        val raw = crlf(
            "From: a@example.com",
            "To: b@example.com",
            "Subject: files",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"b2\"",
            "",
            "--b2",
            "Content-Type: text/plain; charset=UTF-8",
            "",
            "First part",
            "--b2",
            "Content-Type: text/plain; charset=UTF-8",
            "",
            "Second part",
            "--b2",
            "Content-Type: application/pdf",
            "Content-Disposition: attachment; filename=\"=?UTF-8?B?5Y+R56WoLnBkZg==?=\"",
            "",
            "ATTACHMENT BYTES",
            "--b2--",
        )
        val mail = Mime.parse(raw)
        assertEquals("First part\n\nSecond part", mail.body)
        assertEquals(1, mail.attachments.size)
        assertEquals("发票.pdf", mail.attachments.single().filename)
        assertTrue(mail.attachments.single().sizeBytes > 0)
    }

    @Test
    fun `decodes a GBK body`() {
        val out = ByteArrayOutputStream()
        out.write(
            ("From: a@example.com\r\n" +
                "To: b@example.com\r\n" +
                "Subject: gbk\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=GBK\r\n" +
                "\r\n").toByteArray(Charsets.US_ASCII),
        )
        out.write("你好".toByteArray(charset("GBK")))
        assertEquals("你好", Mime.parse(out.toByteArray()).body)
    }

    @Test
    fun `turns html into readable text`() {
        assertEquals("Hello\nworld & all", Mime.htmlToText("<p>Hello<br>world &amp; all</p>"))
        assertEquals("no script", Mime.htmlToText("<script>alert(1)</script>no <b>script</b>"))
    }

    @Test
    fun `parses display names and semicolons`() {
        val list = Mime.parseAddressList("Alice <a@x.com>, \"Bob, Jr\" <b@y.com>; c@z.com")
        assertEquals(listOf("a@x.com", "b@y.com", "c@z.com"), list.map { it.address })
        assertEquals("Alice", list[0].name)
        assertEquals("Bob, Jr", list[1].name)
    }

    @Test
    fun `builds a message with message id, reply headers and intact surrogate pairs`() {
        val longEmojiSubject = "🎉".repeat(25) + "中文主题测试"
        val data = Mime.build(
            from = "me@example.com",
            to = listOf(Mailbox("张三", "bob@example.com")),
            cc = listOf(Mailbox("Carol, Ops", "carol@example.com")),
            subject = longEmojiSubject,
            body = "第一行\n第二行",
            inReplyTo = "<orig@example.com>",
            references = listOf("<root@example.com>", "<orig@example.com>"),
        )
        val asText = data.toString(Charsets.UTF_8)
        assertTrue(asText.contains("MIME-Version: 1.0"))
        assertTrue(asText.contains("Message-ID: <"))
        assertTrue(asText.contains("In-Reply-To: <orig@example.com>"))
        assertTrue(asText.contains("References: <root@example.com> <orig@example.com>"))
        assertTrue(asText.contains("Content-Type: text/plain; charset=UTF-8"))
        assertTrue(asText.contains("Content-Transfer-Encoding: base64"))
        val mail = Mime.parse(data)
        assertEquals(longEmojiSubject, mail.subject)
        assertEquals("第一行\n第二行", mail.body)
        assertEquals("张三 <bob@example.com>", mail.to)
        assertEquals("Carol, Ops <carol@example.com>", mail.cc)
    }
}
