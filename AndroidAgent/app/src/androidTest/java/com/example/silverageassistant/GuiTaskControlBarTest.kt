package com.example.silverageassistant

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.silverageassistant.domain.gui.GuiPauseReason
import com.example.silverageassistant.domain.gui.GuiRunPhase
import com.example.silverageassistant.domain.gui.GuiTaskSnapshot
import com.example.silverageassistant.ui.gui.GuiTaskControlBar
import com.example.silverageassistant.ui.gui.GuiTaskControlTestTags
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuiTaskControlBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun targetAppLeft_showsReturnTaskAndHidesUnavailableVoiceAction() {
        val resumed = AtomicInteger()
        composeRule.setContent {
            SilverAgeAssistantTheme {
                GuiTaskControlBar(
                    task = snapshot(
                        phase = GuiRunPhase.PAUSED,
                        pauseReason = GuiPauseReason.TARGET_APP_LEFT,
                    ),
                    onPause = {},
                    onResume = { resumed.incrementAndGet() },
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText("返回任务").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithTag(GuiTaskControlTestTags.VOICE_BUTTON)
            .assertCountEquals(0)
        assertEquals(1, resumed.get())
    }

    @Test
    fun cancel_requiresSecondConfirmation() {
        val cancelled = AtomicInteger()
        composeRule.setContent {
            SilverAgeAssistantTheme {
                GuiTaskControlBar(
                    task = snapshot(phase = GuiRunPhase.RUNNING),
                    onPause = {},
                    onResume = {},
                    onCancel = { cancelled.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithTag(GuiTaskControlTestTags.CANCEL_BUTTON).performClick()
        composeRule.onNodeWithTag(GuiTaskControlTestTags.CANCEL_DIALOG).assertIsDisplayed()
        assertEquals(0, cancelled.get())

        composeRule.onNodeWithText("确认取消").performClick()
        assertEquals(1, cancelled.get())
    }

    @Test
    fun orderConfirmationGate_usesExplicitConfirmationButton() {
        val resumed = AtomicInteger()
        val paused = AtomicInteger()
        composeRule.setContent {
            SilverAgeAssistantTheme {
                GuiTaskControlBar(
                    task = snapshot(phase = GuiRunPhase.WAITING_ELDER_CONFIRMATION),
                    onPause = { paused.incrementAndGet() },
                    onResume = { resumed.incrementAndGet() },
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText("确认并继续").assertIsDisplayed().performClick()
        assertEquals(1, resumed.get())
        assertEquals(0, paused.get())
    }

    private fun snapshot(
        phase: GuiRunPhase,
        pauseReason: GuiPauseReason? = null,
    ) = GuiTaskSnapshot(
        todoId = "todo-1",
        content = "在美团选择午餐",
        runAttempt = 1,
        phase = phase,
        pauseReason = pauseReason,
    )
}
