package com.example.silverageassistant.service

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local one-shot navigation request from the GUI terminal handler to Compose navigation. */
object GuiTaskNavigationBridge {
    private val sequence = AtomicLong(0)
    private val mutableConversationRequest = MutableStateFlow<Long?>(null)
    val conversationRequest: StateFlow<Long?> = mutableConversationRequest.asStateFlow()

    fun requestConversation() {
        mutableConversationRequest.value = sequence.incrementAndGet()
    }

    fun consumeConversationRequest(requestId: Long) {
        if (mutableConversationRequest.value == requestId) {
            mutableConversationRequest.value = null
        }
    }
}
