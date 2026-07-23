package com.example.silverageassistant.ui.conversation

import com.example.silverageassistant.data.usage.ModelUsagePolicy
import com.example.silverageassistant.domain.agent.PendingPhoneCall

enum class ConversationPhase(
    val statusText: String,
    val guidanceText: String,
) {
    Idle("可以开始聊天", "可以打字，也可以使用系统手写键盘。"),
    Connecting("正在连接", "正在联系模型服务，请稍候。"),
    Thinking("正在准备回答", "我正在理解您的话。"),
    UsingTool("正在查询信息", "正在获取回答需要的信息。"),
    Responding("正在回答", "回答会逐字显示，您也可以随时停止。"),
}

enum class ConversationSpeaker {
    Elder,
    Assistant,
}

enum class ConversationInputMode {
    Keyboard,
    Handwriting,
}

enum class ConversationMessageStatus {
    Complete,
    Streaming,
    Interrupted,
}

data class ConversationMessage(
    val id: String,
    val speaker: ConversationSpeaker,
    val text: String,
    val status: ConversationMessageStatus = ConversationMessageStatus.Complete,
    val includeInModelContext: Boolean = true,
)

data class ConversationUiState(
    val phase: ConversationPhase = ConversationPhase.Idle,
    val messages: List<ConversationMessage> = welcomeMessages,
    val draft: String = "",
    val inputMode: ConversationInputMode = ConversationInputMode.Keyboard,
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
    val contextTokens: Long = 0,
    val contextWindowTokens: Long = ModelUsagePolicy.DEFAULT_CONTEXT_WINDOW_TOKENS,
    val pendingPhoneCall: PendingPhoneCall? = null,
) {
    val isProcessing: Boolean
        get() = phase != ConversationPhase.Idle

    val canSendText: Boolean
        get() = !isProcessing && draft.isNotBlank()

    val contextUsageFraction: Float
        get() = if (contextWindowTokens <= 0) {
            0f
        } else {
            (contextTokens.toDouble() / contextWindowTokens.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }

    companion object {
        val welcomeMessages = listOf(
            ConversationMessage(
                id = "local-assistant-welcome",
                speaker = ConversationSpeaker.Assistant,
                text = "您好，我在这里。今天想聊些什么？",
                includeInModelContext = false,
            ),
        )
    }
}
