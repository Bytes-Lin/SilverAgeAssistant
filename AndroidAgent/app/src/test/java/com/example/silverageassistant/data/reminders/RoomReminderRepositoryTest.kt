package com.example.silverageassistant.data.reminders

import com.example.silverageassistant.data.middleserver.RemoteCommand
import com.example.silverageassistant.data.middleserver.RemoteCommandType
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomReminderRepositoryTest {
    @Test
    fun delayedNotification_isAddedAtReceiveTimeAndAppearsToday() = runBlocking {
        val now = Instant.parse("2026-07-16T08:00:00Z").toEpochMilli()
        val dao = FakeReminderDao()
        val repository = RoomReminderRepository(dao, nowEpochMillis = { now })

        repository.saveRemoteCommand(
            RemoteCommand(
                commandId = "command-1",
                serverSequence = 1,
                elderId = "elder-1",
                type = RemoteCommandType.FAMILY_NOTIFICATION,
                title = null,
                content = "下午有快递",
                scheduledAt = null,
                timezone = "Asia/Shanghai",
                senderDisplayName = "小林",
                createdAt = "2026-07-15T08:00:00Z",
            ),
        )

        assertEquals(now, dao.entities.value.single().scheduledAtEpochMillis)
        assertEquals("家人通知", repository.reminders.first().single().title)
    }

    private class FakeReminderDao : ReminderDao {
        val entities = MutableStateFlow<List<ReminderEntity>>(emptyList())

        override fun observeAll() = entities

        override suspend fun insert(entity: ReminderEntity): Long {
            if (entities.value.none { it.serverCommandId == entity.serverCommandId }) {
                entities.value = entities.value + entity
                return 1
            }
            return -1
        }

        override suspend fun maxServerSequence(): Long =
            entities.value.maxOfOrNull { it.serverSequence ?: 0 } ?: 0

        override suspend fun pendingAcknowledgements(): List<ReminderEntity> =
            entities.value.filter { !it.acknowledged }

        override suspend fun markAcknowledged(commandId: String) {
            entities.value = entities.value.map {
                if (it.serverCommandId == commandId) it.copy(acknowledged = true) else it
            }
        }

        override suspend fun updateStatus(id: String, status: String) {
            entities.value = entities.value.map {
                if (it.id == id) it.copy(status = status) else it
            }
        }

        override suspend fun updateVoiceAnnouncement(
            id: String,
            state: String,
            announcedAt: Long?,
        ) {
            entities.value = entities.value.map {
                if (it.id == id) {
                    it.copy(
                        voiceAnnouncementState = state,
                        voiceAnnouncedAtEpochMillis = announcedAt,
                        voiceAttemptCount = it.voiceAttemptCount + 1,
                    )
                } else {
                    it
                }
            }
        }

        override suspend fun pendingVoiceAnnouncements(): List<ReminderEntity> =
            entities.value.filter { it.voiceAnnouncementState == "PENDING" }
    }
}
