package com.example.silverageassistant.domain.gui

enum class GuiInteractionEventKind {
    WINDOW_CHANGED,
    USER_INTERACTION,
}

/**
 * Accessibility 原始事件到 GUI 暂停原因的确定性映射，不依赖模型判断。
 */
object GuiInteractionEventPolicy {
    fun pauseReason(
        kind: GuiInteractionEventKind,
        eventPackage: String,
        assistantPackage: String,
        targetPackage: String,
    ): GuiPauseReason? {
        if (eventPackage == assistantPackage) return null
        return when (kind) {
            GuiInteractionEventKind.USER_INTERACTION ->
                GuiPauseReason.HUMAN_INTERVENTION.takeIf {
                    eventPackage == targetPackage
                }

            GuiInteractionEventKind.WINDOW_CHANGED ->
                GuiPauseReason.TARGET_APP_LEFT.takeIf {
                    eventPackage != targetPackage
                }
        }
    }
}
