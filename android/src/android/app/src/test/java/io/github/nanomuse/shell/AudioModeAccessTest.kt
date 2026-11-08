package io.github.nanomuse.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioModeAccessTest {
    @Test fun successfulExitWithoutAnAppliedMuteIsRejected() {
        val commands = mutableListOf<Triple<String, String, String?>>()
        val audio = AudioModeAccess { operation, key, mode ->
            commands += Triple(operation, key, mode)
            0 to if (operation == "get") "No operations.\nDefault mode: allow" else ""
        }
        assertFalse(audio.write("fixture", "ignore"))
        assertEquals(
            listOf(Triple("set", "fixture", "ignore"), Triple("get", "fixture", null)),
            commands,
        )
    }

    @Test fun appliedMuteAndRestoredDefaultAreReadBack() {
        var current = "allow"
        val audio = AudioModeAccess { operation, _, mode ->
            if (operation == "set") {
                current = mode!!
                0 to ""
            } else {
                0 to if (current == "allow") "No operations.\nDefault mode: allow" else "PLAY_AUDIO: $current"
            }
        }
        val original = audio.read("fixture")!!
        assertEquals("allow", original)
        assertTrue(audio.write("fixture", "ignore"))
        assertEquals("ignore", audio.read("fixture"))
        assertTrue(audio.write("fixture", original))
        assertEquals(original, audio.read("fixture"))
    }

    @Test fun failedReadbackCannotConfirmAMuteOrRestore() {
        val audio = AudioModeAccess { operation, _, _ ->
            if (operation == "set") 0 to "" else -1 to "PLAY_AUDIO: ignore"
        }
        assertFalse(audio.write("fixture", "ignore"))
        assertNull(audio.read("fixture"))
    }

    @Test fun explicitDefaultIsRestoredWithoutChangingItToAllow() {
        var current = "default"
        val audio = AudioModeAccess { operation, _, mode ->
            if (operation == "set") {
                current = mode!!
                0 to ""
            } else 0 to "PLAY_AUDIO: $current"
        }
        val original = audio.read("fixture")!!
        assertEquals("default", original)
        assertTrue(audio.write("fixture", "ignore"))
        assertTrue(audio.write("fixture", AudioMuteLedger.restoreMode(original)))
        assertEquals("default", current)
    }

    @Test fun allowReadbackCannotConfirmAnExplicitDefaultRestore() {
        val ledger = AudioMuteLedger(null)
        ledger.put("fixture", "default")
        val audio = AudioModeAccess { operation, _, _ ->
            0 to if (operation == "get") "No operations.\nDefault mode: allow" else ""
        }
        assertFalse(ledger.restoreAll(audio::write))
        assertEquals("default", ledger["fixture"])
    }

    @Test fun failedSetDoesNotBecomeSuccessBecauseThePriorModeMatched() {
        val operations = mutableListOf<String>()
        val audio = AudioModeAccess { operation, _, _ ->
            operations += operation
            if (operation == "set") -1 to "" else 0 to "PLAY_AUDIO: ignore"
        }
        assertFalse(audio.write("fixture", "ignore"))
        assertEquals(listOf("set"), operations)
    }

    @Test fun unknownSuccessfulOutputIsUnreadable() {
        val audio = AudioModeAccess { _, _, _ -> 0 to "vendor-specific output" }
        assertNull(audio.read("fixture"))
        assertFalse(audio.write("fixture", "ignore"))
    }
}
