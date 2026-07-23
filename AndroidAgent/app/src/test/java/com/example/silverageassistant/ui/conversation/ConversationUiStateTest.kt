package com.example.silverageassistant.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationUiStateTest {
    @Test
    fun processingFlag_isTrueForEveryActiveModelPhase() {
        assertTrue(ConversationUiState(phase = ConversationPhase.Connecting).isProcessing)
        assertTrue(ConversationUiState(phase = ConversationPhase.Thinking).isProcessing)
        assertTrue(ConversationUiState(phase = ConversationPhase.UsingTool).isProcessing)
        assertTrue(ConversationUiState(phase = ConversationPhase.Responding).isProcessing)
        assertFalse(ConversationUiState(phase = ConversationPhase.Idle).isProcessing)
    }

    @Test
    fun everyPhase_hasClearStatusAndGuidance() {
        ConversationPhase.entries.forEach { phase ->
            assertTrue(phase.statusText.isNotBlank())
            assertTrue(phase.guidanceText.isNotBlank())
        }
    }

    @Test
    fun defaultState_containsOnlyLocalAssistantWelcome() {
        val state = ConversationUiState()

        assertEquals(1, state.messages.size)
        assertEquals(ConversationSpeaker.Assistant, state.messages.single().speaker)
        assertFalse(state.messages.single().includeInModelContext)
    }

    @Test
    fun textCanOnlyBeSentWhenIdleAndNotBlank() {
        assertTrue(ConversationUiState(draft = "您好").canSendText)
        assertFalse(ConversationUiState(draft = "  ").canSendText)
        assertFalse(
            ConversationUiState(
                phase = ConversationPhase.Connecting,
                draft = "您好",
            ).canSendText,
        )
    }

    @Test
    fun contextUsage_usesConfiguredWindowAndClampsAtOneHundredPercent() {
        assertEquals(
            0.5f,
            ConversationUiState(
                contextTokens = 32768,
                contextWindowTokens = 65536,
            ).contextUsageFraction,
        )
        assertEquals(
            1f,
            ConversationUiState(
                contextTokens = 70000,
                contextWindowTokens = 65536,
            ).contextUsageFraction,
        )
    }
}
