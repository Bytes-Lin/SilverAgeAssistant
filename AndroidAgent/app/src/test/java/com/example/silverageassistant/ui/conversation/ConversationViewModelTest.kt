package com.example.silverageassistant.ui.conversation

import com.example.silverageassistant.domain.agent.AgentChatCoordinator
import com.example.silverageassistant.domain.agent.AgentToolRegistry
import com.example.silverageassistant.domain.gui.GuiTaskChatFeedback
import com.example.silverageassistant.domain.gui.GuiTaskChatFeedbackBus
import com.example.silverageassistant.domain.model.ChatModelException
import com.example.silverageassistant.domain.model.ChatModelProvider
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationViewModelTest {
    @Test
    fun sendingTypedMessage_streamsAssistantBubble() {
        val provider = FakeProvider {
            flow {
                emit(ChatStreamEvent.TextDelta("您好，"))
                emit(ChatStreamEvent.TextDelta("很高兴和您聊天。"))
                emit(ChatStreamEvent.Completed("stop"))
            }
        }
        val viewModel = viewModel(provider)
        viewModel.updateDraft("  今天阳光真好。  ")

        viewModel.sendTextMessage()

        val state = viewModel.uiState.value
        assertEquals(3, state.messages.size)
        assertEquals(ConversationSpeaker.Elder, state.messages[state.messages.lastIndex - 1].speaker)
        assertEquals("今天阳光真好。", state.messages[state.messages.lastIndex - 1].text)
        assertEquals("您好，很高兴和您聊天。", state.messages.last().text)
        assertEquals(ConversationMessageStatus.Complete, state.messages.last().status)
        assertEquals(ConversationPhase.Idle, state.phase)
        assertEquals("", state.draft)
        assertFalse(state.canSendText)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun modelFailure_keepsUserMessageAndAllowsRetry() {
        val viewModel = viewModel(
            FakeProvider {
                flow {
                    throw ChatModelException("MODEL_BUSY", "模型现在比较忙，请稍后再试。")
                }
            },
        )
        viewModel.updateDraft("您好")

        viewModel.sendTextMessage()

        val state = viewModel.uiState.value
        assertEquals("您好", state.messages.last().text)
        assertEquals("模型现在比较忙，请稍后再试。", state.errorMessage)
        assertTrue(state.canRetry)
        assertEquals(ConversationPhase.Idle, state.phase)
    }

    @Test
    fun cancellingStream_keepsPartialAnswerAsInterrupted() {
        val viewModel = viewModel(
            FakeProvider {
                flow {
                    emit(ChatStreamEvent.TextDelta("这是一段"))
                    awaitCancellation()
                }
            },
        )
        viewModel.updateDraft("请回答")
        viewModel.sendTextMessage()

        viewModel.cancelResponse()

        val state = viewModel.uiState.value
        assertEquals("这是一段", state.messages.last().text)
        assertEquals(ConversationMessageStatus.Interrupted, state.messages.last().status)
        assertTrue(state.canRetry)
        assertEquals(ConversationPhase.Idle, state.phase)
    }

    @Test
    fun blankMessage_isNotAdded() {
        val viewModel = viewModel(FakeProvider { flow { } })
        val initialMessages = viewModel.uiState.value.messages
        viewModel.updateDraft("   ")

        viewModel.sendTextMessage()

        assertEquals(initialMessages, viewModel.uiState.value.messages)
    }

    @Test
    fun handwritingMode_usesTheSameStringDraft() {
        val viewModel = viewModel(FakeProvider { flow { } })

        viewModel.selectInputMode(ConversationInputMode.Handwriting)
        viewModel.updateDraft("手写识别文字")

        assertEquals(ConversationInputMode.Handwriting, viewModel.uiState.value.inputMode)
        assertEquals("手写识别文字", viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.canSendText)
    }

    @Test
    fun guiTaskTerminalFeedback_isAppendedWithoutAnotherModelRequest() = runBlocking {
        val bus = GuiTaskChatFeedbackBus()
        val viewModel = ConversationViewModel(
            guiTaskChatFeedbackSource = bus,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        bus.publish(GuiTaskChatFeedback.Completed("todo-success"))
        bus.publish(
            GuiTaskChatFeedback.Failed(
                todoId = "todo-failed",
                familyNotified = true,
            ),
        )

        assertEquals("已完成任务。", viewModel.uiState.value.messages.takeLast(2)[0].text)
        assertEquals("任务失败，已通知家人。", viewModel.uiState.value.messages.last().text)
    }

    private fun viewModel(provider: ChatModelProvider) = ConversationViewModel(
        coordinator = AgentChatCoordinator(provider, AgentToolRegistry(emptyList())),
        externalScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private class FakeProvider(
        private val response: (ChatRequest) -> Flow<ChatStreamEvent>,
    ) : ChatModelProvider {
        val requests = mutableListOf<ChatRequest>()

        override fun stream(request: ChatRequest): Flow<ChatStreamEvent> {
            requests += request
            return response(request)
        }
    }
}
