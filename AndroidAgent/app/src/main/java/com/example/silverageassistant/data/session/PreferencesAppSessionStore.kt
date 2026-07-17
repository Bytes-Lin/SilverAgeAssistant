package com.example.silverageassistant.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val APP_SESSION_DATA_STORE_NAME = "app_session"

private val Context.appSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = APP_SESSION_DATA_STORE_NAME,
)

class PreferencesAppSessionStore(
    private val context: Context,
) : AppSessionStore {
    override val session: Flow<PersistedAppSession> = context.appSessionDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences.toAppSession() }

    override suspend fun update(transform: (PersistedAppSession) -> PersistedAppSession) {
        context.appSessionDataStore.edit { preferences ->
            preferences.write(transform(preferences.toAppSession()))
        }
    }

    override suspend fun clear() {
        context.appSessionDataStore.edit { preferences -> preferences.clear() }
    }

    private fun MutablePreferences.write(value: PersistedAppSession) {
        if (value.defaultRole == null) remove(Keys.DEFAULT_ROLE) else {
            this[Keys.DEFAULT_ROLE] = value.defaultRole.name
        }
        this[Keys.ELDER_COMPLETED] = value.elderOnboardingCompleted
        this[Keys.FAMILY_COMPLETED] = value.familyOnboardingCompleted
        this[Keys.ELDER_BOUND] = value.lastKnownElderBound
        this[Keys.FAMILY_BOUND] = value.lastKnownFamilyBound
        if (value.lastSyncedAt == null) remove(Keys.LAST_SYNCED_AT) else {
            this[Keys.LAST_SYNCED_AT] = value.lastSyncedAt
        }
        this[Keys.ELDER_DISPLAY_NAME] = value.elderDisplayName
        this[Keys.FAMILY_DISPLAY_NAME] = value.familyDisplayName
        this[Keys.FAMILY_ELDER_DISPLAY_NAME] = value.familyElderDisplayName
        if (value.familyRelationshipName == null) remove(Keys.FAMILY_RELATIONSHIP) else {
            this[Keys.FAMILY_RELATIONSHIP] = value.familyRelationshipName
        }
        if (value.familyElderId == null) remove(Keys.FAMILY_ELDER_ID) else {
            this[Keys.FAMILY_ELDER_ID] = value.familyElderId
        }
    }

    private fun Preferences.toAppSession() = PersistedAppSession(
        defaultRole = this[Keys.DEFAULT_ROLE]?.let { saved ->
            AppRole.entries.firstOrNull { it.name == saved }
        },
        elderOnboardingCompleted = this[Keys.ELDER_COMPLETED] ?: false,
        familyOnboardingCompleted = this[Keys.FAMILY_COMPLETED] ?: false,
        lastKnownElderBound = this[Keys.ELDER_BOUND] ?: false,
        lastKnownFamilyBound = this[Keys.FAMILY_BOUND] ?: false,
        lastSyncedAt = this[Keys.LAST_SYNCED_AT],
        elderDisplayName = this[Keys.ELDER_DISPLAY_NAME].orEmpty(),
        familyDisplayName = this[Keys.FAMILY_DISPLAY_NAME].orEmpty(),
        familyElderDisplayName = this[Keys.FAMILY_ELDER_DISPLAY_NAME].orEmpty(),
        familyRelationshipName = this[Keys.FAMILY_RELATIONSHIP],
        familyElderId = this[Keys.FAMILY_ELDER_ID],
    )

    private object Keys {
        val DEFAULT_ROLE = stringPreferencesKey("default_role")
        val ELDER_COMPLETED = booleanPreferencesKey("elder_onboarding_completed")
        val FAMILY_COMPLETED = booleanPreferencesKey("family_onboarding_completed")
        val ELDER_BOUND = booleanPreferencesKey("last_known_elder_bound")
        val FAMILY_BOUND = booleanPreferencesKey("last_known_family_bound")
        val LAST_SYNCED_AT = stringPreferencesKey("last_synced_at")
        val ELDER_DISPLAY_NAME = stringPreferencesKey("elder_display_name")
        val FAMILY_DISPLAY_NAME = stringPreferencesKey("family_display_name")
        val FAMILY_ELDER_DISPLAY_NAME = stringPreferencesKey("family_elder_display_name")
        val FAMILY_RELATIONSHIP = stringPreferencesKey("family_relationship")
        val FAMILY_ELDER_ID = stringPreferencesKey("family_elder_id")
    }
}
