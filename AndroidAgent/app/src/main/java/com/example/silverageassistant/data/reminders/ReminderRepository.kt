package com.example.silverageassistant.data.reminders

import com.example.silverageassistant.data.middleserver.RemoteCommand
import com.example.silverageassistant.data.middleserver.RemoteCommandType
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class StoredReminderStatus {
    PENDING,
    COMPLETED,
    SNOOZED,
}

enum class VoiceAnnouncementState {
    NONE,
    PENDING,
    SPOKEN,
    FAILED,
    EXPIRED,
}

data class RemoteCommandSaveResult(
    val inserted: Boolean,
    val reminderId: String,
    val voiceAnnouncementPending: Boolean,
)

data class StoredReminder(
    val id: String,
    val title: String,
    val detail: String,
    val scheduledAtEpochMillis: Long,
    val sourceDisplayName: String?,
    val status: StoredReminderStatus,
)

data class PendingCommandAcknowledgement(
    val commandId: String,
    val storedAtEpochMillis: Long,
)

data class PendingVoiceAnnouncement(
    val reminderId: String,
    val sourceDisplayName: String?,
    val content: String,
    val storedAtEpochMillis: Long,
)

interface ReminderRepository {
    val reminders: Flow<List<StoredReminder>>

    suspend fun saveRemoteCommand(command: RemoteCommand)

    suspend fun saveRemoteCommandWithResult(
        command: RemoteCommand,
        voiceAnnouncementEnabled: Boolean = false,
    ): RemoteCommandSaveResult {
        saveRemoteCommand(command)
        return RemoteCommandSaveResult(
            inserted = false,
            reminderId = command.commandId,
            voiceAnnouncementPending = false,
        )
    }

    suspend fun lastServerSequence(): Long

    suspend fun pendingAcknowledgements(): List<PendingCommandAcknowledgement>

    suspend fun markAcknowledged(commandId: String)

    suspend fun updateStatus(id: String, status: StoredReminderStatus)

    suspend fun markVoiceAnnouncement(id: String, state: VoiceAnnouncementState) = Unit

    suspend fun pendingVoiceAnnouncements(): List<PendingVoiceAnnouncement> = emptyList()
}

class RoomReminderRepository(
    private val dao: ReminderDao,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ReminderRepository {
    override val reminders: Flow<List<StoredReminder>> = dao.observeAll().map { entities ->
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowEpochMillis()).atZone(zone).toLocalDate()
        entities.filter { entity ->
            Instant.ofEpochMilli(entity.scheduledAtEpochMillis).atZone(zone).toLocalDate() == today
        }.sortedWith(
            compareBy<ReminderEntity> { entity ->
                if (entity.status == StoredReminderStatus.COMPLETED.name) 1 else 0
            }.thenBy(ReminderEntity::scheduledAtEpochMillis),
        ).map { entity -> entity.toStoredReminder() }
    }

    override suspend fun saveRemoteCommand(command: RemoteCommand) {
        saveRemoteCommandWithResult(command, voiceAnnouncementEnabled = false)
    }

    override suspend fun saveRemoteCommandWithResult(
        command: RemoteCommand,
        voiceAnnouncementEnabled: Boolean,
    ): RemoteCommandSaveResult {
        val storedAt = nowEpochMillis()
        val scheduledAt = if (command.type == RemoteCommandType.FAMILY_NOTIFICATION) {
            Instant.ofEpochMilli(storedAt)
        } else {
            command.scheduledAt?.let(::parseInstant) ?: parseInstant(command.createdAt)
        }
        val voicePending = command.type == RemoteCommandType.FAMILY_NOTIFICATION &&
            voiceAnnouncementEnabled
        val inserted = dao.insert(
            ReminderEntity(
                id = command.commandId,
                serverCommandId = command.commandId,
                serverSequence = command.serverSequence,
                kind = command.type.name,
                title = if (command.type == RemoteCommandType.FAMILY_NOTIFICATION) {
                    "家人通知"
                } else {
                    command.title.orEmpty().ifBlank { "家人提醒" }
                },
                detail = command.content,
                scheduledAtEpochMillis = scheduledAt.toEpochMilli(),
                timezoneId = command.timezone,
                sourceDisplayName = command.senderDisplayName,
                storedAtEpochMillis = storedAt,
                status = StoredReminderStatus.PENDING.name,
                acknowledged = false,
                voiceAnnouncementState = if (voicePending) {
                    VoiceAnnouncementState.PENDING.name
                } else {
                    VoiceAnnouncementState.NONE.name
                },
                voiceAnnouncedAtEpochMillis = null,
                voiceAttemptCount = 0,
            ),
        ) != -1L
        return RemoteCommandSaveResult(
            inserted = inserted,
            reminderId = command.commandId,
            voiceAnnouncementPending = inserted && voicePending,
        )
    }

    override suspend fun lastServerSequence(): Long = dao.maxServerSequence()

    override suspend fun pendingAcknowledgements(): List<PendingCommandAcknowledgement> =
        dao.pendingAcknowledgements().mapNotNull { entity ->
            entity.serverCommandId?.let { commandId ->
                PendingCommandAcknowledgement(commandId, entity.storedAtEpochMillis)
            }
        }

    override suspend fun markAcknowledged(commandId: String) = dao.markAcknowledged(commandId)

    override suspend fun updateStatus(id: String, status: StoredReminderStatus) {
        dao.updateStatus(id, status.name)
    }

    override suspend fun markVoiceAnnouncement(id: String, state: VoiceAnnouncementState) {
        dao.updateVoiceAnnouncement(
            id = id,
            state = state.name,
            announcedAt = if (state == VoiceAnnouncementState.SPOKEN) nowEpochMillis() else null,
        )
    }

    override suspend fun pendingVoiceAnnouncements(): List<PendingVoiceAnnouncement> =
        dao.pendingVoiceAnnouncements().map { entity ->
            PendingVoiceAnnouncement(
                reminderId = entity.id,
                sourceDisplayName = entity.sourceDisplayName,
                content = entity.detail,
                storedAtEpochMillis = entity.storedAtEpochMillis,
            )
        }

    private fun ReminderEntity.toStoredReminder() = StoredReminder(
        id = id,
        title = title,
        detail = detail,
        scheduledAtEpochMillis = scheduledAtEpochMillis,
        sourceDisplayName = sourceDisplayName,
        status = runCatching { StoredReminderStatus.valueOf(status) }
            .getOrDefault(StoredReminderStatus.PENDING),
    )

    private fun parseInstant(value: String): Instant = try {
        Instant.parse(value)
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid server timestamp", error)
    }
}
