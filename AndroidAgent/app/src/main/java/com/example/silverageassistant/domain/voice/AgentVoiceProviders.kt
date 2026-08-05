package com.example.silverageassistant.domain.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceFeature {
    CONVERSATION,
    FAMILY_NOTIFICATION,
    NEWS,
    GUI_AGENT,
}

enum class VoicePriority(val value: Int) {
    NEWS(0),
    FAMILY_NOTIFICATION(1),
    CONVERSATION(2),
    USER_RECORDING(3),
}

enum class VoiceAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

enum class VoiceListeningState {
    IDLE,
    LISTENING,
    PROCESSING,
}

enum class VoiceSpeakingState {
    IDLE,
    SPEAKING,
}

data class VoiceRequestContext(
    val feature: VoiceFeature,
    val correlationId: String,
    val priority: VoicePriority,
)

data class AgentAsrResult(
    val transcript: String,
    val confidence: Double?,
)

/**
 * 主聊天 Agent 与 GUI Agent 共享的按住说话 ASR 边界。
 *
 * Provider 不读取 Agent 记忆或上下文；owner/taskId 只用于资源仲裁、用量归属和取消。
 */
interface AgentAsrProvider {
    val availability: StateFlow<VoiceAvailability>
    val listeningState: StateFlow<VoiceListeningState>

    suspend fun startListening(context: VoiceRequestContext)

    suspend fun stopListening(): AgentAsrResult

    suspend fun cancelListening()
}

/**
 * 主聊天 Agent 与 GUI Agent 共享的 TTS 边界。调用方负责提供最小化后的待播报文本。
 */
interface AgentTtsProvider {
    val availability: StateFlow<VoiceAvailability>
    val speakingState: StateFlow<VoiceSpeakingState>

    suspend fun speak(context: VoiceRequestContext, text: String)

    suspend fun stop()
}

object UnavailableAgentAsrProvider : AgentAsrProvider {
    private val availabilityState = MutableStateFlow(VoiceAvailability.UNAVAILABLE)
    private val listeningStateValue = MutableStateFlow(VoiceListeningState.IDLE)

    override val availability = availabilityState.asStateFlow()
    override val listeningState = listeningStateValue.asStateFlow()

    override suspend fun startListening(context: VoiceRequestContext) {
        error("ASR 功能尚未接入")
    }

    override suspend fun stopListening(): AgentAsrResult {
        error("ASR 功能尚未接入")
    }

    override suspend fun cancelListening() = Unit
}

object UnavailableAgentTtsProvider : AgentTtsProvider {
    private val availabilityState = MutableStateFlow(VoiceAvailability.UNAVAILABLE)
    private val speakingStateValue = MutableStateFlow(VoiceSpeakingState.IDLE)

    override val availability = availabilityState.asStateFlow()
    override val speakingState = speakingStateValue.asStateFlow()

    override suspend fun speak(context: VoiceRequestContext, text: String) {
        error("TTS 功能尚未接入")
    }

    override suspend fun stop() = Unit
}
