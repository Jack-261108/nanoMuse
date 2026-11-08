package io.github.nanomuse.shell

import org.junit.Assert.*
import org.junit.Test

class DisplayAudioLeaseTest {
    @Test fun keptTaskIsMutedAgainOnTheNextDisplay() {
        val events = mutableListOf<String>()
        val audio = DisplayAudioLease({ events += "mute $it"; true }, { events += "restore $it"; true })
        assertTrue(audio.acquire(1, "app"))
        assertTrue(audio.release(1))
        assertTrue(audio.acquire(2, "app"))
        assertEquals(listOf("mute app", "restore app", "mute app"), events)
    }

    @Test fun endingOneDisplayDoesNotUnmuteAnother() {
        val restored = mutableListOf<String>()
        val audio = DisplayAudioLease({ true }, { restored += it; true })
        assertTrue(audio.acquire(1, "shared"))
        assertTrue(audio.acquire(2, "shared"))
        assertTrue(audio.acquire(2, "other"))
        assertTrue(audio.release(1))
        assertTrue(restored.isEmpty())
        assertEquals(setOf("shared", "other"), audio.activeKeys())
        assertTrue(audio.release(2))
        assertEquals(listOf("shared", "other"), restored)
    }

    @Test fun launchFailureRollsBackOnlyNewAudioChanges() {
        val restored = mutableListOf<String>()
        val audio = DisplayAudioLease({ true }, { restored += it; true })
        audio.acquire(1, "existing")
        val before = audio.keys(1)
        audio.acquire(1, "failedLaunch")
        assertTrue(audio.release(1, audio.keys(1) - before))
        assertEquals(listOf("failedLaunch"), restored)
        assertEquals(setOf("existing"), audio.keys(1))
    }

    @Test fun failedRestoreKeepsOwnershipForRetry() {
        var canRestore = false
        val audio = DisplayAudioLease({ true }, { canRestore })
        audio.acquire(1, "app")
        assertFalse(audio.release(1))
        assertEquals(setOf(1), audio.displays())
        assertEquals(setOf("app"), audio.activeKeys())
        canRestore = true
        assertTrue(audio.release(1))
        assertTrue(audio.displays().isEmpty())
    }

    @Test fun failedMuteDoesNotCreateAnOwner() {
        val audio = DisplayAudioLease({ false }, { error("no restore without a lease") })
        assertFalse(audio.acquire(1, "app"))
        assertTrue(audio.keys(1).isEmpty())
    }

    @Test fun unfinishedTaskRemainsMutedWhileOtherAppsAreRestored() {
        val restored = mutableListOf<String>()
        val audio = DisplayAudioLease({ true }, { restored += it; true })
        audio.acquire(1, "unfinished")
        audio.acquire(1, "finished")
        assertTrue(audio.release(1, audio.keys(1) - "unfinished"))
        assertEquals(listOf("finished"), restored)
        assertEquals(setOf("unfinished"), audio.activeKeys())
    }
}
