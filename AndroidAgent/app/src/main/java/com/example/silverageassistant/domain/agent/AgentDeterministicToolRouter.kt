package com.example.silverageassistant.domain.agent

/**
 * A small, deterministic route for requests that must reach a tool before a language model can
 * truthfully discuss the result. This does not grant extra tool permissions: execution still goes
 * through [AgentToolRegistry] and the same local execution policy as model-originated calls.
 */
data class AgentDeterministicToolRoute(
    val toolName: String,
    val argumentsJson: String,
    val acceptedResponse: String,
    val rejectedResponse: String,
)

fun interface AgentDeterministicToolRouter {
    fun route(userText: String): AgentDeterministicToolRoute?
}
