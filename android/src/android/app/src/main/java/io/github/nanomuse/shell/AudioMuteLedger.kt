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

    fun put(packageName: String, mode: String) {
        modes[packageName] = mode
        save()
    }

    fun remove(packageName: String) {
        if (modes.remove(packageName) != null) save()
    }

    fun restoreAll(write: (packageName: String, mode: String) -> Boolean): Boolean {
        packages().forEach { pkg ->
            val prior = modes[pkg]
            if (write(pkg, restoreMode(prior))) remove(pkg)
        }
        return modes.isEmpty()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            if (modes.isEmpty()) {
                f.delete()
            } else {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(format(modes))
                if (!tmp.renameTo(f)) error("rename failed")
            }
        }
    }

    companion object {
        private val LINE = Regex("""([A-Za-z0-9_.]+(?:@[0-9]+)?) ([a-z_]+)""")

        fun restoreMode(prior: String?): String =
            if (prior.isNullOrEmpty() || prior == "default") "allow" else prior

        fun parseSetMode(output: String): String? =
            Regex("""PLAY_AUDIO:\s*([a-z_]+)""").find(output)?.groupValues?.get(1)

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
