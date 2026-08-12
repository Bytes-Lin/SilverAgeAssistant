package com.example.silverageassistant.ui.safety

import com.example.silverageassistant.data.middleserver.FamilySafetyConfigurationUpdateRequest
import com.example.silverageassistant.data.middleserver.FamilySafetyEventsSnapshot
import com.example.silverageassistant.data.middleserver.FamilySafetyMonitoringRepository
import com.example.silverageassistant.data.middleserver.SafetyEvent
import com.example.silverageassistant.data.middleserver.SafetyEventSeverity
import com.example.silverageassistant.data.middleserver.SafetyEventType
import com.example.silverageassistant.data.safety.SafetyMonitoringConfiguration
import com.example.silverageassistant.data.safety.SafetyMonitoringConfigurationStore
import com.example.silverageassistant.data.safety.FamilyEmergencyNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyMonitoringViewModelTest {
    @Test
    fun refreshEvents_notifiesEachUnacknowledgedEmergencyOnlyOnce() {
        val event = emergencyEvent().copy(acknowledgedAt = null)
        val notified = mutableListOf<String>()
        val viewModel = SafetyMonitoringViewModel(
            store = FakeConfigurationStore(),
            familyRepository = FakeFamilyRepository(events = listOf(event)),
            emergencyNotifier = FamilyEmergencyNotifier {
                notified += it.eventId
                true
            },
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        viewModel.loadForFamily("elder-1")
        viewModel.refreshCurrentEvents()

        assertEquals(listOf(event.eventId), notified)
    }

    @Test
    fun latestEmergencyEvent_usesNewestEmergencyAndIgnoresGeneralEvents() {
        val olderEmergency = emergencyEvent().copy(
            eventId = "event-old",
            occurredAt = "2026-08-03T06:26:00Z",
        )
        val newerEmergency = emergencyEvent().copy(
            eventId = "event-new",
            serverSequence = 3,
            occurredAt = "2026-08-03T08:26:00Z",
        )
        val newerGeneral = emergencyEvent().copy(
            eventId = "event-general",
            serverSequence = 4,
            occurredAt = "2026-08-03T09:26:00Z",
            severity = SafetyEventSeverity.GENERAL,
        )
        val viewModel = viewModel(
            FakeFamilyRepository(events = listOf(olderEmergency, newerGeneral, newerEmergency)),
        )

        viewModel.loadForFamily("elder-1")

        assertEquals("event-new", viewModel.uiState.value.latestEmergencyEvent?.eventId)
    }

    @Test
    fun clearEmergency_resolvesOnServerAndRemovesCard() {
        val event = emergencyEvent()
        val repository = FakeFamilyRepository(events = listOf(event))
        val viewModel = viewModel(repository)

        viewModel.loadForFamily("elder-1")
        viewModel.clearEmergency(event)

        assertEquals(listOf(event.eventId), repository.resolvedEventIds)
        assertTrue(viewModel.uiState.value.emergencyEvents.isEmpty())
        assertTrue(viewModel.uiState.value.resolvingEventIds.isEmpty())
        assertEquals("已清除这条紧急事件。", viewModel.uiState.value.eventsMessage)
    }

    @Test
    fun clearEmergency_whenServerFails_keepsCard() {
        val event = emergencyEvent()
        val repository = FakeFamilyRepository(
            events = listOf(event),
            resolveFailure = IllegalStateException("offline"),
        )
        val viewModel = viewModel(repository)

        viewModel.loadForFamily("elder-1")
        viewModel.clearEmergency(event)

        assertEquals(listOf(event), viewModel.uiState.value.emergencyEvents)
        assertTrue(viewModel.uiState.value.resolvingEventIds.isEmpty())
        assertEquals("清除失败，请稍后再试。", viewModel.uiState.value.eventsMessage)
    }

    private fun viewModel(repository: FamilySafetyMonitoringRepository) =
        SafetyMonitoringViewModel(
            store = FakeConfigurationStore(),
            familyRepository = repository,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun emergencyEvent() = SafetyEvent(
        eventId = "event-1",
        serverSequence = 1,
        occurredAt = "2026-08-03T07:26:00Z",
        eventType = SafetyEventType.OTHER_ABNORMALITY,
        eventSummary = "需要核实：老人俯卧在地板上。",
        severity = SafetyEventSeverity.EMERGENCY,
        acknowledgedAt = "2026-08-03T07:27:00Z",
        createdAt = "2026-08-03T07:26:01Z",
    )

    private class FakeConfigurationStore : SafetyMonitoringConfigurationStore {
        private val state = MutableStateFlow(SafetyMonitoringConfiguration())
        override val configuration: StateFlow<SafetyMonitoringConfiguration> = state
        override suspend fun initialize() = Unit
        override suspend fun save(configuration: SafetyMonitoringConfiguration) {
            state.value = configuration
        }
    }

    private class FakeFamilyRepository(
        private val events: List<SafetyEvent>,
        private val resolveFailure: Exception? = null,
    ) : FamilySafetyMonitoringRepository {
        val resolvedEventIds = mutableListOf<String>()

        override suspend fun getSafetyMonitoringConfiguration(
            elderId: String,
        ): SafetyMonitoringConfiguration? = null

        override suspend fun updateSafetyMonitoringConfiguration(
            request: FamilySafetyConfigurationUpdateRequest,
        ): SafetyMonitoringConfiguration = SafetyMonitoringConfiguration()

        override suspend fun getTodaySafetyEvents(
            elderId: String,
        ) = FamilySafetyEventsSnapshot(
            currentDate = "2026-08-03",
            timeZone = "Asia/Shanghai",
            events = events,
            syncedAt = "2026-08-03T07:30:00Z",
        )

        override suspend fun acknowledgeSafetyEvent(
            elderId: String,
            eventId: String,
            clientRequestId: String,
        ): SafetyEvent = events.first { it.eventId == eventId }

        override suspend fun resolveSafetyEvent(
            elderId: String,
            eventId: String,
            clientRequestId: String,
        ): SafetyEvent {
            resolveFailure?.let { throw it }
            resolvedEventIds += eventId
            return events.first { it.eventId == eventId }.copy(
                resolvedAt = "2026-08-03T07:31:00Z",
            )
        }

        override suspend fun getSafetyEventImage(
            elderId: String,
            eventId: String,
            thumbnail: Boolean,
        ): ByteArray? = null
    }
}
