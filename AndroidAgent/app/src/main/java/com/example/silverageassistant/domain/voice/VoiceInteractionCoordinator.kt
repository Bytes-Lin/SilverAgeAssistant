package com.example.silverageassistant.domain.voice

import com.example.silverageassistant.data.voice.VoiceInteractionSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VoiceInteractionCoordinator(
    settingsStore: VoiceInteractionSettingsStore,
    private val asrProvider: AgentAsrProvider,
    private val ttsProvider: AgentTtsProvider,
    private val applicationScope: CoroutineScope,
) {
    private val elderModeActive = MutableStateFlow(false)
    private val guiAgentActive = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = combine(
        settingsStore.enabled,
        elderModeActive,
        guiAgentActive,
    ) { storedEnabled, isElderMode, isGuiAgentActive ->
        storedEnabled && (isElderMode || isGuiAgentActive)
    }.stateIn(
        applicationScope,
        SharingStarted.Eagerly,
        false,
    )
    val asrAvailability: StateFlow<VoiceAvailability> = asrProvider.availability
    val ttsAvailability: StateFlow<VoiceAvailability> = ttsProvider.availability
    val listeningState: StateFlow<VoiceListeningState> = asrProvider.listeningState
    val speakingState: StateFlow<VoiceSpeakingState> = ttsProvider.speakingState
    private val errorState = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = errorState.asStateFlow()

    private val speechMutex = Mutex()
    private var speechJob: Job? = null

    init {
        applicationScope.launch {
            enabled.collect { isEnabled ->
                if (!isEnabled) {
                    asrProvider.cancelListening()
                    stopSpeaking()
                }
            }
        }
    }

    fun setElderModeActive(active: Boolean) {
        elderModeActive.value = active
        if (!active && !guiAgentActive.value) stopAll()
    }

    fun setGuiAgentActive(active: Boolean) {
        guiAgentActive.value = active
        if (!active && !elderModeActive.value) stopAll()
    }

    suspend fun startConversationRecording(correlationId: String) {
        check(enabled.value) { "语音交互开关尚未开启" }
        errorState.value = null
        stopSpeaking()
        asrProvider.startListening(
            VoiceRequestContext(
                feature = VoiceFeature.CONVERSATION,
                correlationId = correlationId,
                priority = VoicePriority.USER_RECORDING,
            ),
        )
    }

    suspend fun stopConversationRecording(): AgentAsrResult = asrProvider.stopListening()

    suspend fun cancelConversationRecording() {
        asrProvider.cancelListening()
    }

    suspend fun startGuiAgentRecording(correlationId: String) {
        check(enabled.value) { "语音交互开关尚未开启" }
        errorState.value = null
        stopSpeaking()
        asrProvider.startListening(
            VoiceRequestContext(
                feature = VoiceFeature.GUI_AGENT,
                correlationId = correlationId,
                priority = VoicePriority.USER_RECORDING,
            ),
        )
    }

    suspend fun stopGuiAgentRecording(): AgentAsrResult = asrProvider.stopListening()

    suspend fun cancelGuiAgentRecording() {
        asrProvider.cancelListening()
    }

    /**
     * GUI 普通步骤只播报下一步要做什么，不播报坐标、节点、frame_id、重试或执行结果。
     * 商品、订单和付款确认由 [speakGuiAgentConfirmation] 使用更长的文本上限单独播报。
     */
    fun speakGuiAgentStep(correlationId: String, instruction: String) {
        val text = instruction.toVoiceText(MAX_GUI_STEP_SPEECH_LENGTH)
        speak(
            VoiceRequestContext(
                feature = VoiceFeature.GUI_AGENT,
                correlationId = correlationId,
                priority = VoicePriority.CONVERSATION,
            ),
            text,
        )
    }

    fun speakGuiAgentConfirmation(correlationId: String, details: String) {
        val text = details.toVoiceText(MAX_GUI_CONFIRMATION_SPEECH_LENGTH)
        speak(
            VoiceRequestContext(
                feature = VoiceFeature.GUI_AGENT,
                correlationId = correlationId,
                priority = VoicePriority.CONVERSATION,
            ),
            text,
        )
    }

    fun speak(context: VoiceRequestContext, text: String) {
        if (!enabled.value || text.isBlank()) return
        speechJob?.cancel()
        speechJob = applicationScope.launch {
            runCatching { speakNow(context, text) }
                .onFailure { throwable ->
                    // 新播报打断旧播报、按住说话以及页面退出都会主动取消协程。
                    // 这是正常控制流，不能把 JobCancellationException 暴露给老人。
                    errorState.value = if (throwable is CancellationException) {
                        null
                    } else {
                        "语音播报暂时失败，文字内容仍可查看。"
                    }
                }
        }
    }

    suspend fun speakNow(context: VoiceRequestContext, text: String) {
        if (!enabled.value || text.isBlank()) return
        val currentJob = currentCoroutineContext().job
        speechJob = currentJob
        try {
            speechMutex.withLock {
                if (enabled.value) {
                    errorState.value = null
                    ttsProvider.speak(context, text)
                }
            }
        } finally {
            if (speechJob === currentJob) speechJob = null
        }
    }

    suspend fun stopSpeaking() {
        speechJob?.cancel()
        speechJob = null
        ttsProvider.stop()
    }

    fun stopAll() {
        applicationScope.launch {
            asrProvider.cancelListening()
            stopSpeaking()
        }
    }

    private fun String.toVoiceText(maxLength: Int): String =
        replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)

    private companion object {
        const val MAX_GUI_STEP_SPEECH_LENGTH = 80
        const val MAX_GUI_CONFIRMATION_SPEECH_LENGTH = 300
    }
}
