package com.example.silverageassistant

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.example.silverageassistant.data.middleserver.FamilyDailyModelUsage
import com.example.silverageassistant.data.middleserver.FamilyModelUsageSummary
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import com.example.silverageassistant.ui.usage.FamilyUsageScreen
import com.example.silverageassistant.ui.usage.FamilyUsageUiState
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class FamilyUsageScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dailySummaryAndBothMonthlyCharts_areAvailable() {
        val today = LocalDate.now()
        val day = FamilyDailyModelUsage(
            date = today.toString(),
            inputTokens = 120,
            outputTokens = 30,
            mllmRequestCount = 2,
            asrRequestCount = 3,
            ttsRequestCount = 4,
            containsEstimatedValues = false,
        )
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    FamilyUsageScreen(
                        state = FamilyUsageUiState(
                            summary = summary(),
                            dailyUsage = listOf(day),
                        ),
                        onRefresh = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("今日用量").assertIsDisplayed()
        composeRule.onNodeWithText("150").assertIsDisplayed()
        composeRule.onNodeWithText("本月每日聊天 Token")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "${today}，输入 120 Token，输出 30 Token",
        ).assertExists()
        composeRule.onNodeWithText("本月每日语音调用")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "${today}，ASR 3 次，TTS 4 次",
        ).assertExists()
    }

    @Test
    fun missingDailyEndpoint_keepsMonthlySummaryAndShowsExplanation() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    FamilyUsageScreen(
                        state = FamilyUsageUiState(
                            summary = summary(),
                            dailyBreakdownAvailable = false,
                        ),
                        onRefresh = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("本月汇总").assertIsDisplayed()
        composeRule.onNodeWithText("中台尚未提供每日用量明细，当前先显示本月汇总。")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun summary() = FamilyModelUsageSummary(
        periodStartedAt = "2026-07-01T00:00:00Z",
        periodEndedAt = "2026-07-19T04:00:00Z",
        inputTokens = 120,
        outputTokens = 30,
        mllmRequestCount = 2,
        asrRequestCount = 3,
        ttsRequestCount = 4,
        asrAudioDurationMillis = 0,
        ttsCharacterCount = 0,
        ttsAudioDurationMillis = 0,
        containsEstimatedValues = false,
        lastReportedAt = "2026-07-19T04:00:00Z",
    )
}
