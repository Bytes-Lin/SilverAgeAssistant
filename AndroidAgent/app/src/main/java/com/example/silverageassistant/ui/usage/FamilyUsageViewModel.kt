package com.example.silverageassistant.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.data.middleserver.FamilyDailyModelUsage
import com.example.silverageassistant.data.middleserver.FamilyModelUsageRepository
import com.example.silverageassistant.data.middleserver.FamilyModelUsageSummary
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FamilyUsageUiState(
    val isLoading: Boolean = false,
    val summary: FamilyModelUsageSummary? = null,
    val dailyUsage: List<FamilyDailyModelUsage> = emptyList(),
    val elderCurrentDate: String = LocalDate.now().toString(),
    val dailyTimeZoneSource: String? = null,
    val dailyBreakdownAvailable: Boolean = true,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

class FamilyUsageViewModel(
    private val repository: FamilyModelUsageRepository?,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val _uiState = MutableStateFlow(FamilyUsageUiState())
    val uiState: StateFlow<FamilyUsageUiState> = _uiState.asStateFlow()

    private var loadedElderId: String? = null

    fun load(elderId: String?, force: Boolean = false) {
        if (_uiState.value.isLoading) return
        val targetId = elderId?.takeIf(String::isNotBlank) ?: run {
            _uiState.value = FamilyUsageUiState(
                errorMessage = "尚未找到已绑定的老人档案。",
            )
            return
        }
        if (!force && loadedElderId == targetId && _uiState.value.summary != null) return
        val localRepository = repository ?: run {
            _uiState.value = FamilyUsageUiState(
                errorMessage = "模型用量接口尚未配置。",
            )
            return
        }
        loadedElderId = targetId
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                statusMessage = if (force) "正在请求老人手机汇报最新用量…" else null,
            )
        }
        workScope.launch {
            runCatching {
                if (force) {
                    loadCurrentUsage(localRepository, targetId)
                } else {
                    LoadedUsage(queryUsageDashboard(localRepository, targetId))
                }
            }.onSuccess { loaded ->
                _uiState.value = FamilyUsageUiState(
                    summary = loaded.dashboard.summary,
                    dailyUsage = loaded.dashboard.dailyUsage,
                    elderCurrentDate = loaded.dashboard.elderCurrentDate,
                    dailyTimeZoneSource = loaded.dashboard.dailyTimeZoneSource,
                    dailyBreakdownAvailable = loaded.dashboard.dailyBreakdownAvailable,
                    statusMessage = loaded.message,
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (error is MiddleServerRequestException) {
                            error.userMessage
                        } else {
                            "暂时无法读取模型用量，请稍后重试。"
                        },
                        statusMessage = null,
                    )
                }
            }
        }
    }

    private suspend fun loadCurrentUsage(
        repository: FamilyModelUsageRepository,
        elderId: String,
    ): LoadedUsage {
        val baseline = _uiState.value.summary
        val refresh = try {
            repository.requestCurrentModelUsage(
                elderId = elderId,
                clientRequestId = UUID.randomUUID().toString(),
            )
        } catch (error: MiddleServerRequestException) {
            if (error.code != "NOT_FOUND" && error.code != "HTTP_404") throw error
            return LoadedUsage(
                dashboard = queryUsageDashboard(repository, elderId),
                message = "中台暂不支持立即汇报，已显示上次按时汇报的数据。",
            )
        }
        var latest = queryUsageDashboard(repository, elderId)
        if (refresh.deviceOnline) {
            repeat(MANUAL_REFRESH_POLL_COUNT) {
                if (latest.summary.isNewerThan(baseline)) {
                    return LoadedUsage(latest, "已获取老人手机的最新用量。")
                }
                delay(MANUAL_REFRESH_POLL_INTERVAL_MILLIS)
                latest = queryUsageDashboard(repository, elderId)
            }
        }
        return LoadedUsage(
            dashboard = latest,
            message = if (refresh.deviceOnline) {
                "老人手机已收到请求，当前显示中台最新数据；稍后可再次刷新。"
            } else {
                "老人手机暂时不在线，当前显示上次汇报的数据。"
            },
        )
    }

    private suspend fun queryUsageDashboard(
        repository: FamilyModelUsageRepository,
        elderId: String,
    ): UsageDashboard {
        val timeline = try {
            repository.getFamilyDailyModelUsage(elderId)
        } catch (error: MiddleServerRequestException) {
            if (error.code == "NOT_FOUND" || error.code == "HTTP_404") {
                return UsageDashboard(
                    summary = queryLegacyMonthSummary(repository, elderId),
                    dailyUsage = emptyList(),
                    elderCurrentDate = LocalDate.now().toString(),
                    dailyTimeZoneSource = null,
                    dailyBreakdownAvailable = false,
                )
            }
            throw error
        }
        val monthStartDate = LocalDate.parse(timeline.periodStartedOn)
        val nextMonthDate = LocalDate.parse(timeline.periodEndedOn)
        val daysByDate = timeline.days.associateBy { it.date }
        val normalizedDays = buildList {
            var date = monthStartDate
            while (date < nextMonthDate) {
                add(daysByDate[date.toString()] ?: date.emptyUsage())
                date = date.plusDays(1)
            }
        }
        val summary = FamilyModelUsageSummary(
            periodStartedAt = timeline.periodStartedOn,
            periodEndedAt = timeline.periodEndedOn,
            inputTokens = normalizedDays.sumOf(FamilyDailyModelUsage::inputTokens),
            outputTokens = normalizedDays.sumOf(FamilyDailyModelUsage::outputTokens),
            mllmRequestCount = normalizedDays.sumOf(
                FamilyDailyModelUsage::mllmRequestCount,
            ),
            asrRequestCount = normalizedDays.sumOf(FamilyDailyModelUsage::asrRequestCount),
            ttsRequestCount = normalizedDays.sumOf(FamilyDailyModelUsage::ttsRequestCount),
            asrAudioDurationMillis = 0,
            ttsCharacterCount = 0,
            ttsAudioDurationMillis = 0,
            containsEstimatedValues = normalizedDays.any(
                FamilyDailyModelUsage::containsEstimatedValues,
            ),
            lastReportedAt = timeline.lastReportedAt,
        )
        return UsageDashboard(
            summary = summary,
            dailyUsage = normalizedDays,
            elderCurrentDate = timeline.currentDate,
            dailyTimeZoneSource = timeline.timeZoneSource,
            dailyBreakdownAvailable = true,
        )
    }

    private suspend fun queryLegacyMonthSummary(
        repository: FamilyModelUsageRepository,
        elderId: String,
    ): FamilyModelUsageSummary {
        val now = ZonedDateTime.now()
        val monthStart = now.withDayOfMonth(1).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
        return repository.getFamilyModelUsage(
            elderId = elderId,
            from = monthStart.toString(),
            to = Instant.now().plusSeconds(QUERY_END_TOLERANCE_SECONDS).toString(),
        )
    }

    private fun FamilyModelUsageSummary.isNewerThan(
        baseline: FamilyModelUsageSummary?,
    ): Boolean {
        if (baseline == null) return lastReportedAt != null ||
            inputTokens > 0 ||
            outputTokens > 0 ||
            mllmRequestCount > 0
        return lastReportedAt != baseline.lastReportedAt ||
            inputTokens != baseline.inputTokens ||
            outputTokens != baseline.outputTokens ||
            mllmRequestCount != baseline.mllmRequestCount ||
            asrRequestCount != baseline.asrRequestCount ||
            ttsRequestCount != baseline.ttsRequestCount
    }

    private data class LoadedUsage(
        val dashboard: UsageDashboard,
        val message: String? = null,
    )

    private data class UsageDashboard(
        val summary: FamilyModelUsageSummary,
        val dailyUsage: List<FamilyDailyModelUsage>,
        val elderCurrentDate: String,
        val dailyTimeZoneSource: String?,
        val dailyBreakdownAvailable: Boolean,
    )

    private fun LocalDate.emptyUsage() = FamilyDailyModelUsage(
        date = toString(),
        inputTokens = 0,
        outputTokens = 0,
        mllmRequestCount = 0,
        asrRequestCount = 0,
        ttsRequestCount = 0,
        containsEstimatedValues = false,
    )

    private companion object {
        const val MANUAL_REFRESH_POLL_COUNT = 6
        const val MANUAL_REFRESH_POLL_INTERVAL_MILLIS = 1_000L
        const val QUERY_END_TOLERANCE_SECONDS = 10L
    }

    class Factory(
        private val repository: FamilyModelUsageRepository?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FamilyUsageViewModel::class.java))
            return FamilyUsageViewModel(repository) as T
        }
    }
}
