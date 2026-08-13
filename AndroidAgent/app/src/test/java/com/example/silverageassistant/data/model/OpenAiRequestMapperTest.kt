package com.example.silverageassistant.data.model

import com.example.silverageassistant.domain.model.ChatMessage
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatRole
import com.example.silverageassistant.domain.model.ChatToolDefinition
import com.example.silverageassistant.domain.model.ReasoningMode
import com.example.silverageassistant.domain.model.SamplingConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenAiRequestMapperTest {
    @Test
    fun llamaRequest_containsOnlySelectedSamplingAndDisablesThinking() {
        val payload = OpenAiRequestMapper(
            ModelServiceConfig(
                baseUrl = "https://model-provider.example.invalid",
                model = "qwen3_5",
                dialect = OpenAiCompatibleDialect.LlamaCpp,
            ),
        ).encode(
            ChatRequest(
                messages = listOf(
                    ChatMessage(ChatRole.System, "system"),
                    ChatMessage(ChatRole.User, "现在几点"),
                ),
                tools = listOf(
                    ChatToolDefinition(
                        name = "get_current_time",
                        description = "查询时间",
                        parametersJson = """{"type":"object","properties":{}}""",
                    ),
                ),
                sampling = SamplingConfig(temperature = 0.5, topP = 0.8, topK = 30),
                reasoningMode = ReasoningMode.Disabled,
            ),
        )

        val root = Json.parseToJsonElement(payload).jsonObject
        assertEquals("qwen3_5", root["model"]?.jsonPrimitive?.content)
        assertEquals(0.5, root["temperature"]?.jsonPrimitive?.double)
        assertEquals(0.8, root["top_p"]?.jsonPrimitive?.double)
        assertEquals(30, root["top_k"]?.jsonPrimitive?.int)
        assertFalse(
            root["chat_template_kwargs"]
                ?.jsonObject
                ?.get("enable_thinking")
                ?.jsonPrimitive
                ?.boolean ?: true,
        )
        assertEquals(
            "get_current_time",
            root["tools"]
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("function")
                ?.jsonObject
                ?.get("name")
                ?.jsonPrimitive
                ?.content,
        )
        assertFalse(root.containsKey("frequency_penalty"))
        assertFalse(root.containsKey("presence_penalty"))
        assertFalse(root.containsKey("min_p"))
    }

    @Test
    fun baseUrl_withoutV1_isNormalized() {
        val config = ModelServiceConfig(
            baseUrl = "https://model-provider.example.invalid/",
            model = "qwen3_5",
            dialect = OpenAiCompatibleDialect.LlamaCpp,
        )

        assertEquals(
            "https://model-provider.example.invalid/v1/chat/completions",
            config.chatCompletionsUrl,
        )
    }
}
