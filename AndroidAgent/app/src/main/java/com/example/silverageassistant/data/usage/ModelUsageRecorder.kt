package com.example.silverageassistant.data.usage

import com.example.silverageassistant.domain.model.ChatUsage
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ModelUsageRecorder {
    val summary: Flow<ModelUsageSummary>

    suspend fun recordMllm(
        provider: String,
        model: String,
        feature: String,
        startedAtEpochMillis: Long,
        finishedAtEpochMillis: Long,
        usage: ChatUsage,
        estimated: Boolean,
        successful: Boolean,
    )

    suspend fun recordAsr(
        provider: String,
        model: String?,
        feature: String,
        startedAtEpochMillis: Long,
        finishedAtEpochMillis: Long,
        audioDurationMillis: Long,
        successful: Boolean,
    )

    suspend fun recordTts(
        provider: String,
        model: String?,
        feature: String,
        startedAtEpochMillis: Long,
        finishedAtEpochMillis: Long,
        characterCount: Long,
        audioDurationMillis: Long,
        successful: Boolean,
    )
}

class RoomModelUsageRecorder(
    private val dao: ModelUsageDao,
) : ModelUsageRecorder {
    override val summary: Flow<ModelUsageSummary> = dao.observeSummary().map { aggregate ->
        ModelUsageSummary(
            inputTokens = aggregate.inputTokens,
            outputTokens = aggregate.outputTokens,
            mllmRequestCount = aggregate.mllmRequestCount,
            asrRequestCount = aggregate.asrRequestCount,
            ttsRequestCount = aggregate.ttsRequestCount,
            asrAudioDurationMillis = aggregate.asrAudioDurationMillis,
            ttsCharacterCount = aggregate.ttsCharacterCount,
            containsEstimatedValues = aggregate.estimatedRecordCount > 0,
        )
    }

    override suspend fun recordMllm(
        provider: String,
        model: String,
        feature: String,
        startedAtEpochMillis: Long,
        finishedAtEpochMillis: Long,
        usage: ChatUsage,
        estimated: Boolean,
        successful: Boolean,
    ) {
        dao.insert(
            baseEntity(
                modality = ModelUsageModality.MLLM,
                provider = provider,
                model = model,
                feature = feature,
                startedAtEpochMillis = startedAtEpochMillis,
                finishedAtEpochMillis = finishedAtEpochMillis,
                successful = successful,
                estimated = estimated,
            ).copy(
                inputTokens = usage.promptTokens ?: 0,
                outputTokens = usage.completionTokens ?: 0,
            ),
        )
    }

    override suspend fun recordAsr(
        provider: String,
        model: String?,
        feature: String,
        startedAtEpochMillis: Long,
        finishedAtEpochMillis: Long,
        audioDurationMillis: Long,
        successful: Boolean,
    ) {
        dao.insert(
            baseEntity(
                modality = ModelUsageModality.ASR,
                provider = provider,
                model = model,
                feature = feature,
                startedAtEpochMillis = startedAtEpochMillis,
                finishedAtEpochMillis = finishedAtEpochMillis,
                successful = successful,
                estimated = false,
            ).copy(asrAudioDurationMillis = audioDurationMillis.coerceAtLeast(0)),
        )
    }

    override suspend fun recordTts(
        provider: String,
        model: String?,
        feature: String,
        startedAtEpochMillis: Long,
        finishedAtEpochMillis: Long,
        characterCount: Long,
        audioDurationMillis: Long,
        successful: Boolean,
    ) {
        dao.insert(
            baseEntity(
                modality = ModelUsageModality.TTS,
                provider = provider,
                model = model,
                feature = feature,
                startedAtEpochMillis = startedAtEpochMillis,
                finishedAtEpochMillis = finishedAtEpochMillis,
                successful = successful,
                estimated = false,
            ).copy(
                ttsCharacterCount = characterCount.coerceAtLeast(0),
                ttsAudioDurationMillis = audioDurationMillis.coerceAtLeast(0),
            ),
        )
    }

    private fun baseEntity(
        modality: ModelUsageModality,
        provider: String,
        model: String?,
        feature: String,
        startedAtEpochMillis: Long,
        finishedAtEpochMillis: Long,
        successful: Boolean,
        estimated: Boolean,
    ) = ModelUsageEntity(
        id = UUID.randomUUID().toString(),
        modality = modality.name,
        provider = provider.take(80),
        model = model?.take(120),
        feature = feature.take(80),
        startedAtEpochMillis = startedAtEpochMillis,
        finishedAtEpochMillis = finishedAtEpochMillis.coerceAtLeast(startedAtEpochMillis),
        requestCount = 1,
        successCount = if (successful) 1 else 0,
        inputTokens = 0,
        outputTokens = 0,
        asrAudioDurationMillis = 0,
        ttsCharacterCount = 0,
        ttsAudioDurationMillis = 0,
        isEstimated = estimated,
        reportedAtEpochMillis = null,
        reportBatchId = null,
    )
}

fun ModelUsageAggregate.toSummary() = ModelUsageSummary(
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    mllmRequestCount = mllmRequestCount,
    asrRequestCount = asrRequestCount,
    ttsRequestCount = ttsRequestCount,
    asrAudioDurationMillis = asrAudioDurationMillis,
    ttsCharacterCount = ttsCharacterCount,
    containsEstimatedValues = estimatedRecordCount > 0,
)
