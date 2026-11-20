package io.github.nanomuse.email

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class FolderRole(val flag: String, val aliases: Set<String>) {
    INBOX("\\Inbox", setOf("inbox", "收件箱")),
    SENT(
        "\\Sent",
        setOf("sent", "sent mail", "sent messages", "sent items", "已发送", "已发邮件", "发件箱"),
    ),
    DRAFTS("\\Drafts", setOf("draft", "drafts", "草稿", "草稿箱")),
    TRASH(
        "\\Trash",
        setOf("trash", "deleted", "deleted messages", "deleted items", "bin", "已删除", "垃圾箱", "废纸篓"),
    ),
    JUNK("\\Junk", setOf("junk", "spam", "junk e-mail", "junk email", "bulk mail", "垃圾邮件")),
    ARCHIVE("\\Archive", setOf("archive", "archives", "all mail", "归档", "所有邮件"));

    companion object {
        fun fromQuery(query: String): FolderRole? {
            val cleaned = query.trim().lowercase(Locale.US)
            if (cleaned.isEmpty()) return null
            return entries.firstOrNull { cleaned in it.aliases }
        }
    }
}

data class ImapFolder(
    val name: String,
    val rawName: String,
    val flags: Set<String> = emptySet(),
) {
    val role: FolderRole?
        get() {
            val lowerFlags = flags.map { it.lowercase(Locale.US) }.toSet()
            when {
                "\\inbox" in lowerFlags || rawName.equals("INBOX", ignoreCase = true) -> return FolderRole.INBOX
                "\\sent" in lowerFlags -> return FolderRole.SENT
                "\\drafts" in lowerFlags -> return FolderRole.DRAFTS
                "\\trash" in lowerFlags -> return FolderRole.TRASH
                "\\junk" in lowerFlags || "\\spam" in lowerFlags -> return FolderRole.JUNK
                "\\archive" in lowerFlags || "\\all" in lowerFlags -> return FolderRole.ARCHIVE
            }
            val leaf = name.substringAfterLast('/').substringAfterLast('.').trim().lowercase(Locale.US)
            return FolderRole.entries.firstOrNull { leaf in it.aliases }
        }
}

object FolderMatcher {
    fun resolve(requested: String, folders: List<ImapFolder>): ImapFolder? {
        val wanted = requested.trim()
        if (wanted.isEmpty()) return folders.firstOrNull { it.rawName.equals("INBOX", ignoreCase = true) }
        val encoded = runCatching { ModifiedUtf7.encode(wanted) }.getOrDefault(wanted)
        folders.firstOrNull {
            it.name.equals(wanted, ignoreCase = true) ||
                it.rawName.equals(wanted, ignoreCase = true) ||
                it.rawName.equals(encoded, ignoreCase = true)
        }?.let { return it }

        folders.firstOrNull { folder ->
            val leaf = folder.name.substringAfterLast('/').substringAfterLast('.').trim()
            leaf.equals(wanted, ignoreCase = true)
        }?.let { return it }

        val role = FolderRole.fromQuery(wanted) ?: return null
        val flagName = role.flag.lowercase(Locale.US)
        folders.firstOrNull { folder ->
            folder.flags.any {
                val lower = it.lowercase(Locale.US)
                lower == flagName || (role == FolderRole.JUNK && lower == "\\spam")
            }
        }?.let { return it }
        return folders.firstOrNull { it.role == role }
    }
}

/**
 * The IMAP subset the mailbox needs: log in, list folders, open a folder read-only, search by
 * UID and fetch one message. Literals are read as bytes, never through a text reader, so a
 * UTF-8 search term and a raw message survive the trip intact.
 *
 * Production uses implicit TLS on 993 (and STARTTLS on 143, never downgrading to plaintext).
 */
