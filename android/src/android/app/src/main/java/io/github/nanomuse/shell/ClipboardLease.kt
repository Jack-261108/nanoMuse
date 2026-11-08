package io.github.nanomuse.shell

import java.util.UUID

/** A temporary clipboard replacement, restored only while its ownership marker is intact. */
internal class ClipboardLease<T>(
    private val read: () -> Read<T>,
    private val write: (T?) -> Unit,
    private val ownerOf: (T) -> String?,
    private val mark: (T, String) -> T,
) {
    data class Read<T>(val readable: Boolean, val clip: T? = null)

    private data class Saved<T>(val original: T?, val owner: String)
    private var saved: Saved<T>? = null
    val pending: Boolean get() = saved != null

    fun replace(clip: T): Boolean {
        if (pending && !restore()) return false
        val before = runCatching(read).getOrNull()?.takeIf { it.readable } ?: return false
        val owner = UUID.randomUUID().toString()
        val replacement = mark(clip, owner)
        // Retain the original even when the write throws after reaching the clipboard service.
        saved = Saved(before.clip, owner)
        return runCatching { write(replacement) }.isSuccess
    }

    fun restore(): Boolean {
        val lease = saved ?: return true
        val now = runCatching(read).getOrNull()?.takeIf { it.readable } ?: return false
        if (now.clip != null && ownerOf(now.clip) == lease.owner) {
            if (runCatching { write(lease.original) }.isFailure) return false
        }
        // A new copy or an explicit clear belongs to the user. Do not resurrect older contents.
        saved = null
        return true
    }
}
