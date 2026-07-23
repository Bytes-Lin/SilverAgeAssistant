package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.domain.model.ChatToolDefinition

enum class ToolRiskLevel {
    Low,
    Medium,
    High,
}

interface AgentTool {
    val definition: ChatToolDefinition
    val riskLevel: ToolRiskLevel
    val executionPolicy: ToolExecutionPolicy
        get() = ToolExecutionPolicy.Immediate
    val runningDisplayName: String
        get() = "正在使用功能"

    suspend fun execute(argumentsJson: String): String
}

enum class ToolExecutionPolicy {
    Immediate,
    ImmediateAfterLocalPolicy,
    PrepareForUserConfirmation,
}

class AgentToolRegistry(
    tools: List<AgentTool>,
) {
    private val toolsByName = tools.associateBy { it.definition.name }

    val definitions: List<ChatToolDefinition>
        get() = toolsByName.values.map(AgentTool::definition)

    fun find(name: String): AgentTool? = toolsByName[name]
}
