package io.github.nanomuse.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

class RequestEstimateCacheTest {

    private fun sampleTools(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = "file_read",
            description = "Read a file from disk",
            parameters = mapOf(
                "path" to AgentToolParam("string", "File path"),
                "limit" to AgentToolParam("integer", "Max lines"),
            ),
            required = listOf("path"),
        ),
        AgentToolDefinition(
            name = "shell_execute",
            description = "Run a shell command in sandbox",
            parameters = mapOf(
                "command" to AgentToolParam("string", "Shell command"),
            ),
            required = listOf("command"),
        ),
    )

    @Test
    fun `cached fixed and request totals match ContextEstimate request across changes`() {
        val systemCalls = AtomicInteger(0)
        val toolCalls = AtomicInteger(0)
        val cache = RequestEstimateCache(
            imageTokens = { 1000 },
            countSystemTokens = { prompt ->
                systemCalls.incrementAndGet()
                ContextEstimate.systemTokens(prompt)
            },
            countToolTokens = { tool ->
                toolCalls.incrementAndGet()
                ContextEstimate.toolTokens(tool)
            },
        )
        val toolsA = sampleTools()
        val toolsB = toolsA.drop(1)
        val systemA = "You are a helpful assistant.\nUse tools when helpful."
        val systemB = "$systemA\nPrefer concise replies."

        val messages = listOf(
            LLMMessage(LLMMessage.Role.USER, "Check the build status"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                reasoningContent = "Need to inspect logs",
                contentParts = listOf(
                    AgentContentPart.Text("Let me inspect the logs."),
                    AgentContentPart.ToolUse("c1", "file_read", JSONObject().put("path", "build.log")),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult("c1", "file_read", "OK: green\n".repeat(40)),
                ),
            ),
        )

        val memo = IdentityHashMap<LLMMessage, Int>()
        assertEquals(ContextEstimate.request(emptyList(), systemA, toolsA), cache.fixedTokens(systemA, toolsA))
        assertEquals(1, systemCalls.get())
        assertEquals(2, toolCalls.get())

        // Different list instance with equal contents must hit both system and tools caches.
        assertEquals(ContextEstimate.request(emptyList(), systemA, toolsA), cache.fixedTokens(String(systemA.toCharArray()), toolsA.toList()))
        assertEquals(1, systemCalls.get())
        assertEquals(2, toolCalls.get())

        // Tool subset still reuses per-tool cache entry and cached system prompt tokens.
        assertEquals(ContextEstimate.request(emptyList(), systemA, toolsB), cache.fixedTokens(systemA, toolsB))
        assertEquals(1, systemCalls.get())
        assertEquals(2, toolCalls.get())

        // Changing system prompt recomputes only system tokens, not cached tool tokens.
        assertEquals(ContextEstimate.request(emptyList(), systemB, toolsB), cache.fixedTokens(systemB, toolsB))
        assertEquals(2, systemCalls.get())
        assertEquals(2, toolCalls.get())

        assertEquals(
            ContextEstimate.request(messages, systemA, toolsA),
            cache.request(messages, systemA, toolsA, memo),
        )
        assertEquals(3, systemCalls.get())
        assertEquals(2, toolCalls.get())

        assertEquals(
            ContextEstimate.request(messages.dropLast(1), systemA, toolsA),
            cache.request(messages.dropLast(1), systemA, toolsA, memo),
        )
        assertEquals(3, systemCalls.get())
        assertEquals(2, toolCalls.get())

        assertEquals(
            ContextEstimate.request(messages, systemB, toolsB),
            cache.request(messages, systemB, toolsB, memo),
        )
        assertEquals(4, systemCalls.get())
        assertEquals(2, toolCalls.get())
    }

    @Test
    fun `messageMemo avoids recounting image tokens across trim candidates`() {
        val imageCalls = AtomicInteger(0)
        val cache = RequestEstimateCache { bytes ->
            imageCalls.incrementAndGet()
            bytes.size * 10
        }
        val imageMessage = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "look",
            imageParts = listOf(LLMMessage.ImagePart(byteArrayOf(1, 2, 3), "image/png")),
        )
        val textMessage = LLMMessage(LLMMessage.Role.ASSISTANT, "Done.")
        val tools = sampleTools()
        val memo = IdentityHashMap<LLMMessage, Int>()

        val full = cache.request(listOf(imageMessage, textMessage), "system", tools, memo)
        val subset = cache.request(listOf(imageMessage), "system", tools, memo)

        assertEquals(ContextEstimate.request(listOf(imageMessage, textMessage), "system", tools) { it.size * 10 }, full)
        assertEquals(ContextEstimate.request(listOf(imageMessage), "system", tools) { it.size * 10 }, subset)
        assertEquals(1, imageCalls.get())
    }
}
