package io.github.nanomuse.sysfiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiskFreshnessTest {

    @Test
    fun `fileStamp distinguishes missing, regular file, size change, and timestamp change`() {
        val dir = Files.createTempDirectory("nm-disk-freshness-file").toFile()
        try {
            val target = File(dir, "servers.json")
            val missing = DiskFreshness.fileStamp(target)
            assertEquals(FileStamp(exists = false, length = -1L, lastModifiedMs = -1L), missing)

            target.writeText("""{"mcpServers":{}}""")
            target.setLastModified(1_700_000_000_000L)
            val first = DiskFreshness.fileStamp(target)
            assertNotEquals(missing, first)
            assertEquals(first, DiskFreshness.fileStamp(target))

            target.writeText("""{"mcpServers":{"demo":{"command":"npx"}}}""")
            target.setLastModified(1_700_000_000_000L)
            val resized = DiskFreshness.fileStamp(target)
            assertNotEquals(first, resized)

            target.writeText("""{"mcpServers":{"DEMO":{"command":"npx"}}}""")
            target.setLastModified(1_700_000_005_000L)
            val touched = DiskFreshness.fileStamp(target)
            assertNotEquals(resized, touched)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `skillsDirStamp stays stable when unchanged and changes on external add, edit, or delete`() {
        val skillsDir = Files.createTempDirectory("nm-disk-freshness-skills").toFile()
        try {
            val empty = DiskFreshness.skillsDirStamp(skillsDir)
            assertEquals(emptyList<SkillDirEntryStamp>(), empty)

            val alphaDir = File(skillsDir, "alpha").apply { mkdirs() }
            val alphaMd = File(alphaDir, "SKILL.md").apply {
                writeText("---\nname: alpha\ndescription: first\n---\nBody")
                setLastModified(1_700_000_000_000L)
            }
            val snap1 = DiskFreshness.skillsDirStamp(skillsDir)
            assertEquals(snap1, DiskFreshness.skillsDirStamp(skillsDir))

            // Editing SKILL.md out-of-band alters the snapshot.
            alphaMd.writeText("---\nname: alpha\ndescription: updated description\n---\nBody")
            alphaMd.setLastModified(1_700_000_010_000L)
            val snap2 = DiskFreshness.skillsDirStamp(skillsDir)
            assertNotEquals(snap1, snap2)

            // Out-of-band git clone / mkdir + SKILL.md adds an entry.
            val betaDir = File(skillsDir, "beta").apply { mkdirs() }
            File(betaDir, "SKILL.md").apply {
                writeText("---\nname: beta\ndescription: second\n---\n")
                setLastModified(1_700_000_020_000L)
            }
            val snap3 = DiskFreshness.skillsDirStamp(skillsDir)
            assertNotEquals(snap2, snap3)
            assertEquals(listOf("alpha", "beta"), snap3.map { it.id })

            // Deleting a skill directory out-of-band changes the snapshot again.
            alphaDir.deleteRecursively()
            val snap4 = DiskFreshness.skillsDirStamp(skillsDir)
            assertNotEquals(snap3, snap4)
            assertEquals(listOf("beta"), snap4.map { it.id })
        } finally {
            skillsDir.deleteRecursively()
        }
    }

    @Test
    fun `fileStamps preserves order across missing and populated daily logs`() {
        val dir = Files.createTempDirectory("nm-disk-freshness-daily").toFile()
        try {
            val day1 = File(dir, "2026-11-20.md").apply {
                writeText("hello")
                setLastModified(1_700_000_000_000L)
            }
            val day2 = File(dir, "2026-11-19.md")
            val first = DiskFreshness.fileStamps(listOf(day1, day2))
            assertEquals(first, DiskFreshness.fileStamps(listOf(day1, day2)))

            day2.writeText("added entry")
            day2.setLastModified(1_700_000_001_000L)
            val second = DiskFreshness.fileStamps(listOf(day1, day2))
            assertNotEquals(first, second)
        } finally {
            dir.deleteRecursively()
        }
    }
}
