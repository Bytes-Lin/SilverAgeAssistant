package com.example.silverageassistant.data.usage

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.silverageassistant.BuildConfig
import com.example.silverageassistant.data.middleserver.AndroidKeystoreCredentialStore
import com.example.silverageassistant.data.middleserver.DeviceIdentityProvider
import com.example.silverageassistant.data.middleserver.HttpOnboardingMiddleServerRepository
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import com.example.silverageassistant.data.middleserver.ModelUsageUploadBatch
import com.example.silverageassistant.data.middleserver.ModelUsageUploadItem
import com.example.silverageassistant.data.reminders.SilverAgeDatabase
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全局模型用量的可靠批量上报任务。
 *
 * 周期上报和家属触发的即时上报共享同一互斥锁。记录先绑定稳定 batchId，中台确认成功后
 * 才标记 reported；进程退出或网络重试会复用原 batchId，依靠服务端幂等避免重复累计。
 */
class ModelUsageReportWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = uploadMutex.withLock {
        uploadPending()
    }

    private suspend fun uploadPending(): Result {
        val serverBaseUrl = BuildConfig.MIDDLE_SERVER_BASE_URL
        if (serverBaseUrl.isBlank()) return Result.success()
        val credentialStore = AndroidKeystoreCredentialStore(applicationContext)
        if (credentialStore.loadDeviceCredential() == null) return Result.success()
        val dao = SilverAgeDatabase.getInstance(applicationContext).modelUsageDao()
        val candidates = dao.pending(ModelUsagePolicy.MAX_UPLOAD_RECORDS)
        if (candidates.isEmpty()) return Result.success()

        // 一旦某批记录已经分配 batchId，必须优先完成该批，不能与新记录混合后换 ID。
        val existingBatchId = candidates.firstOrNull()?.reportBatchId
        val pending = if (existingBatchId == null) {
            candidates.filter { it.reportBatchId == null }
        } else {
            candidates.filter { it.reportBatchId == existingBatchId }
        }
        val batchId = existingBatchId ?: UUID.randomUUID().toString().also { newBatchId ->
            dao.assignBatch(pending.map(ModelUsageEntity::id), newBatchId)
        }
        val usageTimeZone = LocationTimeZoneStore(applicationContext).current()
        val batch = ModelUsageUploadBatch(
            batchId = batchId,
            periodStartedAt = Instant.ofEpochMilli(
                pending.minOf(ModelUsageEntity::startedAtEpochMillis),
            ).toString(),
            periodEndedAt = Instant.ofEpochMilli(
                pending.maxOf(ModelUsageEntity::finishedAtEpochMillis),
            ).toString(),
            timeZone = usageTimeZone.id,
            timeZoneSource = usageTimeZone.source,
            items = pending.groupBy {
                UsageGroupKey(
                    modality = it.modality,
                    provider = it.provider,
                    model = it.model,
                    feature = it.feature,
                )
            }.map { (key, records) ->
                ModelUsageUploadItem(
                    modality = key.modality,
                    provider = key.provider,
                    model = key.model,
                    feature = key.feature,
                    requestCount = records.sumOf(ModelUsageEntity::requestCount),
                    successCount = records.sumOf(ModelUsageEntity::successCount),
                    inputTokens = records.sumOf(ModelUsageEntity::inputTokens),
                    outputTokens = records.sumOf(ModelUsageEntity::outputTokens),
                    asrAudioDurationMillis = records.sumOf(
                        ModelUsageEntity::asrAudioDurationMillis,
                    ),
                    ttsCharacterCount = records.sumOf(ModelUsageEntity::ttsCharacterCount),
                    ttsAudioDurationMillis = records.sumOf(
                        ModelUsageEntity::ttsAudioDurationMillis,
                    ),
                    containsEstimatedValues = records.any(ModelUsageEntity::isEstimated),
                )
            },
        )
        val repository = HttpOnboardingMiddleServerRepository(
            serverBaseUrl = serverBaseUrl,
            credentialStore = credentialStore,
            deviceId = DeviceIdentityProvider(applicationContext).getOrCreate(),
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        )
        return try {
            repository.uploadModelUsage(batch)
            dao.markReported(
                recordIds = pending.map(ModelUsageEntity::id),
                batchId = batchId,
                reportedAtEpochMillis = System.currentTimeMillis(),
            )
            Result.success()
        } catch (error: MiddleServerRequestException) {
            if (error.code == "AUTHENTICATION_REQUIRED") Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private data class UsageGroupKey(
        val modality: String,
        val provider: String,
        val model: String?,
        val feature: String,
    )

    companion object {
        private const val UNIQUE_WORK_NAME = "hourly-model-usage-report"
        // v2 avoids being appended behind a legacy request that may still carry
        // the old Android validated-network constraint.
        private const val IMMEDIATE_WORK_NAME = "immediate-model-usage-report-v2"
        private val uploadMutex = Mutex()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ModelUsageReportWorker>(
                ModelUsagePolicy.REPORT_INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<ModelUsageReportWorker>()
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
