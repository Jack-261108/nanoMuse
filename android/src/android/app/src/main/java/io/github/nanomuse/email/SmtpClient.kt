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

/**
 * The SMTP subset a mailbox needs: EHLO, STARTTLS or implicit TLS, AUTH LOGIN or PLAIN,
 * XOAUTH2, and one message. Multi-line replies are folded before they are read, because QQ
 * answers EHLO with a dozen `250-` lines and a parser that stops at the first one never sees
 * STARTTLS.
 *
 * There is no plaintext mode: [starttls] false means implicit TLS on 465, true means
 * STARTTLS on 587. A server that does not offer STARTTLS is refused, never downgraded.
 */
internal class SmtpClient(
    private val host: String,
    private val port: Int,
    private val starttls: Boolean,
    private val connect: (String, Int) -> Socket = ::plainSocket,
    private val tls: (Socket, String, Int) -> Socket = ::smtpTlsSocket,
) : Closeable {
    private lateinit var socket: Socket
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val capabilities = mutableSetOf<String>()
    private val authMechanisms = mutableSetOf<String>()

    fun open() {
        socket = connect(host, port)
        if (!starttls) socket = tls(socket, host, port)
        bindStreams()
        expect(readReply(), 220, "greeting")
        ehlo()
        if (starttls) {
            if ("STARTTLS" !in capabilities) {
                throw EmailException("SMTP: the server did not offer STARTTLS")
            }
            send("STARTTLS")
            expect(readReply(), 220, "STARTTLS")
            socket = tls(socket, host, port)
            bindStreams()
            ehlo()
        }
    }

    fun authenticate(user: String, password: String) {
        if ("AUTH" !in capabilities) {
            throw EmailException("SMTP: the server did not offer authentication")
        }
        val preferPlainOnly =
            authMechanisms.isNotEmpty() && "LOGIN" !in authMechanisms && "PLAIN" in authMechanisms
        if (!preferPlainOnly) {
            send("AUTH LOGIN")
            var reply = readReply()
            if (reply.code == 334) {
                send(Base64.getEncoder().encodeToString(user.toByteArray(Charsets.UTF_8)))
                reply = readReply()
                if (reply.code == 334) {
                    send(Base64.getEncoder().encodeToString(password.toByteArray(Charsets.UTF_8)))
                    reply = readReply()
                }
                if (reply.code == 235) return
                throw EmailException("SMTP: the server refused the address or authorization code")
            }
            if (reply.code == 235) return
        }

        val token = Base64.getEncoder().encodeToString(
            "\u0000$user\u0000$password".toByteArray(Charsets.UTF_8),
        )
        send("AUTH PLAIN $token")
        val reply = readReply()
        if (reply.code != 235) {
            throw EmailException("SMTP: the server refused the address or authorization code")
        }
    }

    fun authenticateXoauth2(user: String, accessToken: String) {
        val payload = Base64.getEncoder().encodeToString(
            "user=$user\u0001auth=Bearer $accessToken\u0001\u0001".toByteArray(Charsets.UTF_8),
        )
        send("AUTH XOAUTH2 $payload")
        var reply = readReply()
        if (reply.code == 334) {
            // A 334 here is the server's error challenge (Gmail sends a JSON error in
            // base64); an empty line completes the exchange and the final code follows.
            send("")
            reply = readReply()
        }
        if (reply.code != 235) {
            throw EmailException("SMTP: the server refused the access token")
        }
    }

    fun sendMessage(from: String, recipients: List<String>, data: ByteArray) {
        send("MAIL FROM:<$from>")
        expect(readReply(), 250, "MAIL FROM")
        for (recipient in recipients) {
            send("RCPT TO:<$recipient>")
            val reply = readReply()
            if (reply.code != 250 && reply.code != 251) {
                throw EmailException("SMTP: the server refused $recipient (${reply.code})")
            }
        }
        send("DATA")
        expect(readReply(), 354, "DATA")
        writeData(data)
        expect(readReply(), 250, "message")
    }

    fun quit() {
        runCatching {
            send("QUIT")
            readReply()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun bindStreams() {
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    private fun ehlo() {
        send("EHLO nanomuse")
        val reply = readReply()
        if (reply.code != 250) throw EmailException("SMTP: EHLO failed (${reply.code})")
        capabilities.clear()
        authMechanisms.clear()
        for (line in reply.lines.drop(1)) {
            if (line.length < 4) continue
            val body = line.substring(4).trim()
            if (
                body.equals("AUTH", ignoreCase = true) ||
                body.startsWith("AUTH ", ignoreCase = true) ||
                body.startsWith("AUTH=", ignoreCase = true)
            ) {
                capabilities += "AUTH"
                val rest = body.substring(4).trimStart('=', ' ')
                authMechanisms += rest.split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .map { it.uppercase(Locale.US) }
            } else {
                capabilities += body.split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .map { it.uppercase(Locale.US) }
            }
        }
    }

    private data class Reply(val code: Int, val lines: List<String>)

    private fun readReply(): Reply {
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine()
            lines += line
            if (line.length < 4 || line[3] != '-') break
        }
        return Reply(lines.last().take(3).toIntOrNull() ?: 0, lines)
    }

    private fun expect(reply: Reply, code: Int, step: String) {
        if (reply.code != code) throw EmailException("SMTP: $step failed (${reply.code})")
    }

    private fun readLine(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw EOFException("SMTP connection closed")
            if (byte == '\n'.code) break
            if (byte != '\r'.code) out.write(byte)
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun send(line: String) {
        output.write(line.toByteArray(Charsets.ISO_8859_1))
        output.write(CRLF)
        output.flush()
    }

    private fun writeData(data: ByteArray) {
        var atLineStart = true
        for (byte in data) {
            if (atLineStart && byte == DOT) output.write(DOT.toInt())
            output.write(byte.toInt())
            atLineStart = byte == LF
        }
        if (!atLineStart) output.write(CRLF)
        output.write(".\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private companion object {
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        const val DOT = '.'.code.toByte()
        const val LF = '\n'.code.toByte()
    }
}

private fun plainSocket(host: String, port: Int): Socket = Socket().apply {
    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
    soTimeout = READ_TIMEOUT_MS
}

private fun smtpTlsSocket(raw: Socket, host: String, port: Int): Socket {
    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
    val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
    ssl.soTimeout = READ_TIMEOUT_MS
    ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    ssl.startHandshake()
    return ssl
}
