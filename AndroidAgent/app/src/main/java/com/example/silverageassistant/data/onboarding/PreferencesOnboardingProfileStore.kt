package com.example.silverageassistant.data.onboarding

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private const val ONBOARDING_DATA_STORE_NAME = "onboarding_test_profiles"

private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = ONBOARDING_DATA_STORE_NAME,
)

class PreferencesOnboardingProfileStore(
    private val context: Context,
) : OnboardingProfileStore {
    private val isDebuggable: Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override val profiles: Flow<PersistedOnboardingProfiles> = if (isDebuggable) {
        context.onboardingDataStore.data
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }
            .map { preferences -> preferences.toProfiles() }
    } else {
        flowOf(PersistedOnboardingProfiles())
    }

    override suspend fun saveElderProfile(
        displayName: String,
        familyMobileNumber: String,
        sharingConsent: Boolean,
    ) {
        if (!isDebuggable) return
        context.onboardingDataStore.edit { preferences ->
            preferences[Keys.ELDER_DISPLAY_NAME] = displayName
            preferences[Keys.ELDER_FAMILY_MOBILE] = familyMobileNumber
            preferences[Keys.ELDER_SHARING_CONSENT] = sharingConsent
        }
    }

    override suspend fun saveFamilyProfile(
        displayName: String,
        mobileNumber: String,
        elderDisplayName: String,
        elderMobileNumber: String,
        relationshipName: String?,
        emergencyContact: Boolean,
    ) {
        if (!isDebuggable) return
        context.onboardingDataStore.edit { preferences ->
            preferences[Keys.FAMILY_DISPLAY_NAME] = displayName
            preferences[Keys.FAMILY_MOBILE] = mobileNumber
            preferences[Keys.FAMILY_ELDER_DISPLAY_NAME] = elderDisplayName
            preferences[Keys.FAMILY_ELDER_MOBILE] = elderMobileNumber
            if (relationshipName == null) {
                preferences.remove(Keys.FAMILY_RELATIONSHIP)
            } else {
                preferences[Keys.FAMILY_RELATIONSHIP] = relationshipName
            }
            preferences[Keys.FAMILY_EMERGENCY_CONTACT] = emergencyContact
        }
    }

    override suspend fun clear() {
        if (!isDebuggable) return
        context.onboardingDataStore.edit { it.clear() }
    }

    private fun Preferences.toProfiles() = PersistedOnboardingProfiles(
        elderDisplayName = this[Keys.ELDER_DISPLAY_NAME].orEmpty(),
        elderFamilyMobileNumber = this[Keys.ELDER_FAMILY_MOBILE].orEmpty(),
        elderSharingConsent = this[Keys.ELDER_SHARING_CONSENT] ?: false,
        familyDisplayName = this[Keys.FAMILY_DISPLAY_NAME].orEmpty(),
        familyMobileNumber = this[Keys.FAMILY_MOBILE].orEmpty(),
        familyElderDisplayName = this[Keys.FAMILY_ELDER_DISPLAY_NAME].orEmpty(),
        familyElderMobileNumber = this[Keys.FAMILY_ELDER_MOBILE].orEmpty(),
        familyRelationshipName = this[Keys.FAMILY_RELATIONSHIP],
        familyEmergencyContact = this[Keys.FAMILY_EMERGENCY_CONTACT] ?: true,
    )

    private object Keys {
        val ELDER_DISPLAY_NAME = stringPreferencesKey("elder_display_name")
        val ELDER_FAMILY_MOBILE = stringPreferencesKey("elder_family_mobile")
        val ELDER_SHARING_CONSENT = booleanPreferencesKey("elder_sharing_consent")
        val FAMILY_DISPLAY_NAME = stringPreferencesKey("family_display_name")
        val FAMILY_MOBILE = stringPreferencesKey("family_mobile")
        val FAMILY_ELDER_DISPLAY_NAME = stringPreferencesKey("family_elder_display_name")
        val FAMILY_ELDER_MOBILE = stringPreferencesKey("family_elder_mobile")
        val FAMILY_RELATIONSHIP = stringPreferencesKey("family_relationship")
        val FAMILY_EMERGENCY_CONTACT = booleanPreferencesKey("family_emergency_contact")
    }
}
