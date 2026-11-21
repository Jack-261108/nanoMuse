package io.github.nanomuse.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import java.util.IdentityHashMap

/**
 * Memoizes deterministic [ContextEstimate.request] overhead for repeated planning, trimming,
 * and dispatch checks so fixed system prompts and tool JSON schemas are serialized and tokenized
 * once per unique input rather than on every check or trim candidate.
 */
class RequestEstimateCache internal constructor(
    private val imageTokens: (ByteArray) -> Int,
    private val countSystemTokens: (String?) -> Int,
    private val countToolTokens: (AgentToolDefinition) -> Int,
) {
    constructor(imageTokens: (ByteArray) -> Int = { 1000 }) : this(
        imageTokens = imageTokens,
        countSystemTokens = ContextEstimate::systemTokens,
        countToolTokens = ContextEstimate::toolTokens,
    )

    private var cachedSystemPrompt: String? = null
    private var cachedSystemTokens: Int = 0
    private var cachedTools: List<AgentToolDefinition> = emptyList()
    private var cachedToolsTokens: Int = 0
    private val toolTokenCache = object : LinkedHashMap<AgentToolDefinition, Int>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<AgentToolDefinition, Int>?,
        ): Boolean = size > MAX_TOOL_CACHE_ENTRIES
    }

    /** Return `ContextEstimate.request(emptyList(), systemPrompt, tools, imageTokens)` from cache when unchanged. */
    fun fixedTokens(systemPrompt: String?, tools: List<AgentToolDefinition>): Int {
        if (systemPrompt !== cachedSystemPrompt && systemPrompt != cachedSystemPrompt) {
            cachedSystemPrompt = systemPrompt
            cachedSystemTokens = countSystemTokens(systemPrompt)
        }
        if (tools !== cachedTools && tools != cachedTools) {
            var sum = 0
            for (tool in tools) {
                val cost = toolTokenCache.getOrPut(tool) { countToolTokens(tool) }
                sum = ContextEstimate.add(sum, cost)
            }
            cachedTools = tools.toList()
            cachedToolsTokens = sum
        }
        return ContextEstimate.add(ContextEstimate.REPLY_OVERHEAD, cachedSystemTokens, cachedToolsTokens)
    }

    /**
     * Estimate [messages] + fixed prompt/tools, optionally reusing per-message totals within a
     * single immutable trim/preparation pass via [messageMemo].
     */
    fun request(
        messages: List<LLMMessage>,
        systemPrompt: String? = null,
        tools: List<AgentToolDefinition> = emptyList(),
        messageMemo: IdentityHashMap<LLMMessage, Int>? = null,
    ): Int {
        var total = fixedTokens(systemPrompt, tools)
        if (total == Int.MAX_VALUE) return Int.MAX_VALUE
        for (message in messages) {
            val messageCost = if (messageMemo != null) {
                messageMemo.getOrPut(message) { ContextEstimate.messageTokens(message, imageTokens) }
            } else {
                ContextEstimate.messageTokens(message, imageTokens)
            }
            total = ContextEstimate.add(total, messageCost)
            if (total == Int.MAX_VALUE) return Int.MAX_VALUE
        }
        return total
    }

    private companion object {
        const val MAX_TOOL_CACHE_ENTRIES = 64
    }
}
