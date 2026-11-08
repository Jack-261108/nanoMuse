package io.github.nanomuse.shell

import java.io.File

/**
 * 被静音的 App -> 静音前的 PLAY_AUDIO 模式。
 * 记录在文件里，确保助手或 App 异常退出后能把声音还回去。
 */
class AudioMuteLedger(private val file: File?) {
    private val modes = LinkedHashMap<String, String>()

    init {
        val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
        modes.putAll(parse(text))
    }

    fun contains(packageName: String): Boolean = packageName in modes
    operator fun get(packageName: String): String? = modes[packageName]
    fun packages(): List<String> = modes.keys.toList()

    fun put(packageName: String, mode: String): Boolean {
        val before = modes.put(packageName, mode)
        if (save()) return true
        if (before == null) modes.remove(packageName) else modes[packageName] = before
        return false
    }

    fun remove(packageName: String): Boolean {
        val before = modes.remove(packageName) ?: return true
        if (save()) return true
        modes[packageName] = before
        return false
    }

    fun restoreAll(write: (packageName: String, mode: String) -> Boolean): Boolean {
        packages().forEach { pkg ->
            val prior = modes[pkg]
            if (write(pkg, restoreMode(prior))) remove(pkg)
        }
        return modes.isEmpty()
    }

    private fun save(): Boolean {
        val f = file ?: return true
        return runCatching {
            if (modes.isEmpty()) {
                check(!f.exists() || f.delete()) { "delete failed" }
            } else {
                val tmp = File.createTempFile(f.name, ".tmp", f.parentFile)
                try {
                    tmp.writeText(format(modes))
                    // The next helper may use the shell backend after this one ran as root.
                    check(tmp.setReadable(true, false)) { "chmod failed" }
                    if (!tmp.renameTo(f)) error("rename failed")
                } finally {
                    tmp.delete()
                }
            }
        }.isSuccess
    }

    companion object {
        private val LINE = Regex("""([A-Za-z0-9_.]+(?:@[0-9]+)?) ([a-z_]+)""")
        private const val MODES = "allow|ignore|deny|default|foreground"
        private val EXPLICIT_MODE = Regex(
            """(?m)^\s*(?:PLAY_AUDIO|android:play_audio):\s*($MODES)(?=;|\s|$)""",
        )
        private val DEFAULT_MODE = Regex("""(?m)^\s*Default mode:\s*($MODES)\s*$""")
        private val NO_OPERATIONS = Regex("""(?m)^\s*No operations\.\s*$""")

        // Preserve an explicitly observed MODE_DEFAULT. An unset PLAY_AUDIO is recorded as
        // its reported mode (normally allow), so these two cases must not be normalized together.
        fun restoreMode(prior: String?): String =
            prior?.takeIf { it.isNotEmpty() } ?: "default"

        fun parseSetMode(output: String): String? =
            EXPLICIT_MODE.find(output)?.groupValues?.get(1)

        /** An unset operation has a reported default mode; it is not MODE_DEFAULT. */
        fun parseMode(output: String): String? = parseSetMode(output)
            ?: DEFAULT_MODE.find(output)?.groupValues?.get(1)?.takeIf {
                NO_OPERATIONS.containsMatchIn(output)
            }

        internal fun format(modes: Map<String, String>): String =
            modes.entries.joinToString("") { "${it.key} ${it.value}\n" }

        internal fun parse(text: String): Map<String, String> =
            text.lineSequence().mapNotNull { LINE.matchEntire(it.trim())?.destructured }
                .associate { (pkg, mode) -> pkg to mode }
    }
}

object AppAudioKey {
    private val KEY = Regex("""([A-Za-z0-9_.]+)(?:@([0-9]+))?""")

    fun of(packageName: String, user: Int?): String =
        if (user == null) packageName else "$packageName@$user"

    fun parse(key: String): Pair<String, Int?>? =
        KEY.matchEntire(key)?.destructured?.let { (pkg, user) ->
            pkg to user.takeIf { it.isNotEmpty() }?.toIntOrNull()
        }
}
