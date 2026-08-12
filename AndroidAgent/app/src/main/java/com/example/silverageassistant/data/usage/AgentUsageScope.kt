package com.example.silverageassistant.data.usage

/**
 * 两个 Agent 可复用模型/语音 Provider，但必须使用独立 feature 归属用量。
 * 记忆和上下文实例也由各自 Coordinator 单独持有。
 */
enum class AgentUsageScope(val feature: String) {
    MAIN_CHAT("conversation"),
    MAIN_CHAT_CONTEXT_COMPRESSION("conversation_context_compression"),
    GUI_AGENT("gui_agent"),
}
