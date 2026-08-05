package com.example.silverageassistant.data.model

import com.example.silverageassistant.domain.agent.AgentChatOptions
import com.example.silverageassistant.domain.agent.AgentChatOptionsProvider
import com.example.silverageassistant.data.usage.ModelUsagePolicy
import com.example.silverageassistant.domain.model.ReasoningMode
import com.example.silverageassistant.domain.model.SamplingConfig

enum class VoiceAudioFormat(val wireName: String) {
    Pcm("pcm"),
    Wav("wav"),
    Mp3("mp3"),
    Opus("opus"),
    ;

    companion object {
        fun fromWireName(value: String): VoiceAudioFormat = entries.firstOrNull {
            it.wireName == value.lowercase()
        } ?: error("Unknown voice audio format")
    }
}

data class VoiceRuntimeConfiguration(
    val webSocketUrl: String,
    val asrModel: String = DEFAULT_ASR_MODEL,
    val ttsModel: String = DEFAULT_TTS_MODEL,
    val ttsVoice: String = DEFAULT_TTS_VOICE,
    val ttsResponseFormat: VoiceAudioFormat = VoiceAudioFormat.Pcm,
    val ttsSampleRate: Int = DEFAULT_TTS_SAMPLE_RATE,
    val ttsVolume: Int = DEFAULT_TTS_VOLUME,
    val ttsRate: Double = DEFAULT_TTS_RATE,
    val ttsPitch: Double = DEFAULT_TTS_PITCH,
    val language: String = DEFAULT_LANGUAGE,
) {
    fun validate() {
        require(webSocketUrl.isNotBlank() && webSocketUrl.length <= 500) {
            "请填写语音服务 WebSocket 地址"
        }
        val uri = runCatching { java.net.URI(webSocketUrl) }.getOrNull()
        require(
            uri?.scheme?.lowercase() == "wss" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null,
        ) {
            "语音服务地址必须是安全的 wss:// 地址，且不能包含账号、查询参数或页面片段"
        }
        require(uri?.path?.trimEnd('/') == "/api-ws/v1/inference") {
            "语音服务地址路径应为 /api-ws/v1/inference"
        }
        require(
            uri?.host?.endsWith(".maas.aliyuncs.com") == true ||
                uri?.host == "dashscope.aliyuncs.com" ||
                uri?.host == "dashscope-intl.aliyuncs.com",
        ) {
            "语音服务地址必须使用阿里云百炼官方域名"
        }
        require(asrModel.isNotBlank() && asrModel.length <= 120) { "请填写 ASR 模型名称" }
        require(ttsModel.isNotBlank() && ttsModel.length <= 120) { "请填写 TTS 模型名称" }
        require(ttsVoice.isNotBlank() && ttsVoice.length <= 120) { "请填写 TTS 音色" }
        require(ttsSampleRate in SUPPORTED_TTS_SAMPLE_RATES) { "TTS 采样率不受支持" }
        require(ttsVolume in 0..100) { "TTS 音量应为 0 到 100" }
        require(ttsRate in 0.5..2.0) { "TTS 语速应为 0.5 到 2.0" }
        require(ttsPitch in 0.5..2.0) { "TTS 音调应为 0.5 到 2.0" }
        require(language.matches(Regex("^[a-z]{2,3}(-[A-Z]{2})?$"))) { "语音语言代码格式不正确" }
    }

    companion object {
        const val DEFAULT_ASR_MODEL = "qwen-audio-3.0-asr-flash-streaming"
        const val DEFAULT_TTS_MODEL = "qwen-audio-3.0-tts-flash"
        const val DEFAULT_TTS_VOICE = "longanfengyue"
        const val DEFAULT_TTS_SAMPLE_RATE = 22_050
        const val DEFAULT_TTS_VOLUME = 50
        const val DEFAULT_TTS_RATE = 0.9
        const val DEFAULT_TTS_PITCH = 1.0
        const val DEFAULT_LANGUAGE = "zh"
        val SUPPORTED_TTS_SAMPLE_RATES = setOf(8_000, 16_000, 22_050, 24_000, 44_100, 48_000)
    }
}

data class ModelRuntimeConfiguration(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revision: Long = 0,
    val baseUrl: String,
    val model: String,
    val dialect: OpenAiCompatibleDialect = OpenAiCompatibleDialect.LlamaCpp,
    val contextWindowTokens: Int = ModelUsagePolicy.DEFAULT_CONTEXT_WINDOW_TOKENS.toInt(),
    val maxOutputTokens: Int = 512,
    val temperature: Double = 0.6,
    val topP: Double = 0.9,
    val topK: Int = 40,
    val voice: VoiceRuntimeConfiguration? = null,
    val updatedAt: String? = null,
) {
    fun validate(allowCleartextHttp: Boolean) {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "不支持这个配置文件版本" }
        require(baseUrl.isNotBlank() && baseUrl.length <= 500) { "请填写模型服务地址" }
        require(model.isNotBlank() && model.length <= 120) { "请填写模型名称" }
        val uri = runCatching { java.net.URI(baseUrl) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        require(scheme == "https" || allowCleartextHttp && scheme == "http") {
            if (allowCleartextHttp) "模型地址应以 http:// 或 https:// 开头" else "正式版模型地址必须使用 https://"
        }
        require(
            !uri?.host.isNullOrBlank() &&
                uri?.userInfo == null &&
                uri?.query == null &&
                uri?.fragment == null,
        ) {
            "模型地址不能包含账号密码、查询参数或页面片段"
        }
        require(maxOutputTokens in 64..8192) { "最大生成 Token 应为 64 到 8192" }
        require(contextWindowTokens in 1024..2_000_000) {
            "上下文长度应为 1024 到 2000000 Token"
        }
        require(contextWindowTokens >= maxOutputTokens) {
            "上下文长度不能小于最大生成 Token"
        }
        SamplingConfig(temperature = temperature, topP = topP, topK = topK)
        require(topK <= 1000) { "top-k 应为 0 到 1000" }
        voice?.validate()
    }

    fun toServiceConfig() = ModelServiceConfig(
        baseUrl = baseUrl,
        model = model,
        dialect = dialect,
    )

    fun toAgentOptions() = AgentChatOptions(
        sampling = SamplingConfig(
            temperature = temperature,
            topP = topP,
            topK = topK,
        ),
        reasoningMode = ReasoningMode.Disabled,
        maxOutputTokens = maxOutputTokens,
        contextWindowTokens = contextWindowTokens.toLong(),
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

class ModelConfigurationAgentOptionsProvider(
    private val store: ModelConfigurationStore,
) : AgentChatOptionsProvider {
    override suspend fun options(): AgentChatOptions =
        store.configuration.value.toAgentOptions()
}
