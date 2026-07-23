package com.example.silverageassistant.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.domain.agent.AgentChatCoordinator
import com.example.silverageassistant.domain.agent.AgentChatEvent
import com.example.silverageassistant.domain.agent.PendingPhoneCallCoordinator
import com.example.silverageassistant.domain.agent.PhoneCallLauncher
import com.example.silverageassistant.domain.model.ChatMessage
import com.example.silverageassistant.domain.model.ChatModelException
import com.example.silverageassistant.domain.model.ChatRole
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 对话页的单向状态容器。
 *
 * 当前聊天正文只保存在进程内。ViewModel 将 Agent 流事件映射为适老化 UI 状态，并保存
 * 原请求用于显式重试；取消只终止当前响应，不删除老人已经输入的消息或已收到的部分回复。
 */
class ConversationViewModel(
    private val coordinator: AgentChatCoordinator? = null,
    private val pendingPhoneCallCoordinator: PendingPhoneCallCoordinator? = null,
    private val phoneCallLauncher: PhoneCallLauncher? = null,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private var responseJob: Job? = null
    private var pendingRequest: PendingRequest? = null

    init {
        pendingPhoneCallCoordinator?.let { callCoordinator ->
            workScope.launch {
                callCoordinator.pending.collectLatest { pendingCall ->
                    _uiState.update { it.copy(pendingPhoneCall = pendingCall) }
                }
            }
        }
    }

    fun updateDraft(value: String) {
        _uiState.update {
            it.copy(
                draft = value.take(MAX_DRAFT_LENGTH),
                errorMessage = null,
            )
        }
    }

    fun selectInputMode(mode: ConversationInputMode) {
        _uiState.update { it.copy(inputMode = mode) }
    }

    fun sendTextMessage() {
        val state = _uiState.value
        val message = state.draft.trim()
        if (!state.canSendText || message.isEmpty()) return
        val request = PendingRequest(
            history = state.messages.toModelHistory(),
            userText = message,
            userMessageId = newMessageId("elder"),
        )
        pendingRequest = request
        startRequest(request, appendUserMessage = true)
    }

    fun retryLastMessage() {
        if (_uiState.value.isProcessing) return
        val request = pendingRequest ?: return
        startRequest(request, appendUserMessage = false)
    }

    fun cancelResponse() {
        if (!_uiState.value.isProcessing) return
        responseJob?.cancel()
        responseJob = null
        _uiState.update { state ->
            val messages = state.messages.mapNotNull { message ->
                when {
                    message.status != ConversationMessageStatus.Streaming -> message
                    message.text.isBlank() -> null
                    else -> message.copy(status = ConversationMessageStatus.Interrupted)
                }
            }
            state.copy(
                phase = ConversationPhase.Idle,
                messages = messages,
                errorMessage = "已停止这次回答，您可以重新生成。",
                canRetry = true,
            )
        }
    }

    fun dismissPendingPhoneCall() {
        val pendingCall = _uiState.value.pendingPhoneCall ?: return
        pendingPhoneCallCoordinator?.dismiss(pendingCall.requestId)
    }

    fun confirmPendingPhoneCall(direct: Boolean) {
        val pendingCall = _uiState.value.pendingPhoneCall ?: return
        val callCoordinator = pendingPhoneCallCoordinator ?: return
        val launcher = phoneCallLauncher ?: return
        runCatching {
            callCoordinator.launch(pendingCall.requestId, direct, launcher)
        }.onFailure {
            _uiState.update { state ->
                state.copy(errorMessage = "暂时无法打开电话功能，请稍后再试。")
            }
        }
    }

    private fun startRequest(
        request: PendingRequest,
        appendUserMessage: Boolean,
    ) {
        val localCoordinator = coordinator
        if (localCoordinator == null) {
            _uiState.update {
                it.copy(
                    errorMessage = "模型服务尚未配置，请先在老人设备上完成配置。",
                    canRetry = false,
                )
            }
            return
        }
        responseJob?.cancel()
        val assistantMessageId = newMessageId("assistant")
        _uiState.update { state ->
            val withoutPreviousIncomplete = state.messages.filterNot {
                it.status != ConversationMessageStatus.Complete &&
                    it.speaker == ConversationSpeaker.Assistant
            }
            state.copy(
                phase = ConversationPhase.Connecting,
                messages = withoutPreviousIncomplete +
                    buildList {
                        if (appendUserMessage) {
                            add(
                                ConversationMessage(
                                    id = request.userMessageId,
                                    speaker = ConversationSpeaker.Elder,
                                    text = request.userText,
                                ),
                            )
                        }
                        add(
                            ConversationMessage(
                                id = assistantMessageId,
                                speaker = ConversationSpeaker.Assistant,
                                text = "",
                                status = ConversationMessageStatus.Streaming,
                            ),
                        )
                    },
                draft = if (appendUserMessage) "" else state.draft,
                errorMessage = null,
                canRetry = false,
            )
        }

        responseJob = workScope.launch {
            try {
                localCoordinator.streamTurn(request.history, request.userText)
                    .collect { event ->
                        when (event) {
                            is AgentChatEvent.Started -> {
                                _uiState.update {
                                    it.copy(
                                        phase = ConversationPhase.Connecting,
                                        contextWindowTokens = event.contextWindowTokens,
                                    )
                                }
                            }
                            AgentChatEvent.ReasoningStarted -> {
                                updatePhase(ConversationPhase.Thinking)
                            }
                            is AgentChatEvent.ToolRunning -> {
                                updatePhase(ConversationPhase.UsingTool)
                            }
                            is AgentChatEvent.TextDelta -> {
                                appendAssistantText(assistantMessageId, event.text)
                            }
                            is AgentChatEvent.Usage -> {
                                event.usage.promptTokens?.let { promptTokens ->
                                    _uiState.update {
                                        it.copy(contextTokens = promptTokens.coerceAtLeast(0))
                                    }
                                }
                            }
                            AgentChatEvent.Completed -> completeAssistantMessage(assistantMessageId)
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ChatModelException) {
                showFailure(assistantMessageId, error.userMessage)
            } catch (_: Exception) {
                showFailure(assistantMessageId, "回答失败了，请稍后再试。")
            }
        }
    }

    private fun updatePhase(phase: ConversationPhase) {
        _uiState.update { state ->
            if (state.phase == ConversationPhase.Responding) state else state.copy(phase = phase)
        }
    }

    private fun appendAssistantText(messageId: String, delta: String) {
        if (delta.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                phase = ConversationPhase.Responding,
                messages = state.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(text = message.text + delta)
                    } else {
                        message
                    }
                },
            )
        }
    }

    private fun completeAssistantMessage(messageId: String) {
        _uiState.update { state ->
            val target = state.messages.firstOrNull { it.id == messageId }
            if (target == null || target.text.isBlank()) {
                state.copy(
                    phase = ConversationPhase.Idle,
                    messages = state.messages.filterNot { it.id == messageId },
                    errorMessage = "模型没有返回文字回答，请换一种说法再试。",
                    canRetry = true,
                )
            } else {
                state.copy(
                    phase = ConversationPhase.Idle,
                    messages = state.messages.map { message ->
                        if (message.id == messageId) {
                            message.copy(status = ConversationMessageStatus.Complete)
                        } else {
                            message
                        }
                    },
                    errorMessage = null,
                    canRetry = false,
                )
            }
        }
        responseJob = null
    }

    private fun showFailure(messageId: String, message: String) {
        _uiState.update { state ->
            state.copy(
                phase = ConversationPhase.Idle,
                messages = state.messages.mapNotNull { item ->
                    when {
                        item.id != messageId -> item
                        item.text.isBlank() -> null
                        else -> item.copy(status = ConversationMessageStatus.Interrupted)
                    }
                },
                errorMessage = message,
                canRetry = true,
            )
        }
        responseJob = null
    }

    private fun List<ConversationMessage>.toModelHistory(): List<ChatMessage> = mapNotNull {
        if (!it.includeInModelContext || it.status != ConversationMessageStatus.Complete) {
            null
        } else {
            ChatMessage(
                role = if (it.speaker == ConversationSpeaker.Elder) {
                    ChatRole.User
                } else {
                    ChatRole.Assistant
                },
                content = it.text,
            )
        }
    }

    private fun newMessageId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    class Factory(
        private val coordinator: AgentChatCoordinator?,
        private val pendingPhoneCallCoordinator: PendingPhoneCallCoordinator? = null,
        private val phoneCallLauncher: PhoneCallLauncher? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ConversationViewModel::class.java))
            return ConversationViewModel(
                coordinator = coordinator,
                pendingPhoneCallCoordinator = pendingPhoneCallCoordinator,
                phoneCallLauncher = phoneCallLauncher,
            ) as T
        }
    }

    private data class PendingRequest(
        val history: List<ChatMessage>,
        val userText: String,
        val userMessageId: String,
    )

    private companion object {
        const val MAX_DRAFT_LENGTH = 500
    }
}
