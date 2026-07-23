package com.example.silverageassistant.data.model

enum class OpenAiCompatibleDialect(val wireName: String) {
    Standard("standard"),
    LlamaCpp("llama_cpp"),
}

data class ModelServiceConfig(
    val baseUrl: String,
    val model: String,
    val dialect: OpenAiCompatibleDialect,
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 120,
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
    }

    val chatCompletionsUrl: String
        get() {
            val trimmed = baseUrl.trimEnd('/')
            val apiBase = if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
            return "$apiBase/chat/completions"
        }
}
