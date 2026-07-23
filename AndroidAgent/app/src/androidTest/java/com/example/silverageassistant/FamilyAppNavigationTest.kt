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
import androidx.activity.compose.setContent
import com.example.silverageassistant.data.middleserver.ElderBindingRequest
import com.example.silverageassistant.data.middleserver.ElderBindingResult
import com.example.silverageassistant.data.middleserver.FamilyOnboardingRequest
import com.example.silverageassistant.data.middleserver.FamilyOnboardingResult
import com.example.silverageassistant.data.middleserver.OnboardingMiddleServerRepository
import com.example.silverageassistant.ui.SilverAgeApp
import com.example.silverageassistant.ui.onboarding.OnboardingTestTags
import com.example.silverageassistant.ui.onboarding.OnboardingViewModel
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import org.junit.Rule
import org.junit.Test

class FamilyAppNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun familyRole_registersAndShowsBindingCode() {
        val onboardingViewModel = OnboardingViewModel(
            middleServerRepository = SuccessfulOnboardingRepository,
        )
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    SilverAgeApp(onboardingViewModel = onboardingViewModel)
                }
            }
        }
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
        composeRule.onNodeWithText("已连接中台").assertIsDisplayed()
        composeRule.onNodeWithText("绑定码：654321").assertIsDisplayed()
        composeRule.onNodeWithText("王阿姨").assertIsDisplayed()
        composeRule.onNodeWithText("重新生成绑定码").performScrollTo().performClick()
        composeRule.onNodeWithText("绑定码：112233").assertIsDisplayed()
    }

    private object SuccessfulOnboardingRepository : OnboardingMiddleServerRepository {
        override suspend fun registerFamilyAndCreateBindingCode(
            request: FamilyOnboardingRequest,
        ) = FamilyOnboardingResult(
            bindingCode = "654321",
            bindingCodeExpiresAt = "2026-07-16T12:00:00Z",
            familyMobileMasked = "133****3333",
            elderId = "elder-1",
        )

        override suspend fun regenerateBindingCode(elderId: String) = FamilyOnboardingResult(
            bindingCode = "112233",
            bindingCodeExpiresAt = "2026-07-17T12:00:00Z",
            familyMobileMasked = "133****3333",
            elderId = elderId,
        )

        override suspend fun bindElderDevice(request: ElderBindingRequest) = ElderBindingResult(
            familyMobileMasked = "133****3333",
            relationship = "CHILD",
            boundAt = "2026-07-16T11:50:00Z",
        )
    }
}
