package com.example.silverageassistant.domain.safety

import com.example.silverageassistant.data.middleserver.ElderSafetyEventRequest
import com.example.silverageassistant.data.middleserver.ElderSafetyMonitoringRepository
import com.example.silverageassistant.data.middleserver.SafetyEvent
import com.example.silverageassistant.data.middleserver.SafetyEventImageUpload
import com.example.silverageassistant.data.safety.SafetyDetectionStateRepository
import com.example.silverageassistant.data.safety.SafetyDetectionWindow
import com.example.silverageassistant.data.safety.SafetyMonitoringConfiguration
import com.example.silverageassistant.domain.agent.FamilySituationReporter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyMonitoringAgentTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-22T06:00:00Z"), ZoneOffset.UTC)

    @Test
    fun sixHourWindowReportsOnceAndThirdAbnormalSendsSmsOnce() = runBlocking {
        val events = FakeEventRepository()
        val state = FakeStateStore()
        val sms = FakeSmsSender()
        val agent = SafetyMonitoringAgent(
            imageSource = FakeImageSource,
            analyzer = QueueAnalyzer(
                SafetyAnalysisResult(ElderObservedState.ABNORMAL, "老人疑似跌倒在地"),
                SafetyAnalysisResult(ElderObservedState.ABNORMAL, "老人仍倒地未起身"),
                SafetyAnalysisResult(ElderObservedState.ABNORMAL, "老人持续倒地"),
                SafetyAnalysisResult(ElderObservedState.ABNORMAL, "老人持续倒地"),
            ),
            stateStore = state,
            familyReporter = FamilySituationReporter(events, clock) { "client-${events.requests.size}" },
            smsTool = sms,
            clock = clock,
        )

        agent.runOnce()
        assertEquals(0, events.requests.size)
        agent.runOnce()
        assertEquals(1, events.requests.size)
        agent.runOnce()
        agent.runOnce()

        assertEquals(1, events.requests.size)
        assertEquals(1, events.uploadedImages.size)
        assertEquals(1, sms.sendCount)
        assertEquals(4, state.window?.history?.size)
        assertEquals(4, state.window?.consecutiveAbnormalCount)
        assertTrue(state.window?.smsSent == true)
        assertTrue(state.window?.notificationSent == true)
    }

    @Test
    fun normalResultResetsConsecutiveCountWithoutSendingAnything() = runBlocking {
        val events = FakeEventRepository()
        val state = FakeStateStore()
        val sms = FakeSmsSender()
        val agent = SafetyMonitoringAgent(
            FakeImageSource,
            QueueAnalyzer(
                SafetyAnalysisResult(ElderObservedState.ABNORMAL, "疑似跌倒"),
                SafetyAnalysisResult(ElderObservedState.NORMAL, null),
                SafetyAnalysisResult(ElderObservedState.ABNORMAL, "疑似跌倒"),
            ),
            state,
            FamilySituationReporter(events, clock),
            sms,
            clock,
        )

        repeat(3) { agent.runOnce() }

        assertEquals(0, events.requests.size)
        assertEquals(1, state.window?.consecutiveAbnormalCount)
        assertFalse(state.window?.smsSent ?: true)
        assertFalse(state.window?.notificationSent ?: true)
    }

    @Test
    fun clearStateRemovesWholeSixHourWindow() = runBlocking {
        val state = FakeStateStore()
        val agent = SafetyMonitoringAgent(
            FakeImageSource,
            QueueAnalyzer(SafetyAnalysisResult(ElderObservedState.NORMAL, null)),
            state,
            FamilySituationReporter(FakeEventRepository(), clock),
            FakeSmsSender(),
            clock,
        )
        agent.runOnce()

        agent.clearState()

        assertEquals(null, state.window)
    }

    private object FakeImageSource : SafetyImageSource {
        override suspend fun acquireLatestImage() = SafetyImage(byteArrayOf(1), "image/png", "fake")
    }

    private class QueueAnalyzer(vararg results: SafetyAnalysisResult) : SafetyVisionAnalyzer {
        private val queue = ArrayDeque(results.toList())
        override suspend fun analyze(
            image: SafetyImage,
            previousState: TimedSafetyAnalysis?,
            observedAt: String,
        ): SafetyAnalysisResult = queue.removeFirst()
    }

    private class FakeStateStore : SafetyDetectionStateRepository {
        var window: SafetyDetectionWindow? = null
        override suspend fun load(now: Instant): SafetyDetectionWindow? = window
        override suspend fun save(window: SafetyDetectionWindow) { this.window = window }
        override suspend fun clear() { window = null }
    }

    private class FakeSmsSender : EmergencySmsSender {
        var sendCount = 0
        override suspend fun send(observedAt: String, detail: String): EmergencySmsResult {
            sendCount += 1
            return EmergencySmsResult(1)
        }
    }

    private class FakeEventRepository : ElderSafetyMonitoringRepository {
        val requests = mutableListOf<ElderSafetyEventRequest>()
        val uploadedImages = mutableListOf<SafetyEventImageUpload>()
        override suspend fun getSafetyMonitoringConfiguration(): SafetyMonitoringConfiguration? = null
        override suspend fun createSafetyEvent(request: ElderSafetyEventRequest): SafetyEvent {
            requests += request
            return SafetyEvent(
                eventId = "event-${requests.size}",
                serverSequence = requests.size.toLong(),
                occurredAt = request.occurredAt,
                eventType = request.eventType,
                eventSummary = request.eventSummary,
                severity = request.severity,
                acknowledgedAt = null,
                createdAt = request.occurredAt,
            )
        }


        override suspend fun uploadSafetyEventImage(
            eventId: String,
            image: SafetyEventImageUpload,
        ) {
            uploadedImages += image
        }
    }
}
