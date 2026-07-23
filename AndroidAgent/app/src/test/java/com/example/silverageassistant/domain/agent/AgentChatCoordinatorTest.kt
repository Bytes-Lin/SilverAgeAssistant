package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.domain.model.ChatMessage
import com.example.silverageassistant.domain.model.ChatModelProvider
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatRole
import com.example.silverageassistant.domain.model.ChatStreamEvent
import com.example.silverageassistant.domain.model.ChatToolCall
import com.example.silverageassistant.domain.model.ReasoningMode
import com.example.silverageassistant.domain.model.SamplingConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatCoordinatorTest {
    @Test
    fun runtimeOptionsProvider_appliesLatestGenerationConfiguration() = runBlocking {
        val requests = mutableListOf<ChatRequest>()
        val provider = object : ChatModelProvider {
            override fun stream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
                    requests += request
                    emit(ChatStreamEvent.TextDelta("好的。"))
                    emit(ChatStreamEvent.Completed("stop"))
                }
            }
        val coordinator = AgentChatCoordinator(
            provider = provider,
            toolRegistry = AgentToolRegistry(emptyList()),
            optionsProvider = AgentChatOptionsProvider {
                AgentChatOptions(
                    sampling = SamplingConfig(temperature = 0.8, topP = 0.75, topK = 20),
                    reasoningMode = ReasoningMode.Disabled,
                    maxOutputTokens = 1024,
                    contextWindowTokens = 65536,
                )
            },
        )

        val events = coordinator.streamTurn(emptyList(), "你好").toList()

        val request = requests.single()
        assertEquals(0.8, request.sampling.temperature, 0.0)
        assertEquals(0.75, request.sampling.topP, 0.0)
        assertEquals(20, request.sampling.topK)
        assertEquals(1024, request.maxOutputTokens)
        assertEquals(ReasoningMode.Disabled, request.reasoningMode)
        assertEquals(AgentChatEvent.Started(65536), events.first())
    }

    @Test
    fun timeTool_isExecutedAndReturnedToSecondModelRound() = runBlocking {
        val provider = TimeToolProvider()
        val coordinator = AgentChatCoordinator(
            provider = provider,
            toolRegistry = AgentToolRegistry(
                listOf(
                    CurrentTimeTool(
                        Clock.fixed(
                            Instant.parse("2026-07-17T02:30:00Z"),
                            ZoneId.of("Asia/Shanghai"),
                        ),
                    ),
                ),
            ),
        )

        val events = coordinator.streamTurn(emptyList(), "现在几点？").toList()

        assertEquals(2, provider.requests.size)
        assertEquals(ReasoningMode.Disabled, provider.requests.first().reasoningMode)
        assertEquals(0.6, provider.requests.first().sampling.temperature, 0.0)
        assertEquals(0.9, provider.requests.first().sampling.topP, 0.0)
        assertEquals(40, provider.requests.first().sampling.topK)
        assertTrue(
            provider.requests[1].messages.any {
                it.role == ChatRole.Tool &&
                    it.content?.contains("10:30") == true &&
                    it.content.contains("Asia/Shanghai")
            },
        )
        assertTrue(events.contains(AgentChatEvent.TextDelta("现在是上午十点半。")))
        assertTrue(events.contains(AgentChatEvent.Completed))
    }

    private class TimeToolProvider : ChatModelProvider {
        val requests = mutableListOf<ChatRequest>()

        override fun stream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
            requests += request
            if (requests.size == 1) {
                emit(
                    ChatStreamEvent.ToolCallReady(
                        ChatToolCall(
                            id = "call-1",
                            name = CurrentTimeTool.NAME,
                            argumentsJson = "{}",
                        ),
                    ),
                )
                emit(ChatStreamEvent.Completed("tool_calls"))
            } else {
                emit(ChatStreamEvent.TextDelta("现在是上午十点半。"))
                emit(ChatStreamEvent.Completed("stop"))
            }
        }
    }
}
