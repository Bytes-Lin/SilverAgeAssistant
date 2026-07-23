package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.data.middleserver.ElderSafetyEventRequest
import com.example.silverageassistant.data.middleserver.ElderSafetyMonitoringRepository
import com.example.silverageassistant.data.middleserver.SafetyEvent
import com.example.silverageassistant.data.middleserver.SafetyEventSeverity
import com.example.silverageassistant.data.middleserver.SafetyEventType
import com.example.silverageassistant.data.middleserver.SafetyEventImageUpload
import com.example.silverageassistant.data.safety.SafetyMonitoringConfiguration
import com.example.silverageassistant.domain.model.ChatModelProvider
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatStreamEvent
import com.example.silverageassistant.domain.model.ChatToolCall
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFamilySituationToolTest {
    private val fixedClock = Clock.fixed(
        Instant.parse("2026-07-22T04:05:06Z"),
        ZoneOffset.UTC,
    )

    @Test
    fun healthDiscomfort_usesDeviceTimeAndForcesEmergencySeverity() = runBlocking {
        val repository = FakeRepository()
        val tool = ReportFamilySituationTool(
            FamilySituationReporter(repository, fixedClock) { "event-client-1" },
        )

        val result = tool.execute(
            """{"event_type":"HEALTH_DISCOMFORT_REPORTED","event_summary":" 老人说今天身体不舒服 ","severity":"GENERAL"}""",
        )

        val request = requireNotNull(repository.lastRequest)
        assertEquals("event-client-1", request.clientEventId)
        assertEquals("2026-07-22T04:05:06Z", request.occurredAt)
        assertEquals(SafetyEventSeverity.EMERGENCY, request.severity)
        assertEquals("老人说今天身体不舒服", request.eventSummary)
        assertTrue(result.contains("\"severity\":\"EMERGENCY\""))
    }

    @Test
    fun familyRequest_forcesGeneralSeverity() = runBlocking {
        val repository = FakeRepository()
        val reporter = FamilySituationReporter(repository, fixedClock) { "event-client-2" }

        reporter.report(
            eventType = SafetyEventType.FAMILY_REQUEST,
            eventSummary = "老人想让儿子回家吃饭",
        )

        assertEquals(SafetyEventSeverity.GENERAL, repository.lastRequest?.severity)
    }

    @Test
    fun toolSchema_doesNotAllowModelGeneratedTime() {
        val definition = ReportFamilySituationTool(
            FamilySituationReporter(FakeRepository(), fixedClock),
        ).definition

        assertFalse(definition.parametersJson.contains("occurred_at"))
        assertTrue(definition.parametersJson.contains("HEALTH_DISCOMFORT_REPORTED"))
        assertTrue(definition.parametersJson.contains("FAMILY_REQUEST"))
    }

    @Test
    fun coordinator_executesLocallyPolicyCheckedReportingTool() = runBlocking {
        val repository = FakeRepository()
        val provider = ReportingProvider()
        val coordinator = AgentChatCoordinator(
            provider = provider,
            toolRegistry = AgentToolRegistry(
                listOf(
                    ReportFamilySituationTool(
                        FamilySituationReporter(repository, fixedClock) { "event-client-3" },
                    ),
                ),
            ),
        )

        val events = coordinator.streamTurn(emptyList(), "今天身体不舒服").toList()

        assertEquals(SafetyEventSeverity.EMERGENCY, repository.lastRequest?.severity)
        assertTrue(events.contains(AgentChatEvent.Completed))
        assertEquals(2, provider.requests.size)
    }

    private class FakeRepository : ElderSafetyMonitoringRepository {
        var lastRequest: ElderSafetyEventRequest? = null

        override suspend fun getSafetyMonitoringConfiguration(): SafetyMonitoringConfiguration? = null

        override suspend fun createSafetyEvent(request: ElderSafetyEventRequest): SafetyEvent {
            lastRequest = request
            return SafetyEvent(
                eventId = "server-event-1",
                serverSequence = 1,
                occurredAt = request.occurredAt,
                eventType = request.eventType,
                eventSummary = request.eventSummary,
                severity = request.severity,
                acknowledgedAt = null,
                createdAt = request.occurredAt,
            )
        }

        override suspend fun uploadSafetyEventImage(eventId: String, image: SafetyEventImageUpload) = Unit
    }

    private class ReportingProvider : ChatModelProvider {
        val requests = mutableListOf<ChatRequest>()

        override fun stream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
            requests += request
            if (requests.size == 1) {
                emit(
                    ChatStreamEvent.ToolCallReady(
                        ChatToolCall(
                            id = "call-report-1",
                            name = ReportFamilySituationTool.NAME,
                            argumentsJson = """{"event_type":"HEALTH_DISCOMFORT_REPORTED","event_summary":"老人说今天身体不舒服","severity":"EMERGENCY"}""",
                        ),
                    ),
                )
                emit(ChatStreamEvent.Completed("tool_calls"))
            } else {
                emit(ChatStreamEvent.TextDelta("我已经通知家人。"))
                emit(ChatStreamEvent.Completed("stop"))
            }
        }
    }
}
