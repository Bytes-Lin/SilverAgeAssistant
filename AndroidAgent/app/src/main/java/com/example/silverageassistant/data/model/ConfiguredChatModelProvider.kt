package com.example.silverageassistant.data.model

import com.example.silverageassistant.domain.model.ChatModelException
import com.example.silverageassistant.domain.model.ChatModelProvider
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class ConfiguredChatModelProvider(
    private val configurationStore: ModelConfigurationStore,
    private val credentialStore: ModelApiCredentialStore,
) : ChatModelProvider {
    @Volatile
    private var cachedDelegate: CachedDelegate? = null

    override fun stream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
        val configuration = configurationStore.configuration.value
        if (configuration.baseUrl.isBlank() || configuration.model.isBlank()) {
            throw ChatModelException(
                code = "MODEL_NOT_CONFIGURED",
                userMessage = "模型服务尚未配置，请让家属完成设置。",
            )
        }
        val serviceConfig = configuration.toServiceConfig()
        val delegate = cachedDelegate
            ?.takeIf { it.configuration == serviceConfig }
            ?.provider
            ?: OpenAiCompatibleChatProvider(
                config = serviceConfig,
                credentialStore = credentialStore,
            ).also {
                cachedDelegate = CachedDelegate(serviceConfig, it)
            }
        emitAll(delegate.stream(request))
    }

    private data class CachedDelegate(
        val configuration: ModelServiceConfig,
        val provider: OpenAiCompatibleChatProvider,
    )
}
