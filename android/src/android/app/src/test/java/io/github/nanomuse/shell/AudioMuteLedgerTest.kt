package io.github.nanomuse.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioMuteLedgerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAppAudioKey() {
        assertEquals("com.example.app", AppAudioKey.of("com.example.app", null))
        assertEquals("com.example.app@10", AppAudioKey.of("com.example.app", 10))

        val parsed1 = AppAudioKey.parse("com.example.app")
        assertEquals("com.example.app", parsed1?.first)
        assertNull(parsed1?.second)

        val parsed2 = AppAudioKey.parse("com.example.app@10")
        assertEquals("com.example.app", parsed2?.first)
        assertEquals(10, parsed2?.second)
    }

    @Test
    fun testParseSetMode() {
        val out1 = "PLAY_AUDIO: ignore; time=+1d2h"
        assertEquals("ignore", AudioMuteLedger.parseSetMode(out1))

        val out2 = "No operations."
        assertNull(AudioMuteLedger.parseSetMode(out2))
    }

    @Test
    fun unsetAudioUsesItsReportedAllowMode() {
        assertEquals("allow", AudioMuteLedger.parseMode("No operations.\nDefault mode: allow"))
        assertEquals("default", AudioMuteLedger.parseMode("PLAY_AUDIO: default"))
        assertEquals("ignore", AudioMuteLedger.parseMode("  PLAY_AUDIO: ignore; time=+2s"))
        assertEquals("deny", AudioMuteLedger.parseMode("android:play_audio: deny"))
    }

    @Test
    fun unknownOutputMustNotBecomeAUsableMode() {
        assertNull(AudioMuteLedger.parseMode(""))
        assertNull(AudioMuteLedger.parseMode("No operations."))
        assertNull(AudioMuteLedger.parseMode("Default mode: allow"))
        assertNull(AudioMuteLedger.parseMode("PLAY_AUDIO: vendor_value"))
        assertNull(AudioMuteLedger.parseMode("RECORD_AUDIO: allow"))
        assertNull(AudioMuteLedger.parseMode("Error: package not found"))
    }

    @Test
    fun testRestoreMode() {
        assertEquals("default", AudioMuteLedger.restoreMode(null))
        assertEquals("default", AudioMuteLedger.restoreMode("default"))
        assertEquals("ignore", AudioMuteLedger.restoreMode("ignore"))
        assertEquals("allow", AudioMuteLedger.restoreMode("allow"))
    }

    @Test
    fun testPersistenceAndRestoreAll() {
        val file = tempFolder.newFile("mute_ledger")
        val ledger = AudioMuteLedger(file)

        ledger.put("com.pkg.a", "allow")
        ledger.put("com.pkg.b@10", "default")
        assertTrue(ledger.contains("com.pkg.a"))
        assertTrue(ledger.contains("com.pkg.b@10"))

        // Re-read from file
        val ledger2 = AudioMuteLedger(file)
        assertTrue(ledger2.contains("com.pkg.a"))
        assertTrue(ledger2.contains("com.pkg.b@10"))

        val restored = mutableListOf<Pair<String, String>>()
        val ok = ledger2.restoreAll { pkg, mode ->
            restored.add(pkg to mode)
            true
        }
        assertTrue(ok)
        assertEquals(2, restored.size)
        assertTrue(restored.contains("com.pkg.a" to "allow"))
        assertTrue(restored.contains("com.pkg.b@10" to "default"))
        assertFalse(ledger2.contains("com.pkg.a"))
    }

    @Test
    fun failedRestoreIsRetainedAndOtherEntriesAreAttempted() {
        val file = tempFolder.newFile("pending_audio")
        val ledger = AudioMuteLedger(file)
        ledger.put("com.pkg.a", "default")
        ledger.put("com.pkg.b", "allow")
        val attempted = mutableListOf<String>()
        assertFalse(ledger.restoreAll { key, _ -> attempted += key; key == "com.pkg.b" })
        assertEquals(listOf("com.pkg.a", "com.pkg.b"), attempted)
        assertEquals(mapOf("com.pkg.a" to "default"), AudioMuteLedger.parse(file.readText()))
    }

    @Test
    fun failedPersistenceDoesNotAcceptAChange() {
        val notDirectory = tempFolder.newFile("not_a_directory")
        val ledger = AudioMuteLedger(java.io.File(notDirectory, "ledger"))
        assertFalse(ledger.put("com.pkg.a", "default"))
        assertFalse(ledger.contains("com.pkg.a"))
    }
}
