package com.example.silverageassistant.data.model

import android.content.Context
import com.example.silverageassistant.data.usage.ModelUsagePolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

interface ModelConfigurationStore {
    val configuration: StateFlow<ModelRuntimeConfiguration>

    suspend fun initialize()

    suspend fun save(configuration: ModelRuntimeConfiguration)
}

class JsonModelConfigurationStore private constructor(
    private val configurationFile: File,
    private val defaults: ModelRuntimeConfiguration,
    private val allowCleartextHttp: Boolean,
) : ModelConfigurationStore {
    constructor(
        context: Context,
        defaults: ModelRuntimeConfiguration,
        allowCleartextHttp: Boolean,
    ) : this(
        configurationFile = File(context.filesDir, CONFIG_RELATIVE_PATH),
        defaults = defaults,
        allowCleartextHttp = allowCleartextHttp,
    )

    internal constructor(
        configurationFile: File,
        defaults: ModelRuntimeConfiguration,
        allowCleartextHttp: Boolean,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(configurationFile, defaults, allowCleartextHttp)

    private val mutex = Mutex()
    private val json = Json { prettyPrint = true }
    private val state = MutableStateFlow(defaults)
    override val configuration: StateFlow<ModelRuntimeConfiguration> = state.asStateFlow()

    override suspend fun initialize() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                configurationFile.parentFile?.mkdirs()
                val loaded = if (configurationFile.exists()) {
                    runCatching {
                        decode(configurationFile.readText(Charsets.UTF_8)).also {
                            it.validate(allowCleartextHttp)
                        }
                    }.getOrNull()
                } else {
                    null
                }
                val selected = loaded ?: defaults
                state.value = selected
                if (!configurationFile.exists() && selected.baseUrl.isNotBlank()) {
                    write(selected)
                }
            }
        }
    }

    override suspend fun save(configuration: ModelRuntimeConfiguration) {
        configuration.validate(allowCleartextHttp)
        mutex.withLock {
            withContext(Dispatchers.IO) {
                write(configuration)
                state.value = configuration
            }
        }
    }

    private fun write(configuration: ModelRuntimeConfiguration) {
        configurationFile.parentFile?.mkdirs()
        val content = json.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(),
            encode(configuration),
        ) + "\n"
        val temporary = File(configurationFile.parentFile, "${configurationFile.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(configurationFile)) {
            configurationFile.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun encode(configuration: ModelRuntimeConfiguration) = buildJsonObject {
        put("schema_version", configuration.schemaVersion)
        put("revision", configuration.revision)
        put("base_url", configuration.baseUrl)
        put("model", configuration.model)
        put("dialect", configuration.dialect.wireName)
        put("context_window_tokens", configuration.contextWindowTokens)
        put("max_output_tokens", configuration.maxOutputTokens)
        put(
            "sampling",
            buildJsonObject {
                put("temperature", configuration.temperature)
                put("top_p", configuration.topP)
                put("top_k", configuration.topK)
            },
        )
        put("reasoning_enabled", false)
        configuration.voice?.let { voice ->
            put(
                "voice",
                buildJsonObject {
                    put("websocket_url", voice.webSocketUrl)
                    put("asr_model", voice.asrModel)
                    put("tts_model", voice.ttsModel)
                    put("tts_voice", voice.ttsVoice)
                    put("tts_response_format", voice.ttsResponseFormat.wireName)
                    put("tts_sample_rate", voice.ttsSampleRate)
                    put("tts_volume", voice.ttsVolume)
                    put("tts_rate", voice.ttsRate)
                    put("tts_pitch", voice.ttsPitch)
                    put("language", voice.language)
                },
            )
        }
        configuration.updatedAt?.let { put("updated_at", it) }
    }

    private fun decode(value: String): ModelRuntimeConfiguration {
        val root = json.parseToJsonElement(value).jsonObject
        val sampling = root.getValue("sampling").jsonObject
        val voice = root["voice"]?.jsonObject?.let { voiceObject ->
            VoiceRuntimeConfiguration(
                webSocketUrl = voiceObject.getValue("websocket_url").jsonPrimitive.content,
                asrModel = voiceObject.getValue("asr_model").jsonPrimitive.content,
                ttsModel = voiceObject.getValue("tts_model").jsonPrimitive.content,
                ttsVoice = voiceObject.getValue("tts_voice").jsonPrimitive.content,
                ttsResponseFormat = VoiceAudioFormat.fromWireName(
                    voiceObject.getValue("tts_response_format").jsonPrimitive.content,
                ),
                ttsSampleRate = voiceObject.getValue("tts_sample_rate").jsonPrimitive.int,
                ttsVolume = voiceObject.getValue("tts_volume").jsonPrimitive.int,
                ttsRate = voiceObject.getValue("tts_rate").jsonPrimitive.double,
                ttsPitch = voiceObject.getValue("tts_pitch").jsonPrimitive.double,
                language = voiceObject.getValue("language").jsonPrimitive.content,
            )
        }
        return ModelRuntimeConfiguration(
            schemaVersion = root.getValue("schema_version").jsonPrimitive.int,
            revision = root["revision"]?.jsonPrimitive?.longOrNull ?: 0,
            baseUrl = root.getValue("base_url").jsonPrimitive.content,
            model = root.getValue("model").jsonPrimitive.content,
            dialect = when (root.getValue("dialect").jsonPrimitive.content.lowercase()) {
                "standard" -> OpenAiCompatibleDialect.Standard
                "llama_cpp" -> OpenAiCompatibleDialect.LlamaCpp
                else -> error("Unknown model protocol dialect")
            },
            contextWindowTokens = root["context_window_tokens"]
                ?.jsonPrimitive
                ?.int
                ?: ModelUsagePolicy.DEFAULT_CONTEXT_WINDOW_TOKENS.toInt(),
            maxOutputTokens = root.getValue("max_output_tokens").jsonPrimitive.int,
            temperature = sampling.getValue("temperature").jsonPrimitive.double,
            topP = sampling.getValue("top_p").jsonPrimitive.double,
            topK = sampling.getValue("top_k").jsonPrimitive.int,
            voice = voice,
            updatedAt = root["updated_at"]?.jsonPrimitive?.content
                ?.takeIf(String::isNotBlank),
        )
    }

    private companion object {
        const val CONFIG_RELATIVE_PATH = "agent/model-config.json"
    }
}

class InMemoryModelConfigurationStore(
    initial: ModelRuntimeConfiguration,
) : ModelConfigurationStore {
    private val state = MutableStateFlow(initial)
    override val configuration: StateFlow<ModelRuntimeConfiguration> = state.asStateFlow()

    override suspend fun initialize() = Unit

    override suspend fun save(configuration: ModelRuntimeConfiguration) {
        state.value = configuration
    }
}
