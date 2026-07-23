package com.example.silverageassistant.data.usage

import com.example.silverageassistant.domain.model.ChatMessage
import com.example.silverageassistant.domain.model.ChatRequest
import kotlin.math.ceil

enum class ModelUsageModality {
    MLLM,
    ASR,
    TTS,
}

data class ModelUsageSummary(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val mllmRequestCount: Long = 0,
    val asrRequestCount: Long = 0,
    val ttsRequestCount: Long = 0,
    val asrAudioDurationMillis: Long = 0,
    val ttsCharacterCount: Long = 0,
    val containsEstimatedValues: Boolean = false,
)

data class TokenEstimate(
    val inputTokens: Long,
    val outputTokens: Long,
)

object LocalTokenEstimator {
    fun estimate(request: ChatRequest, outputText: String): TokenEstimate = TokenEstimate(
        inputTokens = request.messages.sumOf(::estimateMessage) +
            request.tools.sumOf { tool ->
                estimateText(tool.name) + estimateText(tool.description) +
                    estimateText(tool.parametersJson) + TOOL_OVERHEAD_TOKENS
            },
        outputTokens = estimateText(outputText),
    )

    private fun estimateMessage(message: ChatMessage): Long =
        estimateText(message.content.orEmpty()) +
            message.toolCalls.sumOf {
                estimateText(it.name) + estimateText(it.argumentsJson) + TOOL_OVERHEAD_TOKENS
            } +
            MESSAGE_OVERHEAD_TOKENS

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

object ModelUsagePolicy {
    const val DEFAULT_CONTEXT_WINDOW_TOKENS = 32_768L
    const val REPORT_INTERVAL_HOURS = 1L
    const val MAX_UPLOAD_RECORDS = 500
}
