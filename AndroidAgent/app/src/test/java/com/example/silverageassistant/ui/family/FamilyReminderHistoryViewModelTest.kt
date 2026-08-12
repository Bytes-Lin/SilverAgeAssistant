package com.example.silverageassistant.ui.family

import com.example.silverageassistant.data.middleserver.FamilyCommandResult
import com.example.silverageassistant.data.middleserver.FamilyCommunicationRepository
import com.example.silverageassistant.data.middleserver.FamilyNotificationRequest
import com.example.silverageassistant.data.middleserver.FamilyReminderHistoryItem
import com.example.silverageassistant.data.middleserver.FamilyReminderHistoryResult
import com.example.silverageassistant.data.middleserver.FamilyReminderRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyReminderHistoryViewModelTest {
    @Test
    fun refresh_loadsEveryPageAndKeepsAllCompletionStates() {
        val pages = mapOf(
            null to FamilyReminderHistoryResult(
                reminders = listOf(item("reminder-1", "PENDING", "2026-08-12T08:00:00Z")),
                nextCursor = "page-2",
            ),
            "page-2" to FamilyReminderHistoryResult(
                reminders = listOf(item("reminder-2", "COMPLETED", "2026-08-13T08:00:00Z")),
                nextCursor = null,
            ),
        )
        val repository = PagingRepository(pages)
        val viewModel = FamilyReminderHistoryViewModel(
            repository = repository,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        viewModel.refresh("elder-1")

        assertEquals(listOf(null, "page-2"), repository.requestedCursors)
        assertEquals(listOf("reminder-2", "reminder-1"), viewModel.uiState.value.reminders.map { it.commandId })
        assertEquals(listOf("COMPLETED", "PENDING"), viewModel.uiState.value.reminders.map { it.completionStatus })
    }

    @Test
    fun clearReminder_archivesOnServerBeforeRemovingItFromHistory() {
        val repository = ArchivingRepository(
            mutableListOf(
                item("reminder-1", "PENDING", "2026-08-12T08:00:00Z"),
                item("reminder-2", "COMPLETED", "2026-08-13T08:00:00Z"),
            ),
        )
        val viewModel = FamilyReminderHistoryViewModel(
            repository = repository,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )
        viewModel.refresh("elder-1")

        viewModel.clearReminder("reminder-1")

        assertEquals(listOf("reminder-1"), repository.archivedCommandIds)
        assertEquals(listOf("reminder-2"), viewModel.uiState.value.reminders.map { it.commandId })
        assertEquals(emptySet<String>(), viewModel.uiState.value.archivingReminderIds)
    }

    private fun item(id: String, status: String, scheduledAt: String) = FamilyReminderHistoryItem(
        commandId = id,
        title = id,
        content = "content",
        scheduledAt = scheduledAt,
        timezone = "Asia/Shanghai",
        createdAt = scheduledAt,
        deliveryStatus = "STORED",
        completionStatus = status,
        storedAt = scheduledAt,
        completedAt = scheduledAt.takeIf { status == "COMPLETED" },
    )

    private class PagingRepository(
        private val pages: Map<String?, FamilyReminderHistoryResult>,
    ) : FamilyCommunicationRepository {
        val requestedCursors = mutableListOf<String?>()

        override suspend fun getReminderHistory(
            elderId: String,
            limit: Int,
            cursor: String?,
        ): FamilyReminderHistoryResult {
            requestedCursors += cursor
            return requireNotNull(pages[cursor])
        }

        override suspend fun sendNotification(request: FamilyNotificationRequest): FamilyCommandResult =
            error("Not used")

        override suspend fun createReminder(request: FamilyReminderRequest): FamilyCommandResult =
            error("Not used")
    }

    private class ArchivingRepository(
        private val items: MutableList<FamilyReminderHistoryItem>,
    ) : FamilyCommunicationRepository {
        val archivedCommandIds = mutableListOf<String>()

        override suspend fun getReminderHistory(
            elderId: String,
            limit: Int,
            cursor: String?,
        ) = FamilyReminderHistoryResult(items.toList(), null)

        override suspend fun archiveReminder(
            elderId: String,
            commandId: String,
            clientRequestId: String,
        ) {
            archivedCommandIds += commandId
            items.removeAll { it.commandId == commandId }
        }

        override suspend fun sendNotification(request: FamilyNotificationRequest): FamilyCommandResult =
            error("Not used")

        override suspend fun createReminder(request: FamilyReminderRequest): FamilyCommandResult =
            error("Not used")
    }
}
