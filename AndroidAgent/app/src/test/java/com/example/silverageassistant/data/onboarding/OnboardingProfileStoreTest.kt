package com.example.silverageassistant.data.onboarding

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingProfileStoreTest {
    @Test
    fun profiles_areSavedAndCanBeCleared() = runBlocking {
        val store = InMemoryOnboardingProfileStore()

        store.saveElderProfile(
            displayName = "王阿姨",
            familyMobileNumber = validTestMobile(),
            sharingConsent = true,
        )
        store.saveFamilyProfile(
            displayName = "小林",
            mobileNumber = validTestMobile(),
            elderDisplayName = "王阿姨",
            elderMobileNumber = validTestMobile(lastDigit = '1'),
            relationshipName = "Child",
            emergencyContact = true,
        )

        val saved = store.profiles.first()
        assertEquals("王阿姨", saved.elderDisplayName)
        assertEquals("小林", saved.familyDisplayName)
        assertEquals("Child", saved.familyRelationshipName)
        assertTrue(saved.elderSharingConsent)

        store.clear()
        assertEquals(PersistedOnboardingProfiles(), store.profiles.first())
    }

    private fun validTestMobile(lastDigit: Char = '0'): String =
        "1" + "3".repeat(9) + lastDigit
}
