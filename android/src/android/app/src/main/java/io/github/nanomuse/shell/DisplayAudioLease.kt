package io.github.nanomuse.shell

/** Audio changes belong to displays; ending one display cannot unmute another display's app. */
internal class DisplayAudioLease(
    private val mute: (String) -> Boolean,
    private val restore: (String) -> Boolean,
) {
    private val owners = LinkedHashMap<Int, MutableSet<String>>()
    private val failed = HashMap<Int, MutableSet<String>>()

    fun keys(displayId: Int): Set<String> = owners[displayId]?.toSet().orEmpty()
    fun displays(): Set<Int> = owners.keys.toSet()
    fun activeKeys(): Set<String> = owners.values.flatten().toSet()
    fun hasFailed(displayId: Int, key: String): Boolean = failed[displayId]?.contains(key) == true

    fun acquire(displayId: Int, key: String): Boolean = tryAcquire(displayId, key, retryFailed = true)

    fun tryAcquire(displayId: Int, key: String, retryFailed: Boolean = true): Boolean {
        if (key in keys(displayId)) return true
        if (!retryFailed && hasFailed(displayId, key)) return false
        if (key !in activeKeys() && !mute(key)) {
            failed.getOrPut(displayId) { LinkedHashSet() }.add(key)
            return false
        }
        failed[displayId]?.remove(key)
        owners.getOrPut(displayId) { LinkedHashSet() }.add(key)
        return true
    }

    fun forgetFailed(displayId: Int) {
        failed.remove(displayId)
    }

    fun clearFailures() {
        failed.clear()
    }

    fun release(displayId: Int, keys: Set<String> = keys(displayId)): Boolean {
        val owned = owners[displayId] ?: return true
        for (key in keys.intersect(owned)) {
            val shared = owners.any { (id, other) -> id != displayId && key in other }
            if (shared || restore(key)) owned.remove(key)
        }
        if (owned.isEmpty()) owners.remove(displayId)
        return keys.intersect(owned).isEmpty()
    }
}
