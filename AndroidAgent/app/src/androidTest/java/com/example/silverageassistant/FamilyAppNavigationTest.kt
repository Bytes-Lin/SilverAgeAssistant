package com.example.silverageassistant

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.silverageassistant.ui.onboarding.OnboardingTestTags
import org.junit.Rule
import org.junit.Test

class FamilyAppNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun familyRole_completesProfileAndOpensOfflineDashboard() {
        composeRule.onNodeWithContentDescription("进入家属模式").performClick()
        val familyNameInput = composeRule.onNodeWithTag(OnboardingTestTags.FAMILY_NAME_INPUT)
        familyNameInput.performTextClearance()
        familyNameInput.performTextInput("小林")
        val familyMobileInput = composeRule.onNodeWithTag(OnboardingTestTags.FAMILY_MOBILE_INPUT)
        familyMobileInput.performTextClearance()
        familyMobileInput.performTextInput("1" + "3".repeat(10))
        val elderNameInput = composeRule.onNodeWithTag(OnboardingTestTags.FAMILY_ELDER_NAME_INPUT)
        elderNameInput.performTextClearance()
        elderNameInput.performTextInput("王阿姨")
        val elderMobileInput = composeRule.onNodeWithTag(OnboardingTestTags.FAMILY_ELDER_MOBILE_INPUT)
        elderMobileInput.performTextClearance()
        elderMobileInput.performTextInput("1" + "5".repeat(10))
        composeRule.onNodeWithText("子女").performScrollTo().performClick()
        composeRule.onNodeWithText("保存并进入家属模式").performScrollTo().performClick()

        composeRule.onNodeWithText("家属模式").assertIsDisplayed()
        composeRule.onNodeWithText("尚未连接中台").assertIsDisplayed()
        composeRule.onNodeWithText("王阿姨").assertIsDisplayed()
    }
}
