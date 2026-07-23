package com.example.silverageassistant.domain.agent

import com.example.silverageassistant.data.middleserver.ElderSafetyEventRequest
import com.example.silverageassistant.data.middleserver.ElderSafetyMonitoringRepository
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import com.example.silverageassistant.data.middleserver.SafetyEvent
import com.example.silverageassistant.data.middleserver.SafetyEventSeverity
import com.example.silverageassistant.data.middleserver.SafetyEventType
import com.example.silverageassistant.data.middleserver.SafetyEventImageUpload
import com.example.silverageassistant.domain.model.ChatToolDefinition
import com.example.silverageassistant.domain.safety.SafetyImage
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class FamilySituationReporter(
    private val repository: ElderSafetyMonitoringRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun report(
        eventType: SafetyEventType,
        eventSummary: String,
    ): SafetyEvent {
        val summary = eventSummary.trim()
        require(summary.isNotBlank()) { "event_summary must not be blank" }
        require(summary.length <= MAX_EVENT_SUMMARY_LENGTH) { "event_summary is too long" }
        require(eventType in ALLOWED_EVENT_TYPES) { "event_type is not reportable" }
        val enforcedSeverity = when (eventType) {
            SafetyEventType.FAMILY_REQUEST -> SafetyEventSeverity.GENERAL
            SafetyEventType.HEALTH_DISCOMFORT_REPORTED,
            SafetyEventType.FALL_SUSPECTED,
            SafetyEventType.UNCONSCIOUSNESS_SUSPECTED,
            SafetyEventType.OTHER_ABNORMALITY -> SafetyEventSeverity.EMERGENCY
        }
        return repository.createSafetyEvent(
            ElderSafetyEventRequest(
                clientEventId = idFactory(),
                occurredAt = Instant.now(clock).toString(),
                eventType = eventType,
                eventSummary = summary,
                severity = enforcedSeverity,
            ),
        )
    }

    suspend fun attachEvidence(eventId: String, image: SafetyImage) {
        repository.uploadSafetyEventImage(
            eventId = eventId,
            image = SafetyEventImageUpload(
                bytes = image.bytes,
                contentType = image.mimeType,
            ),
        )
    }

    private companion object {
        const val MAX_EVENT_SUMMARY_LENGTH = 200
        val ALLOWED_EVENT_TYPES = setOf(
            SafetyEventType.HEALTH_DISCOMFORT_REPORTED,
            SafetyEventType.FAMILY_REQUEST,
            SafetyEventType.FALL_SUSPECTED,
            SafetyEventType.UNCONSCIOUSNESS_SUSPECTED,
            SafetyEventType.OTHER_ABNORMALITY,
        )
    }
}

class ReportFamilySituationTool(
    private val reporter: FamilySituationReporter,
) : AgentTool {
    override val definition = ChatToolDefinition(
        name = NAME,
        description = "向已绑定家属上报老人当前情况。老人说今天身体不舒服时上报紧急事件；明确说想让家人回家吃饭等家庭请求时上报一般事件。普通闲聊、过去的身体情况、假设问题和他人的情况不要调用。时间由手机自动生成。",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "event_type": {
                  "type": "string",
                  "enum": [
                    "HEALTH_DISCOMFORT_REPORTED",
                    "FAMILY_REQUEST",
                    "FALL_SUSPECTED",
                    "UNCONSCIOUSNESS_SUSPECTED",
                    "OTHER_ABNORMALITY"
                  ]
                },
                "event_summary": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 200,
                  "description": "用简短中文客观概括老人当前说的情况，不添加诊断或未说过的事实"
                },
                "severity": {
                  "type": "string",
                  "enum": ["GENERAL", "EMERGENCY"]
                }
              },
              "required": ["event_type", "event_summary", "severity"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )
    override val riskLevel = ToolRiskLevel.Medium
    override val executionPolicy = ToolExecutionPolicy.ImmediateAfterLocalPolicy
    override val runningDisplayName = "正在通知家人"

    override suspend fun execute(argumentsJson: String): String {
        val arguments = Json.parseToJsonElement(argumentsJson).jsonObject
        require(arguments.keys == setOf("event_type", "event_summary", "severity"))
        val eventType = SafetyEventType.valueOf(
            arguments.getValue("event_type").jsonPrimitive.content,
        )
        val summary = arguments.getValue("event_summary").jsonPrimitive.content
        SafetyEventSeverity.valueOf(
            arguments.getValue("severity").jsonPrimitive.content,
        )
        return try {
            val event = reporter.report(eventType, summary)
            buildJsonObject {
                put("ok", true)
                put("event_id", event.eventId)
                put("occurred_at", event.occurredAt)
                put("event_type", event.eventType.name)
                put("severity", event.severity.name)
                put("message", "中台已保存事件，将通知有权限的家属")
            }.toString()
        } catch (error: MiddleServerRequestException) {
            errorResult(error.code, error.userMessage)
        }
    }

    private fun errorResult(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error_code", code)
        put("message", message)
    }.toString()

    companion object {
        const val NAME = "report_family_situation"
    }
}
