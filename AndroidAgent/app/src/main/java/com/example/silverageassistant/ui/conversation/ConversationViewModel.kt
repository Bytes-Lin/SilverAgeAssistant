package com.example.silverageassistant.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.domain.agent.AgentChatCoordinator
import com.example.silverageassistant.domain.agent.AgentChatEvent
import com.example.silverageassistant.domain.agent.PendingPhoneCallCoordinator
import com.example.silverageassistant.domain.agent.PhoneCallLauncher
import com.example.silverageassistant.domain.gui.GuiTaskChatFeedback
import com.example.silverageassistant.domain.gui.GuiTaskChatFeedbackSource
import com.example.silverageassistant.domain.model.ChatModelException
import com.example.silverageassistant.domain.voice.VoiceFeature
import com.example.silverageassistant.domain.voice.VoiceInteractionCoordinator
import com.example.silverageassistant.domain.voice.VoicePriority
import com.example.silverageassistant.domain.voice.VoiceRequestContext
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
    private val guiTaskChatFeedbackSource: GuiTaskChatFeedbackSource? = null,
    private val voiceCoordinator: VoiceInteractionCoordinator? = null,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private var responseJob: Job? = null
    private var voiceStartJob: Job? = null
    private var voiceCompletionJob: Job? = null
    private var voicePressActive = false
    private var pendingRequest: PendingRequest? = null
    private val announcedGuiTaskIds = mutableSetOf<String>()

    init {
        coordinator?.let { agentCoordinator ->
            workScope.launch {
                agentCoordinator.contextUsage.collect { usage ->
                    _uiState.update {
                        it.copy(
                            contextTokens = usage.usedTokens,
                            contextWindowTokens = usage.totalTokens,
                        )
                    }
                }
            }
        }
        pendingPhoneCallCoordinator?.let { callCoordinator ->
            workScope.launch {
                callCoordinator.pending.collectLatest { pendingCall ->
                    _uiState.update { it.copy(pendingPhoneCall = pendingCall) }
                }
            }
        }
        guiTaskChatFeedbackSource?.let { source ->
            workScope.launch {
                source.feedback.collect { feedback ->
                    appendGuiTaskFeedback(feedback)
                }
            }
        }
        voiceCoordinator?.let { voice ->
            workScope.launch {
                voice.enabled.collect { enabled ->
                    _uiState.update { it.copy(voiceEnabled = enabled) }
                    if (!enabled) voice.stopAll()
                }
            }
            workScope.launch {
                voice.listeningState.collect { listening ->
                    _uiState.update { it.copy(voiceListeningState = listening) }
                }
            }
            workScope.launch {
                voice.speakingState.collect { speaking ->
                    _uiState.update { it.copy(voiceSpeakingState = speaking) }
                }
            }
            workScope.launch {
                voice.error.collect { message ->
                    if (message != null) _uiState.update { it.copy(voiceMessage = message) }
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
            userText = message,
            userMessageId = newMessageId("elder"),
        )
        pendingRequest = request
        startRequest(request, appendUserMessage = true)
    }

    fun startVoiceRecording() {
        val voice = voiceCoordinator ?: return
        if (!_uiState.value.canStartVoice || voicePressActive || voiceStartJob?.isActive == true) return
        voicePressActive = true
        val correlationId = newMessageId("voice")
        _uiState.update { it.copy(voiceMessage = "正在准备麦克风…", errorMessage = null) }
        voiceStartJob = workScope.launch {
            runCatching { voice.startConversationRecording(correlationId) }
                .onSuccess {
                    if (voicePressActive) {
                        _uiState.update { it.copy(voiceMessage = "正在听，请继续按住说话") }
                    }
                }
                .onFailure { error ->
                    voicePressActive = false
                    if (error is CancellationException) return@onFailure
                    _uiState.update {
                        it.copy(voiceMessage = error.toVoiceUserMessage("无法开始语音识别，请继续打字。"))
                    }
                }
        }
    }

    fun stopVoiceRecording() {
        val voice = voiceCoordinator ?: return
        if (!voicePressActive) return
        voicePressActive = false
        val pendingStart = voiceStartJob
        voiceCompletionJob?.cancel()
        voiceCompletionJob = workScope.launch {
            // 松手可能早于 WebSocket task-started。先等待启动协程结束，再停止录音，
            // 避免把仍在准备中的停止请求误判为“当前没有录音”。
            pendingStart?.join()
            if (voice.listeningState.value == com.example.silverageassistant.domain.voice.VoiceListeningState.IDLE) {
                return@launch
            }
            _uiState.update { it.copy(voiceMessage = "正在识别…") }
            runCatching { voice.stopConversationRecording() }
                .onSuccess { result ->
                    val transcript = result.transcript.trim().take(MAX_DRAFT_LENGTH)
                    if (transcript.isBlank()) {
                        _uiState.update { it.copy(voiceMessage = "没有听清，请按住按钮再说一次。") }
                    } else {
                        _uiState.update { it.copy(voiceMessage = null) }
                        submitUserText(transcript)
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    _uiState.update {
                        it.copy(voiceMessage = error.toVoiceUserMessage("语音识别失败，请再试一次或继续打字。"))
                    }
                }
        }
    }

    fun cancelVoiceRecording() {
        val voice = voiceCoordinator ?: return
        if (
            !voicePressActive &&
            voiceStartJob?.isActive != true &&
            voice.listeningState.value == com.example.silverageassistant.domain.voice.VoiceListeningState.IDLE
        ) {
            return
        }
        cancelVoiceRecording(showMessage = true)
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                voiceMessage = if (granted) {
                    "麦克风权限已允许，请再次按住说话。"
                } else {
                    "没有麦克风权限，您仍可以打字聊天。"
                },
            )
        }
    }

    fun onPageClosed() {
        cancelVoiceRecording(showMessage = false)
        stopVoicePlayback()
    }

    private fun cancelVoiceRecording(showMessage: Boolean) {
        voicePressActive = false
        voiceStartJob?.cancel()
        voiceStartJob = null
        voiceCompletionJob?.cancel()
        voiceCompletionJob = workScope.launch {
            voiceCoordinator?.cancelConversationRecording()
            if (showMessage) {
                _uiState.update { it.copy(voiceMessage = "已取消录音。") }
            } else {
                _uiState.update { it.copy(voiceMessage = null) }
            }
        }
    }

    fun stopVoicePlayback() {
        workScope.launch { voiceCoordinator?.stopSpeaking() }
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
                localCoordinator.streamTurn(request.userText)
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
                                // Usage 进入独立统计账本。圆圈订阅 AgentContextManager 的
                                // 当前上下文状态，避免把单次请求 Usage 当成持久上下文。
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

    private fun submitUserText(text: String) {
        val state = _uiState.value
        if (state.isProcessing) return
        val request = PendingRequest(
            userText = text,
            userMessageId = newMessageId("elder"),
        )
        pendingRequest = request
        startRequest(request, appendUserMessage = true)
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
        var completedText: String? = null
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
                completedText = target.text
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
        completedText?.toSpeakableText()?.takeIf(String::isNotBlank)?.let { text ->
            voiceCoordinator?.speak(
                VoiceRequestContext(
                    feature = VoiceFeature.CONVERSATION,
                    correlationId = messageId,
                    priority = VoicePriority.CONVERSATION,
                ),
                text,
            )
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

    private suspend fun appendGuiTaskFeedback(feedback: GuiTaskChatFeedback) {
        if (!announcedGuiTaskIds.add(feedback.todoId)) return
        val text = when (feedback) {
            is GuiTaskChatFeedback.Completed -> "已完成任务。"
            is GuiTaskChatFeedback.Failed -> if (feedback.familyNotified) {
                "任务失败，已通知家人。"
            } else {
                "任务失败，请稍后再试。"
            }
        }
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ConversationMessage(
                    id = newMessageId("gui-result"),
                    speaker = ConversationSpeaker.Assistant,
                    text = text,
                ),
            )
        }
        coordinator?.recordExternalToolOutcome(feedback.todoId, text)
    }

    private fun newMessageId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    class Factory(
        private val coordinator: AgentChatCoordinator?,
        private val pendingPhoneCallCoordinator: PendingPhoneCallCoordinator? = null,
        private val phoneCallLauncher: PhoneCallLauncher? = null,
        private val guiTaskChatFeedbackSource: GuiTaskChatFeedbackSource? = null,
        private val voiceCoordinator: VoiceInteractionCoordinator? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ConversationViewModel::class.java))
            return ConversationViewModel(
                coordinator = coordinator,
                pendingPhoneCallCoordinator = pendingPhoneCallCoordinator,
                phoneCallLauncher = phoneCallLauncher,
                guiTaskChatFeedbackSource = guiTaskChatFeedbackSource,
                voiceCoordinator = voiceCoordinator,
            ) as T
        }
    }

    private data class PendingRequest(
        val userText: String,
        val userMessageId: String,
    )

    private companion object {
        const val MAX_DRAFT_LENGTH = 500
    }
}

private fun String.toSpeakableText(): String = replace(Regex("```[\\s\\S]*?```"), "")
    .replace(Regex("https?://\\S+"), "")
    .replace(Regex("[*_#>`~]+"), "")
    .trim()

/** 只允许明确、适合老人的配置提示进入 UI，网络栈和协程内部消息一律隐藏。 */
private fun Throwable.toVoiceUserMessage(fallback: String): String = when (message) {
    "家属尚未配置语音模型",
    "请先在老人端设置语音 API Key",
    "请允许银龄助手使用麦克风",
    "当前无法使用麦克风，请稍后再试",
    "麦克风暂时不可用",
    "麦克风初始化失败",
    -> message.orEmpty()
    else -> fallback
}
