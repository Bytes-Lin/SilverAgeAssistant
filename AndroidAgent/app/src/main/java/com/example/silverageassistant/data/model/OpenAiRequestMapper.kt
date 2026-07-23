package com.example.silverageassistant.data.model

import com.example.silverageassistant.domain.model.ChatMessage
import com.example.silverageassistant.domain.model.ChatRequest
import com.example.silverageassistant.domain.model.ChatRole
import com.example.silverageassistant.domain.model.ReasoningMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class OpenAiRequestMapper(
    private val config: ModelServiceConfig,
    private val json: Json = Json,
) {
    fun encode(request: ChatRequest): String = buildJsonObject {
        put("model", config.model)
        put("messages", JsonArray(request.messages.map(::messageJson)))
        put("stream", true)
        put(
            "stream_options",
            buildJsonObject { put("include_usage", true) },
        )
        put("temperature", request.sampling.temperature)
        put("top_p", request.sampling.topP)
        put("max_tokens", request.maxOutputTokens)
        if (config.dialect == OpenAiCompatibleDialect.LlamaCpp) {
            put("top_k", request.sampling.topK)
            put(
                "chat_template_kwargs",
                buildJsonObject {
                    put(
                        "enable_thinking",
                        request.reasoningMode == ReasoningMode.Enabled,
                    )
                },
            )
        }
        if (request.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    request.tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put(
                                            "parameters",
                                            json.parseToJsonElement(tool.parametersJson).jsonObject,
                                        )
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put("tool_choice", "auto")
            put("parallel_tool_calls", false)
        }
    }.toString()

    private fun messageJson(message: ChatMessage): JsonObject = buildJsonObject {
        put("role", message.role.wireName)
        if (message.content != null) {
            put("content", message.content)
        } else if (message.role == ChatRole.Assistant && message.toolCalls.isNotEmpty()) {
            put("content", JsonNull)
        }
        message.toolCallId?.let { put("tool_call_id", it) }
        if (message.toolCalls.isNotEmpty()) {
            put(
                "tool_calls",
                buildJsonArray {
                    message.toolCalls.forEach { call ->
                        add(
                            buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.argumentsJson)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}
