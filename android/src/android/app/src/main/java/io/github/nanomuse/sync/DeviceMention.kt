package io.github.nanomuse.sync

import android.content.Context
import io.github.nanomuse.chat.SessionAddenda
import io.github.nanomuse.hub.Device
import io.github.nanomuse.hub.Hub

/**
 * "@Mac tidy the downloads folder": a message that opens with one of the account's device
 * names is work for that device (contract C7, rule 8). The mention is taken out of the text,
 * and a one-turn note in front of the system prompt tells the Muse to hand the task over
 * through `nanomuse-pc task` — the existing hub path does the rest. Without a mention the
 * work stays on this phone.
 */
object DeviceMention {
    data class Target(val id: String, val name: String)

    /** The device that was addressed and the message without the mention. */
    data class Match(val target: Target, val text: String)

    const val TAG = "nm_device_mention"

    private val separators = charArrayOf(':', ',', '，', '：')

    /**
     * The whole name first, longest first, so "@Pixel 8 Pro …" is not read as "Pixel 8"; then
     * the first word as a prefix of a name ("@mac" for "MacBook Pro"), case-insensitive.
     * Accepts both ASCII "@" and full-width "＠".
     */
    fun parse(text: String, targets: List<Target>): Match? {
        val t = text.trimStart()
        if ((!t.startsWith("@") && !t.startsWith("＠")) || targets.isEmpty()) return null
        val body = t.substring(1)
        for (d in targets.sortedByDescending { it.name.length }) {
            if (d.name.isBlank()) continue
            if (body.startsWith(d.name, ignoreCase = true) && boundary(body, d.name.length)) {
                return Match(d, rest(body, d.name.length))
            }
        }
        val word = body.takeWhile { !it.isWhitespace() && it !in separators }
        if (word.isEmpty()) return null
        val d = targets.firstOrNull { it.name.startsWith(word, ignoreCase = true) } ?: return null
        return Match(d, rest(body, word.length))
    }

    /**
     * What is being typed after a leading "@" or full-width "＠" and before any separator/space.
     * Returns null when the text does not open with a mention in progress.
     */
    fun prefix(text: String): String? {
        val t = text.trimStart()
        if (!t.startsWith("@") && !t.startsWith("＠")) return null
        val body = t.substring(1)
        if (body.any { it.isWhitespace() || it in separators }) return null
        return body
    }

    /**
     * Devices to offer while the user is typing an "@" mention at the start of [text].
     * Only online non-web devices can take tasks (contract C7, rule 8); duplicate names deduplicated.
     * Returns empty when [text] does not open with a mention in progress.
     */
    fun suggestions(text: String, devices: List<Device>, limit: Int = 6): List<Device> {
        val p = prefix(text)?.lowercase() ?: return emptyList()
        return devices
            .filter { it.name.isNotBlank() && it.kind != "web" && it.online }
            .distinctBy { it.name.trim().lowercase() }
            .filter { it.name.lowercase().startsWith(p) }
            .sortedBy { it.name.lowercase() }
            .take(limit)
    }

    /**
     * The device addressed at the start of [text] if any, null otherwise.
     */
    fun target(text: String, devices: List<Device>): Device? {
        val targets = devices.filter { it.name.isNotBlank() && it.kind != "web" && it.online }.map { Target(it.id, it.name) }
        val m = parse(text, targets) ?: return null
        return devices.firstOrNull { it.id == m.target.id }
    }

    private fun boundary(body: String, n: Int): Boolean = n >= body.length || !body[n].isLetterOrDigit()

    private fun rest(body: String, n: Int): String = body.substring(n).trimStart { it.isWhitespace() || it in separators }

    /** The line in front of the turn. The Muse here knows `nanomuse-pc task` as the way to that device. */
    fun note(name: String, id: String): String =
        "The person addressed $name (id $id): run this there with `nanomuse-pc task \"<what they asked, in full>\" --on \"$name\"` " +
            "(the delegate to that device's own Muse) and report what it did. Do not do the work on this phone; " +
            "if that device does not answer, say so and point to Devices."

    /**
     * The composer's hook: with a mention of a device that is online, the text comes back
     * without it and the note is set for this one turn of [sessionId]; otherwise the text as it was.
     */
    fun apply(context: Context, sessionId: String, text: String): String {
        if (!text.trimStart().startsWith("@")) return text
        val targets = Hub.others(context).filter { it.online }.map { Target(it.id, it.name) }
        val m = parse(text, targets) ?: return text
        if (m.text.isBlank()) return text
        SessionAddenda.add(sessionId, TAG, note(m.target.name, m.target.id), turns = 1)
        return m.text
    }
}
