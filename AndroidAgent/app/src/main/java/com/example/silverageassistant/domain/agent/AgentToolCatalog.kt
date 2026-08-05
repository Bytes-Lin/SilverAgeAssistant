package com.example.silverageassistant.domain.agent

/**
 * 多 Agent 共享 Tool 实例的能力目录。
 *
 * 当前只共享 get_current_time。主聊天 Agent 与 GUI Agent 使用不同 Registry，但 Registry
 * 中引用同一个无状态 Tool 实例；其他工具保持原有装配，未经确认不加入 GUI 能力视图。
 */
class AgentToolCatalog(
    tools: List<AgentTool>,
) {
    private val toolsByName = tools.associateBy { it.definition.name }

    init {
        require(toolsByName.size == tools.size) { "Tool names must be unique" }
    }

    fun toolsFor(capabilityNames: Set<String>): List<AgentTool> {
        require(capabilityNames.all { toolsByName.containsKey(it) }) {
            "Capability contains an unregistered tool"
        }
        return capabilityNames.map { toolsByName.getValue(it) }
    }
}

object SharedAgentToolCapabilities {
    val MainChat: Set<String> = setOf(CurrentTimeTool.NAME)
    val GuiAgent: Set<String> = setOf(CurrentTimeTool.NAME)
}
