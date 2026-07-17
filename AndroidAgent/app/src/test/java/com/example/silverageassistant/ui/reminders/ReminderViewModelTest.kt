package com.example.silverageassistant.ui.reminders

import com.example.silverageassistant.data.middleserver.ElderCommandRepository
import com.example.silverageassistant.data.middleserver.PendingCommandsResult
import com.example.silverageassistant.data.middleserver.RemoteCommand
import com.example.silverageassistant.data.middleserver.RemoteCommandType
import com.example.silverageassistant.data.reminders.PendingCommandAcknowledgement
import com.example.silverageassistant.data.reminders.ReminderRepository
import com.example.silverageassistant.data.reminders.StoredReminder
import com.example.silverageassistant.data.reminders.StoredReminderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderViewModelTest {
    @Test
    fun reminders_areSortedByEventTimeWithCompletedItemsAtBottom() {
        val repository = FakeReminderRepository(
            initialReminders = listOf(
                storedReminder("late-pending", 4_000, StoredReminderStatus.PENDING),
                storedReminder("early-completed", 1_000, StoredReminderStatus.COMPLETED),
                storedReminder("middle-snoozed", 3_000, StoredReminderStatus.SNOOZED),
                storedReminder("early-pending", 2_000, StoredReminderStatus.PENDING),
            ),
        )

        val viewModel = ReminderViewModel(
            reminderRepository = repository,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(
            listOf("early-pending", "middle-snoozed", "late-pending", "early-completed"),
            viewModel.reminders.value.map(ReminderItemUi::id),
        )
    }

    @Test
    fun markCompleted_updatesOnlySelectedReminder() {
        val viewModel = ReminderViewModel()

        viewModel.markCompleted("medicine")

        val reminders = viewModel.reminders.value
        assertEquals(ReminderStatus.Completed, reminders.first { it.id == "medicine" }.status)
        assertEquals(ReminderStatus.Pending, reminders.first { it.id == "water" }.status)
        assertEquals("medicine", reminders.last().id)
    }

    @Test
    fun snooze_marksReminderAsSnoozed() {
        val viewModel = ReminderViewModel()

        viewModel.snooze("water")

        assertEquals(
            ReminderStatus.Snoozed,
            viewModel.reminders.value.first { it.id == "water" }.status,
        )
    }

    @Test
    fun sync_savesCommandBeforeAcknowledgingIt() {
        val operations = mutableListOf<String>()
        val local = FakeReminderRepository(operations)
        val remote = FakeElderCommandRepository(operations)
        val viewModel = ReminderViewModel(
            reminderRepository = local,
            commandRepository = remote,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        viewModel.syncRemoteCommands()

        assertEquals(listOf("save:command-1", "ack:command-1"), operations)
        assertEquals("家人通知", viewModel.reminders.value.single().title)
        assertTrue(local.acknowledged)
    }

    private class FakeReminderRepository(
        private val operations: MutableList<String>,
        initialReminders: List<StoredReminder> = emptyList(),
    ) : ReminderRepository {
        constructor(initialReminders: List<StoredReminder>) : this(mutableListOf(), initialReminders)

        private val state = MutableStateFlow(initialReminders)
        override val reminders = state
        var acknowledged = false

        override suspend fun saveRemoteCommand(command: RemoteCommand) {
            operations += "save:${command.commandId}"
            state.value = listOf(
                StoredReminder(
                    id = command.commandId,
                    title = "家人通知",
                    detail = command.content,
                    scheduledAtEpochMillis = 1_752_652_800_000,
                    sourceDisplayName = command.senderDisplayName,
                    status = StoredReminderStatus.PENDING,
                ),
            )
        }

        override suspend fun lastServerSequence(): Long = 0

        override suspend fun pendingAcknowledgements(): List<PendingCommandAcknowledgement> = emptyList()

        override suspend fun markAcknowledged(commandId: String) {
            acknowledged = true
        }

        override suspend fun updateStatus(id: String, status: StoredReminderStatus) = Unit
    }

    private fun storedReminder(
        id: String,
        eventTime: Long,
        status: StoredReminderStatus,
    ) = StoredReminder(
        id = id,
        title = id,
        detail = id,
        scheduledAtEpochMillis = eventTime,
        sourceDisplayName = null,
        status = status,
    )

    private class FakeElderCommandRepository(
        private val operations: MutableList<String>,
    ) : ElderCommandRepository {
        override suspend fun getPendingCommands(afterSequence: Long, limit: Int) =
            PendingCommandsResult(
                commands = listOf(
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
                        createdAt = "2026-07-16T08:00:00Z",
                    ),
                ),
                nextAfterSequence = 1,
                hasMore = false,
            )

        override suspend fun acknowledgeCommand(
            commandId: String,
            clientRequestId: String,
            storedAt: String,
        ) {
            operations += "ack:$commandId"
        }
    }
}
