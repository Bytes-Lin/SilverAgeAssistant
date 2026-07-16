package com.example.silverageassistant

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.silverageassistant.ui.onboarding.OnboardingTestTags
import org.junit.Rule
import org.junit.Test

class ElderAppNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun elderRole_opensHomeAndConversation() {
        composeRule.onNodeWithContentDescription("进入老人模式").performClick()
        val elderNameInput = composeRule.onNodeWithTag(OnboardingTestTags.ELDER_NAME_INPUT)
        elderNameInput.performTextClearance()
        elderNameInput.performTextInput("王阿姨")
        composeRule.onNodeWithText("保存并进入老人模式").performClick()

        composeRule.onNodeWithText("今日提醒").assertIsDisplayed()
        composeRule.onNodeWithText("紧急求助").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("打开语音对话").performClick()

        composeRule.onNodeWithText("可以开始说话").assertIsDisplayed()
        composeRule.onNodeWithText("开始说话").performClick()
        composeRule.onNodeWithText("正在听").assertIsDisplayed()
    }
}
