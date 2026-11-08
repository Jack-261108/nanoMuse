package io.github.nanomuse.shell

/** Some systems return success from appops set without changing the mode. Verify every write. */
internal class AudioModeAccess(
    private val command: (operation: String, key: String, mode: String?) -> Pair<Int, String>,
) {
    fun read(key: String): String? {
        val (code, output) = command("get", key, null)
        return if (code == 0) AudioMuteLedger.parseMode(output) else null
    }

    fun write(key: String, mode: String): Boolean =
        command("set", key, mode).first == 0 && read(key) == mode
}
