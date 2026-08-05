package com.example.silverageassistant.ui.modelconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.data.middleserver.ElderModelConfigurationRepository
import com.example.silverageassistant.data.middleserver.FamilyModelConfigurationRepository
import com.example.silverageassistant.data.middleserver.FamilyModelConfigurationUpdateRequest
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import com.example.silverageassistant.data.model.ModelConfigurationStore
import com.example.silverageassistant.data.model.ModelRuntimeConfiguration
import com.example.silverageassistant.data.model.OpenAiCompatibleDialect
import com.example.silverageassistant.data.model.VoiceAudioFormat
import com.example.silverageassistant.data.model.VoiceRuntimeConfiguration
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelConfigurationUiState(
    val baseUrl: String = "",
    val model: String = "",
    val dialect: OpenAiCompatibleDialect = OpenAiCompatibleDialect.LlamaCpp,
    val contextWindowTokens: String = "32768",
    val maxOutputTokens: String = "512",
    val temperature: String = "0.6",
    val topP: String = "0.9",
    val topK: String = "40",
    val voiceWebSocketUrl: String = "",
    val asrModel: String = VoiceRuntimeConfiguration.DEFAULT_ASR_MODEL,
    val ttsModel: String = VoiceRuntimeConfiguration.DEFAULT_TTS_MODEL,
    val ttsVoice: String = VoiceRuntimeConfiguration.DEFAULT_TTS_VOICE,
    val ttsResponseFormat: VoiceAudioFormat = VoiceAudioFormat.Pcm,
    val ttsSampleRate: String = VoiceRuntimeConfiguration.DEFAULT_TTS_SAMPLE_RATE.toString(),
    val ttsVolume: String = VoiceRuntimeConfiguration.DEFAULT_TTS_VOLUME.toString(),
    val ttsRate: String = VoiceRuntimeConfiguration.DEFAULT_TTS_RATE.toString(),
    val ttsPitch: String = VoiceRuntimeConfiguration.DEFAULT_TTS_PITCH.toString(),
    val voiceLanguage: String = VoiceRuntimeConfiguration.DEFAULT_LANGUAGE,
    val revision: Long? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val fieldError: String? = null,
    val resultMessage: String? = null,
    val resultIsError: Boolean = false,
)

