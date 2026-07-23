package com.example.silverageassistant.data.safety

import android.util.Base64
import com.example.silverageassistant.data.model.ModelApiCredentialStore
import com.example.silverageassistant.data.model.ModelConfigurationStore
import com.example.silverageassistant.data.model.ModelRuntimeConfiguration
import com.example.silverageassistant.data.model.OpenAiCompatibleDialect
import com.example.silverageassistant.data.usage.ModelUsageRecorder
import com.example.silverageassistant.domain.model.ChatUsage
import com.example.silverageassistant.domain.safety.ElderObservedState
import com.example.silverageassistant.domain.safety.SafetyAnalysisResult
import com.example.silverageassistant.domain.safety.SafetyImage
import com.example.silverageassistant.domain.safety.SafetyVisionAnalyzer
import com.example.silverageassistant.domain.safety.TimedSafetyAnalysis
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAiSafetyVisionAnalyzer(
    private val configurationStore: ModelConfigurationStore,
    private val credentialStore: ModelApiCredentialStore,
    private val usageRecorder: ModelUsageRecorder? = null,
    private val client: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SafetyVisionAnalyzer {
    override suspend fun analyze(
        image: SafetyImage,
        previousState: TimedSafetyAnalysis?,
        observedAt: String,
    ): SafetyAnalysisResult = withContext(Dispatchers.IO) {
        require(image.bytes.isNotEmpty()) { "状态检测图像为空" }
        require(image.bytes.size <= MAX_IMAGE_BYTES) { "状态检测图像过大" }
        val configuration = configurationStore.configuration.value
        val startedAt = clock()
        var successful = false
        var usage = ChatUsage()
        try {
            val body = buildRequest(configuration, image, previousState, observedAt)
            val builder = Request.Builder()
                .url(configuration.toServiceConfig().chatCompletionsUrl)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            credentialStore.loadApiKey()?.takeIf(String::isNotBlank)?.let {
                builder.header("Authorization", "Bearer $it")
            }
            val responseJson = client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("MLLM HTTP ${response.code}")
                JSONObject(response.body?.string() ?: throw IOException("MLLM 返回为空"))
            }
            usage = responseJson.optJSONObject("usage")?.let {
                ChatUsage(
                    promptTokens = it.optLongOrNull("prompt_tokens"),
                    completionTokens = it.optLongOrNull("completion_tokens"),
                    totalTokens = it.optLongOrNull("total_tokens"),
                )
            } ?: ChatUsage()
            val content = responseJson.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .get("content")
                .let(::readContent)
            parseResult(content).also { successful = true }
        } finally {
            runCatching {
                usageRecorder?.recordMllm(
                    provider = "openai_compatible",
                    model = configuration.model,
                    feature = "safety_monitoring",
                    startedAtEpochMillis = startedAt,
                    finishedAtEpochMillis = clock(),
                    usage = usage,
                    estimated = usage.promptTokens == null || usage.completionTokens == null,
                    successful = successful,
                )
            }
        }
    }

    private fun buildRequest(
        configuration: ModelRuntimeConfiguration,
        image: SafetyImage,
        previousState: TimedSafetyAnalysis?,
        observedAt: String,
    ): JSONObject {
        val context = previousState?.let {
            JSONObject()
                .put("observed_at", it.observedAt)
                .put("state", it.result.state.wireName)
                .put("detail", it.result.detail ?: JSONObject.NULL)
                .toString()
        } ?: "空"
        val dataUrl = "data:${image.mimeType};base64,${Base64.encodeToString(image.bytes, Base64.NO_WRAP)}"
        val userContent = JSONArray()
            .put(JSONObject()
                .put("type", "text")
                .put("text", "当前检测时间：$observedAt\ncontext（最近一次状态）：$context\n请分析本次图像，只返回规定 JSON。"))
            .put(JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", dataUrl)))
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", userContent))
        return JSONObject()
            .put("model", configuration.model)
            .put("stream", false)
            .put("temperature", configuration.temperature)
            .put("top_p", configuration.topP)
            .put("max_tokens", minOf(configuration.maxOutputTokens, 256))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", messages)
            .also { root ->
                if (configuration.dialect == OpenAiCompatibleDialect.LlamaCpp) {
                    root.put("top_k", configuration.topK)
                    root.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                }
            }
    }

    private fun readContent(value: Any): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val item = value.optJSONObject(index) ?: continue
                if (item.optString("type") == "text") append(item.optString("text"))
            }
        }
        else -> value.toString()
    }

    private fun parseResult(raw: String): SafetyAnalysisResult {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val root = JSONObject(cleaned)
        require(root.keys().asSequence().toSet() in ALLOWED_KEYS) { "模型返回了未允许字段" }
        val state = when (root.getString("state").trim().lowercase()) {
            "normal", "正常" -> ElderObservedState.NORMAL
            "abnormal", "异常" -> ElderObservedState.ABNORMAL
            else -> error("无法识别老人状态")
        }
        val detail = root.optString("detail").trim().takeIf(String::isNotBlank)
        if (state == ElderObservedState.ABNORMAL) require(!detail.isNullOrBlank()) {
            "异常状态必须包含详情"
        }
        return SafetyAnalysisResult(
            state = state,
            detail = detail?.take(MAX_DETAIL_LENGTH),
        )
    }

    private val ElderObservedState.wireName: String
        get() = if (this == ElderObservedState.NORMAL) "正常" else "异常"

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) getLong(name) else null

    private companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_DETAIL_LENGTH = 200
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val ALLOWED_KEYS = setOf(setOf("state"), setOf("state", "detail"))
        val SYSTEM_PROMPT = """
            你是银龄助手中独立运行的老人安全状态检测 Agent，与聊天 Agent 完全分离。
            你的唯一任务是根据当前图像和最近一次状态，判断老人当前状态是否正常。
            重点观察疑似跌倒、倒地、晕倒、昏迷或长时间失去正常姿态等明显异常。
            不做医疗诊断，不识别人脸身份，不推测图像外的信息；证据不足时选择正常，但不要忽略明显危险。
            只返回 JSON：正常时 {"state":"正常"}；异常时 {"state":"异常","detail":"简短、客观描述可见异常"}。
            禁止返回 Markdown、解释、建议、置信度或其他字段。
        """.trimIndent()
    }
}
