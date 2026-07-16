package com.example.silverageassistant.ui.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationUiStateTest {
    @Test
    fun processingFlag_isTrueOnlyForTranscribingAndThinking() {
        assertTrue(ConversationUiState(phase = ConversationPhase.Transcribing).isProcessing)
        assertTrue(ConversationUiState(phase = ConversationPhase.Thinking).isProcessing)
        assertFalse(ConversationUiState(phase = ConversationPhase.Idle).isProcessing)
        assertFalse(ConversationUiState(phase = ConversationPhase.Listening).isProcessing)
        assertFalse(ConversationUiState(phase = ConversationPhase.Speaking).isProcessing)
    }

    @Test
    fun everyPhase_hasClearStatusAndGuidance() {
        ConversationPhase.entries.forEach { phase ->
            assertTrue(phase.statusText.isNotBlank())
            assertTrue(phase.guidanceText.isNotBlank())
        }
    }
}
