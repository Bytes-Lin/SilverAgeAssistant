package com.example.silverageassistant.domain.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuiInteractionEventPolicyTest {
    @Test
    fun targetAppUserInteraction_pausesForHumanIntervention() {
        assertEquals(
            GuiPauseReason.HUMAN_INTERVENTION,
            GuiInteractionEventPolicy.pauseReason(
                kind = GuiInteractionEventKind.USER_INTERACTION,
                eventPackage = TARGET,
                assistantPackage = ASSISTANT,
                targetPackage = TARGET,
            ),
        )
    }

    @Test
    fun leavingTargetApp_pausesWithoutTreatingAssistantOverlayAsExternal() {
        assertEquals(
            GuiPauseReason.TARGET_APP_LEFT,
            GuiInteractionEventPolicy.pauseReason(
                kind = GuiInteractionEventKind.WINDOW_CHANGED,
                eventPackage = "com.android.launcher",
                assistantPackage = ASSISTANT,
                targetPackage = TARGET,
            ),
        )
        assertNull(
            GuiInteractionEventPolicy.pauseReason(
                kind = GuiInteractionEventKind.WINDOW_CHANGED,
                eventPackage = ASSISTANT,
                assistantPackage = ASSISTANT,
                targetPackage = TARGET,
            ),
        )
    }

    private companion object {
        const val ASSISTANT = "com.example.silverageassistant"
        const val TARGET = "com.sankuai.meituan"
    }
}
