package io.github.nanomuse.email

import java.util.Base64

/**
 * IMAP mailbox names travel as modified UTF-7 (RFC 3501 section 5.1.3). QQ and 163 use it
 * for their Chinese folders, so a folder the person names in Chinese has to be encoded
 * before it reaches SELECT or EXAMINE.
 */
object ModifiedUtf7 {
    fun encode(input: String): String {
        val out = StringBuilder(input.length)
        val pending = StringBuilder()
        fun flush() {
            if (pending.isEmpty()) return
            val bytes = pending.toString().toByteArray(Charsets.UTF_16BE)
            val base64 = Base64.getEncoder().encodeToString(bytes)
                .replace('/', ',')
                .trimEnd('=')
            out.append('&').append(base64).append('-')
            pending.clear()
        }
        for (ch in input) {
            when {
                ch == '&' -> {
                    flush()
                    out.append("&-")
                }
                ch.code in 0x20..0x7e -> {
                    flush()
                    out.append(ch)
                }
                else -> pending.append(ch)
            }
        }
        flush()
        return out.toString()
    }

    fun decode(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch != '&') {
                out.append(ch)
                i++
                continue
            }
            val end = input.indexOf('-', i + 1)
            if (end < 0) {
                out.append(input.substring(i))
                break
            }
            if (end == i + 1) {
                out.append('&')
            } else {
                val body = input.substring(i + 1, end).replace(',', '/')
                val padded = body + "=".repeat((4 - body.length % 4) % 4)
                val bytes = Base64.getDecoder().decode(padded)
                out.append(String(bytes, Charsets.UTF_16BE))
            }
            i = end + 1
        }
        return out.toString()
    }
}
