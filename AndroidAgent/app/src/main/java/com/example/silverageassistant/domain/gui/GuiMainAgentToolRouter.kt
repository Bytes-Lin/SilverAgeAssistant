package com.example.silverageassistant.domain.gui

import com.example.silverageassistant.domain.agent.AgentDeterministicToolRoute
import com.example.silverageassistant.domain.agent.AgentDeterministicToolRouter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Routes explicit supported-app operation requests before the main chat model can ask speculative
 * page questions or claim that an operation has already happened.
 *
 * Questions asking only for instructions are intentionally left to normal chat handling.
 */
class GuiMainAgentToolRouter : AgentDeterministicToolRouter {
    override fun route(userText: String): AgentDeterministicToolRoute? {
        val content = userText.trim()
        if (!content.mentionsSupportedApp()) return null
        if (!content.requestsGuiOperation()) return null
        if (content.isInstructionOnlyQuestion() || content.isNegatedOperation()) return null

        GuiDebugTrace.record(
            source = "main_agent_router",
            stage = "gui_start_routed",
            message = "明确的 GUI 操作指令已在主模型调用前路由",
            details = content,
        )
        return AgentDeterministicToolRoute(
            toolName = GuiAgentTool.NAME,
            argumentsJson = buildJsonObject {
                put("action", "START")
                put("task_content", content)
            }.toString(),
            acceptedResponse = "好的，已经开始处理。你可以随时让我暂停或取消。",
            rejectedResponse = "现在还不能开始这个手机操作，请稍后再试。",
        )
    }

    private fun String.mentionsSupportedApp(): Boolean =
        SUPPORTED_APP_ALIASES.any { contains(it, ignoreCase = true) }

    private fun String.requestsGuiOperation(): Boolean =
        OPERATION_TERMS.any { contains(it, ignoreCase = true) }

    private fun String.isInstructionOnlyQuestion(): Boolean =
        INSTRUCTION_ONLY_TERMS.any { contains(it, ignoreCase = true) }

    private fun String.isNegatedOperation(): Boolean =
        NEGATED_OPERATION_TERMS.any { contains(it, ignoreCase = true) }

    private companion object {
        val SUPPORTED_APP_ALIASES = setOf(
            "美团",
            "Meituan",
            "微信",
            "WeChat",
            "淘宝",
            "Taobao",
        )
        val OPERATION_TERMS = setOf(
            "打开",
            "进入",
            "点击",
            "点一下",
            "点开",
            "搜索",
            "查找",
            "找一",
            "输入",
            "发送",
            "购买",
            "买一",
            "下单",
            "点餐",
            "外卖",
            "购物车",
        )
        val INSTRUCTION_ONLY_TERMS = setOf(
            "怎么操作",
            "怎么打开",
            "怎么点",
            "怎么下单",
            "怎么搜索",
            "怎么用",
            "如何操作",
            "如何打开",
            "如何点",
            "如何下单",
            "如何搜索",
            "如何用",
            "教我",
            "教程",
        )
        val NEGATED_OPERATION_TERMS = setOf(
            "不要打开",
            "别打开",
            "不用打开",
            "取消打开",
            "打不开",
            "无法打开",
            "没有打开",
            "还没打开",
        )
    }
}
