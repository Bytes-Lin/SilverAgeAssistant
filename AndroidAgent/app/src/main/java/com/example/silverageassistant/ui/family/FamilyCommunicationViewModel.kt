package com.example.silverageassistant.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.data.middleserver.FamilyCommunicationRepository
import com.example.silverageassistant.data.middleserver.FamilyNotificationRequest
import com.example.silverageassistant.data.middleserver.FamilyReminderRequest
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FamilyCommunicationUiState(
    val notificationContent: String = "",
    val reminderTitle: String = "",
    val reminderContent: String = "",
    val reminderDate: String = LocalDate.now().toString(),
    val reminderTime: String = LocalTime.now().plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")),
    val isSubmitting: Boolean = false,
    val notificationError: String? = null,
    val reminderTitleError: String? = null,
    val reminderContentError: String? = null,
    val reminderDateTimeError: String? = null,
    val resultMessage: String? = null,
    val resultIsError: Boolean = false,
)

class FamilyCommunicationViewModel(
    private val repository: FamilyCommunicationRepository? = null,
    externalScope: CoroutineScope? = null,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val initialReminderSchedule = now().plusHours(1)
    private val _uiState = MutableStateFlow(
        FamilyCommunicationUiState(
            reminderDate = initialReminderSchedule.toLocalDate().toString(),
            reminderTime = initialReminderSchedule.toLocalTime()
                .format(DateTimeFormatter.ofPattern("HH:mm")),
        ),
    )
    val uiState: StateFlow<FamilyCommunicationUiState> = _uiState.asStateFlow()
    private var pendingNotificationRequestId: String? = null
    private var pendingNotificationCreatedAt: String? = null
    private var pendingReminderRequestId: String? = null

    fun updateNotificationContent(value: String) {
        if (value != _uiState.value.notificationContent) {
            pendingNotificationRequestId = null
            pendingNotificationCreatedAt = null
        }
        _uiState.update {
            it.copy(
                notificationContent = value.take(MAX_CONTENT_LENGTH),
                notificationError = null,
                resultMessage = null,
            )
        }
    }

    fun updateReminderTitle(value: String) {
        if (value != _uiState.value.reminderTitle) pendingReminderRequestId = null
        _uiState.update {
            it.copy(
                reminderTitle = value.take(MAX_TITLE_LENGTH),
                reminderTitleError = null,
                resultMessage = null,
            )
        }
    }

    fun updateReminderContent(value: String) {
        if (value != _uiState.value.reminderContent) pendingReminderRequestId = null
        _uiState.update {
            it.copy(
                reminderContent = value.take(MAX_CONTENT_LENGTH),
                reminderContentError = null,
                resultMessage = null,
            )
        }
    }

    fun updateReminderDate(value: String) {
        if (value != _uiState.value.reminderDate) pendingReminderRequestId = null
        _uiState.update {
            it.copy(reminderDate = value.take(10), reminderDateTimeError = null, resultMessage = null)
        }
    }

    fun updateReminderTime(value: String) {
        if (value != _uiState.value.reminderTime) pendingReminderRequestId = null
        _uiState.update {
            it.copy(reminderTime = value.take(5), reminderDateTimeError = null, resultMessage = null)
        }
    }

    fun selectReminderDate(value: String) {
        val selectedDate = runCatching {
            LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrNull() ?: return
        val current = now()
        if (selectedDate.isBefore(current.toLocalDate())) {
            _uiState.update { it.copy(reminderDateTimeError = "截止日期不能早于今天") }
            return
        }
        updateReminderDate(value)
        val candidate = parseSchedule(value, _uiState.value.reminderTime).getOrNull()
        if (candidate != null && !candidate.toInstant().isAfter(current.toInstant())) {
            _uiState.update { it.copy(reminderDateTimeError = "今天请选择晚于当前时间的截止时刻") }
        }
    }

    fun selectReminderTime(value: String) {
        val candidate = parseSchedule(_uiState.value.reminderDate, value).getOrNull()
        if (candidate == null) {
            _uiState.update { it.copy(reminderDateTimeError = "请选择有效时间") }
            return
        }
        if (!candidate.toInstant().isAfter(now().toInstant())) {
            _uiState.update { it.copy(reminderDateTimeError = "截止时间必须晚于当前时间") }
            return
        }
        updateReminderTime(value)
    }

    fun sendNotification(elderId: String?): Boolean {
        if (_uiState.value.isSubmitting) return false
        val content = _uiState.value.notificationContent.trim()
        if (content.isBlank()) {
            _uiState.update { it.copy(notificationError = "请填写要告诉老人的内容") }
            return false
        }
        val targetElderId = requireElderId(elderId) ?: return false
        val communicationRepository = requireRepository() ?: return false
        val requestId = pendingNotificationRequestId ?: UUID.randomUUID().toString().also {
            pendingNotificationRequestId = it
        }
        val createdAt = pendingNotificationCreatedAt ?: Instant.now().toString().also {
            pendingNotificationCreatedAt = it
        }
        _uiState.update { it.copy(isSubmitting = true, resultMessage = null) }
        workScope.launch {
            try {
                communicationRepository.sendNotification(
                    FamilyNotificationRequest(
                        elderId = targetElderId,
                        content = content,
                        clientRequestId = requestId,
                        createdAt = createdAt,
                    ),
                )
                pendingNotificationRequestId = null
                pendingNotificationCreatedAt = null
                _uiState.update {
                    it.copy(
                        notificationContent = "",
                        isSubmitting = false,
                        resultMessage = "通知已交给中台，老人端联网后会收到。",
                        resultIsError = false,
                    )
                }
            } catch (error: MiddleServerRequestException) {
                showFailure(error.userMessage)
            } catch (_: Exception) {
                showFailure("通知发送失败，请稍后重试。")
            }
        }
        return true
    }

    fun createReminder(elderId: String?): Boolean {
        if (_uiState.value.isSubmitting) return false
        val state = _uiState.value
        val title = state.reminderTitle.trim()
        val content = state.reminderContent.trim()
        val scheduled = parseSchedule(state.reminderDate, state.reminderTime)
        val titleError = if (title.isBlank()) "请填写提醒名称" else null
        val contentError = if (content.isBlank()) "请填写提醒内容" else null
        val dateTimeError = when {
            scheduled.isFailure -> "日期和时间格式应为 2026-07-16、08:30"
            !scheduled.getOrThrow().toInstant().isAfter(now().toInstant()) -> "截止时间应晚于现在"
            else -> null
        }
        _uiState.update {
            it.copy(
                reminderTitleError = titleError,
                reminderContentError = contentError,
                reminderDateTimeError = dateTimeError,
            )
        }
        if (titleError != null || contentError != null || dateTimeError != null) return false
        val targetElderId = requireElderId(elderId) ?: return false
        val communicationRepository = requireRepository() ?: return false
        val requestId = pendingReminderRequestId ?: UUID.randomUUID().toString().also {
            pendingReminderRequestId = it
        }
        _uiState.update { it.copy(isSubmitting = true, resultMessage = null) }
        workScope.launch {
            try {
                communicationRepository.createReminder(
                    FamilyReminderRequest(
                        elderId = targetElderId,
                        title = title,
                        content = content,
                        scheduledAt = scheduled.getOrThrow().toInstant().toString(),
                        timezone = ZoneId.systemDefault().id,
                        clientRequestId = requestId,
                    ),
                )
                pendingReminderRequestId = null
                _uiState.update {
                    it.copy(
                        reminderTitle = "",
                        reminderContent = "",
                        isSubmitting = false,
                        resultMessage = "提醒已交给中台，老人端收到后会保存到本地。",
                        resultIsError = false,
                    )
                }
            } catch (error: MiddleServerRequestException) {
                showFailure(error.userMessage)
            } catch (_: Exception) {
                showFailure("提醒创建失败，请稍后重试。")
            }
        }
        return true
    }

    private fun parseSchedule(date: String, time: String) = runCatching {
        val localDate = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)
        val localTime = try {
            LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (error: DateTimeParseException) {
            throw error
        }
        localDate.atTime(localTime).atZone(now().zone)
    }

    private fun requireElderId(elderId: String?): String? {
        if (!elderId.isNullOrBlank()) return elderId
        showFailure("尚未取得老人档案，请返回首页同步绑定状态后重试。")
        return null
    }

    private fun requireRepository(): FamilyCommunicationRepository? {
        if (repository != null) return repository
        showFailure("中台通信尚未配置，请检查开发地址。")
        return null
    }

    private fun showFailure(message: String) {
        _uiState.update {
            it.copy(isSubmitting = false, resultMessage = message, resultIsError = true)
        }
    }

    class Factory(
        private val repository: FamilyCommunicationRepository?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FamilyCommunicationViewModel::class.java))
            return FamilyCommunicationViewModel(repository) as T
        }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 40
        const val MAX_CONTENT_LENGTH = 200
    }
}
