package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.domain.model.ChatMessage
import com.example.silverageassistant.domain.model.ChatToolDefinition
import kotlin.math.ceil

data class AgentContextPolicy(
    val maxTurns: Int = 8,
    val compressedTurnCount: Int = 5,
    val retainedTurnCount: Int = 3,
    val stablePrefixFraction: Double = 0.20,
    val summaryFraction: Double = 0.10,
    val windowFraction: Double = 0.60,
) {
    init {
        require(maxTurns > 0)
        require(compressedTurnCount > 0)
        require(retainedTurnCount > 0)
        require(compressedTurnCount + retainedTurnCount == maxTurns)
        require(stablePrefixFraction in 0.0..1.0)
        require(summaryFraction in 0.0..1.0)
        require(windowFraction in 0.0..1.0)
    }
}

data class AgentTurnRecord(
    val turnId: String,
    val messages: List<ChatMessage>,
    val synthetic: Boolean = false,
) {
    init {
        require(turnId.isNotBlank())
        require(messages.isNotEmpty())
    }
}

data class AgentContextUsage(
    val usedTokens: Long = 0,
    val totalTokens: Long = 32_768,
    val stablePrefixTokens: Long = 0,
    val summaryTokens: Long = 0,
    val windowTokens: Long = 0,
    val toolDefinitionTokens: Long = 0,
    val estimated: Boolean = true,
)

data class PreparedAgentContext(
    val messages: List<ChatMessage>,
    val usage: AgentContextUsage,
)

data class LongTermMemoryCandidate(
    val fact: String,
    val evidence: String,
)

data class SlidingWindowCompressionResult(
    val summaryEvents: List<String>,
    val memoryCandidates: List<LongTermMemoryCandidate>,
)

data class WholeWindowCompressionResult(
    val compressedUserContext: String,
    val compressedAssistantContext: String,
    val memoryCandidates: List<LongTermMemoryCandidate>,
)

interface AgentContextCompressor {
    suspend fun compressSlidingWindow(
        existingSummary: String,
        turns: List<AgentTurnRecord>,
    ): SlidingWindowCompressionResult

    suspend fun compressWholeWindow(
        turns: List<AgentTurnRecord>,
    ): WholeWindowCompressionResult

    suspend fun compressSummary(summary: String): List<String>
}

interface AgentContextTokenEstimator {
    fun estimateMessages(messages: List<ChatMessage>): Long

    fun estimateTools(tools: List<ChatToolDefinition>): Long
}

object HeuristicAgentContextTokenEstimator : AgentContextTokenEstimator {
    override fun estimateMessages(messages: List<ChatMessage>): Long = messages.sumOf { message ->
        estimateText(message.content.orEmpty()) +
            message.toolCalls.sumOf { call ->
                estimateText(call.name) + estimateText(call.argumentsJson) + TOOL_OVERHEAD_TOKENS
            } +
            MESSAGE_OVERHEAD_TOKENS
    }

    override fun estimateTools(tools: List<ChatToolDefinition>): Long = tools.sumOf { tool ->
        estimateText(tool.name) + estimateText(tool.description) +
            estimateText(tool.parametersJson) + TOOL_OVERHEAD_TOKENS
    }

    private fun estimateText(text: String): Long {
        if (text.isEmpty()) return 0
        var weightedUnits = 0.0
        text.forEach { character ->
            weightedUnits += if (character.code > 0x7f) 1.0 else 0.25
        }
        return ceil(weightedUnits).toLong().coerceAtLeast(1)
    }

    private const val MESSAGE_OVERHEAD_TOKENS = 4L
    private const val TOOL_OVERHEAD_TOKENS = 8L
}