internal class ImapClient(
    private val host: String,
    private val port: Int,
    private val starttls: Boolean = port == 143,
    private val connect: (String, Int) -> Socket = if (starttls) ::plainImapSocket else ::imapTlsSocket,
    private val tls: (Socket, String, Int) -> Socket = ::upgradeImapTlsSocket,
) : Closeable {
    constructor(host: String, port: Int, connect: (String, Int) -> Socket) :
        this(host, port, starttls = false, connect = connect)

    private lateinit var socket: Socket
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var counter = 0
    private var preauthenticated = false

    fun open() {
        socket = connect(host, port)
        bindStreams()
        val greeting = readLine()
        when {
            greeting.startsWith("* PREAUTH", ignoreCase = true) -> {
                if (starttls) throw EmailException("IMAP: refused PREAUTH before STARTTLS")
                preauthenticated = true
            }
            greeting.startsWith("* OK", ignoreCase = true) -> Unit
            else -> throw EmailException("IMAP: ${greeting.take(200)}")
        }
        if (starttls) {
            val reply = command("STARTTLS")
            if (!reply.ok) throw EmailException("IMAP: the server did not accept STARTTLS")
            socket = tls(socket, host, port)
            bindStreams()
        }
    }

    private fun bindStreams() {
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    fun login(user: String, password: String) {
        if (!preauthenticated) {
            val reply = command("LOGIN ${quote(user)} ${quote(password)}")
            if (!reply.ok) {
                throw EmailException("IMAP: the server refused the address or authorization code")
            }
        }
        sendId()
    }

    /** Microsoft and Google expect the OAuth token as an XOAUTH2 SASL exchange. */
    fun loginXoauth2(user: String, accessToken: String) {
        if (!preauthenticated) {
            val tag = "A${++counter}"
            write("$tag AUTHENTICATE XOAUTH2\r\n")
            val continuation = readLine()
            if (!continuation.startsWith("+")) {
                if (!continuation.startsWith("$tag ", ignoreCase = true)) readReply(tag)
                throw EmailException("IMAP: the server refused the access token")
            }
            val payload = Base64.getEncoder().encodeToString(
                "user=$user\u0001auth=Bearer $accessToken\u0001\u0001".toByteArray(Charsets.UTF_8),
            )
            write("$payload\r\n")
            val reply = readReply(tag)
            if (!reply.ok) throw EmailException("IMAP: the server refused the access token")
        }
        sendId()
    }

    private fun sendId() {
        // QQ and 163 ask clients to identify themselves; 163 enforces it even when its
        // CAPABILITY line does not advertise ID. Best effort, after login.
        runCatching {
            command("ID (\"name\" \"nanomuse\" \"version\" \"1.0\" \"vendor\" \"nanomuse\")")
        }
    }

    fun listFolders(): List<ImapFolder> {
        val reply = command("LIST \"\" \"*\"")
        if (!reply.ok) return emptyList()
        return reply.items.mapNotNull { parseListItem(it.line, it.literal) }
    }

    fun examine(folder: String) {
        val encoded = if (folder.contains('&') && folder.contains('-')) {
            // If already a raw server mailbox name from LIST, keep it as-is when decoding changes it.
            val decoded = runCatching { ModifiedUtf7.decode(folder) }.getOrDefault(folder)
            if (decoded != folder) folder else ModifiedUtf7.encode(folder)
        } else {
            ModifiedUtf7.encode(folder)
        }
        val reply = command("EXAMINE ${quote(encoded)}")
        if (!reply.ok) throw EmailException("IMAP: cannot open folder $folder")
    }

    /** UIDs of matching messages, oldest first, or null when the server rejects the search. */
    fun searchOrNull(unreadOnly: Boolean, term: String?): List<Int>? {
        val criteria = if (unreadOnly) "UNSEEN" else "ALL"
        val text = term?.trim()?.replace(Regex("[\\x00-\\x1f\\x7f]+"), " ")?.trim().orEmpty()
        val (command, literal) = if (text.isEmpty()) {
            "UID SEARCH $criteria" to null
        } else if (text.all { it.code < 0x80 }) {
            "UID SEARCH $criteria TEXT ${quote(text)}" to null
        } else {
            val bytes = text.toByteArray(Charsets.UTF_8)
            "UID SEARCH CHARSET UTF-8 $criteria TEXT {${bytes.size}}" to bytes
        }
        val reply = command(command, literal)
        if (!reply.ok) return null
        val out = mutableListOf<Int>()
        for (line in reply.untagged) {
            if (!line.equals("* SEARCH", ignoreCase = true) && !line.startsWith("* SEARCH ", ignoreCase = true)) {
                continue
            }
            val rest = line.substringAfter("SEARCH", "").trim()
            if (rest.isNotEmpty()) {
                rest.split(Regex("\\s+")).mapNotNullTo(out) { it.toIntOrNull() }
            }
        }
        return out
    }

    /** UIDs of matching messages, oldest first. */
    fun search(unreadOnly: Boolean, term: String?): List<Int> =
        searchOrNull(unreadOnly, term) ?: throw EmailException("IMAP: search failed")

    fun size(uid: Int): Long? {
        val reply = command("UID FETCH $uid (RFC822.SIZE)")
        if (!reply.ok) return null
        val line = reply.untagged.firstOrNull { it.contains("FETCH", ignoreCase = true) } ?: return null
        return sizeOf(line)
    }

    fun fetch(uid: Int): ByteArray {
        val reply = command("UID FETCH $uid (BODY.PEEK[])")
        if (!reply.ok) throw EmailException("IMAP: cannot fetch message $uid")
        return reply.literal ?: throw EmailException("IMAP: the server sent no message body for $uid")
    }

    fun logout() {
        runCatching { command("LOGOUT") }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private data class UntaggedItem(val line: String, val literal: ByteArray?)

    private data class Reply(
        val ok: Boolean,
        val tagged: String,
        val untagged: List<String>,
        val items: List<UntaggedItem>,
        val literal: ByteArray?,
    )

    private fun command(text: String, literal: ByteArray? = null): Reply {
        val tag = "A${++counter}"
        write("$tag $text\r\n")
        if (literal != null) {
            while (true) {
                val line = readLine()
                if (line.startsWith("+")) break
                if (line.startsWith("* BYE", ignoreCase = true)) throw EmailException("IMAP: connection closed")
                if (line.startsWith("$tag ", ignoreCase = true)) {
                    return Reply(
                        line.startsWith("$tag OK", ignoreCase = true),
                        line,
                        emptyList(),
                        emptyList(),
                        null,
                    )
                }
            }
            output.write(literal)
            write("\r\n")
        }
        return readReply(tag)
    }

    private fun readReply(tag: String): Reply {
        val untagged = mutableListOf<String>()
        val items = mutableListOf<UntaggedItem>()
        var literal: ByteArray? = null
        while (true) {
            val line = readLine()
            if (line.startsWith("* BYE", ignoreCase = true)) throw EmailException("IMAP: connection closed")
            var lineLiteral: ByteArray? = null
            literalLength(line)?.let { length ->
                if (length > MAX_LITERAL_BYTES) throw EmailException("IMAP: message too large")
                lineLiteral = readExactly(length)
                literal = lineLiteral
            }
            if (line.startsWith("* ")) {
                untagged += line
                items += UntaggedItem(line, lineLiteral)
            }
            if (line.startsWith("$tag ", ignoreCase = true)) {
                return Reply(
                    ok = line.startsWith("$tag OK", ignoreCase = true),
                    tagged = line,
                    untagged = untagged,
                    items = items,
                    literal = literal,
                )
            }
        }
    }

    private fun readLine(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw EOFException("IMAP connection closed")
            if (byte == '\n'.code) break
            if (byte != '\r'.code) out.write(byte)
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun readExactly(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw EOFException("IMAP connection closed")
            offset += read
        }
        return bytes
    }

    private fun write(text: String) {
        output.write(text.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun quote(value: String): String {
        require(value.none { it == '\r' || it == '\n' || it == '\u0000' })
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** The length in `... {123}` or `... {123+}`, or null. Parsed by hand: Android's ICU
     *  regex engine rejects an unescaped `}` here, and a static Regex would crash the app. */
    private fun literalLength(line: String): Int? {
        if (!line.endsWith("}")) return null
        val open = line.lastIndexOf('{')
        if (open < 0) return null
        return line.substring(open + 1, line.length - 1).removeSuffix("+").toIntOrNull()
    }

    private fun sizeOf(line: String): Long? {
        val marker = "RFC822.SIZE"
        val at = line.indexOf(marker, ignoreCase = true)
        if (at < 0) return null
        var index = at + marker.length
        while (index < line.length && line[index].isWhitespace()) index++
        val start = index
        while (index < line.length && line[index].isDigit()) index++
        return if (index > start) line.substring(start, index).toLongOrNull() else null
    }

    companion object {
        private const val MAX_LITERAL_BYTES = 25 * 1024 * 1024

        internal fun parseListItem(line: String, literal: ByteArray? = null): ImapFolder? {
            if (!line.startsWith("* LIST ", ignoreCase = true)) return null
            val openFlags = line.indexOf('(', startIndex = 7)
            val closeFlags = if (openFlags >= 0) line.indexOf(')', startIndex = openFlags + 1) else -1
            if (openFlags < 0 || closeFlags < 0) return null
            val flags = line.substring(openFlags + 1, closeFlags)
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .toSet()
            if (flags.any { it.equals("\\Noselect", ignoreCase = true) || it.equals("\\NonExistent", ignoreCase = true) }) {
                return null
            }
            var cursor = closeFlags + 1
            while (cursor < line.length && line[cursor].isWhitespace()) cursor++
            // Skip hierarchy delimiter: NIL or quoted string.
            if (line.regionMatches(cursor, "NIL", 0, 3, ignoreCase = true)) {
                cursor += 3
            } else if (cursor < line.length && line[cursor] == '"') {
                val (_, next) = readQuoted(line, cursor) ?: return null
                cursor = next
            } else {
                while (cursor < line.length && !line[cursor].isWhitespace()) cursor++
            }
            while (cursor < line.length && line[cursor].isWhitespace()) cursor++
            val rawName = when {
                literal != null -> String(literal, Charsets.ISO_8859_1)
                cursor < line.length && line[cursor] == '"' -> readQuoted(line, cursor)?.first ?: return null
                cursor < line.length -> line.substring(cursor).trim()
                else -> return null
            }
            if (rawName.isEmpty()) return null
            val decoded = runCatching { ModifiedUtf7.decode(rawName) }.getOrDefault(rawName)
            return ImapFolder(name = decoded, rawName = rawName, flags = flags)
        }

        private fun readQuoted(text: String, startQuote: Int): Pair<String, Int>? {
            if (startQuote >= text.length || text[startQuote] != '"') return null
            val out = StringBuilder()
            var i = startQuote + 1
            while (i < text.length) {
                val ch = text[i]
                if (ch == '\\' && i + 1 < text.length) {
                    out.append(text[i + 1])
                    i += 2
                    continue
                }
                if (ch == '"') return out.toString() to (i + 1)
                out.append(ch)
                i++
            }
            return null
        }
    }
}

private fun plainImapSocket(host: String, port: Int): Socket = Socket().apply {
    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
    soTimeout = READ_TIMEOUT_MS
}

private fun upgradeImapTlsSocket(raw: Socket, host: String, port: Int): Socket {
    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
    val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
    ssl.soTimeout = READ_TIMEOUT_MS
    ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    ssl.startHandshake()
    return ssl
}

private fun imapTlsSocket(host: String, port: Int): Socket =
    upgradeImapTlsSocket(plainImapSocket(host, port), host, port)

internal const val CONNECT_TIMEOUT_MS = 30_000
internal const val READ_TIMEOUT_MS = 30_000
