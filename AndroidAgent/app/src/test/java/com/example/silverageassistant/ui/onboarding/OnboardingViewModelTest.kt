package com.example.silverageassistant.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingViewModelTest {
    @Test
    fun validFamilySetup_isPreparedForServerBinding() {
        val viewModel = OnboardingViewModel()
        viewModel.updateFamilyName("小林")
        viewModel.updateMobileNumber(validTestMobile())
        viewModel.updateElderDisplayName("王阿姨")
        viewModel.updateFamilyElderMobileNumber(validTestMobile(lastDigit = '1'))
        viewModel.updateRelationship(FamilyRelationship.Child)

        assertTrue(viewModel.submitFamilySetup())
        assertEquals(
            BindingPreparationStatus.AwaitingCodeGeneration,
            viewModel.uiState.value.familyBindingStatus,
        )
    }

    @Test
    fun elderBindingPair_isPreparedForJointServerVerification() {
        val viewModel = OnboardingViewModel()
        viewModel.updateElderName("王阿姨")
        viewModel.updateElderFamilyMobileNumber(validTestMobile())
        viewModel.updateBindingCode("654321")
        viewModel.updateSharingConsent(true)

        assertTrue(viewModel.submitElderSetup())
        assertEquals(
            BindingPreparationStatus.PendingJointVerification,
            viewModel.uiState.value.elderBindingStatus,
        )
    }

    @Test
    fun elderCanEnterWithoutBindingCode_butIsNotMarkedPrepared() {
        val viewModel = OnboardingViewModel()
        viewModel.updateElderName("王阿姨")

        assertTrue(viewModel.submitElderSetup())
        assertEquals(
            BindingPreparationStatus.NotPrepared,
            viewModel.uiState.value.elderBindingStatus,
        )
    }

    @Test
    fun invalidFamilySetup_doesNotPrepareBinding() {
        val viewModel = OnboardingViewModel()

        assertFalse(viewModel.submitFamilySetup())
        assertEquals(
            BindingPreparationStatus.NotPrepared,
            viewModel.uiState.value.familyBindingStatus,
        )
    }

    private fun validTestMobile(lastDigit: Char = '0'): String =
        "1" + "3".repeat(9) + lastDigit
}
