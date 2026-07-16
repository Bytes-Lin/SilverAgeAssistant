package com.example.silverageassistant.ui.onboarding

object OnboardingTestTags {
    const val ELDER_NAME_INPUT = "elder_name_input"
    const val ELDER_FAMILY_MOBILE_INPUT = "elder_family_mobile_input"
    const val ELDER_BINDING_CODE_INPUT = "elder_binding_code_input"
    const val FAMILY_NAME_INPUT = "family_name_input"
    const val FAMILY_MOBILE_INPUT = "family_mobile_input"
    const val FAMILY_ELDER_NAME_INPUT = "family_elder_name_input"
    const val FAMILY_ELDER_MOBILE_INPUT = "family_elder_mobile_input"
}

enum class FamilyRelationship(val displayName: String) {
    Child("子女"),
    Relative("其他亲属"),
    Caregiver("照护人"),
    Other("其他"),
}

enum class BindingPreparationStatus {
    NotPrepared,
    AwaitingCodeGeneration,
    PendingJointVerification,
}

data class ElderSetupDraft(
    val displayName: String = "",
    val familyMobileNumber: String = "",
    val bindingCode: String = "",
    val sharingConsent: Boolean = false,
)

data class FamilySetupDraft(
    val displayName: String = "",
    val mobileNumber: String = "",
    val elderDisplayName: String = "",
    val elderMobileNumber: String = "",
    val relationship: FamilyRelationship? = null,
    val emergencyContact: Boolean = true,
)

data class ElderSetupErrors(
    val displayName: String? = null,
    val familyMobileNumber: String? = null,
    val bindingCode: String? = null,
    val sharingConsent: String? = null,
) {
    val hasErrors: Boolean
        get() = displayName != null || familyMobileNumber != null ||
            bindingCode != null || sharingConsent != null
}

data class FamilySetupErrors(
    val displayName: String? = null,
    val mobileNumber: String? = null,
    val elderDisplayName: String? = null,
    val elderMobileNumber: String? = null,
    val relationship: String? = null,
) {
    val hasErrors: Boolean
        get() = displayName != null || mobileNumber != null ||
            elderDisplayName != null || elderMobileNumber != null || relationship != null
}

data class OnboardingUiState(
    val elderDraft: ElderSetupDraft = ElderSetupDraft(),
    val familyDraft: FamilySetupDraft = FamilySetupDraft(),
    val elderErrors: ElderSetupErrors = ElderSetupErrors(),
    val familyErrors: FamilySetupErrors = FamilySetupErrors(),
    val elderBindingStatus: BindingPreparationStatus = BindingPreparationStatus.NotPrepared,
    val familyBindingStatus: BindingPreparationStatus = BindingPreparationStatus.NotPrepared,
    val isRestoringProfiles: Boolean = false,
    val persistenceMessage: String? = null,
)

object OnboardingValidator {
    fun validateElder(draft: ElderSetupDraft): ElderSetupErrors {
        val name = draft.displayName.trim()
        val familyMobile = draft.familyMobileNumber.trim()
        val code = draft.bindingCode.trim()
        val hasBindingCode = code.isNotBlank()
        val hasFamilyMobile = familyMobile.isNotBlank()
        return ElderSetupErrors(
            displayName = when {
                name.isBlank() -> "请填写您的称呼"
                name.length > MAX_DISPLAY_NAME_LENGTH -> "称呼不能超过20个字"
                else -> null
            },
            familyMobileNumber = when {
                hasBindingCode && !hasFamilyMobile -> "请填写家人手机号"
                hasFamilyMobile && !isValidMobile(familyMobile) -> "请输入11位中国大陆家人手机号"
                else -> null
            },
            bindingCode = when {
                hasFamilyMobile && !hasBindingCode -> "请填写6位绑定码"
                code.isBlank() -> null
                !code.matches(Regex("\\d{$BINDING_CODE_LENGTH}")) -> "绑定码应为6位数字"
                else -> null
            },
            sharingConsent = if (hasBindingCode && hasFamilyMobile && !draft.sharingConsent) {
                "绑定家人前，请确认共享范围"
            } else {
                null
            },
        )
    }

    fun validateFamily(draft: FamilySetupDraft): FamilySetupErrors {
        val name = draft.displayName.trim()
        val mobile = draft.mobileNumber.trim()
        val elderName = draft.elderDisplayName.trim()
        val elderMobile = draft.elderMobileNumber.trim()
        return FamilySetupErrors(
            displayName = when {
                name.isBlank() -> "请填写您的称呼"
                name.length > MAX_DISPLAY_NAME_LENGTH -> "称呼不能超过20个字"
                else -> null
            },
            mobileNumber = when {
                mobile.isBlank() -> "请填写手机号"
                !isValidMobile(mobile) -> "请输入11位中国大陆手机号"
                else -> null
            },
            elderDisplayName = when {
                elderName.isBlank() -> "请填写老人的称呼"
                elderName.length > MAX_DISPLAY_NAME_LENGTH -> "称呼不能超过20个字"
                else -> null
            },
            elderMobileNumber = when {
                elderMobile.isBlank() -> "请填写老人手机号"
                !isValidMobile(elderMobile) -> "请输入11位中国大陆老人手机号"
                else -> null
            },
            relationship = if (draft.relationship == null) "请选择与老人的关系" else null,
        )
    }

    private fun isValidMobile(value: String): Boolean = value.matches(Regex("1[3-9]\\d{9}"))

    private const val MAX_DISPLAY_NAME_LENGTH = 20
    private const val BINDING_CODE_LENGTH = 6
}
