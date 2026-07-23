package com.example.silverageassistant.data.middleserver

data class ModelUsageUploadItem(
    val modality: String,
    val provider: String,
    val model: String?,
    val feature: String,
    val requestCount: Long,
    val successCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val asrAudioDurationMillis: Long,
    val ttsCharacterCount: Long,
    val ttsAudioDurationMillis: Long,
    val containsEstimatedValues: Boolean,
)

data class ModelUsageUploadBatch(
    val batchId: String,
    val periodStartedAt: String,
    val periodEndedAt: String,
    val timeZone: String,
    val timeZoneSource: String,
    val items: List<ModelUsageUploadItem>,
)

data class FamilyModelUsageSummary(
    val periodStartedAt: String,
    val periodEndedAt: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val mllmRequestCount: Long,
    val asrRequestCount: Long,
    val ttsRequestCount: Long,
    val asrAudioDurationMillis: Long,
    val ttsCharacterCount: Long,
    val ttsAudioDurationMillis: Long,
    val containsEstimatedValues: Boolean,
    val lastReportedAt: String?,
)

data class ModelUsageRefreshResult(
    val deviceOnline: Boolean,
    val requestedAt: String,
)

data class FamilyDailyModelUsage(
    val date: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val mllmRequestCount: Long,
    val asrRequestCount: Long,
    val ttsRequestCount: Long,
    val containsEstimatedValues: Boolean,
)

data class FamilyDailyModelUsageTimeline(
    val periodStartedOn: String,
    val periodEndedOn: String,
    val currentDate: String,
    val timeZone: String,
    val timeZoneSource: String,
    val days: List<FamilyDailyModelUsage>,
    val lastReportedAt: String?,
)

interface ElderModelUsageReportingRepository {
    suspend fun uploadModelUsage(batch: ModelUsageUploadBatch)
}

interface FamilyModelUsageRepository {
    suspend fun getFamilyModelUsage(
        elderId: String,
        from: String,
        to: String,
    ): FamilyModelUsageSummary

    suspend fun getFamilyDailyModelUsage(
        elderId: String,
    ): FamilyDailyModelUsageTimeline

    suspend fun requestCurrentModelUsage(
        elderId: String,
        clientRequestId: String,
    ): ModelUsageRefreshResult
}
