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
    fun remoteReminder_schedulesDeadlineAndCompletionCancelsIt() = runBlocking {
        val now = Instant.parse("2026-07-16T08:00:00Z").toEpochMilli()
        val scheduler = FakeDeadlineScheduler()
        val notifier = FakeReceivedNotifier()
        val repository = RoomReminderRepository(
            FakeReminderDao(),
            nowEpochMillis = { now },
            deadlineScheduler = scheduler,
            receivedNotifier = notifier,
        )
        repository.saveRemoteCommand(
            RemoteCommand(
                commandId = "deadline-reminder",
                serverSequence = 4,
                elderId = "elder-1",
                type = RemoteCommandType.REMOTE_REMINDER,
                title = "量血压",
                content = "测量后记下来",
                scheduledAt = "2026-07-16T09:00:00Z",
                timezone = "Asia/Shanghai",
                senderDisplayName = "小林",
                createdAt = "2026-07-16T07:00:00Z",
            ),
        )

        assertEquals("deadline-reminder", scheduler.scheduled.single().first)
        assertEquals(listOf("deadline-reminder"), notifier.shownReminderIds)
        repository.updateStatus("deadline-reminder", StoredReminderStatus.COMPLETED)
        assertEquals(listOf("deadline-reminder"), scheduler.cancelled)
    }

    @Test
    fun duplicateRemoteReminder_isNotNotifiedTwice() = runBlocking {
        val now = Instant.parse("2026-07-16T08:00:00Z").toEpochMilli()
        val notifier = FakeReceivedNotifier()
        val repository = RoomReminderRepository(
            FakeReminderDao(),
            nowEpochMillis = { now },
            receivedNotifier = notifier,
        )
        val command = RemoteCommand(
            commandId = "duplicate-reminder",
            serverSequence = 7,
            elderId = "elder-1",
            type = RemoteCommandType.REMOTE_REMINDER,
            title = "Take medicine",
            content = "Take the evening medicine",
            scheduledAt = "2026-07-16T09:00:00Z",
            timezone = "Asia/Shanghai",
            senderDisplayName = "Family",
            createdAt = "2026-07-16T07:00:00Z",
        )

        repository.saveRemoteCommand(command)
        repository.saveRemoteCommand(command)

        assertEquals(listOf("duplicate-reminder"), notifier.shownReminderIds)
    }

    @Test
    fun familyNotification_doesNotPublishReminderSystemNotification() = runBlocking {
        val now = Instant.parse("2026-07-16T08:00:00Z").toEpochMilli()
        val notifier = FakeReceivedNotifier()
        val repository = RoomReminderRepository(
            FakeReminderDao(),
            nowEpochMillis = { now },
            receivedNotifier = notifier,
        )

        repository.saveRemoteCommand(
            RemoteCommand(
                commandId = "family-notification",
                serverSequence = 8,
                elderId = "elder-1",
                type = RemoteCommandType.FAMILY_NOTIFICATION,
                title = null,
                content = "Come home for dinner",
                scheduledAt = null,
                timezone = "Asia/Shanghai",
                senderDisplayName = "Family",
                createdAt = "2026-07-16T07:00:00Z",
            ),
        )

        assertEquals(emptyList<String>(), notifier.shownReminderIds)
    }

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

    @Test
    fun completedRemoteReminder_isQueuedUntilMiddleServerAcceptsReport() = runBlocking {
        val now = Instant.parse("2026-07-16T08:00:00Z").toEpochMilli()
        val dao = FakeReminderDao()
        val repository = RoomReminderRepository(dao, nowEpochMillis = { now })
        repository.saveRemoteCommand(
            RemoteCommand(
                commandId = "reminder-1",
                serverSequence = 2,
                elderId = "elder-1",
                type = RemoteCommandType.REMOTE_REMINDER,
                title = "量血压",
                content = "测量后记下来",
                scheduledAt = "2026-07-16T09:00:00Z",
                timezone = "Asia/Shanghai",
                senderDisplayName = "小林",
                createdAt = "2026-07-16T07:00:00Z",
            ),
        )

        repository.updateStatus("reminder-1", StoredReminderStatus.COMPLETED)

        val pending = repository.pendingCompletionReports().single()
        assertEquals("reminder-1", pending.commandId)
        assertEquals(now, pending.completedAtEpochMillis)
        repository.markCompletionReported("reminder-1")
        assertEquals(emptyList<PendingReminderCompletion>(), repository.pendingCompletionReports())
    }

    @Test
    fun sameDayRemoteReminder_isVisibleInTodaySnapshot() = runBlocking {
        val now = Instant.parse("2026-07-16T08:00:00Z").toEpochMilli()
        val repository = RoomReminderRepository(FakeReminderDao(), nowEpochMillis = { now })

        repository.saveRemoteCommand(
            RemoteCommand(
                commandId = "reminder-today",
                serverSequence = 3,
                elderId = "elder-1",
                type = RemoteCommandType.REMOTE_REMINDER,
                title = "量血压",
                content = "测量后记下来",
                scheduledAt = "2026-07-16T09:00:00Z",
                timezone = "Asia/Shanghai",
                senderDisplayName = "小林",
                createdAt = "2026-07-16T07:00:00Z",
            ),
        )

        assertEquals("量血压", repository.reminders.first().single().title)
    }

    @Test
    fun futureDeadlineReminder_isVisibleUntilCompleted() = runBlocking {
        val now = Instant.parse("2026-07-16T08:00:00Z").toEpochMilli()
        val repository = RoomReminderRepository(FakeReminderDao(), nowEpochMillis = { now })

        repository.saveRemoteCommand(
            RemoteCommand(
                commandId = "future-reminder",
                serverSequence = 5,
                elderId = "elder-1",
                type = RemoteCommandType.REMOTE_REMINDER,
                title = "Future reminder",
                content = "Complete before the deadline",
                scheduledAt = "2026-07-18T09:00:00Z",
                timezone = "GMT",
                senderDisplayName = "Family",
                createdAt = "2026-07-16T07:00:00Z",
            ),
        )

        assertEquals("future-reminder", repository.reminders.first().single().id)
    }

    @Test
    fun completedReminder_isHiddenAfterCompletionDay() = runBlocking {
        var now = Instant.parse("2026-07-16T08:00:00Z").toEpochMilli()
        val repository = RoomReminderRepository(FakeReminderDao(), nowEpochMillis = { now })
        repository.saveRemoteCommand(
            RemoteCommand(
                commandId = "completed-reminder",
                serverSequence = 6,
                elderId = "elder-1",
                type = RemoteCommandType.REMOTE_REMINDER,
                title = "Completed reminder",
                content = "Already completed",
                scheduledAt = "2026-07-18T09:00:00Z",
                timezone = "GMT",
                senderDisplayName = "Family",
                createdAt = "2026-07-16T07:00:00Z",
            ),
        )
        repository.updateStatus("completed-reminder", StoredReminderStatus.COMPLETED)
        now = Instant.parse("2026-07-18T08:00:00Z").toEpochMilli()

        assertEquals(emptyList<StoredReminder>(), repository.reminders.first())
    }

    private class FakeReminderDao : ReminderDao {
        val entities = MutableStateFlow<List<ReminderEntity>>(emptyList())

        override fun observeAll() = entities

        override suspend fun findById(id: String): ReminderEntity? =
            entities.value.firstOrNull { it.id == id }

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

        override suspend fun markCompleted(id: String, completedAt: Long, requestId: String) {
            entities.value = entities.value.map {
                if (it.id == id) {
                    it.copy(
                        status = StoredReminderStatus.COMPLETED.name,
                        completedAtEpochMillis = completedAt,
                        completionSyncState = if (it.kind == RemoteCommandType.REMOTE_REMINDER.name) {
                            "PENDING"
                        } else {
                            "NOT_REQUIRED"
                        },
                        completionRequestId = requestId,
                    )
                } else {
                    it
                }
            }
        }

        override suspend fun pendingCompletionReports(): List<ReminderEntity> =
            entities.value.filter { it.completionSyncState == "PENDING" }

        override suspend fun markCompletionReported(commandId: String) {
            entities.value = entities.value.map {
                if (it.serverCommandId == commandId) it.copy(completionSyncState = "SYNCED") else it
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

        override suspend fun incompleteRemoteReminders(): List<ReminderEntity> =
            entities.value.filter {
                it.kind == RemoteCommandType.REMOTE_REMINDER.name &&
                    it.status != StoredReminderStatus.COMPLETED.name
            }
    }

    private class FakeDeadlineScheduler : ReminderDeadlineScheduler {
        val scheduled = mutableListOf<Pair<String, Long>>()
        val cancelled = mutableListOf<String>()
        override fun schedule(reminderId: String, deadlineEpochMillis: Long) {
            scheduled += reminderId to deadlineEpochMillis
        }
        override fun cancel(reminderId: String) {
            cancelled += reminderId
        }
    }

    private class FakeReceivedNotifier : RemoteReminderReceivedNotifier {
        val shownReminderIds = mutableListOf<String>()

        override fun show(reminder: ReminderEntity) {
            shownReminderIds += reminder.id
        }
    }
}
