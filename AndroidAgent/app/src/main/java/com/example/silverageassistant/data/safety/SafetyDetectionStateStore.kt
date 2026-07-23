package com.example.silverageassistant.data.safety

import android.content.Context
import com.example.silverageassistant.domain.safety.ElderObservedState
import com.example.silverageassistant.domain.safety.SafetyAnalysisResult
import com.example.silverageassistant.domain.safety.TimedSafetyAnalysis
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SafetyDetectionWindow(
    val startedAt: String,
    val history: List<TimedSafetyAnalysis>,
    val consecutiveAbnormalCount: Int,
    val notificationSent: Boolean,
    val smsSent: Boolean,
)

interface SafetyDetectionStateRepository {
    suspend fun load(now: Instant): SafetyDetectionWindow?
    suspend fun save(window: SafetyDetectionWindow)
    suspend fun clear()
}

class SafetyDetectionStateStore(context: Context) : SafetyDetectionStateRepository {
    private val file = File(context.filesDir, "agent/safety-detection-state.json")
    private val mutex = Mutex()

    override suspend fun load(now: Instant): SafetyDetectionWindow? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val loaded = runCatching { decode(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
            loaded?.takeIf {
                Duration.between(Instant.parse(it.startedAt), now) < WINDOW_DURATION
            } ?: run {
                file.delete()
                null
            }
        }
    }

    override suspend fun save(window: SafetyDetectionWindow) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val root = JSONObject()
                .put("schema_version", 1)
                .put("started_at", window.startedAt)
                .put("consecutive_abnormal_count", window.consecutiveAbnormalCount)
                .put("notification_sent", window.notificationSent)
                .put("sms_sent", window.smsSent)
                .put("history", JSONArray().apply {
                    window.history.forEach { item ->
                        put(JSONObject()
                            .put("observed_at", item.observedAt)
                            .put("state", item.result.state.name)
                            .put("detail", item.result.detail ?: JSONObject.NULL))
                    }
                })
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(root.toString(2) + "\n", Charsets.UTF_8)
            if (!temporary.renameTo(file)) {
                file.writeText(root.toString(2) + "\n", Charsets.UTF_8)
                temporary.delete()
            }
        }
    }

    override suspend fun clear(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.delete()
            Unit
        }
    }

    private fun decode(root: JSONObject): SafetyDetectionWindow {
        val historyJson = root.getJSONArray("history")
        return SafetyDetectionWindow(
            startedAt = root.getString("started_at"),
            consecutiveAbnormalCount = root.getInt("consecutive_abnormal_count"),
            notificationSent = root.optBoolean("notification_sent", false),
            smsSent = root.optBoolean("sms_sent", false),
            history = buildList {
                for (index in 0 until historyJson.length()) {
                    val item = historyJson.getJSONObject(index)
                    val state = ElderObservedState.valueOf(item.getString("state"))
                    add(TimedSafetyAnalysis(
                        observedAt = item.getString("observed_at"),
                        result = SafetyAnalysisResult(
                            state = state,
                            detail = if (item.isNull("detail")) {
                                null
                            } else {
                                item.optString("detail").takeIf(String::isNotBlank)
                            },
                        ),
                    ))
                }
            },
        )
    }

    companion object {
        val WINDOW_DURATION: Duration = Duration.ofHours(6)
    }
}