class ModelConfigurationViewModel(
    private val store: ModelConfigurationStore,
    private val familyRepository: FamilyModelConfigurationRepository? = null,
    private val elderRepository: ElderModelConfigurationRepository? = null,
    private val allowCleartextHttp: Boolean,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val _uiState = MutableStateFlow(store.configuration.value.toUiState())
    val uiState: StateFlow<ModelConfigurationUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var elderSyncJob: Job? = null
    private var loadedElderId: String? = null
    private var pendingRequestId: String? = null

    init {
        workScope.launch {
            store.initialize()
            if (loadedElderId == null) {
                _uiState.value = store.configuration.value.toUiState()
            }
        }
    }

    fun loadForFamily(elderId: String?) {
        val targetElderId = elderId?.takeIf(String::isNotBlank) ?: run {
            showFailure("尚未取得老人档案，请先完成绑定。")
            return
        }
        val repository = familyRepository ?: run {
            showFailure("中台模型配置接口尚未接入。")
            return
        }
        if (loadJob?.isActive == true || loadedElderId == targetElderId) return
        loadJob = workScope.launch {
            _uiState.update { it.copy(isLoading = true, resultMessage = null) }
            try {
                val remote = repository.getFamilyModelConfiguration(targetElderId)
                loadedElderId = targetElderId
                pendingRequestId = null
                _uiState.value = (remote ?: store.configuration.value).toUiState().copy(
                    resultMessage = if (remote == null) {
                        "尚未下发过配置，当前显示应用默认值。"
                    } else {
                        "已加载老人端模型配置。"
                    },
                )
            } catch (error: MiddleServerRequestException) {
                showFailure(error.userMessage)
            } catch (_: Exception) {
                showFailure("模型配置加载失败，请稍后重试。")
            }
        }
    }

    fun saveForFamily(elderId: String?): Boolean {
        if (_uiState.value.isSaving) return false
        val targetElderId = elderId?.takeIf(String::isNotBlank) ?: run {
            showFailure("尚未取得老人档案，请先完成绑定。")
            return false
        }
        val repository = familyRepository ?: run {
            showFailure("中台模型配置接口尚未接入。")
            return false
        }
        val configuration = parseConfiguration() ?: return false
        val requestId = pendingRequestId ?: UUID.randomUUID().toString().also {
            pendingRequestId = it
        }
        saveJob = workScope.launch {
            _uiState.update { it.copy(isSaving = true, resultMessage = null) }
            try {
                val saved = repository.updateFamilyModelConfiguration(
                    FamilyModelConfigurationUpdateRequest(
                        elderId = targetElderId,
                        configuration = configuration,
                        expectedRevision = _uiState.value.revision,
                        clientRequestId = requestId,
                    ),
                )
                pendingRequestId = null
                loadedElderId = targetElderId
                _uiState.value = saved.toUiState().copy(
                    resultMessage = "配置已交给中台，老人端联网后会自动使用。",
                    resultIsError = false,
                )
            } catch (error: MiddleServerRequestException) {
                showFailure(error.userMessage)
            } catch (_: Exception) {
                showFailure("模型配置保存失败，请稍后重试。")
            }
        }
        return true
    }

    fun syncElderConfiguration() {
        val repository = elderRepository ?: return
        if (elderSyncJob?.isActive == true) return
        elderSyncJob = workScope.launch {
            runCatching { store.initialize() }
            try {
                repository.getElderModelConfiguration()?.let { remote ->
                    remote.validate(allowCleartextHttp)
                    store.save(remote)
                }
            } catch (_: Exception) {
                // 保留上次可用的本地配置；聊天仍可离线恢复配置。
            }
        }
    }

    fun updateBaseUrl(value: String) = updateDraft { copy(baseUrl = value.take(500)) }
    fun updateModel(value: String) = updateDraft { copy(model = value.take(120)) }
    fun updateDialect(value: OpenAiCompatibleDialect) = updateDraft { copy(dialect = value) }
    fun updateContextWindowTokens(value: String) =
        updateDraft { copy(contextWindowTokens = value.filter(Char::isDigit).take(7)) }
    fun updateMaxOutputTokens(value: String) =
        updateDraft { copy(maxOutputTokens = value.filter(Char::isDigit).take(5)) }
    fun updateTemperature(value: String) =
        updateDraft { copy(temperature = value.filterDecimal().take(5)) }
    fun updateTopP(value: String) = updateDraft { copy(topP = value.filterDecimal().take(5)) }
    fun updateTopK(value: String) = updateDraft { copy(topK = value.filter(Char::isDigit).take(4)) }
    fun updateVoiceWebSocketUrl(value: String) =
        updateDraft { copy(voiceWebSocketUrl = value.take(500)) }
    fun updateAsrModel(value: String) = updateDraft { copy(asrModel = value.take(120)) }
    fun updateTtsModel(value: String) = updateDraft { copy(ttsModel = value.take(120)) }
    fun updateTtsVoice(value: String) = updateDraft { copy(ttsVoice = value.take(120)) }
    fun updateTtsResponseFormat(value: VoiceAudioFormat) =
        updateDraft { copy(ttsResponseFormat = value) }
    fun updateTtsSampleRate(value: String) =
        updateDraft { copy(ttsSampleRate = value.filter(Char::isDigit).take(5)) }
    fun updateTtsVolume(value: String) =
        updateDraft { copy(ttsVolume = value.filter(Char::isDigit).take(3)) }
    fun updateTtsRate(value: String) =
        updateDraft { copy(ttsRate = value.filterDecimal().take(4)) }
    fun updateTtsPitch(value: String) =
        updateDraft { copy(ttsPitch = value.filterDecimal().take(4)) }
    fun updateVoiceLanguage(value: String) = updateDraft {
        copy(voiceLanguage = value.filter { it.isLetter() || it == '-' }.take(10))
    }

    private fun updateDraft(transform: ModelConfigurationUiState.() -> ModelConfigurationUiState) {
        pendingRequestId = null
        _uiState.update {
            it.transform().copy(fieldError = null, resultMessage = null)
        }
    }

    private fun parseConfiguration(): ModelRuntimeConfiguration? {
        val state = _uiState.value
        val contextWindowTokens = state.contextWindowTokens.toIntOrNull()
            ?: return fieldFailure("请填写上下文长度")
        val maxTokens = state.maxOutputTokens.toIntOrNull()
            ?: return fieldFailure("请填写最大生成 Token")
        val temperature = state.temperature.toDoubleOrNull()
            ?: return fieldFailure("请填写 Temperature")
        val topP = state.topP.toDoubleOrNull()
            ?: return fieldFailure("请填写 Top-p")
        val topK = state.topK.toIntOrNull()
            ?: return fieldFailure("请填写 Top-k")
        val voice = if (state.voiceWebSocketUrl.isBlank()) {
            null
        } else {
            val sampleRate = state.ttsSampleRate.toIntOrNull()
                ?: return fieldFailure("请填写 TTS 采样率")
            val volume = state.ttsVolume.toIntOrNull()
                ?: return fieldFailure("请填写 TTS 音量")
            val rate = state.ttsRate.toDoubleOrNull()
                ?: return fieldFailure("请填写 TTS 语速")
            val pitch = state.ttsPitch.toDoubleOrNull()
                ?: return fieldFailure("请填写 TTS 音调")
            VoiceRuntimeConfiguration(
                webSocketUrl = state.voiceWebSocketUrl.trim().trimEnd('/'),
                asrModel = state.asrModel.trim(),
                ttsModel = state.ttsModel.trim(),
                ttsVoice = state.ttsVoice.trim(),
                ttsResponseFormat = state.ttsResponseFormat,
                ttsSampleRate = sampleRate,
                ttsVolume = volume,
                ttsRate = rate,
                ttsPitch = pitch,
                language = state.voiceLanguage.trim(),
            )
        }
        val parsed = runCatching {
            ModelRuntimeConfiguration(
                revision = state.revision ?: 0,
                baseUrl = state.baseUrl.trim().trimEnd('/'),
                model = state.model.trim(),
                dialect = state.dialect,
                contextWindowTokens = contextWindowTokens,
                maxOutputTokens = maxTokens,
                temperature = temperature,
                topP = topP,
                topK = topK,
                voice = voice,
            ).also { it.validate(allowCleartextHttp) }
        }
        val error = parsed.exceptionOrNull()?.message
        if (error != null) {
            _uiState.update { it.copy(fieldError = error, resultMessage = null) }
            return null
        }
        return parsed.getOrThrow()
    }

    private fun fieldFailure(message: String): ModelRuntimeConfiguration? {
        _uiState.update { it.copy(fieldError = message, resultMessage = null) }
        return null
    }

    private fun showFailure(message: String) {
        _uiState.update {
            it.copy(
                isLoading = false,
                isSaving = false,
                resultMessage = message,
                resultIsError = true,
            )
        }
    }

    class Factory(
        private val store: ModelConfigurationStore,
        private val familyRepository: FamilyModelConfigurationRepository?,
        private val elderRepository: ElderModelConfigurationRepository?,
        private val allowCleartextHttp: Boolean,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ModelConfigurationViewModel::class.java))
            return ModelConfigurationViewModel(
                store = store,
                familyRepository = familyRepository,
                elderRepository = elderRepository,
                allowCleartextHttp = allowCleartextHttp,
            ) as T
        }
    }
}

