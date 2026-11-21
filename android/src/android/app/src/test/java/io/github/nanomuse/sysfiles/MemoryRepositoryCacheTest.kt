package io.github.nanomuse.sysfiles

import com.openminis.app.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryRepositoryCacheTest {

    @Test
    fun `global memory cache invalidates on saveGlobalMd and saveFile even with same length and timestamp`() {
        val dir = Files.createTempDirectory("nm-mem-global").toFile()
        try {
            val repo = MemoryRepository(dir)
            assertNull(repo.loadGlobalMemoryFragment())

            val globalFile = File(dir, "GLOBAL.md")
            repo.saveGlobalMd("alpha")
            val fixedMtime = 1_700_000_000_000L
            globalFile.setLastModified(fixedMtime)
            val first = repo.loadGlobalMemoryFragment()
            assertNotNull(first)
            assertTrue(first!!.contains("alpha"))
            assertEquals(2, repo.globalDiskReadsForTest)

            // Repeated call with unchanged stamp must hit cache without reading disk again.
            val hit = repo.loadGlobalMemoryFragment()
            assertEquals(first, hit)
            assertEquals(2, repo.globalDiskReadsForTest)

            // Overwrite with identical byte length and reset mtime to the exact same millisecond:
            // saveGlobalMd must still invalidate the prompt cache.
            repo.saveGlobalMd("bravo")
            globalFile.setLastModified(fixedMtime)
            val second = repo.loadGlobalMemoryFragment()
            assertNotNull(second)
            assertTrue(second!!.contains("bravo"))
            assertFalse(second.contains("alpha"))
            assertEquals(3, repo.globalDiskReadsForTest)

            // saveFile("GLOBAL.md", ...) must likewise invalidate even with identical length & mtime.
            repo.saveFile("GLOBAL.md", "delta")
            globalFile.setLastModified(fixedMtime)
            val third = repo.loadGlobalMemoryFragment()
            assertNotNull(third)
            assertTrue(third!!.contains("delta"))
            assertFalse(third.contains("bravo"))
            assertEquals(4, repo.globalDiskReadsForTest)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `global memory cache refreshes when external writer changes mtime`() {
        val dir = Files.createTempDirectory("nm-mem-global-ext").toFile()
        try {
            val repo = MemoryRepository(dir)
            val globalFile = File(dir, "GLOBAL.md")
            globalFile.writeText("first")
            globalFile.setLastModified(1_700_000_000_000L)

            val initial = repo.loadGlobalMemoryFragment()
            assertNotNull(initial)
            assertTrue(initial!!.contains("first"))
            assertEquals(1, repo.globalDiskReadsForTest)

            // External rewrite with identical length and identical mtime hits cache (proving no disk read).
            globalFile.writeText("secnd")
            globalFile.setLastModified(1_700_000_000_000L)
            assertEquals(initial, repo.loadGlobalMemoryFragment())
            assertEquals(1, repo.globalDiskReadsForTest)

            // Newer mtime written externally out-of-band invalidates and refreshes.
            globalFile.setLastModified(1_700_000_005_000L)

            val refreshed = repo.loadGlobalMemoryFragment()
            assertNotNull(refreshed)
            assertTrue(refreshed!!.contains("secnd"))
            assertFalse(refreshed.contains("first"))
            assertEquals(2, repo.globalDiskReadsForTest)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `daily memory cache invalidates on internal writes and refreshes on external mtime change`() {
        val dir = Files.createTempDirectory("nm-mem-daily").toFile()
        try {
            val repo = MemoryRepository(dir)
            assertNull(repo.loadRecentDailyMemoryFragment())

            val todayName = "${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.md"
            val todayFile = File(dir, todayName)

            repo.writeMemory("remember alpha")
            val first = repo.loadRecentDailyMemoryFragment()
            assertNotNull(first)
            assertTrue(first!!.contains("remember alpha"))
            assertEquals(2, repo.dailyDiskReadsForTest)

            // Repeated call with unchanged stamps hits daily cache without re-reading disk.
            assertEquals(first, repo.loadRecentDailyMemoryFragment())
            assertEquals(2, repo.dailyDiskReadsForTest)

            val fixedMtime = 1_700_000_000_000L
            // Replace entry body with same length and force identical mtime.
            todayFile.setLastModified(fixedMtime)
            assertNotNull(repo.loadRecentDailyMemoryFragment())
            val replaced = repo.replaceEntryBody("remember alpha", "remember bravo")
            assertTrue(replaced is MemoryRepository.EntryMutationResult.Success)
            todayFile.setLastModified(fixedMtime)

            val afterReplace = repo.loadRecentDailyMemoryFragment()
            assertNotNull(afterReplace)
            assertTrue(afterReplace!!.contains("remember bravo"))
            assertFalse(afterReplace.contains("remember alpha"))

            // External overwrite with changed mtime refreshes cache.
            todayFile.writeText("<!-- 2026-11-21 00:00:00 -->\nexternal entry\n\n")
            todayFile.setLastModified(1_700_000_010_000L)
            val afterExternal = repo.loadRecentDailyMemoryFragment()
            assertNotNull(afterExternal)
            assertTrue(afterExternal!!.contains("external entry"))

            // Revoke and delete invalidate cache.
            val revoked = repo.revokeEntry("external entry")
            assertTrue(revoked is MemoryRepository.EntryMutationResult.Success)
            assertNull(repo.loadRecentDailyMemoryFragment())

            repo.saveFile(todayName, "temp note")
            assertTrue(repo.loadRecentDailyMemoryFragment()!!.contains("temp note"))
            assertTrue(repo.deleteFile(todayName))
            assertNull(repo.loadRecentDailyMemoryFragment())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `transient read failure does not cache empty global or daily fragment`() {
        val dir = Files.createTempDirectory("nm-mem-readfail").toFile()
        try {
            val repo = MemoryRepository(dir)
            val globalFile = File(dir, "GLOBAL.md").apply {
                writeText("persisted global")
                setLastModified(1_700_000_000_000L)
            }
            val todayName = "${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.md"
            val todayFile = File(dir, todayName).apply {
                writeText("persisted daily")
                setLastModified(1_700_000_000_000L)
            }

            if (globalFile.setReadable(false) && !globalFile.canRead()) {
                assertNull(repo.loadGlobalMemoryFragment())
                assertEquals(1, repo.globalDiskReadsForTest)
                globalFile.setReadable(true)
                // Same mtime and length: must NOT hit a cached null from the failed read.
                val recovered = repo.loadGlobalMemoryFragment()
                assertNotNull(recovered)
                assertTrue(recovered!!.contains("persisted global"))
                assertEquals(2, repo.globalDiskReadsForTest)
            }

            if (todayFile.setReadable(false) && !todayFile.canRead()) {
                assertNull(repo.loadRecentDailyMemoryFragment())
                assertEquals(1, repo.dailyDiskReadsForTest)
                todayFile.setReadable(true)
                val recovered = repo.loadRecentDailyMemoryFragment()
                assertNotNull(recovered)
                assertTrue(recovered!!.contains("persisted daily"))
                assertEquals(2, repo.dailyDiskReadsForTest)
            }
        } finally {
            dir.listFiles()?.forEach { it.setReadable(true) }
            dir.deleteRecursively()
        }
    }

    @Test
    fun `failed write still invalidates global and daily prompt caches`() {
        val dir = Files.createTempDirectory("nm-mem-writefail").toFile()
        try {
            val repo = MemoryRepository(dir)
            val fixedMtime = 1_700_000_000_000L

            repo.saveFile("GLOBAL.md", "initial")
            val globalFile = File(dir, "GLOBAL.md").apply { setLastModified(fixedMtime) }
            assertNotNull(repo.loadGlobalMemoryFragment())
            val initialGlobalReads = repo.globalDiskReadsForTest

            if (globalFile.setWritable(false) && !globalFile.canWrite()) {
                assertTrue(runCatching { repo.saveFile("GLOBAL.md", "updated") }.isFailure)
                globalFile.setWritable(true)
                globalFile.setLastModified(fixedMtime)
                assertNotNull(repo.loadGlobalMemoryFragment())
                assertEquals(initialGlobalReads + 1, repo.globalDiskReadsForTest)
            }

            val todayName = "${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.md"
            repo.writeMemory("daily initial")
            val todayFile = File(dir, todayName).apply { setLastModified(fixedMtime) }
            assertNotNull(repo.loadRecentDailyMemoryFragment())
            var expectedDailyReads = repo.dailyDiskReadsForTest

            if (todayFile.setWritable(false) && !todayFile.canWrite()) {
                assertTrue(repo.writeMemory("daily failed").startsWith("Error"))
                todayFile.setWritable(true)
                todayFile.setLastModified(fixedMtime)
                assertNotNull(repo.loadRecentDailyMemoryFragment())
                expectedDailyReads += 1
                assertEquals(expectedDailyReads, repo.dailyDiskReadsForTest)

                todayFile.setWritable(false)
                assertTrue(repo.revokeEntry("daily initial") is MemoryRepository.EntryMutationResult.IOError)
                todayFile.setWritable(true)
                todayFile.setLastModified(fixedMtime)
                assertNotNull(repo.loadRecentDailyMemoryFragment())
                expectedDailyReads += 1
                assertEquals(expectedDailyReads, repo.dailyDiskReadsForTest)

                todayFile.setWritable(false)
                assertTrue(repo.replaceEntryBody("daily initial", "daily edited") is MemoryRepository.EntryMutationResult.IOError)
                todayFile.setWritable(true)
                todayFile.setLastModified(fixedMtime)
                assertNotNull(repo.loadRecentDailyMemoryFragment())
                expectedDailyReads += 1
                assertEquals(expectedDailyReads, repo.dailyDiskReadsForTest)
            }
        } finally {
            dir.listFiles()?.forEach {
                it.setReadable(true)
                it.setWritable(true)
            }
            dir.deleteRecursively()
        }
    }
}
