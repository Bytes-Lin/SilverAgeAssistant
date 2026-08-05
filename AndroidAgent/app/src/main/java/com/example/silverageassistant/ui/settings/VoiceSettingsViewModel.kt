package com.example.silverageassistant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.data.model.VoiceApiCredentialStore
import com.example.silverageassistant.data.voice.VoiceInteractionSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceSettingsUiState(
    val enabled: Boolean = false,
    val apiKeyDraft: String = "",
    val apiKeyVisible: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val maskedApiKey: String? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

class VoiceSettingsViewModel(
    private val settingsStore: VoiceInteractionSettingsStore,
    private val credentialStore: VoiceApiCredentialStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(VoiceSettingsUiState())
    val uiState: StateFlow<VoiceSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val enabled = runCatching { settingsStore.enabled.first() }.getOrDefault(false)
            val key = runCatching { credentialStore.loadVoiceApiKey() }.getOrNull()
            _uiState.value = VoiceSettingsUiState(
                enabled = enabled,
                apiKeyConfigured = !key.isNullOrBlank(),
                maskedApiKey = key?.takeIf(String::isNotBlank)?.toMaskedKey(),
                isLoading = false,
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled, message = null) }
        viewModelScope.launch {
            runCatching { settingsStore.setEnabled(enabled) }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            enabled = !enabled,
                            message = "语音开关保存失败，请稍后重试。",
                            isError = true,
                        )
                    }
                }
        }
    }

    fun updateApiKeyDraft(value: String) {
        _uiState.update {
            it.copy(
                apiKeyDraft = value.replace("\r", "").replace("\n", "").take(1024),
                message = null,
                isError = false,
            )
        }
    }

    fun toggleApiKeyVisibility() {
        _uiState.update { it.copy(apiKeyVisible = !it.apiKeyVisible) }
    }

    fun saveApiKey() {
        val key = _uiState.value.apiKeyDraft.trim()
        if (key.isBlank() || _uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, message = null) }
        viewModelScope.launch {
            runCatching { credentialStore.saveVoiceApiKey(key) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            apiKeyDraft = "",
                            apiKeyVisible = false,
                            apiKeyConfigured = true,
                            maskedApiKey = key.toMaskedKey(),
                            isSaving = false,
                            message = "语音 API Key 已加密保存在本机。",
                            isError = false,
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            message = "语音 API Key 保存失败，请稍后重试。",
                            isError = true,
                        )
                    }
                }
        }
    }

    fun clearApiKey() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, message = null) }
        viewModelScope.launch {
            runCatching { credentialStore.clearVoiceApiKey() }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            apiKeyConfigured = false,
                            maskedApiKey = null,
                            isSaving = false,
                            message = "语音 API Key 已删除。",
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            message = "语音 API Key 删除失败。",
                            isError = true,
                        )
                    }
                }
        }
    }

    private fun String.toMaskedKey(): String =
        if (length <= 4) "已配置" else "••••${takeLast(4)}"

    class Factory(
        private val settingsStore: VoiceInteractionSettingsStore,
        private val credentialStore: VoiceApiCredentialStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(VoiceSettingsViewModel::class.java))
            return VoiceSettingsViewModel(settingsStore, credentialStore) as T
        }
    }
}
