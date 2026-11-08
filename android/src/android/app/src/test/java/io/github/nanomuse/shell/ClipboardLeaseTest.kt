package io.github.nanomuse.shell

import org.junit.Assert.*
import org.junit.Test

class ClipboardLeaseTest {
    private data class Clip(val text: String, val owner: String? = null)

    private class Clipboard(initial: Clip? = null) {
        var clip = initial
        var readable = true
        var failWrite = false
        val writes = mutableListOf<Clip?>()
        val lease = ClipboardLease(
            read = { ClipboardLease.Read(readable, if (readable) clip else null) },
            write = { replacement: Clip? ->
                check(!failWrite)
                clip = replacement
                writes += replacement
            },
            ownerOf = { it.owner },
            mark = { value, owner -> value.copy(owner = owner) },
        )
    }

    @Test fun unreadableOriginalIsNeverOverwritten() {
        val clipboard = Clipboard(Clip("original"))
        clipboard.readable = false
        assertFalse(clipboard.lease.replace(Clip("input")))
        assertTrue(clipboard.writes.isEmpty())
        assertFalse(clipboard.lease.pending)
    }

    @Test fun originalIsRestoredAfterConfirmedPaste() {
        val original = Clip("original")
        val clipboard = Clipboard(original)
        assertTrue(clipboard.lease.replace(Clip("input")))
        assertEquals("input", clipboard.clip?.text)
        assertTrue(clipboard.lease.pending)
        assertTrue(clipboard.lease.restore())
        assertEquals(original, clipboard.clip)
        assertFalse(clipboard.lease.pending)
    }

    @Test fun emptyOriginalIsClearedOnlyWhileOwned() {
        val clipboard = Clipboard()
        assertTrue(clipboard.lease.replace(Clip("input")))
        assertTrue(clipboard.lease.restore())
        assertNull(clipboard.clip)
    }

    @Test fun userCopyOfIdenticalTextStillWins() {
        val clipboard = Clipboard(Clip("original"))
        assertTrue(clipboard.lease.replace(Clip("input")))
        clipboard.clip = Clip("input")
        assertTrue(clipboard.lease.restore())
        assertEquals(Clip("input"), clipboard.clip)
        assertEquals(1, clipboard.writes.size)
    }

    @Test fun userClearDoesNotResurrectOriginal() {
        val clipboard = Clipboard(Clip("original"))
        assertTrue(clipboard.lease.replace(Clip("input")))
        clipboard.clip = null
        assertTrue(clipboard.lease.restore())
        assertNull(clipboard.clip)
        assertEquals(1, clipboard.writes.size)
    }

    @Test fun failedRestoreRetainsOriginalAndBlocksAnotherReplacement() {
        val original = Clip("original")
        val clipboard = Clipboard(original)
        assertTrue(clipboard.lease.replace(Clip("first")))
        clipboard.failWrite = true
        assertFalse(clipboard.lease.restore())
        assertTrue(clipboard.lease.pending)
        assertFalse(clipboard.lease.replace(Clip("second")))
        clipboard.failWrite = false
        assertTrue(clipboard.lease.restore())
        assertEquals(original, clipboard.clip)
    }

    @Test fun unreadableRestoreWaitsWithoutDiscardingTheOriginal() {
        val original = Clip("original")
        val clipboard = Clipboard(original)
        assertTrue(clipboard.lease.replace(Clip("input")))
        clipboard.readable = false
        assertFalse(clipboard.lease.restore())
        assertTrue(clipboard.lease.pending)
        clipboard.readable = true
        assertTrue(clipboard.lease.restore())
        assertEquals(original, clipboard.clip)
    }
}
