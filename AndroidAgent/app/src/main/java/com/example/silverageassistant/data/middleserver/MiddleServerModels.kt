package com.example.silverageassistant.data.middleserver

data class FamilyOnboardingRequest(
    val displayName: String,
    val mobileNumber: String,
    val elderDisplayName: String,
    val elderMobileNumber: String,
    val relationship: String,
    val emergencyContact: Boolean,
)

data class FamilyOnboardingResult(
    val bindingCode: String,
    val bindingCodeExpiresAt: String,
    val familyMobileMasked: String,
)

data class ElderBindingRequest(
    val displayName: String,
    val familyMobileNumber: String,
    val bindingCode: String,
    val sharingConsent: Boolean,
)

data class ElderBindingResult(
    val familyMobileMasked: String,
    val relationship: String,
    val boundAt: String,
)

interface OnboardingMiddleServerRepository {
    suspend fun registerFamilyAndCreateBindingCode(
        request: FamilyOnboardingRequest,
    ): FamilyOnboardingResult

    suspend fun bindElderDevice(request: ElderBindingRequest): ElderBindingResult
}

class MiddleServerRequestException(
    val code: String,
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)
