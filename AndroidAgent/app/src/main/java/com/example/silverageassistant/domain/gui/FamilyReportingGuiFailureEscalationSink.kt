package com.example.silverageassistant.domain.gui

import com.example.silverageassistant.data.middleserver.SafetyEventType
import com.example.silverageassistant.domain.agent.FamilySituationReporter

/**
 * Reuses the same deterministic family-event reporter as the main chat tool. The GUI task UUID is
 * also the middle-server idempotency key, so retrying an interrupted submission cannot create a
 * second family emergency for the same GUI task.
 */
class FamilyReportingGuiFailureEscalationSink(
    private val reporter: FamilySituationReporter,
) : GuiFailureEscalationSink {
    override suspend fun escalate(todo: GuiTodo, failureMessage: String): String {
        val event = reporter.report(
            eventType = SafetyEventType.GUI_ORDER_ASSISTANCE_REQUIRED,
            eventSummary = todo.familyFailureSummary(),
            clientEventId = todo.id,
        )
        return event.eventId
    }

    private fun GuiTodo.familyFailureSummary(): String = when {
        content.contains("淘宝", ignoreCase = true) ||
            content.contains("网购", ignoreCase = true) ->
            "老人端网购任务连续两次失败，需要家属协助。"

        content.contains("美团", ignoreCase = true) ||
            content.contains("外卖", ignoreCase = true) ||
            content.contains("点餐", ignoreCase = true) ->
            "老人端点外卖任务连续两次失败，需要家属协助。"

        else -> "老人端生活服务操作连续两次失败，需要家属协助。"
    }
}
