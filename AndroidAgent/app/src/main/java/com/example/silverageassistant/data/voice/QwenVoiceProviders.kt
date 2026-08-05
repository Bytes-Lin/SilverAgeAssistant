package com.example.silverageassistant.data.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.example.silverageassistant.data.model.ModelConfigurationStore
import com.example.silverageassistant.data.model.VoiceApiCredentialStore
import com.example.silverageassistant.data.model.VoiceAudioFormat
import com.example.silverageassistant.data.usage.ModelUsageRecorder
import com.example.silverageassistant.domain.voice.AgentAsrProvider
import com.example.silverageassistant.domain.voice.AgentAsrResult
import com.example.silverageassistant.domain.voice.AgentTtsProvider
import com.example.silverageassistant.domain.voice.VoiceAvailability
import com.example.silverageassistant.domain.voice.VoiceFeature
import com.example.silverageassistant.domain.voice.VoiceListeningState
import com.example.silverageassistant.domain.voice.VoiceRequestContext
import com.example.silverageassistant.domain.voice.VoiceSpeakingState
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

private const val PROVIDER_NAME = "aliyun-bailian"
private const val SAMPLE_RATE = 16_000
private const val PCM_BYTES_PER_SAMPLE = 2
private const val FRAME_MILLIS = 100
private const val FRAME_BYTES = SAMPLE_RATE * PCM_BYTES_PER_SAMPLE * FRAME_MILLIS / 1_000
private const val TASK_TIMEOUT_MILLIS = 30_000L

