package com.example.silverageassistant.data.onboarding

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

data class PersistedOnboardingProfiles(
    val elderDisplayName: String = "",
    val elderFamilyMobileNumber: String = "",
    val elderSharingConsent: Boolean = false,
    val familyDisplayName: String = "",
    val familyMobileNumber: String = "",
    val familyElderDisplayName: String = "",
    val familyElderMobileNumber: String = "",
    val familyRelationshipName: String? = null,
    val familyEmergencyContact: Boolean = true,
)

interface OnboardingProfileStore {
    val profiles: Flow<PersistedOnboardingProfiles>

    suspend fun saveElderProfile(
        displayName: String,
        familyMobileNumber: String,
        sharingConsent: Boolean,
    )

    suspend fun saveFamilyProfile(
        displayName: String,
        mobileNumber: String,
        elderDisplayName: String,
        elderMobileNumber: String,
        relationshipName: String?,
        emergencyContact: Boolean,
    )

    suspend fun clear()
}

class InMemoryOnboardingProfileStore : OnboardingProfileStore {
    private val state = MutableStateFlow(PersistedOnboardingProfiles())
    override val profiles: Flow<PersistedOnboardingProfiles> = state

    override suspend fun saveElderProfile(
        displayName: String,
        familyMobileNumber: String,
        sharingConsent: Boolean,
    ) {
        state.value = state.value.copy(
            elderDisplayName = displayName,
            elderFamilyMobileNumber = familyMobileNumber,
            elderSharingConsent = sharingConsent,
        )
    }

    override suspend fun saveFamilyProfile(
        displayName: String,
        mobileNumber: String,
        elderDisplayName: String,
        elderMobileNumber: String,
        relationshipName: String?,
        emergencyContact: Boolean,
    ) {
        state.value = state.value.copy(
            familyDisplayName = displayName,
            familyMobileNumber = mobileNumber,
            familyElderDisplayName = elderDisplayName,
            familyElderMobileNumber = elderMobileNumber,
            familyRelationshipName = relationshipName,
            familyEmergencyContact = emergencyContact,
        )
    }

    override suspend fun clear() {
        state.value = PersistedOnboardingProfiles()
    }
}
