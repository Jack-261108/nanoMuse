package io.github.nanomuse.sysfiles

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.openminis.app.data.repository.SkillRepository
import io.github.nanomuse.skills.PendingSkillToggle
import io.github.nanomuse.skills.PendingSkillToggles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import java.io.File
import java.nio.file.Files
import java.util.Collections

class SkillRepositoryCacheTest {

    private data class SkillRow(
        val id: String,
        var name: String,
        var description: String,
        var version: String,
        var importSource: String,
        var sourceUrl: String?,
        var isEnabled: Int,
        val installedAt: Long,
        var updatedAt: Long,
        var useCount: Double = 0.0,
    )

    private class InMemorySkillDb {
        val rows = linkedMapOf<String, SkillRow>()

        private val columns = listOf(
            "id", "name", "description", "version", "import_source",
            "source_url", "is_enabled", "installed_at", "updated_at", "use_count",
        )

        fun asMockDb(): SQLiteDatabase {
            val db = Mockito.mock(SQLiteDatabase::class.java)
            Mockito.lenient().`when`(db.rawQuery(anyString(), Mockito.nullable(Array<String>::class.java)))
                .thenAnswer {
                    val snapshot = synchronized(rows) {
                        rows.values.sortedByDescending { it.installedAt }.map { it.copy() }
                    }
                    buildCursor(snapshot)
                }
            Mockito.lenient().`when`(db.execSQL(anyString(), any())).thenAnswer { inv ->
                val sql = inv.getArgument<String>(0).trim()
                val args = inv.getArgument<Array<Any?>>(1)
                exec(sql, args)
                null
            }
            return db
        }

        private fun exec(sql: String, args: Array<Any?>) {
            synchronized(rows) {
                when {
                    sql.startsWith("INSERT OR REPLACE INTO skills") -> {
                        val row = SkillRow(
                            id = args[0] as String,
                            name = args[1] as String,
                            description = args[2] as String,
                            version = args[3] as String,
                            importSource = args[4] as String,
                            sourceUrl = args[5] as String?,
                            isEnabled = (args[6] as Number).toInt(),
                            installedAt = (args[7] as Number).toLong(),
                            updatedAt = (args[8] as Number).toLong(),
                        )
                        rows[row.id] = row
                    }
                    sql.startsWith("UPDATE skills SET is_enabled=? WHERE id=?") -> {
                        rows[args[1] as String]?.isEnabled = (args[0] as Number).toInt()
                    }
                    sql.startsWith("UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?") -> {
                        rows[args[4] as String]?.let {
                            it.name = args[0] as String
                            it.description = args[1] as String
                            it.version = args[2] as String
                            it.updatedAt = (args[3] as Number).toLong()
                        }
                    }
                    sql.startsWith("UPDATE skills SET name=?, description=?, version=?, import_source=?, source_url=?, updated_at=? WHERE id=?") -> {
                        rows[args[6] as String]?.let {
                            it.name = args[0] as String
                            it.description = args[1] as String
                            it.version = args[2] as String
                            it.importSource = args[3] as String
                            it.sourceUrl = args[4] as String?
                            it.updatedAt = (args[5] as Number).toLong()
                        }
                    }
                    sql.startsWith("UPDATE skills SET name=?, updated_at=? WHERE id=?") -> {
                        rows[args[2] as String]?.let {
                            it.name = args[0] as String
                            it.updatedAt = (args[1] as Number).toLong()
                        }
                    }
                    sql.startsWith("DELETE FROM skills WHERE id=?") -> {
                        rows.remove(args[0] as String)
                    }
                }
            }
        }

