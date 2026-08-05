package com.example.silverageassistant.domain.gui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface GuiTaskChatFeedback {
    val todoId: String

    data class Completed(override val todoId: String) : GuiTaskChatFeedback

    data class Failed(
        override val todoId: String,
        val familyNotified: Boolean,
    ) : GuiTaskChatFeedback
}

fun interface GuiTaskChatFeedbackSink {
    suspend fun publish(feedback: GuiTaskChatFeedback)
}

interface GuiTaskChatFeedbackSource {
    val feedback: Flow<GuiTaskChatFeedback>
}

/** Process-local bridge; chat text and GUI task results remain out of Room and long-term memory. */
class GuiTaskChatFeedbackBus : GuiTaskChatFeedbackSink, GuiTaskChatFeedbackSource {
    private val mutableFeedback = MutableSharedFlow<GuiTaskChatFeedback>(extraBufferCapacity = 8)
    override val feedback: Flow<GuiTaskChatFeedback> = mutableFeedback.asSharedFlow()

    override suspend fun publish(feedback: GuiTaskChatFeedback) {
        mutableFeedback.emit(feedback)
    }
}

object NoOpGuiTaskChatFeedbackSink : GuiTaskChatFeedbackSink {
    override suspend fun publish(feedback: GuiTaskChatFeedback) = Unit
}
