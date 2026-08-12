package com.example.silverageassistant.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.data.middleserver.FamilyCommunicationRepository
import com.example.silverageassistant.data.middleserver.FamilyReminderHistoryItem
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class FamilyReminderHistoryUiState(
    val reminders: List<FamilyReminderHistoryItem> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val archivingReminderIds: Set<String> = emptySet(),
)

class FamilyReminderHistoryViewModel(
    private val repository: FamilyCommunicationRepository?,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val _uiState = MutableStateFlow(FamilyReminderHistoryUiState())
    val uiState: StateFlow<FamilyReminderHistoryUiState> = _uiState.asStateFlow()
    private var currentElderId: String? = null
    private var refreshJob: Job? = null
    private var refreshPending = false

    fun refresh(elderId: String?) {
        val id = elderId?.takeIf(String::isNotBlank) ?: run {
            _uiState.update { it.copy(message = "请先完成老人设备绑定。") }
            return
        }
        currentElderId = id
        val remote = repository ?: run {
            _uiState.update { it.copy(message = "中台提醒记录接口尚未接入。") }
            return
        }
        refreshPending = true
        if (refreshJob?.isActive == true) return
        refreshJob = workScope.launch {
            do {
                refreshPending = false
                loadAllPages(id, remote)
            } while (refreshPending)
        }
    }

    fun refreshCurrent() {
        refresh(currentElderId)
    }

    fun clearReminder(commandId: String) {
        val elderId = currentElderId ?: run {
            _uiState.update { it.copy(message = "请先完成老人设备绑定。") }
            return
        }
        val remote = repository ?: run {
            _uiState.update { it.copy(message = "中台提醒清除接口尚未接入。") }
            return
        }
        if (commandId in _uiState.value.archivingReminderIds) return
        workScope.launch {
            _uiState.update {
                it.copy(
                    archivingReminderIds = it.archivingReminderIds + commandId,
                    message = null,
                )
            }
            try {
                remote.archiveReminder(
                    elderId = elderId,
                    commandId = commandId,
                    clientRequestId = UUID.randomUUID().toString(),
                )
                _uiState.update {
                    it.copy(
                        reminders = it.reminders.filterNot { reminder ->
                            reminder.commandId == commandId
                        },
                        archivingReminderIds = it.archivingReminderIds - commandId,
                    )
                }
            } catch (error: MiddleServerRequestException) {
                archiveFailed(commandId, error.userMessage)
            } catch (_: Exception) {
                archiveFailed(commandId, "提醒暂时无法清除，请稍后重试。")
            }
        }
    }

    private fun archiveFailed(commandId: String, message: String) {
        _uiState.update {
            it.copy(
                archivingReminderIds = it.archivingReminderIds - commandId,
                message = message,
            )
        }
    }

    private suspend fun loadAllPages(id: String, remote: FamilyCommunicationRepository) {
        _uiState.update { it.copy(isLoading = true, message = null) }
        try {
            val reminders = mutableListOf<FamilyReminderHistoryItem>()
            val seenCursors = mutableSetOf<String>()
            var cursor: String? = null
            do {
                val result = remote.getReminderHistory(id, limit = PAGE_SIZE, cursor = cursor)
                reminders += result.reminders
                cursor = result.nextCursor?.takeIf { seenCursors.add(it) }
            } while (cursor != null)
            _uiState.update {
                it.copy(
                    reminders = reminders
                        .distinctBy(FamilyReminderHistoryItem::commandId)
                        .sortedByDescending { item -> item.scheduledAt },
                    isLoading = false,
                    message = null,
                )
            }
        } catch (error: MiddleServerRequestException) {
            _uiState.update { it.copy(isLoading = false, message = error.userMessage) }
        } catch (_: Exception) {
            _uiState.update { it.copy(isLoading = false, message = "提醒记录加载失败，请稍后重试。") }
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
    }

    class Factory(
        private val repository: FamilyCommunicationRepository?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FamilyReminderHistoryViewModel::class.java))
            return FamilyReminderHistoryViewModel(repository) as T
        }
    }
}