class QwenRealtimeAsrProvider(
    private val context: Context,
    private val configurationStore: ModelConfigurationStore,
    private val credentialStore: VoiceApiCredentialStore,
    private val usageRecorder: ModelUsageRecorder,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AgentAsrProvider {
    private val availabilityState = MutableStateFlow(VoiceAvailability.AVAILABLE)
    private val listeningStateValue = MutableStateFlow(VoiceListeningState.IDLE)
    override val availability = availabilityState.asStateFlow()
    override val listeningState = listeningStateValue.asStateFlow()

    private val operationMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var active: AsrTask? = null

    @SuppressLint("MissingPermission")
    override suspend fun startListening(context: VoiceRequestContext) = operationMutex.withLock {
        check(active == null) { "已经在录音" }
        check(
            ContextCompat.checkSelfPermission(this.context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "请允许银龄助手使用麦克风" }
        val configuration = configurationStore.configuration.value.voice
            ?: error("家属尚未配置语音模型")
        val apiKey = credentialStore.loadVoiceApiKey()?.takeIf(String::isNotBlank)
            ?: error("请先在老人端设置语音 API Key")
        val task = AsrTask(
            requestContext = context,
            taskId = UUID.randomUUID().toString(),
            model = configuration.asrModel,
            startedAt = System.currentTimeMillis(),
        )
        active = task
        listeningStateValue.value = VoiceListeningState.PROCESSING
        try {
            val request = Request.Builder()
                .url(configuration.webSocketUrl)
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", "SilverAgeAssistant-Android")
                .build()
            task.socket = client.newWebSocket(request, AsrListener(task, configuration.language))
            withTimeout(TASK_TIMEOUT_MILLIS) { task.taskStarted.await() }
            @Suppress("DEPRECATION")
            check(
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
            ) { "当前无法使用麦克风，请稍后再试" }

            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumBuffer > 0) { "麦克风暂时不可用" }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBuffer, FRAME_BYTES * 2),
            )
            // 先挂到任务上再校验，确保初始化或 startRecording 抛错时也能 release。
            task.audioRecord = recorder
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            recorder.startRecording()
            listeningStateValue.value = VoiceListeningState.LISTENING
            task.recordingJob = scope.launch { streamMicrophone(task) }
        } catch (error: Exception) {
            withContext(NonCancellable) { finishTask(task, successful = false) }
            throw error
        }
    }

    override suspend fun stopListening(): AgentAsrResult = operationMutex.withLock {
        val task = active ?: error("当前没有录音")
        listeningStateValue.value = VoiceListeningState.PROCESSING
        stopRecorder(task)
        task.socket?.send(finishTaskJson(task.taskId))
        return@withLock try {
            withTimeout(TASK_TIMEOUT_MILLIS) { task.taskFinished.await() }
            val transcript = synchronized(task.finalSentences) {
                task.finalSentences.joinToString("").trim()
            }
            finishTask(task, successful = transcript.isNotBlank())
            AgentAsrResult(transcript = transcript, confidence = null)
        } catch (error: Exception) {
            withContext(NonCancellable) { finishTask(task, successful = false) }
            throw error
        }
    }

    override suspend fun cancelListening() {
        operationMutex.withLock {
            active?.let { task ->
                stopRecorder(task)
                task.socket?.close(1000, null)
                finishTask(task, successful = false)
            }
        }
    }

    private suspend fun streamMicrophone(task: AsrTask) {
        val buffer = ByteArray(FRAME_BYTES)
        while (scope.isActive && active === task) {
            val read = task.audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read > 0) {
                task.socket?.send(buffer.toByteString(0, read))
            }
        }
        buffer.fill(0)
    }

    private suspend fun stopRecorder(task: AsrTask) {
        val recorder = task.audioRecord
        task.audioRecord = null
        recorder?.let {
            runCatching { it.stop() }
        }
        task.recordingJob?.cancelAndJoin()
        task.recordingJob = null
        recorder?.let { runCatching { it.release() } }
    }

    private suspend fun finishTask(task: AsrTask, successful: Boolean) {
        runCatching { stopRecorder(task) }
        task.socket?.close(1000, null)
        task.socket = null
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(null)
        if (!task.usageRecorded) {
            task.usageRecorded = true
            usageRecorder.recordAsr(
                provider = PROVIDER_NAME,
                model = task.model,
                feature = task.requestContext.feature.usageFeature,
                startedAtEpochMillis = task.startedAt,
                finishedAtEpochMillis = System.currentTimeMillis(),
                audioDurationMillis = 0,
                successful = successful,
            )
        }
        if (active === task) active = null
        listeningStateValue.value = VoiceListeningState.IDLE
    }

    private inner class AsrListener(
        private val task: AsrTask,
        private val language: String,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(asrRunTaskJson(task.taskId, task.model, language))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return
            val header = root.optJSONObject("header") ?: return
            when (header.optString("event")) {
                "task-started" -> task.taskStarted.complete(Unit)
                "result-generated" -> {
                    val sentence = root.optJSONObject("payload")
                        ?.optJSONObject("output")
                        ?.optJSONObject("sentence")
                    if (
                        sentence?.optBoolean("sentence_end") == true &&
                        !sentence.optBoolean("heartbeat")
                    ) {
                        val value = sentence.optString("text").trim()
                        if (value.isNotBlank()) synchronized(task.finalSentences) {
                            task.finalSentences += value
                        }
                    }
                }
                "task-finished" -> task.taskFinished.complete(Unit)
                "task-failed" -> task.fail(
                    IllegalStateException(header.optString("error_message", "语音识别失败")),
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            task.fail(IllegalStateException("无法连接语音识别服务", t))
        }
    }

    private data class AsrTask(
        val requestContext: VoiceRequestContext,
        val taskId: String,
        val model: String,
        val startedAt: Long,
        val taskStarted: CompletableDeferred<Unit> = CompletableDeferred(),
        val taskFinished: CompletableDeferred<Unit> = CompletableDeferred(),
        val finalSentences: MutableList<String> = mutableListOf(),
        var socket: WebSocket? = null,
        var audioRecord: AudioRecord? = null,
        var recordingJob: Job? = null,
        var usageRecorded: Boolean = false,
    ) {
        fun fail(error: Throwable) {
            if (!taskStarted.isCompleted) taskStarted.completeExceptionally(error)
            if (!taskFinished.isCompleted) taskFinished.completeExceptionally(error)
        }
    }
}

