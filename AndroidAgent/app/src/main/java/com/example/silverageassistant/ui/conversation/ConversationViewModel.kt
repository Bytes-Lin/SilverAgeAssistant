package com.example.silverageassistant.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConversationViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private var mockProcessingJob: Job? = null

    fun onPrimaryAction() {
        when (_uiState.value.phase) {
            ConversationPhase.Idle -> startListening()
            ConversationPhase.Listening -> finishListening()
            ConversationPhase.Speaking -> stopSpeaking()
            ConversationPhase.Transcribing,
            ConversationPhase.Thinking -> Unit
        }
    }

    fun cancel() {
        mockProcessingJob?.cancel()
        _uiState.value = _uiState.value.copy(phase = ConversationPhase.Idle)
    }

    fun replay() {
        if (_uiState.value.reply.isNotBlank()) {
            _uiState.value = _uiState.value.copy(phase = ConversationPhase.Speaking)
        }
    }

    private fun startListening() {
        _uiState.value = ConversationUiState(phase = ConversationPhase.Listening)
    }

    private fun finishListening() {
        mockProcessingJob?.cancel()
        mockProcessingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(phase = ConversationPhase.Transcribing)
            delay(MOCK_STEP_DELAY_MILLIS)
            _uiState.value = _uiState.value.copy(
                phase = ConversationPhase.Thinking,
                transcript = "我想知道今天有什么提醒。",
            )
            delay(MOCK_STEP_DELAY_MILLIS)
            _uiState.value = _uiState.value.copy(
                phase = ConversationPhase.Speaking,
                reply = "今天上午八点有服药提醒。完成后，请记得在提醒页面确认。",
            )
        }
    }

    private fun stopSpeaking() {
        _uiState.value = _uiState.value.copy(phase = ConversationPhase.Idle)
    }

    private companion object {
        const val MOCK_STEP_DELAY_MILLIS = 900L
    }
}
