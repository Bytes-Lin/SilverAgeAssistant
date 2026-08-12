package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.data.reminders.ReminderRepository
import com.example.silverageassistant.data.reminders.StoredReminderStatus
import com.example.silverageassistant.domain.model.ChatToolDefinition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 向聊天 Agent 导出老人设备 Room 中的今日提醒快照。
 *
 * 这是只读 Tool。模型不能通过本工具修改完成状态；完成只能来自老人明确确认。
 */
class TodayRemindersTool(
    private val repository: ReminderRepository,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) : AgentTool {
    override val definition = ChatToolDefinition(
        name = NAME,
        description = "读取老人手机中今天的提醒状态，用于列出尚未确认完成的提醒或核对某条提醒是否已确认。",
        parametersJson = """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
        """.trimIndent(),
    )
    override val riskLevel = ToolRiskLevel.Low
    override val runningDisplayName = "正在查看今日提醒"

    override suspend fun execute(argumentsJson: String): String {
        val arguments = Json.parseToJsonElement(argumentsJson).jsonObject
        require(arguments.isEmpty()) { "$NAME does not accept arguments" }
        val zone = zoneId()
        val reminders = repository.reminders.first()
        return buildJsonObject {
            put("ok", true)
            put("local_date", java.time.LocalDate.now(zone).toString())
            put("timezone", zone.id)
            put("count", reminders.size)
            put("pending_count", reminders.count { it.status == StoredReminderStatus.PENDING })
            put("snoozed_count", reminders.count { it.status == StoredReminderStatus.SNOOZED })
            put("completed_count", reminders.count { it.status == StoredReminderStatus.COMPLETED })
            put(
                "items",
                buildJsonArray {
                    reminders.forEach { reminder ->
                        add(
                            buildJsonObject {
                                put("deadline_at", Instant.ofEpochMilli(reminder.scheduledAtEpochMillis).toString())
                                put(
                                    "local_deadline_time",
                                    Instant.ofEpochMilli(reminder.scheduledAtEpochMillis)
                                        .atZone(zone)
                                        .format(DateTimeFormatter.ofPattern("a h:mm", Locale.CHINA)),
                                )
                                put("title", reminder.title)
                                put("detail", reminder.detail)
                                reminder.sourceDisplayName?.let { put("source_display_name", it) }
                                put(
                                    "status",
                                    when (reminder.status) {
                                        StoredReminderStatus.PENDING -> "pending"
                                        StoredReminderStatus.SNOOZED -> "snoozed"
                                        StoredReminderStatus.COMPLETED -> "completed"
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    companion object {
        const val NAME = "list_today_reminders"
    }
}
