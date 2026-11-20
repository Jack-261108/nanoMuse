package io.github.nanomuse.email

import org.apache.james.mime4j.codec.DecodeMonitor
import org.apache.james.mime4j.codec.DecoderUtil
import org.apache.james.mime4j.dom.Entity
import org.apache.james.mime4j.dom.Message
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.SingleBody
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.stream.MimeConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Mailbox(val name: String?, val address: String)

data class MailAttachment(
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
    fun summary(): String = if (sizeBytes > 0) {
        "$filename (${formatBytes(sizeBytes)})"
    } else {
        filename
    }

    private companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "${(bytes + 512) / 1024} KB"
            else -> "$bytes B"
        }
    }
}

data class ParsedMail(
    val from: String,
    val to: String,
    val cc: String = "",
    val replyTo: String = "",
    val date: String,
    val subject: String,
    val messageId: String = "",
    val references: List<String> = emptyList(),
    val attachments: List<MailAttachment> = emptyList(),
    val body: String,
)

/**
 * MIME parsing and building for the native email connector. Parsing is Apache MIME4J
 * (Apache-2.0); building stays here so the exact headers sent on the wire are visible in
 * one place.
 */
object Mime {
    private const val MAX_MESSAGE_BYTES = 25L * 1024 * 1024
    private const val MAX_PART_CHARS = 1_000_000
    private const val MAX_DEPTH = 10

    fun parse(raw: ByteArray): ParsedMail {
        val builder = DefaultMessageBuilder()
        builder.setMimeEntityConfig(
            MimeConfig.Builder()
                .setMaxContentLen(MAX_MESSAGE_BYTES)
                .setMaxLineLen(100_000)
                .setMaxHeaderLen(1_000_000)
                .build(),
        )
        val message = builder.parseMessage(ByteArrayInputStream(raw))
        val attachments = mutableListOf<MailAttachment>()
        val (plain, html) = extractContent(message, attachments, 0)
        val body = when {
            plain.isNotBlank() -> plain.trim()
            html.isNotBlank() -> htmlToText(html)
            else -> "(no text body)"
        }
        val from = toMailboxes(message.from)
        val to = toMailboxes(message.to)
        val cc = toMailboxes(message.cc)
        val replyTo = toMailboxes(message.replyTo)
        val messageId = cleanHeaderValue(headerOf(message, "Message-ID"))
        val refs = parseMessageIds(
            headerOf(message, "References") + " " + headerOf(message, "In-Reply-To"),
        )
        return ParsedMail(
            from = displayMailboxes(from),
            to = displayMailboxes(to),
            cc = displayMailboxes(cc),
            replyTo = displayMailboxes(replyTo),
            date = message.date?.let { rfc2822(it) }.orEmpty(),
            subject = decodeHeader(message.subject.orEmpty()),
            messageId = messageId,
            references = refs,
            attachments = attachments,
            body = body,
        )
    }

    private fun headerOf(message: Message, name: String): String =
        message.header?.getField(name)?.body?.trim().orEmpty()

    fun parseMessageIds(raw: String): List<String> {
        val out = mutableListOf<String>()
        var cursor = 0
        while (cursor < raw.length) {
            val open = raw.indexOf('<', cursor)
            if (open < 0) break
            val close = raw.indexOf('>', open + 1)
            if (close < 0) break
            val token = cleanHeaderValue(raw.substring(open, close + 1))
            if (token.length > 2 && token !in out) out += token
            cursor = close + 1
        }
        return out
    }

    private fun toMailboxes(list: List<org.apache.james.mime4j.dom.address.Address>?): List<Mailbox> {
        if (list == null) return emptyList()
        val out = mutableListOf<Mailbox>()
        for (address in list) {
            when (address) {
                is org.apache.james.mime4j.dom.address.Mailbox ->
                    out += Mailbox(address.name?.let(::decodeHeader), address.address)
                is org.apache.james.mime4j.dom.address.Group ->
                    out += address.mailboxes.map { Mailbox(it.name?.let(::decodeHeader), it.address) }
            }
        }
        return out
    }

