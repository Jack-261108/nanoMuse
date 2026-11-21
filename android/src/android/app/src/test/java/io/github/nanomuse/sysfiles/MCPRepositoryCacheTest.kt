package io.github.nanomuse.sysfiles

import android.content.Context
import com.openminis.app.data.repository.MCPRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.nio.file.Files

class MCPRepositoryCacheTest {

    private fun mockContext(filesRoot: File): Context =
        Mockito.mock(Context::class.java).also { ctx ->
            Mockito.`when`(ctx.filesDir).thenReturn(filesRoot)
        }

    @Test
    fun `reloadFromDisk hits cache when unchanged and invalidates on external edit`() {
        val root = Files.createTempDirectory("nm-mcp-cache").toFile()
        try {
            val mcpDir = File(root, "minis-global/mcp-servers").apply { mkdirs() }
            val serversFile = File(mcpDir, "servers.json")
            serversFile.writeText(
                """{"mcpServers":{"alpha":{"command":"npx","createdAt":1000,"enabled":true}}}""",
            )
            serversFile.setLastModified(1_700_000_000_000L)

            val repo = MCPRepository(mockContext(root))
            assertEquals(1, repo.diskLoadsForTest)
            assertEquals(listOf("alpha"), repo.servers.value.map { it.id })

            // Unchanged file on disk -> cache hit, no extra disk load.
            repo.reloadFromDisk()
            repo.reloadFromDisk()
            assertEquals(1, repo.diskLoadsForTest)

            // Internal write updates stamp so subsequent reloadFromDisk stays a cache hit.
            assertTrue(
                repo.add(
                    MCPRepository.MCPServerConfig(
                        id = "beta",
                        command = "uvx",
                        createdAt = 2000L,
                    ),
                ),
            )
            repo.reloadFromDisk()
            assertEquals(1, repo.diskLoadsForTest)
            assertTrue(repo.servers.value.any { it.id == "beta" })

            repo.setEnabled("alpha", false)
            repo.reloadFromDisk()
            assertEquals(1, repo.diskLoadsForTest)
            assertFalse(repo.servers.value.first { it.id == "alpha" }.enabled)

            // External edit with updated mtime invalidates stamp and reloads.
            serversFile.writeText(
                """{"mcpServers":{"gamma":{"command":"node","createdAt":3000,"enabled":true}}}""",
            )
            serversFile.setLastModified(1_700_000_010_000L)
            repo.reloadFromDisk()
            assertEquals(2, repo.diskLoadsForTest)
            assertEquals(listOf("gamma"), repo.servers.value.map { it.id })

            // Second call after external reload hits cache again.
            repo.reloadFromDisk()
            assertEquals(2, repo.diskLoadsForTest)

            // Transient invalid JSON must NOT cache the file stamp.
            serversFile.writeText("{not-valid-json")
            serversFile.setLastModified(1_700_000_020_000L)
            repo.reloadFromDisk()
            assertEquals(3, repo.diskLoadsForTest)
            assertTrue(repo.servers.value.isEmpty())

            // Even with the same length and same mtime, fixing the JSON reloads instead of hitting a bad cache.
            serversFile.writeText("""{"mcpServers":{}}""")
            serversFile.setLastModified(1_700_000_020_000L)
            repo.reloadFromDisk()
            assertEquals(4, repo.diskLoadsForTest)

            // Failed save invalidates loadedServersStamp so next reloadFromDisk reconciles.
            serversFile.delete()
            serversFile.mkdirs() // make writeText fail
            repo.add(MCPRepository.MCPServerConfig(id = "delta", command = "npx", createdAt = 4000L))
            serversFile.delete()
            serversFile.writeText("""{"mcpServers":{"reconciled":{"command":"npx","createdAt":5000,"enabled":true}}}""")
            serversFile.setLastModified(1_700_000_030_000L)
            repo.reloadFromDisk()
            assertEquals(5, repo.diskLoadsForTest)
            assertEquals(listOf("reconciled"), repo.servers.value.map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }
}
