package com.example.silverageassistant.data.model

import com.example.silverageassistant.domain.model.ChatModelException
import com.example.silverageassistant.domain.model.ChatModelProvider
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatStreamEvent
import com.example.silverageassistant.domain.model.ChatToolCall
import com.example.silverageassistant.domain.model.ChatUsage
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Chat Completions 的流式协议适配器。
 *
 * 该类只处理请求映射、可选本地 API Key、SSE 增量解析和错误分类，不执行 Agent Tool。
 * Flow 被取消时会同步取消底层 OkHttp Call，防止老人点击停止后网络请求仍继续消耗用量。
 */
class OpenAiCompatibleChatProvider(
    private val config: ModelServiceConfig,
    private val credentialStore: ModelApiCredentialStore,
    client: OkHttpClient? = null,
) : ChatModelProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMapper = OpenAiRequestMapper(config, json)
    private val httpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    override fun stream(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val callReference = AtomicReference<okhttp3.Call?>()
        val worker = launch(Dispatchers.IO) {
            val call = try {
                val body = requestMapper.encode(request)
                    .toRequestBody(JSON_MEDIA_TYPE)
                val requestBuilder = Request.Builder()
                    .url(config.chatCompletionsUrl)
                    .header("Accept", "text/event-stream")
                    .post(body)
                credentialStore.loadApiKey()
                    ?.takeIf(String::isNotBlank)
                    ?.let { requestBuilder.header("Authorization", "Bearer $it") }
                httpClient.newCall(requestBuilder.build())
            } catch (error: Exception) {
                close(mapFailure(error))
                return@launch
            }

            callReference.set(call)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw httpFailure(response.code)
                    }
                    val responseBody = response.body
                        ?: throw invalidResponse()
                    parseStream(responseBody.source())
                }
                close()
            } catch (error: Exception) {
                if (!call.isCanceled()) close(mapFailure(error))
            }
        }
        awaitClose {
            callReference.get()?.cancel()
            worker.cancel()
        }
    }

    private suspend fun ProducerScope<ChatStreamEvent>.parseStream(
        source: okio.BufferedSource,
    ) {
        // 工具参数可能被拆在多个 SSE chunk 中，必须按 index 聚合到完整 JSON 后再交给 Domain。
        val dataLines = mutableListOf<String>()
        val toolCalls = linkedMapOf<Int, MutableToolCall>()
        val thinkingFilter = StreamingThinkingFilter()
        var finishReason: String? = null
        var doneReceived = false

        suspend fun processEventData(data: String): Boolean {
            if (data == "[DONE]") return true
            val root = try {
                json.parseToJsonElement(data).jsonObject
            } catch (error: Exception) {
                throw invalidResponse(error)
            }
            root["error"]?.let { throw apiPayloadFailure(it) }
            root["usage"]?.asObjectOrNull()?.let { usageObject ->
                send(
                    ChatStreamEvent.Usage(
                        ChatUsage(
                            promptTokens = usageObject.longOrNull("prompt_tokens"),
                            completionTokens = usageObject.longOrNull("completion_tokens"),
                            totalTokens = usageObject.longOrNull("total_tokens"),
                        ),
                    ),
                )
            }
            val choices = root["choices"]?.asArrayOrNull().orEmpty()
            choices.forEach { choiceElement ->
                val choice = choiceElement.asObjectOrNull() ?: return@forEach
                finishReason = choice.stringOrNull("finish_reason") ?: finishReason
                val delta = choice["delta"]?.asObjectOrNull() ?: return@forEach
                val reasoning = delta.stringOrNull("reasoning_content")
                if (!reasoning.isNullOrEmpty()) {
                    send(ChatStreamEvent.ReasoningStarted)
                }
                delta.stringOrNull("content")?.let { content ->
                    val filtered = thinkingFilter.feed(content)
                    if (filtered.reasoningDetected) {
                        send(ChatStreamEvent.ReasoningStarted)
                    }
                    if (filtered.visibleText.isNotEmpty()) {
                        send(ChatStreamEvent.TextDelta(filtered.visibleText))
                    }
                }
                delta["tool_calls"]?.asArrayOrNull().orEmpty().forEach { callElement ->
                    val callObject = callElement.asObjectOrNull() ?: return@forEach
                    val index = callObject["index"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0
                    val accumulator = toolCalls.getOrPut(index) { MutableToolCall() }
                    callObject.stringOrNull("id")?.let(accumulator::mergeId)
                    val function = callObject["function"]?.asObjectOrNull()
                    function?.stringOrNull("name")?.let(accumulator::mergeName)
                    function?.get("arguments")?.let(accumulator::appendArguments)
                }
            }
            return false
        }

        while (true) {
            coroutineContext.ensureActive()
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (dataLines.isNotEmpty()) {
                        doneReceived = processEventData(dataLines.joinToString("\n"))
                        dataLines.clear()
                        if (doneReceived) break
                    }
                }
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").trimStart()
                line.startsWith(":") -> Unit
            }
        }
        if (!doneReceived && dataLines.isNotEmpty()) {
            doneReceived = processEventData(dataLines.joinToString("\n"))
        }
        val tail = thinkingFilter.finish()
        if (tail.reasoningDetected) send(ChatStreamEvent.ReasoningStarted)
        if (tail.visibleText.isNotEmpty()) send(ChatStreamEvent.TextDelta(tail.visibleText))
        if (!doneReceived && finishReason == null) {
            throw ChatModelException(
                code = "MODEL_STREAM_INTERRUPTED",
                userMessage = "回答中途断开了，请重新试一次。",
            )
        }
        toolCalls.toSortedMap().values.forEach { accumulator ->
            send(ChatStreamEvent.ToolCallReady(accumulator.toToolCall()))
        }
        send(ChatStreamEvent.Completed(finishReason))
    }

    private fun mapFailure(error: Exception): ChatModelException = when (error) {
        is ChatModelException -> error
        is SocketTimeoutException -> ChatModelException(
            code = "MODEL_TIMEOUT",
            userMessage = "模型回答超时了，请稍后再试。",
            cause = error,
        )
        is IOException -> ChatModelException(
            code = "MODEL_UNREACHABLE",
            userMessage = "暂时连接不上模型服务，请检查网络后重试。",
            cause = error,
        )
        else -> ChatModelException(
            code = "MODEL_REQUEST_FAILED",
            userMessage = "模型回答失败了，请稍后再试。",
            cause = error,
        )
    }

    private fun httpFailure(statusCode: Int): ChatModelException = when (statusCode) {
        401, 403 -> ChatModelException(
            code = "MODEL_AUTHENTICATION_FAILED",
            userMessage = "模型服务密钥无效或尚未配置。",
        )
        404 -> ChatModelException(
            code = "MODEL_NOT_FOUND",
            userMessage = "没有找到配置的模型，请检查模型名称。",
        )
        408 -> ChatModelException(
            code = "MODEL_TIMEOUT",
            userMessage = "模型回答超时了，请稍后再试。",
        )
        429 -> ChatModelException(
            code = "MODEL_BUSY",
            userMessage = "模型现在比较忙，请稍后再试。",
        )
        in 500..599 -> ChatModelException(
            code = "MODEL_SERVICE_ERROR",
            userMessage = "模型服务暂时不可用，请稍后再试。",
        )
        else -> ChatModelException(
            code = "MODEL_HTTP_$statusCode",
            userMessage = "模型请求没有成功，请稍后再试。",
        )
    }

    private fun apiPayloadFailure(error: JsonElement): ChatModelException {
        val errorObject = error.asObjectOrNull()
        val code = errorObject?.stringOrNull("code").orEmpty().ifBlank {
            "MODEL_API_ERROR"
        }
        return ChatModelException(
            code = code,
            userMessage = "模型服务返回了错误，请稍后再试。",
        )
    }

    private fun invalidResponse(cause: Throwable? = null) = ChatModelException(
        code = "INVALID_MODEL_RESPONSE",
        userMessage = "模型返回的内容无法识别，请检查服务版本。",
        cause = cause,
    )

    private class MutableToolCall {
        private var id: String = ""
        private var name: String = ""
        private val arguments = StringBuilder()

        fun mergeId(value: String) {
            id = mergeFragment(id, value)
        }

        fun mergeName(value: String) {
            name = mergeFragment(name, value)
        }

        fun appendArguments(value: JsonElement) {
            val text = if (value is JsonPrimitive && value.isString) {
                value.content
            } else {
                value.toString()
            }
            arguments.append(text)
        }

        fun toToolCall(): ChatToolCall {
            if (name.isBlank()) throw invalidToolCall()
            return ChatToolCall(
                id = id.ifBlank { "tool-${name.hashCode().toUInt()}" },
                name = name,
                argumentsJson = arguments.toString().ifBlank { "{}" },
            )
        }

        private fun mergeFragment(current: String, incoming: String): String = when {
            current.isEmpty() -> incoming
            incoming.startsWith(current) -> incoming
            current.endsWith(incoming) -> current
            else -> current + incoming
        }

        private fun invalidToolCall() = ChatModelException(
            code = "INVALID_TOOL_CALL",
            userMessage = "模型没有正确说明要使用的功能，请重新试一次。",
        )
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.asArrayOrNull() = this as? kotlinx.serialization.json.JsonArray

    private fun JsonObject.stringOrNull(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.longOrNull(name: String): Long? =
        (get(name) as? JsonPrimitive)?.longOrNull

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