private fun ModelRuntimeConfiguration.toUiState() = ModelConfigurationUiState(
    baseUrl = baseUrl,
    model = model,
    dialect = dialect,
    contextWindowTokens = contextWindowTokens.toString(),
    maxOutputTokens = maxOutputTokens.toString(),
    temperature = temperature.toString(),
    topP = topP.toString(),
    topK = topK.toString(),
    voiceWebSocketUrl = voice?.webSocketUrl.orEmpty(),
    asrModel = voice?.asrModel ?: VoiceRuntimeConfiguration.DEFAULT_ASR_MODEL,
    ttsModel = voice?.ttsModel ?: VoiceRuntimeConfiguration.DEFAULT_TTS_MODEL,
    ttsVoice = voice?.ttsVoice ?: VoiceRuntimeConfiguration.DEFAULT_TTS_VOICE,
    ttsResponseFormat = voice?.ttsResponseFormat ?: VoiceAudioFormat.Pcm,
    ttsSampleRate = (voice?.ttsSampleRate
        ?: VoiceRuntimeConfiguration.DEFAULT_TTS_SAMPLE_RATE).toString(),
    ttsVolume = (voice?.ttsVolume ?: VoiceRuntimeConfiguration.DEFAULT_TTS_VOLUME).toString(),
    ttsRate = (voice?.ttsRate ?: VoiceRuntimeConfiguration.DEFAULT_TTS_RATE).toString(),
    ttsPitch = (voice?.ttsPitch ?: VoiceRuntimeConfiguration.DEFAULT_TTS_PITCH).toString(),
    voiceLanguage = voice?.language ?: VoiceRuntimeConfiguration.DEFAULT_LANGUAGE,
    revision = revision.takeIf { it > 0 },
)

private fun String.filterDecimal(): String {
    var decimalSeen = false
    return filter { character ->
        when {
            character.isDigit() -> true
            character == '.' && !decimalSeen -> {
                decimalSeen = true
                true
            }
            else -> false
        }
    }
}
