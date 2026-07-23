package com.example.silverageassistant.data.safety

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SafetyMonitoringConfiguration(
    val enabled: Boolean = true,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val revision: Long = 0,
    val updatedAt: String? = null,
) {
    fun validate() {
        require(intervalMinutes in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES) {
            "检测间隔必须在 $MIN_INTERVAL_MINUTES 到 $MAX_INTERVAL_MINUTES 分钟之间"
        }
        require(revision >= 0) { "配置版本不正确" }
    }

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 5
        const val MIN_INTERVAL_MINUTES = 1
        const val MAX_INTERVAL_MINUTES = 60
    }
}

interface SafetyMonitoringConfigurationStore {
    val configuration: StateFlow<SafetyMonitoringConfiguration>

    suspend fun initialize()

    suspend fun save(configuration: SafetyMonitoringConfiguration)
}

class JsonSafetyMonitoringConfigurationStore private constructor(
    private val file: File,
) : SafetyMonitoringConfigurationStore {
    constructor(context: Context) : this(File(context.filesDir, CONFIG_RELATIVE_PATH))

    private val mutex = Mutex()
    private val state = MutableStateFlow(SafetyMonitoringConfiguration())
    override val configuration: StateFlow<SafetyMonitoringConfiguration> = state.asStateFlow()

    override suspend fun initialize() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val loaded = if (file.exists()) {
                runCatching { decode(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
            } else {
                null
            }
            val selected = loaded ?: SafetyMonitoringConfiguration()
            state.value = selected
            if (loaded == null) write(selected)
        }
    }

    override suspend fun save(configuration: SafetyMonitoringConfiguration) {
        configuration.validate()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                write(configuration)
                state.value = configuration
            }
        }
    }

    private fun write(configuration: SafetyMonitoringConfiguration) {
        file.parentFile?.mkdirs()
        val content = JSONObject()
            .put("schema_version", 2)
            .put("enabled", configuration.enabled)
            .put("interval_minutes", configuration.intervalMinutes)
            .put("revision", configuration.revision)
            .put("updated_at", configuration.updatedAt ?: JSONObject.NULL)
            .toString(2) + "\n"
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun decode(json: JSONObject) = SafetyMonitoringConfiguration(
        enabled = json.optBoolean("enabled", true),
        intervalMinutes = json.getInt("interval_minutes"),
        revision = json.optLong("revision", 0),
        updatedAt = if (json.isNull("updated_at")) {
            null
        } else {
            json.optString("updated_at").takeIf(String::isNotBlank)
        },
    ).also(SafetyMonitoringConfiguration::validate)

    private companion object {
        const val CONFIG_RELATIVE_PATH = "agent/safety-monitor-config.json"
    }
}