class QwenRealtimeTtsProvider(
    context: Context,
    private val configurationStore: ModelConfigurationStore,
    private val credentialStore: VoiceApiCredentialStore,
    private val usageRecorder: ModelUsageRecorder,
) : AgentTtsProvider {
    private val availabilityState = MutableStateFlow(VoiceAvailability.AVAILABLE)
    private val speakingStateValue = MutableStateFlow(VoiceSpeakingState.IDLE)
    override val availability = availabilityState.asStateFlow()
    override val speakingState = speakingStateValue.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val operationMutex = Mutex()
    private var active: TtsTask? = null

    override suspend fun speak(context: VoiceRequestContext, text: String) = operationMutex.withLock {
        val normalized = text.trim()
        if (normalized.isBlank()) return@withLock
        require(normalized.length <= 20_000) { "回答太长，已保留文字但暂时不能播报" }
        stopInternal()
        val configuration = configurationStore.configuration.value.voice
            ?: error("家属尚未配置语音模型")
        require(configuration.ttsResponseFormat == VoiceAudioFormat.Pcm) {
            "老人端流式播放暂时只支持 PCM 格式"
        }
        val apiKey = credentialStore.loadVoiceApiKey()?.takeIf(String::isNotBlank)
            ?: error("请先在老人端设置语音 API Key")
        val task = TtsTask(
            requestContext = context,
            taskId = UUID.randomUUID().toString(),
            model = configuration.ttsModel,
            startedAt = System.currentTimeMillis(),
        )
        active = task
        speakingStateValue.value = VoiceSpeakingState.SPEAKING
        try {
            val request = Request.Builder()
                .url(configuration.webSocketUrl)
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", "SilverAgeAssistant-Android")
                .build()
            task.socket = client.newWebSocket(
                request,
                TtsListener(task, normalized, configuration),
            )
            withTimeout(TASK_TIMEOUT_MILLIS * 2) { task.taskFinished.await() }
            waitForPlayback(task)
            finishTts(task, successful = true)
        } catch (error: Exception) {
            finishTts(task, successful = false)
            throw error
        }
    }

    override suspend fun stop() = operationMutex.withLock { stopInternal() }

    private suspend fun stopInternal() {
        active?.let { task ->
            task.socket?.send(cancelTaskJson(task.taskId))
            finishTts(task, successful = false)
        }
    }

    private suspend fun finishTts(task: TtsTask, successful: Boolean) {
        task.socket?.close(1000, null)
        task.socket = null
        task.audioTrack?.let { track ->
            runCatching { track.stop() }
            track.release()
        }
        task.audioTrack = null
        abandonAudioFocus()
        if (!task.usageRecorded) {
            task.usageRecorded = true
            usageRecorder.recordTts(
                provider = PROVIDER_NAME,
                model = task.model,
                feature = task.requestContext.feature.usageFeature,
                startedAtEpochMillis = task.startedAt,
                finishedAtEpochMillis = System.currentTimeMillis(),
                characterCount = 0,
                audioDurationMillis = 0,
                successful = successful,
            )
        }
        if (active === task) active = null
        speakingStateValue.value = VoiceSpeakingState.IDLE
    }

    private suspend fun waitForPlayback(task: TtsTask) {
        val track = task.audioTrack ?: return
        val targetFrames = task.receivedBytes / PCM_BYTES_PER_SAMPLE
        withTimeout(TASK_TIMEOUT_MILLIS) {
            while (track.playbackHeadPosition.toLong() < targetFrames) delay(40)
        }
    }

    private fun requestAudioFocus(): Boolean {
        @Suppress("DEPRECATION")
        return audioManager.requestAudioFocus(
            null,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(null)
    }

    private inner class TtsListener(
        private val task: TtsTask,
        private val text: String,
        private val configuration: com.example.silverageassistant.data.model.VoiceRuntimeConfiguration,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(ttsRunTaskJson(task.taskId, configuration))
        }

        override fun onMessage(webSocket: WebSocket, textMessage: String) {
            val root = runCatching { JSONObject(textMessage) }.getOrNull() ?: return
            val header = root.optJSONObject("header") ?: return
            when (header.optString("event")) {
                "task-started" -> {
                    if (!requestAudioFocus()) {
                        task.fail(IllegalStateException("当前无法播放语音"))
                        return
                    }
                    task.audioTrack = createPcmAudioTrack(configuration.ttsSampleRate).also {
                        it.play()
                    }
                    webSocket.send(continueTaskJson(task.taskId, text))
                    webSocket.send(finishTaskJson(task.taskId))
                }
                "task-finished" -> task.taskFinished.complete(Unit)
                "task-failed" -> task.fail(
                    IllegalStateException(header.optString("error_message", "语音播报失败")),
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val audio = bytes.toByteArray()
            task.audioTrack?.write(audio, 0, audio.size, AudioTrack.WRITE_BLOCKING)
            task.receivedBytes += audio.size
            audio.fill(0)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            task.fail(IllegalStateException("无法连接语音播报服务", t))
        }
    }

    private data class TtsTask(
        val requestContext: VoiceRequestContext,
        val taskId: String,
        val model: String,
        val startedAt: Long,
        val taskFinished: CompletableDeferred<Unit> = CompletableDeferred(),
        var socket: WebSocket? = null,
        var audioTrack: AudioTrack? = null,
        var receivedBytes: Long = 0,
        var usageRecorded: Boolean = false,
    ) {
        fun fail(error: Throwable) {
            if (!taskFinished.isCompleted) taskFinished.completeExceptionally(error)
        }
    }
}

private fun createPcmAudioTrack(sampleRate: Int): AudioTrack {
    val minimum = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    check(minimum > 0) { "音频播放器初始化失败" }
    return AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(maxOf(minimum, sampleRate * PCM_BYTES_PER_SAMPLE))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
}

private fun asrRunTaskJson(taskId: String, model: String, language: String): String =
    JSONObject().apply {
        put("header", taskHeader("run-task", taskId))
        put(
            "payload",
            JSONObject().apply {
                put("task_group", "audio")
                put("task", "asr")
                put("function", "recognition")
                put("model", model)
                put(
                    "parameters",
                    JSONObject().apply {
                        put("format", "pcm")
                        put("sample_rate", SAMPLE_RATE)
                        put("language_hints", org.json.JSONArray().put(language))
                        put("semantic_punctuation_enabled", false)
                        put("max_sentence_silence", 800)
                    },
                )
                put("input", JSONObject())
            },
        )
    }.toString()

private fun ttsRunTaskJson(
    taskId: String,
    configuration: com.example.silverageassistant.data.model.VoiceRuntimeConfiguration,
): String = JSONObject().apply {
    put("header", taskHeader("run-task", taskId))
    put(
        "payload",
        JSONObject().apply {
            put("task_group", "audio")
            put("task", "tts")
            put("function", "SpeechSynthesizer")
            put("model", configuration.ttsModel)
            put(
                "parameters",
                JSONObject().apply {
                    put("text_type", "PlainText")
                    put("voice", configuration.ttsVoice)
                    put("format", configuration.ttsResponseFormat.wireName)
                    put("sample_rate", configuration.ttsSampleRate)
                    put("volume", configuration.ttsVolume)
                    put("rate", configuration.ttsRate)
                    put("pitch", configuration.ttsPitch)
                    put("enable_ssml", false)
                    put("language_hints", org.json.JSONArray().put(configuration.language))
                },
            )
            put("input", JSONObject())
        },
    )
}.toString()

private fun continueTaskJson(taskId: String, text: String): String = JSONObject().apply {
    put("header", taskHeader("continue-task", taskId))
    put("payload", JSONObject().put("input", JSONObject().put("text", text)))
}.toString()

private fun finishTaskJson(taskId: String): String = JSONObject().apply {
    put("header", taskHeader("finish-task", taskId))
    put("payload", JSONObject().put("input", JSONObject()))
}.toString()

private fun cancelTaskJson(taskId: String): String = JSONObject().apply {
    put("header", taskHeader("finish-task", taskId))
    put("payload", JSONObject().put("input", JSONObject().put("directive", "cancel")))
}.toString()

private fun taskHeader(action: String, taskId: String): JSONObject = JSONObject().apply {
    put("action", action)
    put("task_id", taskId)
    put("streaming", "duplex")
}

private val VoiceFeature.usageFeature: String
    get() = when (this) {
        VoiceFeature.CONVERSATION -> "voice"
        VoiceFeature.FAMILY_NOTIFICATION -> "voice"
        VoiceFeature.NEWS -> "voice"
        VoiceFeature.GUI_AGENT -> "voice"
    }
