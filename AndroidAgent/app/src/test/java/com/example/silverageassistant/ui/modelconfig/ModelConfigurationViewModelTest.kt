package com.example.silverageassistant.ui.modelconfig

import com.example.silverageassistant.data.middleserver.ElderModelConfigurationRepository
import com.example.silverageassistant.data.middleserver.FamilyModelConfigurationRepository
import com.example.silverageassistant.data.middleserver.FamilyModelConfigurationUpdateRequest
import com.example.silverageassistant.data.model.InMemoryModelConfigurationStore
import com.example.silverageassistant.data.model.ModelRuntimeConfiguration
import com.example.silverageassistant.data.model.OpenAiCompatibleDialect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigurationViewModelTest {
    @Test
    fun familySave_validatesAndSubmitsNonSecretConfiguration() {
        val initial = configuration()
        val repository = FakeFamilyRepository(
            savedResult = initial.copy(revision = 4, model = "qwen3_5"),
        )
        val viewModel = ModelConfigurationViewModel(
            store = InMemoryModelConfigurationStore(initial),
            familyRepository = repository,
            allowCleartextHttp = true,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )
        viewModel.updateBaseUrl("http://58.199.163.98:11435/")
        viewModel.updateModel("qwen3_5")
        viewModel.updateContextWindowTokens("65536")
        viewModel.updateMaxOutputTokens("768")
        viewModel.updateTemperature("0.7")
        viewModel.updateTopP("0.85")
        viewModel.updateTopK("30")
        viewModel.updateVoiceWebSocketUrl(
            "wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference/",
        )

        assertTrue(viewModel.saveForFamily("elder-1"))

        val request = requireNotNull(repository.lastRequest)
        assertEquals("elder-1", request.elderId)
        assertEquals("http://58.199.163.98:11435", request.configuration.baseUrl)
        assertEquals(65536, request.configuration.contextWindowTokens)
        assertEquals(768, request.configuration.maxOutputTokens)
        assertEquals(0.7, request.configuration.temperature, 0.0)
        assertEquals(
            "wss://workspace.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
            request.configuration.voice?.webSocketUrl,
        )
        assertEquals(
            "qwen-audio-3.0-asr-flash-streaming",
            request.configuration.voice?.asrModel,
        )
        assertEquals("qwen-audio-3.0-tts-flash", request.configuration.voice?.ttsModel)
        assertEquals("longanfengyue", request.configuration.voice?.ttsVoice)
        assertEquals(4L, viewModel.uiState.value.revision)
        assertEquals("配置已交给中台，老人端联网后会自动使用。", viewModel.uiState.value.resultMessage)
    }

    @Test
    fun invalidReleaseHttp_isRejectedBeforeNetworkRequest() {
        val repository = FakeFamilyRepository(savedResult = configuration())
        val viewModel = ModelConfigurationViewModel(
            store = InMemoryModelConfigurationStore(configuration()),
            familyRepository = repository,
            allowCleartextHttp = false,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )
        viewModel.updateBaseUrl("http://model.example.com")

        assertFalse(viewModel.saveForFamily("elder-1"))

        assertNull(repository.lastRequest)
        assertEquals("正式版模型地址必须使用 https://", viewModel.uiState.value.fieldError)
    }

    @Test
    fun elderSync_persistsRemoteConfigurationForNextChatTurn() {
        val local = configuration()
        val remote = local.copy(
            revision = 5,
            baseUrl = "https://model.example.com",
            model = "cloud-model",
            dialect = OpenAiCompatibleDialect.Standard,
            contextWindowTokens = 131072,
            maxOutputTokens = 1024,
        )
        val store = InMemoryModelConfigurationStore(local)
        val viewModel = ModelConfigurationViewModel(
            store = store,
            elderRepository = FakeElderRepository(remote),
            allowCleartextHttp = false,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        viewModel.syncElderConfiguration()

        assertEquals(remote, store.configuration.value)
    }

    @Test
    fun invalidVoiceWebSocketUrl_isRejectedBeforeNetworkRequest() {
        val repository = FakeFamilyRepository(savedResult = configuration())
        val viewModel = ModelConfigurationViewModel(
            store = InMemoryModelConfigurationStore(configuration()),
            familyRepository = repository,
            allowCleartextHttp = true,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )
        viewModel.updateVoiceWebSocketUrl("ws://example.com/api-ws/v1/inference")

        assertFalse(viewModel.saveForFamily("elder-1"))

        assertNull(repository.lastRequest)
        assertEquals(
            "语音服务地址必须是安全的 wss:// 地址，且不能包含账号、查询参数或页面片段",
            viewModel.uiState.value.fieldError,
        )
    }

    private fun configuration() = ModelRuntimeConfiguration(
        baseUrl = "http://58.199.163.98:11435",
        model = "qwen3_5",
        dialect = OpenAiCompatibleDialect.LlamaCpp,
    )

    private class FakeFamilyRepository(
        private val loaded: ModelRuntimeConfiguration? = null,
        private val savedResult: ModelRuntimeConfiguration,
    ) : FamilyModelConfigurationRepository {
        var lastRequest: FamilyModelConfigurationUpdateRequest? = null

        override suspend fun getFamilyModelConfiguration(
            elderId: String,
        ): ModelRuntimeConfiguration? = loaded

        override suspend fun updateFamilyModelConfiguration(
            request: FamilyModelConfigurationUpdateRequest,
        ): ModelRuntimeConfiguration {
            lastRequest = request
            return savedResult
        }
    }

    private class FakeElderRepository(
        private val configuration: ModelRuntimeConfiguration?,
    ) : ElderModelConfigurationRepository {
        override suspend fun getElderModelConfiguration(): ModelRuntimeConfiguration? =
            configuration
    }
}
