package com.example.silverageassistant.data.model

import com.example.silverageassistant.domain.agent.AgentChatOptions
import com.example.silverageassistant.domain.agent.AgentChatOptionsProvider
import com.example.silverageassistant.domain.model.ReasoningMode
import com.example.silverageassistant.domain.model.SamplingConfig
import com.example.silverageassistant.data.usage.ModelUsagePolicy

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
