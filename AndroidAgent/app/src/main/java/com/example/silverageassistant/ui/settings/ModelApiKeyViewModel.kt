package com.example.silverageassistant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.data.model.ModelApiCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelApiKeyUiState(
    val draft: String = "",
    val isKeyVisible: Boolean = false,
    val isConfigured: Boolean = false,
    val maskedKey: String? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
) {
    val canSave: Boolean
        get() = draft.isNotBlank() && !isLoading && !isSaving

    override fun toString(): String =
        "ModelApiKeyUiState(" +
            "draft=<redacted>, " +
            "isKeyVisible=$isKeyVisible, " +
            "isConfigured=$isConfigured, " +
            "maskedKey=$maskedKey, " +
            "isLoading=$isLoading, " +
            "isSaving=$isSaving, " +
            "showDeleteConfirmation=$showDeleteConfirmation, " +
            "message=$message, " +
            "isError=$isError)"
}

class ModelApiKeyViewModel(
    private val credentialStore: ModelApiCredentialStore,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val _uiState = MutableStateFlow(ModelApiKeyUiState())
    val uiState: StateFlow<ModelApiKeyUiState> = _uiState.asStateFlow()
    private var operationJob: Job? = null

    init {
        loadStatus()
    }

    fun updateDraft(value: String) {
        _uiState.update {
            it.copy(
                draft = value.replace("\r", "").replace("\n", "").take(MAX_KEY_LENGTH),
                message = null,
                isError = false,
            )
        }
    }

    fun toggleKeyVisibility() {
        _uiState.update { it.copy(isKeyVisible = !it.isKeyVisible) }
    }

    fun saveApiKey(): Boolean {
        if (operationJob?.isActive == true) return false
        val apiKey = _uiState.value.draft.trim()
        if (apiKey.isBlank()) {
            showFailure("请填写 API Key。")
            return false
        }
        _uiState.update { it.copy(isSaving = true, message = null, isError = false) }
        operationJob = workScope.launch {
            try {
                credentialStore.saveApiKey(apiKey)
                _uiState.update {
                    it.copy(
                        draft = "",
                        isKeyVisible = false,
                        isConfigured = true,
                        maskedKey = apiKey.toMaskedKey(),
                        isSaving = false,
                        message = "API Key 已加密保存在本机。",
                        isError = false,
                    )
                }
            } catch (_: Exception) {
                showFailure("API Key 保存失败，请稍后重试。")
            }
        }
        return true
    }

    fun requestDelete() {
        if (_uiState.value.isConfigured && operationJob?.isActive != true) {
            _uiState.update { it.copy(showDeleteConfirmation = true, message = null) }
        }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun confirmDelete() {
        if (!_uiState.value.showDeleteConfirmation || operationJob?.isActive == true) return
        _uiState.update {
            it.copy(
                showDeleteConfirmation = false,
                isSaving = true,
                message = null,
            )
        }
        operationJob = workScope.launch {
            try {
                credentialStore.clearApiKey()
                _uiState.update {
                    it.copy(
                        draft = "",
                        isKeyVisible = false,
                        isConfigured = false,
                        maskedKey = null,
                        isSaving = false,
                        message = "本机 API Key 已删除。",
                        isError = false,
                    )
                }
            } catch (_: Exception) {
                showFailure("API Key 删除失败，请稍后重试。")
            }
        }
    }

    private fun loadStatus() {
        operationJob = workScope.launch {
            try {
                val saved = credentialStore.loadApiKey()
                _uiState.update {
                    it.copy(
                        isConfigured = !saved.isNullOrBlank(),
                        maskedKey = saved?.takeIf(String::isNotBlank)?.toMaskedKey(),
                        isLoading = false,
                        message = null,
                        isError = false,
                    )
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isConfigured = false,
                        maskedKey = null,
                        message = "无法读取本机 API Key，请重新设置。",
                        isError = true,
                    )
                }
            }
        }
    }

    private fun showFailure(message: String) {
        _uiState.update {
            it.copy(
                isLoading = false,
                isSaving = false,
                message = message,
                isError = true,
            )
        }
    }

    class Factory(
        private val credentialStore: ModelApiCredentialStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ModelApiKeyViewModel::class.java))
            return ModelApiKeyViewModel(credentialStore) as T
        }
    }

    private fun String.toMaskedKey(): String =
        if (length <= MASK_SUFFIX_LENGTH) "已配置" else "••••${takeLast(MASK_SUFFIX_LENGTH)}"

    private companion object {
        const val MAX_KEY_LENGTH = 1_024
        const val MASK_SUFFIX_LENGTH = 4
    }
}