    private fun extractContent(
        entity: Entity,
        attachments: MutableList<MailAttachment>,
        depth: Int,
    ): Pair<String, String> {
        if (depth > MAX_DEPTH) return "" to ""
        val disposition = entity.dispositionType?.lowercase(Locale.US)
        val rawName = entity.filename
        val mime = entity.mimeType.orEmpty().lowercase(Locale.US)
        if (disposition == "attachment" || (!rawName.isNullOrBlank() && !mime.startsWith("multipart/"))) {
            val name = decodeHeader(rawName?.takeIf { it.isNotBlank() } ?: "attachment")
            val size = measureBytes(entity.body as? SingleBody)
            attachments += MailAttachment(filename = name, mimeType = mime, sizeBytes = size)
            return "" to ""
        }
        return when {
            mime == "text/plain" -> textOf(entity).orEmpty() to ""
            mime == "text/html" -> "" to textOf(entity).orEmpty()
            mime == "multipart/alternative" -> {
                val multipart = entity.body as? Multipart ?: return "" to ""
                var bestPlain = ""
                var bestHtml = ""
                for (part in multipart.bodyParts) {
                    val (p, h) = extractContent(part, attachments, depth + 1)
                    if (p.isNotBlank()) bestPlain = p
                    if (h.isNotBlank()) bestHtml = h
                }
                bestPlain to bestHtml
            }
            mime.startsWith("multipart/") -> {
                val multipart = entity.body as? Multipart ?: return "" to ""
                val plains = mutableListOf<String>()
                val htmls = mutableListOf<String>()
                var htmlChars = 0
                for (part in multipart.bodyParts) {
                    val (p, h) = extractContent(part, attachments, depth + 1)
                    if (p.isNotBlank()) plains += p.trim()
                    if (h.isNotBlank() && htmlChars < MAX_PART_CHARS) {
                        htmls += h
                        htmlChars += h.length
                    }
                }
                plains.joinToString("\n\n") to htmls.joinToString("\n")
            }
            else -> "" to ""
        }
    }

