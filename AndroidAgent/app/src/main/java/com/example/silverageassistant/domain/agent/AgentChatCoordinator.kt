package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.domain.model.ChatMessage
import com.example.silverageassistant.domain.model.ChatModelException
import com.example.silverageassistant.domain.model.ChatModelProvider
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatRole
import com.example.silverageassistant.domain.model.ChatStreamEvent
import com.example.silverageassistant.domain.model.ChatToolCall
import com.example.silverageassistant.domain.model.ChatUsage
import com.example.silverageassistant.domain.model.ReasoningMode
import com.example.silverageassistant.domain.model.SamplingConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentChatOptions(
    val sampling: SamplingConfig = SamplingConfig(),
    val reasoningMode: ReasoningMode = ReasoningMode.Disabled,
    val maxOutputTokens: Int = 512,
    val contextWindowTokens: Long = 32_768L,
)

fun interface AgentChatOptionsProvider {
    suspend fun options(): AgentChatOptions
}

sealed interface AgentChatEvent {
    data class Started(val contextWindowTokens: Long) : AgentChatEvent
    data object ReasoningStarted : AgentChatEvent
    data class ToolRunning(val displayName: String) : AgentChatEvent
    data class TextDelta(val text: String) : AgentChatEvent
    data class Usage(val usage: ChatUsage) : AgentChatEvent
    data object Completed : AgentChatEvent
}

/**
 * 单轮 Agent 对话编排器。
 *
 * Provider 只负责模型协议，本类负责构造上下文、限制模型轮次、解析 Tool Call、执行已注册
 * 工具并把结果送回模型。系统操作不能绕过 ToolRegistry，因此 UI 和模型都不直接接触电话、
 * 中台或定位实现。
 */
class AgentChatCoordinator(
    private val provider: ChatModelProvider,
    private val toolRegistry: AgentToolRegistry,
    private val systemPromptProvider: SystemPromptProvider = DefaultSystemPromptProvider(),
    private val options: AgentChatOptions = AgentChatOptions(),
    private val optionsProvider: AgentChatOptionsProvider? = null,
) {
    fun streamTurn(
        history: List<ChatMessage>,
        userText: String,
    ): Flow<AgentChatEvent> = flow {
        require(userText.isNotBlank()) { "userText must not be blank" }
        val messages = buildList {
            add(ChatMessage(ChatRole.System, systemPromptProvider.systemPrompt()))
            addAll(history)
            add(ChatMessage(ChatRole.User, userText))
        }.toMutableList()
        val currentOptions = optionsProvider?.options() ?: options
        emit(AgentChatEvent.Started(currentOptions.contextWindowTokens))

        // 限制“模型 -> 工具 -> 模型”的递归轮数，避免错误模型不断调用工具耗尽 Token。
        repeat(MAX_MODEL_ROUNDS) {
            val responseText = StringBuilder()
            val toolCalls = mutableListOf<ChatToolCall>()
            var completed = false
            provider.stream(
                ChatRequest(
                    messages = messages,
                    tools = toolRegistry.definitions,
                    sampling = currentOptions.sampling,
                    reasoningMode = currentOptions.reasoningMode,
                    maxOutputTokens = currentOptions.maxOutputTokens,
                ),
            ).collect { event ->
                when (event) {
                    ChatStreamEvent.ReasoningStarted -> {
                        emit(AgentChatEvent.ReasoningStarted)
                    }
                    is ChatStreamEvent.TextDelta -> {
                        responseText.append(event.text)
                        emit(AgentChatEvent.TextDelta(event.text))
                    }
                    is ChatStreamEvent.ToolCallReady -> toolCalls += event.call
                    is ChatStreamEvent.Usage -> emit(AgentChatEvent.Usage(event.usage))
                    is ChatStreamEvent.Completed -> completed = true
                }
            }
            if (!completed) {
                throw ChatModelException(
                    code = "MODEL_STREAM_INCOMPLETE",
                    userMessage = "模型回答没有完成，请重新试一次。",
                )
            }
            if (toolCalls.isEmpty()) {
                emit(AgentChatEvent.Completed)
                return@flow
            }

            // 按 OpenAI 协议同时保留 assistant tool_calls 与对应 tool result，下一轮模型
            // 才能基于真实执行结果组织回复，而不是假设工具已经成功。
            messages += ChatMessage(
                role = ChatRole.Assistant,
                content = responseText.toString().takeIf(String::isNotBlank),
                toolCalls = toolCalls,
            )
            toolCalls.forEach { call ->
                val tool = toolRegistry.find(call.name)
                emit(
                    AgentChatEvent.ToolRunning(
                        displayName = tool?.runningDisplayName ?: "正在使用功能",
                    ),
                )
                val result = when {
                    tool == null -> toolError("未找到这个功能")
                    tool.riskLevel != ToolRiskLevel.Low &&
                        tool.executionPolicy == ToolExecutionPolicy.Immediate -> {
                        toolError("这个功能需要进一步确认")
                    }
                    else -> runCatching { tool.execute(call.argumentsJson) }
                        .getOrElse { toolError("功能参数不正确") }
                }
                messages += ChatMessage(
                    role = ChatRole.Tool,
                    content = result,
                    toolCallId = call.id,
                )
            }
        }

        throw ChatModelException(
            code = "TOOL_LOOP_LIMIT",
            userMessage = "这次操作步骤太多了，请换一种说法再试。",
        )
    }

    private fun toolError(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

    private companion object {
        const val MAX_MODEL_ROUNDS = 3
    }
}
