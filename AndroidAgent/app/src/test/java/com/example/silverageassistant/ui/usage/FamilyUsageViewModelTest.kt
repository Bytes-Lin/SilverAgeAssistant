package com.example.silverageassistant.ui.usage

import com.example.silverageassistant.data.middleserver.FamilyModelUsageRepository
import com.example.silverageassistant.data.middleserver.FamilyModelUsageSummary
import com.example.silverageassistant.data.middleserver.FamilyDailyModelUsage
import com.example.silverageassistant.data.middleserver.FamilyDailyModelUsageTimeline
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import com.example.silverageassistant.data.middleserver.ModelUsageRefreshResult
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyUsageViewModelTest {
    @Test
    fun openingPage_readsServerSummaryWithoutRequestingImmediateReport() {
        val repository = FakeRepository(summaries = mutableListOf(summary(inputTokens = 120)))
        val viewModel = viewModel(repository)

        viewModel.load("elder-1")

        assertEquals(0, repository.refreshRequestCount)
        assertEquals(120L, viewModel.uiState.value.summary?.inputTokens)
        assertTrue(viewModel.uiState.value.dailyBreakdownAvailable)
        assertEquals(LocalDate.now().lengthOfMonth(), viewModel.uiState.value.dailyUsage.size)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun manualRefresh_requestsElderAndDisplaysNewerSummary() {
        val repository = FakeRepository(
            summaries = mutableListOf(
                summary(inputTokens = 120, lastReportedAt = "2026-07-19T01:00:00Z"),
                summary(inputTokens = 180, lastReportedAt = "2026-07-19T01:01:00Z"),
            ),
            deviceOnline = true,
        )
        val viewModel = viewModel(repository)
        viewModel.load("elder-1")

        viewModel.load("elder-1", force = true)

        assertEquals(1, repository.refreshRequestCount)
        assertEquals(180L, viewModel.uiState.value.summary?.inputTokens)
        assertTrue(viewModel.uiState.value.statusMessage.orEmpty().contains("最新用量"))
    }

    @Test
    fun manualRefresh_whenElderOffline_keepsLastReportAndExplainsState() {
        val repository = FakeRepository(
            summaries = mutableListOf(summary(inputTokens = 120)),
            deviceOnline = false,
        )
        val viewModel = viewModel(repository)
        viewModel.load("elder-1")

        viewModel.load("elder-1", force = true)

        assertEquals(1, repository.refreshRequestCount)
        assertEquals(120L, viewModel.uiState.value.summary?.inputTokens)
        assertTrue(viewModel.uiState.value.statusMessage.orEmpty().contains("不在线"))
    }

    @Test
    fun missingDailyEndpoint_keepsMonthlySummaryAvailable() {
        val repository = FakeRepository(
            summaries = mutableListOf(summary(inputTokens = 120)),
            dailyError = MiddleServerRequestException(
                code = "NOT_FOUND",
                userMessage = "接口不存在",
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.load("elder-1")

        assertEquals(120L, viewModel.uiState.value.summary?.inputTokens)
        assertFalse(viewModel.uiState.value.dailyBreakdownAvailable)
        assertTrue(viewModel.uiState.value.dailyUsage.isEmpty())
    }

    private fun viewModel(repository: FamilyModelUsageRepository) = FamilyUsageViewModel(
        repository = repository,
        externalScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun summary(
        inputTokens: Long,
        lastReportedAt: String? = null,
    ) = FamilyModelUsageSummary(
        periodStartedAt = "2026-07-01T00:00:00Z",
        periodEndedAt = "2026-08-01T00:00:00Z",
        inputTokens = inputTokens,
        outputTokens = inputTokens / 2,
        mllmRequestCount = if (inputTokens > 0) 1 else 0,
        asrRequestCount = 0,
        ttsRequestCount = 0,
        asrAudioDurationMillis = 0,
        ttsCharacterCount = 0,
        ttsAudioDurationMillis = 0,
        containsEstimatedValues = false,
        lastReportedAt = lastReportedAt,
    )

    private class FakeRepository(
        private val summaries: MutableList<FamilyModelUsageSummary>,
        private val deviceOnline: Boolean = true,
        private val dailyError: MiddleServerRequestException? = null,
    ) : FamilyModelUsageRepository {
        var refreshRequestCount = 0
            private set

        override suspend fun getFamilyModelUsage(
            elderId: String,
            from: String,
            to: String,
        ): FamilyModelUsageSummary = nextSummary()

        override suspend fun getFamilyDailyModelUsage(
            elderId: String,
        ): FamilyDailyModelUsageTimeline {
            dailyError?.let { throw it }
            val summary = nextSummary()
            val monthStart = LocalDate.now().withDayOfMonth(1)
            return FamilyDailyModelUsageTimeline(
                periodStartedOn = monthStart.toString(),
                periodEndedOn = monthStart.plusMonths(1).toString(),
                currentDate = LocalDate.now().toString(),
                timeZone = "Asia/Shanghai",
                timeZoneSource = "LOCATION",
                days = listOf(
                    FamilyDailyModelUsage(
                        date = LocalDate.now().toString(),
                        inputTokens = summary.inputTokens,
                        outputTokens = summary.outputTokens,
                        mllmRequestCount = summary.mllmRequestCount,
                        asrRequestCount = 0,
                        ttsRequestCount = 0,
                        containsEstimatedValues = false,
                    ),
                ),
                lastReportedAt = summary.lastReportedAt,
            )
        }

        private fun nextSummary(): FamilyModelUsageSummary {
            if (summaries.size > 1) return summaries.removeAt(0)
            return summaries.single()
        }

        override suspend fun requestCurrentModelUsage(
            elderId: String,
            clientRequestId: String,
        ): ModelUsageRefreshResult {
            refreshRequestCount += 1
            return ModelUsageRefreshResult(
                deviceOnline = deviceOnline,
                requestedAt = "2026-07-19T01:01:00Z",
            )
        }
    }
}
