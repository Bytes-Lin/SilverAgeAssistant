package com.example.silverageassistant.data.usage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "model_usage_records",
    indices = [
        Index(value = ["reported_at_epoch_millis"]),
        Index(value = ["started_at_epoch_millis"]),
    ],
)
data class ModelUsageEntity(
    @PrimaryKey val id: String,
    val modality: String,
    val provider: String,
    val model: String?,
    val feature: String,
    @ColumnInfo(name = "started_at_epoch_millis") val startedAtEpochMillis: Long,
    @ColumnInfo(name = "finished_at_epoch_millis") val finishedAtEpochMillis: Long,
    @ColumnInfo(name = "request_count") val requestCount: Long,
    @ColumnInfo(name = "success_count") val successCount: Long,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long,
    @ColumnInfo(name = "asr_audio_duration_millis") val asrAudioDurationMillis: Long,
    @ColumnInfo(name = "tts_character_count") val ttsCharacterCount: Long,
    @ColumnInfo(name = "tts_audio_duration_millis") val ttsAudioDurationMillis: Long,
    @ColumnInfo(name = "is_estimated") val isEstimated: Boolean,
    @ColumnInfo(name = "reported_at_epoch_millis") val reportedAtEpochMillis: Long?,
    @ColumnInfo(name = "report_batch_id") val reportBatchId: String?,
)
