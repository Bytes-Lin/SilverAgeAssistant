package com.example.silverageassistant.domain.model

enum class ChatRole(val wireName: String) {
    System("system"),
    User("user"),
    Assistant("assistant"),
    Tool("tool"),
}

data class ChatToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ChatMessage(
    val role: ChatRole,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ChatToolCall> = emptyList(),
)

data class ChatToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String,
)

data class SamplingConfig(
    val temperature: Double = 0.6,
    val topP: Double = 0.9,
    val topK: Int = 40,
) {
    init {
        require(temperature in 0.0..2.0) { "temperature must be between 0 and 2" }
        require(topP in 0.0..1.0) { "topP must be between 0 and 1" }
        require(topK >= 0) { "topK must be non-negative" }
    }
}

enum class ReasoningMode {
    Enabled,
    Disabled,
}

data class ChatRequest(
    val messages: List<ChatMessage>,
    val tools: List<ChatToolDefinition> = emptyList(),
    val sampling: SamplingConfig = SamplingConfig(),
    val reasoningMode: ReasoningMode = ReasoningMode.Disabled,
    val maxOutputTokens: Int = 512,
) {
    init {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
    }
}

data class ChatUsage(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
)

sealed interface ChatStreamEvent {
    data object ReasoningStarted : ChatStreamEvent

    data class TextDelta(val text: String) : ChatStreamEvent

    data class ToolCallReady(val call: ChatToolCall) : ChatStreamEvent

    data class Usage(val usage: ChatUsage) : ChatStreamEvent

    data class Completed(val finishReason: String?) : ChatStreamEvent
}

class ChatModelException(
    val code: String,
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)
