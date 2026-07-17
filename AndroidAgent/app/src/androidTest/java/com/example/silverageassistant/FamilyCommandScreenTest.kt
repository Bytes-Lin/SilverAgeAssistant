package com.example.silverageassistant

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.silverageassistant.ui.family.FamilyCommunicationUiState
import com.example.silverageassistant.ui.family.FamilyNotificationScreen
import com.example.silverageassistant.ui.family.FamilyReminderScreen
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import org.junit.Rule
import org.junit.Test

class FamilyCommandScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun notificationScreen_explainsMiddleServerDelivery() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    FamilyNotificationScreen(
                        state = FamilyCommunicationUiState(),
                        elderDisplayName = "王阿姨",
                        onContentChange = {},
                        onSend = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("发送给王阿姨").assertIsDisplayed()
        composeRule.onNodeWithText("通知会先交给中台。老人手机联网后会接收，并加入今日提醒。")
            .assertIsDisplayed()
    }

    @Test
    fun reminderScreen_showsOneTimeScheduleFields() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    FamilyReminderScreen(
                        state = FamilyCommunicationUiState(
                            reminderDate = "2026-07-17",
                            reminderTime = "08:30",
                        ),
                        elderDisplayName = "王阿姨",
                        onTitleChange = {},
                        onContentChange = {},
                        onDateChange = {},
                        onTimeChange = {},
                        onCreate = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("为王阿姨创建提醒").assertIsDisplayed()
        composeRule.onNodeWithText("日期").assertIsDisplayed()
        composeRule.onNodeWithText("时间").assertIsDisplayed()
    }
}
