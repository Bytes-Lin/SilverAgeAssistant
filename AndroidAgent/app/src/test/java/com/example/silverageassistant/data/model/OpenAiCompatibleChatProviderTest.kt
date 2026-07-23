package com.example.silverageassistant.data.model

import com.example.silverageassistant.domain.model.ChatMessage
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatRole
import com.example.silverageassistant.domain.model.ChatStreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleChatProviderTest {
    @Test
    fun sseResponse_filtersReasoningAndUsesBearerCredential() = runBlocking {
        var authorizationHeader: String? = null
        val sse = """
            data: {"choices":[{"delta":{"reasoning_content":"内部推理"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"<think>不要展示</think>您好"},"finish_reason":null}]}

            data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                authorizationHeader = chain.request().header("Authorization")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(sse.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
            .build()
        val provider = OpenAiCompatibleChatProvider(
            config = ModelServiceConfig(
                baseUrl = "http://model.test",
                model = "qwen3_5",
                dialect = OpenAiCompatibleDialect.LlamaCpp,
            ),
            credentialStore = InMemoryModelApiCredentialStore("test-api-key"),
            client = client,
        )

        val events = withTimeout(2_000) {
            provider.stream(
                ChatRequest(
                    messages = listOf(ChatMessage(ChatRole.User, "您好")),
                ),
            ).toList()
        }

        assertEquals("Bearer test-api-key", authorizationHeader)
        assertTrue(events.contains(ChatStreamEvent.ReasoningStarted))
        assertTrue(events.contains(ChatStreamEvent.TextDelta("您好")))
        assertFalse(
            events.filterIsInstance<ChatStreamEvent.TextDelta>()
                .any { it.text.contains("不要展示") || it.text.contains("内部推理") },
        )
        assertTrue(events.any { it is ChatStreamEvent.Usage && it.usage.totalTokens == 15L })
        assertTrue(events.contains(ChatStreamEvent.Completed("stop")))
    }
}