        private fun buildCursor(snapshot: List<SkillRow>): Cursor {
            val cursor = Mockito.mock(Cursor::class.java)
            var index = -1
            Mockito.lenient().`when`(cursor.moveToNext()).thenAnswer {
                index++
                index < snapshot.size
            }
            Mockito.lenient().`when`(cursor.getColumnIndex(anyString())).thenAnswer { inv ->
                columns.indexOf(inv.getArgument<String>(0))
            }
            Mockito.lenient().`when`(cursor.getColumnIndexOrThrow(anyString())).thenAnswer { inv ->
                val name = inv.getArgument<String>(0)
                val idx = columns.indexOf(name)
                require(idx >= 0) { "Missing column $name" }
                idx
            }
            Mockito.lenient().`when`(cursor.isNull(Mockito.anyInt())).thenAnswer { inv ->
                val row = snapshot[index]
                inv.getArgument<Int>(0) == 5 && row.sourceUrl == null
            }
            Mockito.lenient().`when`(cursor.getString(Mockito.anyInt())).thenAnswer { inv ->
                val row = snapshot[index]
                when (inv.getArgument<Int>(0)) {
                    0 -> row.id
                    1 -> row.name
                    2 -> row.description
                    3 -> row.version
                    4 -> row.importSource
                    5 -> row.sourceUrl
                    else -> ""
                }
            }
            Mockito.lenient().`when`(cursor.getInt(Mockito.anyInt())).thenAnswer { inv ->
                val row = snapshot[index]
                when (inv.getArgument<Int>(0)) {
                    6 -> row.isEnabled
                    else -> 0
                }
            }
            Mockito.lenient().`when`(cursor.getLong(Mockito.anyInt())).thenAnswer { inv ->
                val row = snapshot[index]
                when (inv.getArgument<Int>(0)) {
                    7 -> row.installedAt
                    8 -> row.updatedAt
                    else -> 0L
                }
            }
            Mockito.lenient().`when`(cursor.getDouble(Mockito.anyInt())).thenAnswer { inv ->
                val row = snapshot[index]
                when (inv.getArgument<Int>(0)) {
                    9 -> row.useCount
                    else -> 0.0
                }
            }
            return cursor
        }
    }

    private fun mockContext(filesRoot: File): Context =
        Mockito.mock(Context::class.java).also { ctx ->
            Mockito.`when`(ctx.filesDir).thenReturn(filesRoot)
        }