    private fun measureBytes(body: SingleBody?): Long {
        if (body == null) return 0L
        return try {
            body.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (total < MAX_MESSAGE_BYTES) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun textOf(entity: Entity): String? {
        val body = entity.body as? TextBody ?: return null
        return try {
            body.reader.use { reader ->
                val buffer = CharArray(8192)
                val out = StringBuilder()
                while (out.length < MAX_PART_CHARS) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseAddressList(raw: String): List<Mailbox> {
        val tokens = splitAddresses(raw)
        return tokens.mapNotNull { token ->
            val trimmed = token.trim().trim(',', ';')
            if (trimmed.isEmpty()) return@mapNotNull null
            val open = trimmed.lastIndexOf('<')
            val close = trimmed.lastIndexOf('>')
            if (open >= 0 && close > open) {
                val address = trimmed.substring(open + 1, close).trim()
                val name = trimmed.substring(0, open).trim().trim('"').ifBlank { null }
                if (address.contains('@')) Mailbox(name, address) else null
            } else {
                val address = trimmed.trim('"')
                if (address.contains('@')) Mailbox(null, address) else null
            }
        }
    }

    private fun splitAddresses(raw: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var angle = 0
        for (ch in raw) {
            when {
                ch == '"' -> {
                    quoted = !quoted
                    current.append(ch)
                }
                !quoted && ch == '<' -> {
                    angle++
                    current.append(ch)
                }
                !quoted && ch == '>' -> {
                    angle = (angle - 1).coerceAtLeast(0)
                    current.append(ch)
                }
                !quoted && angle == 0 && (ch == ',' || ch == ';') -> {
                    out += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Decoded, human-readable mailbox list for tool output and UI previews. */
    fun displayMailboxes(list: List<Mailbox>): String = list.joinToString(", ") { mailbox ->
        val name = mailbox.name?.trim()
        if (name.isNullOrEmpty()) mailbox.address else "$name <${mailbox.address}>"
    }

    /** RFC 2047 encoded mailbox list for wire headers. */
    fun renderMailboxes(list: List<Mailbox>): String = list.joinToString(", ") { mailbox ->
        val name = mailbox.name?.trim()
        if (name.isNullOrEmpty()) {
            mailbox.address
        } else {
            val renderedName = if (name.all { it.code in 0x20..0x7e }) {
                if (name.any { it in "()<>[]:;@\\,.\"" }) {
                    "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                } else {
                    name
                }
            } else {
                encodePhrase(name)
            }
            "$renderedName <${mailbox.address}>"
        }
    }

    fun decodeHeader(value: String): String =
        if (value.contains("=?")) DecoderUtil.decodeEncodedWords(value, DecodeMonitor.SILENT) else value

    fun encodePhrase(value: String): String {
        val clean = cleanHeaderValue(value)
        if (clean.all { it.code in 0x20..0x7e }) return clean
        val words = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isEmpty()) return
            val encoded = Base64.getEncoder().encodeToString(current.toString().toByteArray(Charsets.UTF_8))
            words += "=?UTF-8?B?$encoded?="
            current.clear()
        }
        var index = 0
        while (index < clean.length) {
            val cp = clean.codePointAt(index)
            val count = Character.charCount(cp)
            val startLen = current.length
            current.appendCodePoint(cp)
            val encodedLength = Base64.getEncoder()
                .encodeToString(current.toString().toByteArray(Charsets.UTF_8)).length
            if (encodedLength > 60 && startLen > 0) {
                current.setLength(startLen)
                flush()
                current.appendCodePoint(cp)
            }
            index += count
        }
        flush()
        return words.joinToString(" ")
    }

    fun newMessageId(from: String): String {
        val at = from.lastIndexOf('@')
        val rawDomain = if (at >= 0 && at < from.lastIndex) from.substring(at + 1).trim() else ""
        val domain = rawDomain
            .takeIf { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '-' } }
            ?: "nanomuse.local"
        return "<${UUID.randomUUID()}@$domain>"
    }

    /**
     * The message as it goes on the wire. Headers are CRLF-terminated, every message carries a
     * Message-ID, and the UTF-8 body is base64 so QQ, 163, Outlook and Gmail all see Chinese
     * text rather than a charset guess.
     */
    fun build(
        from: String,
        to: List<Mailbox>,
        cc: List<Mailbox>,
        subject: String,
        body: String,
        date: Date = Date(),
        messageId: String = newMessageId(from),
        inReplyTo: String? = null,
        references: List<String> = emptyList(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(value: String) {
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write(CRLF)
        }
        line("From: ${encodeAddress(cleanHeaderValue(from))}")
        line("To: ${renderMailboxes(to)}")
        if (cc.isNotEmpty()) line("Cc: ${renderMailboxes(cc)}")
        line("Subject: ${encodePhrase(subject)}")
        line("Date: ${rfc2822(date)}")
        line("Message-ID: ${cleanHeaderValue(messageId)}")
        val replyHeader = inReplyTo?.let(::cleanHeaderValue)?.takeIf { it.isNotBlank() }
        if (replyHeader != null) line("In-Reply-To: $replyHeader")
        val refList = references.map(::cleanHeaderValue).filter { it.isNotBlank() }.distinct()
        if (refList.isNotEmpty()) line("References: ${refList.joinToString(" ")}")
        line("MIME-Version: 1.0")
        line("Content-Type: text/plain; charset=UTF-8")
        line("Content-Transfer-Encoding: base64")
        line("")
        val encoded = Base64.getMimeEncoder(76, CRLF).encodeToString(body.toByteArray(Charsets.UTF_8))
        out.write(encoded.toByteArray(Charsets.US_ASCII))
        out.write(CRLF)
        return out.toByteArray()
    }

    private fun cleanHeaderValue(value: String): String =
        value.replace("\r", " ").replace("\n", " ").trim()

    private fun encodeAddress(address: String): String =
        if (address.all { it.code in 0x20..0x7e }) address else encodePhrase(address)

    private fun rfc2822(date: Date): String =
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(date)

    fun htmlToText(html: String): String {
        var text = html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1\\s*>"), " ")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
        text = text.replace(Regex("(?i)</(p|div|li|tr|h[1-6]|blockquote|pre)\\s*>"), "\n")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = decodeEntities(text)
        text = text.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        text = text.replace(Regex(" *\\n *"), "\n")
        return text.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private val entityRegex = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")

    private fun decodeEntities(value: String): String = entityRegex.replace(value) { match ->
        val token = match.groupValues[1]
        when {
            token.startsWith("#x", ignoreCase = true) ->
                codePoint(token.substring(2).toIntOrNull(16))
            token.startsWith("#") -> codePoint(token.substring(1).toIntOrNull())
            token.equals("nbsp", ignoreCase = true) -> " "
            token.equals("amp", ignoreCase = true) -> "&"
            token.equals("lt", ignoreCase = true) -> "<"
            token.equals("gt", ignoreCase = true) -> ">"
            token.equals("quot", ignoreCase = true) -> "\""
            token.equals("apos", ignoreCase = true) -> "'"
            token.equals("hellip", ignoreCase = true) -> "..."
            token.equals("mdash", ignoreCase = true) -> ","
            token.equals("ndash", ignoreCase = true) -> ","
            else -> match.value
        }
    }

    private fun codePoint(value: Int?): String {
        if (value == null || value < 0 || value > 0x10FFFF) return ""
        return String(Character.toChars(value))
    }

    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
}
