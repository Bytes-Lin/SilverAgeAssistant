package com.example.silverageassistant.domain.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 对明确查询今日待办/提醒的说法进行确定性路由，避免模型使用旧对话或长期记忆猜测。
 * 创建、修改提醒以及仅询问操作方法的说法仍交给正常 Agent 流程。
 */
class TodayRemindersMainAgentToolRouter : AgentDeterministicToolRouter {
    override fun route(userText: String): AgentDeterministicToolRoute? {
        val normalized = userText.normalizeReminderQuery()
        if (normalized.isBlank()) return null
        if (INSTRUCTION_ONLY_TERMS.any(normalized::contains)) return null
        if (QUERY_TERMS.none(normalized::contains)) return null
        return AgentDeterministicToolRoute(
            toolName = TodayRemindersTool.NAME,
            argumentsJson = "{}",
            acceptedResponse = "已经查看了今天的提醒。",
            rejectedResponse = "暂时无法读取今天的提醒，请稍后再试。",
            resultPresenter = TodayRemindersToolResultPresenter,
        )
    }

    private fun String.normalizeReminderQuery(): String =
        lowercase().replace(IGNORED_CHARACTERS, "")

    private companion object {
        val IGNORED_CHARACTERS = Regex("[\\s，。！？、,.!?：:；;]")
        val QUERY_TERMS = setOf(
            "今天有什么事",
            "今天还有什么",
            "今天有什么安排",
            "今天有啥安排",
            "今天有哪些提醒",
            "今天有什么提醒",
            "今天还有提醒吗",
            "今天还有没有提醒",
            "今日提醒有哪些",
            "今日有什么提醒",
            "还有什么事没做",
            "还有什么没做",
            "有什么事没做",
            "有哪些没做",
            "未完成的事",
            "没完成的事",
            "今天待办",
        )
        val INSTRUCTION_ONLY_TERMS = setOf(
            "怎么查看",
            "如何查看",
            "怎么使用",
            "如何使用",
            "教我",
        )
    }
}

/** 只向老人罗列尚未确认完成的提醒；completed 只参与“是否都已确认”的判断。 */
object TodayRemindersToolResultPresenter : AgentDeterministicToolResultPresenter {
    override fun present(resultJson: String): String {
        val result = Json.parseToJsonElement(resultJson).jsonObject
        val items = result["items"]?.jsonArray.orEmpty()
        val outstanding = items.mapNotNull { element ->
            val item = element.jsonObject
            val status = item["status"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (status != "pending" && status != "snoozed") return@mapNotNull null
            ReminderSpeechItem(
                localTime = item["local_time"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                detail = item["detail"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                source = item["source_display_name"]?.jsonPrimitive?.contentOrNull,
                snoozed = status == "snoozed",
            )
        }
        if (outstanding.isEmpty()) {
            return if (items.isEmpty()) {
                "今天暂时没有提醒。"
            } else {
                "今天的提醒都已经确认完成了。"
            }
        }
        return buildString {
            append("今天还有")
            append(outstanding.size)
            append("个提醒尚未确认完成：")
            outstanding.forEachIndexed { index, item ->
                if (index > 0) append('；')
                append(index + 1)
                append(". ")
                item.localTime.takeIf(String::isNotBlank)?.let {
                    append(it)
                    append('，')
                }
                append(item.title.ifBlank { "未命名提醒" })
                item.detail
                    .takeIf { it.isNotBlank() && it != item.title }
                    ?.let {
                        append("，")
                        append(it)
                    }
                item.source?.takeIf(String::isNotBlank)?.let {
                    append("，来自")
                    append(it)
                }
                if (item.snoozed) append("，已设置稍后提醒")
            }
            append('。')
        }
    }

    private data class ReminderSpeechItem(
        val localTime: String,
        val title: String,
        val detail: String,
        val source: String?,
        val snoozed: Boolean,
    )
}