    @Test
    fun `reloadFromDisk hits cache when unchanged and targeted stamp keeps pending external skills`() {
        val root = Files.createTempDirectory("nm-skill-cache").toFile()
        try {
            val skillsDir = File(root, "minis-global/skills").apply { mkdirs() }
            val fakeDb = InMemorySkillDb()
            val repo = SkillRepository(mockContext(root), fakeDb.asMockDb())
            assertEquals(1, repo.loadAllRunsForTest)

            // Unchanged directory -> cache hit.
            repo.reloadFromDisk()
            repo.reloadFromDisk()
            assertEquals(1, repo.loadAllRunsForTest)

            // Internal add keeps stamp aligned without absorbing other dirs.
            val alpha = repo.add("alpha", "First skill", "Body A")
            assertNotNull(alpha)
            repo.reloadFromDisk()
            assertEquals(1, repo.loadAllRunsForTest)

            // Drop a new skill externally, then modify alpha internally before reloading:
            // alpha's stamp update must NOT swallow beta.
            val betaDir = File(skillsDir, "beta").apply { mkdirs() }
            File(betaDir, "SKILL.md").apply {
                writeText("---\nname: beta\ndescription: External skill\n---\nBody B\n")
                setLastModified(1_700_000_005_000L)
            }
            assertTrue(repo.update("alpha", description = "Updated alpha"))
            assertEquals(1, repo.loadAllRunsForTest)

            // Next reloadFromDisk must detect beta and load it.
            repo.reloadFromDisk()
            assertEquals(2, repo.loadAllRunsForTest)
            assertTrue(repo.skills.value.any { it.id == "beta" })

            // Subsequent reloadFromDisk with no changes hits cache again.
            repo.reloadFromDisk()
            assertEquals(2, repo.loadAllRunsForTest)

            // External mtime + content edit to beta invalidates cache and refreshes.
            val betaMd = File(betaDir, "SKILL.md")
            betaMd.writeText("---\nname: beta\ndescription: External skill\n---\nUpdated Body B\n")
            betaMd.setLastModified(1_700_000_010_000L)
            repo.reloadFromDisk()
            assertEquals(3, repo.loadAllRunsForTest)
            assertEquals("Updated Body B", repo.skills.value.first { it.id == "beta" }.body.trim())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent setEnabled during loadAll is not overwritten by stale DB snapshot`() {
        val root = Files.createTempDirectory("nm-skill-race").toFile()
        try {
            val skillsDir = File(root, "minis-global/skills").apply { mkdirs() }
            val fakeDb = InMemorySkillDb()
            val repo = SkillRepository(mockContext(root), fakeDb.asMockDb())
            val alpha = repo.add("alpha", "First skill", "Body A")
            assertNotNull(alpha)
            assertTrue(repo.skills.value.first { it.id == "alpha" }.isEnabled)

            // Trigger reload via an external skill addition, and flip setEnabled("alpha", false)
            // right before loadAll() commits its snapshot.
            val gammaDir = File(skillsDir, "gamma").apply { mkdirs() }
            File(gammaDir, "SKILL.md").writeText("---\nname: gamma\ndescription: Third\n---\nBody C\n")
            var flipped = false
            repo.onBeforeLoadCommitForTest = {
                if (!flipped) {
                    flipped = true
                    repo.setEnabled("alpha", false)
                }
            }

            repo.reloadFromDisk()
            assertTrue(flipped)
            assertFalse(repo.skills.value.first { it.id == "alpha" }.isEnabled)
            assertTrue(repo.skills.value.any { it.id == "gamma" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `writeQueue preserves FIFO order and survives failed actions without dropping later writes`() = runBlocking {
        val root = Files.createTempDirectory("nm-skill-writequeue").toFile()
        try {
            val fakeDb = InMemorySkillDb()
            val repo = SkillRepository(mockContext(root), fakeDb.asMockDb())
            val alpha = repo.add("alpha", "First skill", "Body A")
            assertNotNull(alpha)

            val order = Collections.synchronizedList(mutableListOf<String>())
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val blocker = repo.enqueueWrite {
                kotlinx.coroutines.runBlocking { gate.await() }
                order.add("blocker")
            }
            val first = repo.setEnabledAsync("alpha", false)
            val failing = repo.enqueueWrite {
                order.add("failing")
                throw IllegalStateException("simulated write failure")
            }
            val second = repo.setEnabledAsync("alpha", true)
            val deleted = repo.deleteAsync("alpha")

            gate.complete(Unit)
            blocker.await()
            first.await()

            val failureResult = runCatching { failing.await() }
            assertTrue(failureResult.isFailure)
            assertEquals("simulated write failure", failureResult.exceptionOrNull()?.message)

            // Consumer must remain alive after failure and execute subsequent FIFO writes.
            second.await()
            deleted.await()

            assertEquals(listOf("blocker", "failing"), order)
            assertFalse(repo.skills.value.any { it.id == "alpha" })
            assertFalse(File(root, "minis-global/skills/alpha").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `pendingToggles sequence logic keeps latest tap and rolls back only latest failure`() {
        val pending = mutableMapOf<String, PendingSkillToggle>()
        val skillId = "alpha"

        // Initial persisted state is enabled=true. Rapid double-tap: true -> false -> true.
        assertTrue(PendingSkillToggles.displayEnabled(pending, skillId, persistedEnabled = true))

        val seq1 = PendingSkillToggles.beginToggle(pending, skillId, enabled = false)
        assertEquals(1, seq1)
        assertFalse(PendingSkillToggles.displayEnabled(pending, skillId, persistedEnabled = true))

        val seq2 = PendingSkillToggles.beginToggle(pending, skillId, enabled = true)
        assertEquals(2, seq2)
        assertTrue(PendingSkillToggles.displayEnabled(pending, skillId, persistedEnabled = true))

        // First toggle finishes while second is still in flight: stale seq1 must NOT clear seq2.
        assertFalse(PendingSkillToggles.finishToggle(pending, skillId, seq1))
        assertTrue(PendingSkillToggles.displayEnabled(pending, skillId, persistedEnabled = false))

        // Second toggle fails (persisted state remained false): seq2 clears and rolls back to persistedEnabled.
        assertTrue(PendingSkillToggles.finishToggle(pending, skillId, seq2))
        assertFalse(PendingSkillToggles.displayEnabled(pending, skillId, persistedEnabled = false))
    }

    @Test
    fun `writeQueue handles CancellationException and closed-channel trySend failure`() = runBlocking {
        val root = Files.createTempDirectory("nm-skill-cancel-close").toFile()
        try {
            val fakeDb = InMemorySkillDb()
            val repo = SkillRepository(mockContext(root), fakeDb.asMockDb())

            // CancellationException inside a queued action propagates to its Deferred, closes the queue,
            // drains remaining buffered items with IllegalStateException (shutdown error), and rejects later enqueues.
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val blocker = repo.enqueueWrite {
                kotlinx.coroutines.runBlocking { gate.await() }
            }
            val cancelled = repo.enqueueWrite {
                throw CancellationException("job cancelled")
            }
            val bufferedAfterCancel = repo.setEnabledAsync("alpha", true)
            gate.complete(Unit)
            blocker.await()

            val cancelResult = runCatching { cancelled.await() }
            assertTrue(cancelResult.isFailure)
            assertTrue(cancelResult.exceptionOrNull() is CancellationException)

            val drainedResult = runCatching { bufferedAfterCancel.await() }
            assertTrue(drainedResult.isFailure)
            assertTrue(drainedResult.exceptionOrNull() is IllegalStateException)

            // Queue shutdown must NOT be misclassified as caller cancellation by PendingSkillToggles.
            val pending = mutableMapOf<String, PendingSkillToggle>()
            val seqShutdown = PendingSkillToggles.beginToggle(pending, "alpha", enabled = true)
            val shutdownOutcome = PendingSkillToggles.awaitToggleOutcome(pending, "alpha", seqShutdown) {
                bufferedAfterCancel.await()
            }
            assertTrue(shutdownOutcome.rolledBack)
            assertTrue(shutdownOutcome.shouldNotifyFailure)
            assertTrue(shutdownOutcome.error is IllegalStateException)

            // Subsequent enqueues fail immediately with IllegalStateException instead of hanging on a dead consumer.
            val rejectedAfterCancel = repo.deleteAsync("alpha")
            val rejectedErr = PendingSkillToggles.awaitDeleteError { rejectedAfterCancel.await() }
            assertTrue(rejectedErr is IllegalStateException)

            // trySend failure (closed queue) fails the returned Deferred immediately.
            repo.closeWriteQueueForTest()
            val rejectedSet = repo.setEnabledAsync("alpha", true)
            val rejectedDelete = repo.deleteAsync("alpha")
            assertTrue(runCatching { rejectedSet.await() }.isFailure)
            assertTrue(runCatching { rejectedDelete.await() }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `awaitToggleOutcome and awaitDeleteError rethrow cancellation and suppress stale failure notification`() = runBlocking {
        val pending = mutableMapOf<String, PendingSkillToggle>()
        val skillId = "alpha"

        // Stale seq1 failure must NOT notify or roll back newer seq2.
        val seq1 = PendingSkillToggles.beginToggle(pending, skillId, enabled = false)
        val seq2 = PendingSkillToggles.beginToggle(pending, skillId, enabled = true)
        val staleFailure = PendingSkillToggles.awaitToggleOutcome(pending, skillId, seq1) {
            throw IllegalStateException("stale failure")
        }
        assertFalse(staleFailure.rolledBack)
        assertFalse(staleFailure.shouldNotifyFailure)
        assertFalse(PendingSkillToggles.shouldNotifyToggleFailure(staleFailure, isScopeActive = true))
        assertTrue(PendingSkillToggles.displayEnabled(pending, skillId, persistedEnabled = false))

        // Latest seq2 failure rolls back and requests failure notification only while scope is active.
        val latestFailure = PendingSkillToggles.awaitToggleOutcome(pending, skillId, seq2) {
            throw IllegalStateException("latest failure")
        }
        assertTrue(latestFailure.rolledBack)
        assertTrue(latestFailure.shouldNotifyFailure)
        assertTrue(PendingSkillToggles.shouldNotifyToggleFailure(latestFailure, isScopeActive = true))
        assertFalse(PendingSkillToggles.shouldNotifyToggleFailure(latestFailure, isScopeActive = false))
        assertEquals("latest failure", latestFailure.error?.message)
        assertFalse(PendingSkillToggles.displayEnabled(pending, skillId, persistedEnabled = false))

        // Success path: latest seq removes pending entry and reports (rolledBack=false, shouldNotifyFailure=false).
        val seqSuccess = PendingSkillToggles.beginToggle(pending, skillId, enabled = true)
        val successOutcome = PendingSkillToggles.awaitToggleOutcome(pending, skillId, seqSuccess) { }
        assertFalse(successOutcome.rolledBack)
        assertFalse(successOutcome.shouldNotifyFailure)
        assertFalse(PendingSkillToggles.shouldNotifyToggleFailure(successOutcome, isScopeActive = true))
        assertEquals(null, successOutcome.error)
        assertFalse(pending.containsKey(skillId))
        assertTrue(PendingSkillToggles.displayEnabled(pending, skillId, persistedEnabled = true))

        // CancellationException is rethrown rather than converted to a UI error notification.
        val seq3 = PendingSkillToggles.beginToggle(pending, skillId, enabled = true)
        val toggleCancelled = runCatching {
            PendingSkillToggles.awaitToggleOutcome(pending, skillId, seq3) {
                throw CancellationException("left screen")
            }
        }
        assertTrue(toggleCancelled.exceptionOrNull() is CancellationException)

        assertEquals(null, PendingSkillToggles.awaitDeleteError { })
        assertEquals("disk full", PendingSkillToggles.awaitDeleteError { throw IllegalStateException("disk full") }?.message)
        val deleteCancelled = runCatching {
            PendingSkillToggles.awaitDeleteError { throw CancellationException("left screen") }
        }
        assertTrue(deleteCancelled.exceptionOrNull() is CancellationException)

        // Delete UI outcome wiring: active success pops once; double-pop and inactive suppress navigation/Toast.
        val okActive = PendingSkillToggles.resolveDeleteUiOutcome(error = null, isScopeActive = true, alreadyNavigatedBack = false)
        assertTrue(okActive.shouldNavigateBack)
        assertFalse(okActive.shouldNotifyFailure)
        assertFalse(okActive.restoreDeletedFlag)

        val okAlreadyPopped = PendingSkillToggles.resolveDeleteUiOutcome(error = null, isScopeActive = true, alreadyNavigatedBack = true)
        assertFalse(okAlreadyPopped.shouldNavigateBack)

        val okInactive = PendingSkillToggles.resolveDeleteUiOutcome(error = null, isScopeActive = false, alreadyNavigatedBack = false)
        assertFalse(okInactive.shouldNavigateBack)

        val errActive = PendingSkillToggles.resolveDeleteUiOutcome(error = IllegalStateException("io"), isScopeActive = true)
        assertFalse(errActive.shouldNavigateBack)
        assertTrue(errActive.shouldNotifyFailure)
        assertTrue(errActive.restoreDeletedFlag)

        val errAlreadyPopped = PendingSkillToggles.resolveDeleteUiOutcome(
            error = IllegalStateException("io"),
            isScopeActive = true,
            alreadyNavigatedBack = true,
        )
        assertFalse(errAlreadyPopped.shouldNavigateBack)
        assertFalse(errAlreadyPopped.shouldNotifyFailure)
        assertFalse(errAlreadyPopped.restoreDeletedFlag)
        assertEquals("io", errAlreadyPopped.error?.message)

        val errInactive = PendingSkillToggles.resolveDeleteUiOutcome(error = IllegalStateException("io"), isScopeActive = false)
        assertFalse(errInactive.shouldNavigateBack)
        assertFalse(errInactive.shouldNotifyFailure)
        assertTrue(errInactive.restoreDeletedFlag)
    }

    @Test
    fun `transient read failure during loadAll does not pin loadedDiskStamp`() {
        val root = Files.createTempDirectory("nm-skill-readfail").toFile()
        val skillsDir = File(root, "minis-global/skills").apply { mkdirs() }
        try {
            val fakeDb = InMemorySkillDb()
            val repo = SkillRepository(mockContext(root), fakeDb.asMockDb())
            assertEquals(1, repo.loadAllRunsForTest)

            val alpha = repo.add("alpha", "First skill", "Body Alpha")
            assertNotNull(alpha)
            val alphaMd = File(skillsDir, "alpha/SKILL.md")

            if (alphaMd.setReadable(false) && !alphaMd.canRead()) {
                alphaMd.setLastModified(1_700_000_001_000L)
                repo.reloadFromDisk()
                assertEquals(2, repo.loadAllRunsForTest)
                assertEquals("", repo.skills.value.first { it.id == "alpha" }.body)

                // Restoring readability with identical mtime must NOT hit a stale cache.
                alphaMd.setReadable(true)
                repo.reloadFromDisk()
                assertEquals(3, repo.loadAllRunsForTest)
                assertEquals("Body Alpha", repo.skills.value.first { it.id == "alpha" }.body.trim())
            }

            val betaDir = File(skillsDir, "beta").apply { mkdirs() }
            val betaMd = File(betaDir, "SKILL.md").apply {
                writeText("---\nname: beta\ndescription: Second skill\n---\nBody Beta\n")
                setLastModified(1_700_000_002_000L)
            }
            if (betaMd.setReadable(false) && !betaMd.canRead()) {
                val runsBeforeBeta = repo.loadAllRunsForTest
                repo.reloadFromDisk()
                assertEquals(runsBeforeBeta + 1, repo.loadAllRunsForTest)
                assertFalse(repo.skills.value.any { it.id == "beta" })

                // Restoring readability with identical mtime must auto-discover beta on next reload.
                betaMd.setReadable(true)
                repo.reloadFromDisk()
                assertEquals(runsBeforeBeta + 2, repo.loadAllRunsForTest)
                assertTrue(repo.skills.value.any { it.id == "beta" })
            }
        } finally {
            skillsDir.walkTopDown().forEach { it.setReadable(true) }
            root.deleteRecursively()
        }
    }
}
