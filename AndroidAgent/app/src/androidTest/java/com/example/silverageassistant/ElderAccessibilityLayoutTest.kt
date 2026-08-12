package com.example.silverageassistant

import android.content.pm.ActivityInfo
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

class ElderAccessibilityLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun elderHome_keepsPrimaryActionsInLandscape() {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("进入老人模式").performClick()
        val elderNameInput = composeRule.onNodeWithTag(OnboardingTestTags.ELDER_NAME_INPUT)
        elderNameInput.performTextClearance()
        elderNameInput.performTextInput("王阿姨")
        composeRule.onNodeWithText("保存并进入老人模式").performClick()

        composeRule.onNodeWithContentDescription("和我说话").assertIsDisplayed()
        composeRule.onNodeWithText("新闻播报").fetchSemanticsNode()
    }
}
