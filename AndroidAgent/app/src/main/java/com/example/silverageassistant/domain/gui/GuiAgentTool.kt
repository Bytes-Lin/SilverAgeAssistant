package com.example.silverageassistant.domain.gui

import com.example.silverageassistant.domain.agent.AgentTool
import com.example.silverageassistant.domain.agent.ToolExecutionPolicy
import com.example.silverageassistant.domain.agent.ToolRiskLevel
import com.example.silverageassistant.domain.model.ChatToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 主聊天 Agent 面向 GUI Agent 的异步门面。
 *
 * Tool 只创建或控制任务并立即返回，不等待 GUI Run 完成。真实截图、规划和动作由独立的
 * GuiRunExecutor 在后台运行，因此不会阻塞主聊天 Agent。
 */
class GuiAgentTool(
    private val controller: GuiTaskController,
) : AgentTool {
    override val definition = ChatToolDefinition(
        name = NAME,
        description = "实际打开或操作美团、微信、淘宝等手机App。异步创建/查询/控制 GUI 任务；STARTED 仅表示任务创建，绝不表示 App 或页面操作已经完成。一次只能运行一个任务。",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["START", "STATUS", "PAUSE", "RESUME", "CANCEL"]
                },
                "task_content": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 200
                }
              },
              "required": ["action"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )
    override val riskLevel = ToolRiskLevel.Medium
    override val executionPolicy = ToolExecutionPolicy.ImmediateAfterLocalPolicy
    override val runningDisplayName = "正在准备手机辅助任务"

    override suspend fun execute(argumentsJson: String): String {
        val arguments = Json.parseToJsonElement(argumentsJson).jsonObject
        require(arguments.keys.all { it == "action" || it == "task_content" })
        val action = GuiAgentAction.valueOf(
            arguments.getValue("action").jsonPrimitive.content,
        )
        GuiDebugTrace.record(
            source = "main_agent_tool",
            stage = "call",
            message = "主 Agent 调用 gui_agent：${action.name}",
            details = argumentsJson,
        )
        val result = when (action) {
            GuiAgentAction.START -> start(
                arguments["task_content"]?.jsonPrimitive?.content.orEmpty(),
            )
            GuiAgentAction.STATUS -> status()
            GuiAgentAction.PAUSE -> control(
                controller.pause(GuiPauseReason.MAIN_AGENT_REQUEST),
            )
            GuiAgentAction.RESUME -> control(controller.resume())
            GuiAgentAction.CANCEL -> control(controller.cancel())
        }
        GuiDebugTrace.record(
            source = "main_agent_tool",
            stage = "result",
            message = "gui_agent ${action.name} 已返回",
            details = result,
        )
        return result
    }

    private suspend fun start(content: String): String = when (
        val result = controller.startTask(content)
    ) {
        is GuiTaskStartResult.Accepted -> taskResult(
            ok = true,
            status = "STARTED",
            snapshot = result.snapshot,
            message = "任务刚创建，尚未确认目标 App 已打开，也没有确认任何点击、输入、下单或付款操作完成。主 Agent 只能简短告知已开始处理。",
        )
        is GuiTaskStartResult.Busy -> taskResult(
            ok = false,
            status = "BUSY",
            snapshot = result.snapshot,
            message = "当前已有 GUI 任务正在进行",
        )
        is GuiTaskStartResult.Invalid -> errorResult("INVALID_GUI_TASK", result.message)
    }

    private fun status(): String {
        val snapshot = controller.activeTask.value
            ?: return errorResult("NO_ACTIVE_GUI_TASK", "当前没有 GUI 任务")
        return taskResult(
            ok = true,
            status = snapshot.phase.name,
            snapshot = snapshot,
            message = "已取得 GUI 任务状态",
        )
    }

    private fun control(result: GuiTaskControlResult): String = when (result) {
        is GuiTaskControlResult.Updated -> taskResult(
            ok = true,
            status = result.snapshot.phase.name,
            snapshot = result.snapshot,
            message = "GUI 任务状态已更新",
        )
        GuiTaskControlResult.NoActiveTask ->
            errorResult("NO_ACTIVE_GUI_TASK", "当前没有 GUI 任务")
        GuiTaskControlResult.AlreadyFinished ->
            errorResult("GUI_TASK_ALREADY_FINISHED", "GUI 任务已经结束")
    }

    private fun taskResult(
        ok: Boolean,
        status: String,
        snapshot: GuiTaskSnapshot,
        message: String,
    ): String = buildJsonObject {
        put("ok", ok)
        put("status", status)
        put("todo_id", snapshot.todoId)
        put("run_attempt", snapshot.runAttempt)
        put("message", message)
    }.toString()

    private fun errorResult(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error_code", code)
        put("message", message)
    }.toString()

    private enum class GuiAgentAction {
        START,
        STATUS,
        PAUSE,
        RESUME,
        CANCEL,
    }

    companion object {
        const val NAME = "gui_agent"
    }
}
