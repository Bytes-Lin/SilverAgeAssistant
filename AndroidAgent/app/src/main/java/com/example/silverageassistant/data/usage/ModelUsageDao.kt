package com.example.silverageassistant.data.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class ModelUsageAggregate(
    val inputTokens: Long,
    val outputTokens: Long,
    val mllmRequestCount: Long,
    val asrRequestCount: Long,
    val ttsRequestCount: Long,
    val asrAudioDurationMillis: Long,
    val ttsCharacterCount: Long,
    val estimatedRecordCount: Long,
)

@Dao
interface ModelUsageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ModelUsageEntity)

    @Query(
        """
        SELECT
            COALESCE(SUM(input_tokens), 0) AS inputTokens,
            COALESCE(SUM(output_tokens), 0) AS outputTokens,
            COALESCE(SUM(CASE WHEN modality = 'MLLM' THEN request_count ELSE 0 END), 0)
                AS mllmRequestCount,
            COALESCE(SUM(CASE WHEN modality = 'ASR' THEN request_count ELSE 0 END), 0)
                AS asrRequestCount,
            COALESCE(SUM(CASE WHEN modality = 'TTS' THEN request_count ELSE 0 END), 0)
                AS ttsRequestCount,
            COALESCE(SUM(asr_audio_duration_millis), 0) AS asrAudioDurationMillis,
            COALESCE(SUM(tts_character_count), 0) AS ttsCharacterCount,
            COALESCE(SUM(CASE WHEN is_estimated = 1 THEN 1 ELSE 0 END), 0)
                AS estimatedRecordCount
        FROM model_usage_records
        """,
    )
    fun observeSummary(): Flow<ModelUsageAggregate>

    @Query(
        """
        SELECT * FROM model_usage_records
        WHERE reported_at_epoch_millis IS NULL
        ORDER BY CASE WHEN report_batch_id IS NULL THEN 1 ELSE 0 END,
            started_at_epoch_millis ASC
        LIMIT :limit
        """,
    )
    suspend fun pending(limit: Int): List<ModelUsageEntity>

    @Query(
        """
        UPDATE model_usage_records
        SET report_batch_id = :batchId
        WHERE id IN (:recordIds)
            AND reported_at_epoch_millis IS NULL
            AND report_batch_id IS NULL
        """,
    )
    suspend fun assignBatch(recordIds: List<String>, batchId: String)

    @Query(
        """
        UPDATE model_usage_records
        SET reported_at_epoch_millis = :reportedAtEpochMillis, report_batch_id = :batchId
        WHERE id IN (:recordIds) AND reported_at_epoch_millis IS NULL
        """,
    )
    suspend fun markReported(
        recordIds: List<String>,
        batchId: String,
        reportedAtEpochMillis: Long,
    )
}
