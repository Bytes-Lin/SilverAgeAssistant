package com.example.silverageassistant.ui.family

import com.example.silverageassistant.data.middleserver.FamilyCommandResult
import com.example.silverageassistant.data.middleserver.FamilyCommunicationRepository
import com.example.silverageassistant.data.middleserver.FamilyNotificationRequest
import com.example.silverageassistant.data.middleserver.FamilyReminderRequest
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyCommunicationViewModelTest {
    @Test
    fun notification_isSentThroughRepositoryAndClearedAfterSuccess() {
        val repository = FakeFamilyCommunicationRepository()
        val viewModel = viewModel(repository)
        viewModel.updateNotificationContent("记得带钥匙")

        assertTrue(viewModel.sendNotification("elder-1"))

        assertEquals("记得带钥匙", repository.notifications.single().content)
        assertEquals("elder-1", repository.notifications.single().elderId)
        assertEquals("", viewModel.uiState.value.notificationContent)
        assertFalse(viewModel.uiState.value.resultIsError)
    }

    @Test
    fun failedNotificationRetry_reusesClientRequestId() {
        val repository = FakeFamilyCommunicationRepository(failFirstNotification = true)
        val viewModel = viewModel(repository)
        viewModel.updateNotificationContent("下午有快递")

        viewModel.sendNotification("elder-1")
        viewModel.sendNotification("elder-1")

        assertEquals(2, repository.notifications.size)
        assertEquals(
            repository.notifications[0].clientRequestId,
            repository.notifications[1].clientRequestId,
        )
        assertEquals(repository.notifications[0].createdAt, repository.notifications[1].createdAt)
    }

    @Test
    fun reminder_convertsLocalDateTimeToServerRequest() {
        val repository = FakeFamilyCommunicationRepository()
        val viewModel = viewModel(repository)
        viewModel.updateReminderTitle("量血压")
        viewModel.updateReminderContent("测量后把结果记下来")
        viewModel.updateReminderDate("2099-07-16")
        viewModel.updateReminderTime("08:30")

        assertTrue(viewModel.createReminder("elder-1"))

        val request = repository.reminders.single()
        assertEquals("量血压", request.title)
        assertTrue(request.scheduledAt.endsWith("Z"))
        assertTrue(request.timezone.isNotBlank())
    }

    private fun viewModel(repository: FamilyCommunicationRepository) =
        FamilyCommunicationViewModel(
            repository = repository,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

    private class FakeFamilyCommunicationRepository(
        private val failFirstNotification: Boolean = false,
    ) : FamilyCommunicationRepository {
        val notifications = mutableListOf<FamilyNotificationRequest>()
        val reminders = mutableListOf<FamilyReminderRequest>()

        override suspend fun sendNotification(request: FamilyNotificationRequest): FamilyCommandResult {
            notifications += request
            if (failFirstNotification && notifications.size == 1) {
                throw MiddleServerRequestException("NETWORK_TIMEOUT", "连接超时")
            }
            return result()
        }

        override suspend fun createReminder(request: FamilyReminderRequest): FamilyCommandResult {
            reminders += request
            return result()
        }

        private fun result() = FamilyCommandResult(
            commandId = "command-1",
            serverSequence = 1,
            status = "PENDING",
            createdAt = "2026-07-16T08:00:00Z",
        )
    }
}
