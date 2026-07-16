package com.example.silverageassistant.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingValidatorTest {
    @Test
    fun elderWithoutBindingCode_canContinueWithoutSharingConsent() {
        val errors = OnboardingValidator.validateElder(
            ElderSetupDraft(displayName = "王阿姨"),
        )

        assertFalse(errors.hasErrors)
    }

    @Test
    fun elderWithBindingCode_mustAlsoProvideFamilyMobile() {
        val errors = OnboardingValidator.validateElder(
            ElderSetupDraft(displayName = "王阿姨", bindingCode = "123456"),
        )

        assertEquals("请填写家人手机号", errors.familyMobileNumber)
    }

    @Test
    fun elderWithCompleteBindingPair_mustConfirmSharingScope() {
        val errors = OnboardingValidator.validateElder(
            ElderSetupDraft(
                displayName = "王阿姨",
                familyMobileNumber = validTestMobile(),
                bindingCode = "123456",
            ),
        )

        assertEquals("绑定家人前，请确认共享范围", errors.sharingConsent)
    }

    @Test
    fun elderWithFamilyMobile_mustAlsoProvideBindingCode() {
        val errors = OnboardingValidator.validateElder(
            ElderSetupDraft(displayName = "王阿姨", familyMobileNumber = validTestMobile()),
        )

        assertEquals("请填写6位绑定码", errors.bindingCode)
    }

    @Test
    fun invalidMobile_usesShortElevenDigitPrompt() {
        val elderErrors = OnboardingValidator.validateElder(
            ElderSetupDraft(displayName = "王阿姨", familyMobileNumber = "123"),
        )
        val familyErrors = OnboardingValidator.validateFamily(
            FamilySetupDraft(
                displayName = "小林",
                mobileNumber = "123",
                elderDisplayName = "王阿姨",
                elderMobileNumber = "456",
                relationship = FamilyRelationship.Child,
            ),
        )

        assertEquals("请输入11位手机号", elderErrors.familyMobileNumber)
        assertEquals("请输入11位手机号", familyErrors.mobileNumber)
        assertEquals("请输入11位手机号", familyErrors.elderMobileNumber)
    }

    @Test
    fun elderBindingCode_mustBeSixDigits() {
        val errors = OnboardingValidator.validateElder(
            ElderSetupDraft(
                displayName = "王阿姨",
                bindingCode = "12345",
                sharingConsent = true,
            ),
        )

        assertEquals("绑定码应为6位数字", errors.bindingCode)
    }

    @Test
    fun validFamilyDraft_passesValidation() {
        val errors = OnboardingValidator.validateFamily(
            FamilySetupDraft(
                displayName = "小林",
                mobileNumber = validTestMobile(),
                elderDisplayName = "王阿姨",
                elderMobileNumber = validTestMobile(lastDigit = '1'),
                relationship = FamilyRelationship.Child,
            ),
        )

        assertFalse(errors.hasErrors)
    }

    @Test
    fun incompleteFamilyDraft_reportsAllRequiredFields() {
        val errors = OnboardingValidator.validateFamily(FamilySetupDraft())

        assertTrue(errors.hasErrors)
        assertEquals("请填写您的称呼", errors.displayName)
        assertEquals("请填写手机号", errors.mobileNumber)
        assertEquals("请填写老人的称呼", errors.elderDisplayName)
        assertEquals("请填写老人手机号", errors.elderMobileNumber)
        assertEquals("请选择与老人的关系", errors.relationship)
    }

    private fun validTestMobile(lastDigit: Char = '0'): String =
        "1" + "3".repeat(9) + lastDigit
}
