package com.example.silverageassistant.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.data.middleserver.ElderCommandRepository
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import com.example.silverageassistant.data.reminders.ReminderRepository
import com.example.silverageassistant.data.reminders.StoredReminder
import com.example.silverageassistant.data.reminders.StoredReminderStatus
import com.example.silverageassistant.data.reminders.VoiceAnnouncementState
import com.example.silverageassistant.data.middleserver.RemoteCommand
import com.example.silverageassistant.domain.voice.VoiceFeature
import com.example.silverageassistant.domain.voice.VoiceInteractionCoordinator
import com.example.silverageassistant.domain.voice.VoicePriority
import com.example.silverageassistant.domain.voice.VoiceRequestContext
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReminderStatus {
    Pending,
    Completed,
    Snoozed,
}

data class ReminderItemUi(
    val id: String,
    val eventTimeEpochMillis: Long,
    val time: String,
    val title: String,
    val detail: String,
    val sourceDisplayName: String? = null,
    val status: ReminderStatus = ReminderStatus.Pending,
)

data class ReminderSyncState(
    val isSyncing: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

class ReminderViewModel(
    private val reminderRepository: ReminderRepository? = null,
    private val commandRepository: ElderCommandRepository? = null,
    private val voiceCoordinator: VoiceInteractionCoordinator? = null,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val _reminders = MutableStateFlow(if (reminderRepository == null) mockReminders else emptyList())
    val reminders: StateFlow<List<ReminderItemUi>> = _reminders.asStateFlow()
    private val _syncState = MutableStateFlow(ReminderSyncState())
    val syncState: StateFlow<ReminderSyncState> = _syncState.asStateFlow()
    private var syncJob: Job? = null
    private var recoveredVoiceAnnouncements = false

    init {
        if (reminderRepository != null) {
            workScope.launch {
                reminderRepository.reminders.collectLatest { stored ->
                    _reminders.value = stored.map { reminder -> reminder.toUi() }.sortedForToday()
                }
            }
        }
        if (reminderRepository != null && voiceCoordinator != null) {
            workScope.launch {
                voiceCoordinator.enabled.collect { enabled ->
                    if (enabled && !recoveredVoiceAnnouncements) {
                        recoveredVoiceAnnouncements = true
                        recoverVoiceAnnouncements(reminderRepository)
                    }
                }
            }
        }
    }

    fun markCompleted(id: String) = updateStatus(id, ReminderStatus.Completed)

    fun snooze(id: String) = updateStatus(id, ReminderStatus.Snoozed)

    fun syncRemoteCommands() {
        val localRepository = reminderRepository ?: return
        val remoteRepository = commandRepository ?: return
        if (syncJob?.isActive == true) return
        syncJob = workScope.launch {
            _syncState.value = ReminderSyncState(isSyncing = true, message = "正在接收家人消息…")
            try {
                retryPendingAcknowledgements(localRepository, remoteRepository)
                var afterSequence = localRepository.lastServerSequence()
                var hasMore: Boolean
                do {
                    val requestedAfterSequence = afterSequence
                    val page = remoteRepository.getPendingCommands(afterSequence)
                    page.commands.forEach { command ->
                        val saveResult = localRepository.saveRemoteCommandWithResult(
                            command = command,
                            voiceAnnouncementEnabled = voiceCoordinator?.enabled?.value == true,
                        )
                        if (saveResult.voiceAnnouncementPending) {
                            announceFamilyNotification(
                                reminderId = command.commandId,
                                sourceDisplayName = command.senderDisplayName,
                                content = command.content,
                                localRepository = localRepository,
                            )
                        }
                        acknowledgeStoredCommand(
                            commandId = command.commandId,
                            storedAt = Instant.now(),
                            localRepository = localRepository,
                            remoteRepository = remoteRepository,
                        )
                    }
                    afterSequence = maxOf(afterSequence, page.nextAfterSequence)
                    hasMore = page.hasMore
                    check(!hasMore || afterSequence > requestedAfterSequence) {
                        "Command pagination did not advance"
                    }
                } while (hasMore)
                _syncState.value = ReminderSyncState(message = "已同步家人发送的通知和提醒。")
            } catch (error: MiddleServerRequestException) {
                _syncState.value = ReminderSyncState(message = error.userMessage, isError = true)
            } catch (_: Exception) {
                _syncState.value = ReminderSyncState(
                    message = "暂时无法同步家人消息，已保存的提醒仍可使用。",
                    isError = true,
                )
            }
        }
    }

    private fun announceFamilyNotification(
        reminderId: String,
        sourceDisplayName: String?,
        content: String,
        localRepository: ReminderRepository,
    ) {
        val voice = voiceCoordinator ?: return
        workScope.launch {
            val source = sourceDisplayName?.trim().orEmpty().ifBlank { "家人" }
            val speakableContent = content
                .replace(Regex("https?://\\S+"), "链接")
                .trim()
                .take(800)
            val result = runCatching {
                voice.stopSpeaking()
                voice.speakNow(
                    VoiceRequestContext(
                        feature = VoiceFeature.FAMILY_NOTIFICATION,
                        correlationId = reminderId,
                        priority = VoicePriority.FAMILY_NOTIFICATION,
                    ),
                    "您收到一条来自${source}的通知。$speakableContent",
                )
            }
            localRepository.markVoiceAnnouncement(
                reminderId,
                if (result.isSuccess) VoiceAnnouncementState.SPOKEN else VoiceAnnouncementState.FAILED,
            )
        }
    }

    private suspend fun recoverVoiceAnnouncements(localRepository: ReminderRepository) {
        val cutoff = System.currentTimeMillis() - VOICE_RECOVERY_WINDOW_MILLIS
        localRepository.pendingVoiceAnnouncements().forEach { pending ->
            if (pending.storedAtEpochMillis < cutoff) {
                localRepository.markVoiceAnnouncement(
                    pending.reminderId,
                    VoiceAnnouncementState.EXPIRED,
                )
            } else {
                announceFamilyNotification(
                    reminderId = pending.reminderId,
                    sourceDisplayName = pending.sourceDisplayName,
                    content = pending.content,
                    localRepository = localRepository,
                )
            }
        }
    }

    private suspend fun retryPendingAcknowledgements(
        localRepository: ReminderRepository,
        remoteRepository: ElderCommandRepository,
    ) {
        localRepository.pendingAcknowledgements().forEach { pending ->
            acknowledgeStoredCommand(
                commandId = pending.commandId,
                storedAt = Instant.ofEpochMilli(pending.storedAtEpochMillis),
                localRepository = localRepository,
                remoteRepository = remoteRepository,
            )
        }
    }

    private suspend fun acknowledgeStoredCommand(
        commandId: String,
        storedAt: Instant,
        localRepository: ReminderRepository,
        remoteRepository: ElderCommandRepository,
    ) {
        val requestId = UUID.nameUUIDFromBytes(
            "command-ack:$commandId".toByteArray(StandardCharsets.UTF_8),
        ).toString()
        remoteRepository.acknowledgeCommand(
            commandId = commandId,
            clientRequestId = requestId,
            storedAt = storedAt.toString(),
        )
        localRepository.markAcknowledged(commandId)
    }

    private fun updateStatus(id: String, status: ReminderStatus) {
        val repository = reminderRepository
        if (repository == null) {
            _reminders.update { reminders ->
                reminders.map { reminder ->
                    if (reminder.id == id) reminder.copy(status = status) else reminder
                }.sortedForToday()
            }
            return
        }
        workScope.launch {
            repository.updateStatus(id, status.toStoredStatus())
        }
    }

    private fun StoredReminder.toUi(): ReminderItemUi {
        val timeFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.CHINA)
        val time = Instant.ofEpochMilli(scheduledAtEpochMillis)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
        return ReminderItemUi(
            id = id,
            eventTimeEpochMillis = scheduledAtEpochMillis,
            time = time,
            title = title,
            detail = detail,
            sourceDisplayName = sourceDisplayName,
            status = when (status) {
                StoredReminderStatus.PENDING -> ReminderStatus.Pending
                StoredReminderStatus.COMPLETED -> ReminderStatus.Completed
                StoredReminderStatus.SNOOZED -> ReminderStatus.Snoozed
            },
        )
    }

    private fun ReminderStatus.toStoredStatus() = when (this) {
        ReminderStatus.Pending -> StoredReminderStatus.PENDING
        ReminderStatus.Completed -> StoredReminderStatus.COMPLETED
        ReminderStatus.Snoozed -> StoredReminderStatus.SNOOZED
    }

    private fun List<ReminderItemUi>.sortedForToday(): List<ReminderItemUi> =
        sortedWith(
            compareBy<ReminderItemUi> { reminder ->
                if (reminder.status == ReminderStatus.Completed) 1 else 0
            }.thenBy(ReminderItemUi::eventTimeEpochMillis),
        )

    class Factory(
        private val reminderRepository: ReminderRepository,
        private val commandRepository: ElderCommandRepository?,
        private val voiceCoordinator: VoiceInteractionCoordinator? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReminderViewModel::class.java))
            return ReminderViewModel(
                reminderRepository,
                commandRepository,
                voiceCoordinator,
            ) as T
        }
    }

    private companion object {
        val mockReminders = listOf(
            ReminderItemUi("medicine", 8, "上午 8:00", "服药提醒", "请按家人设置的计划服药。"),
            ReminderItemUi("water", 10, "上午 10:00", "喝水提醒", "喝一杯温水。"),
            ReminderItemUi(
                "appointment",
                15,
                "下午 3:00",
                "复诊准备",
                "准备好就诊卡和随身物品。",
            ),
        )
        const val VOICE_RECOVERY_WINDOW_MILLIS = 10 * 60 * 1_000L
    }
}
