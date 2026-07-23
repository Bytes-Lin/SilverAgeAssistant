package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.domain.model.ChatToolDefinition
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class CurrentTimeTool(
    private val clock: Clock = Clock.systemDefaultZone(),
) : AgentTool {
    override val definition = ChatToolDefinition(
        name = NAME,
        description = "查询老人设备所在时区的当前日期、星期和时间。",
        parametersJson = """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
        """.trimIndent(),
    )
    override val riskLevel = ToolRiskLevel.Low
    override val runningDisplayName = "正在查询时间"

    override suspend fun execute(argumentsJson: String): String {
        val arguments = Json.parseToJsonElement(argumentsJson).jsonObject
        require(arguments.isEmpty()) { "get_current_time does not accept arguments" }
        val now = ZonedDateTime.now(clock)
        return buildJsonObject {
            put("local_datetime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("date", now.toLocalDate().toString())
            put("time", now.toLocalTime().withNano(0).toString())
            put(
                "weekday",
                now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE),
            )
            put("timezone", now.zone.id)
        }.toString()
    }

    companion object {
        const val NAME = "get_current_time"
    }
}
