package com.example.silverageassistant.data.voice

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.voiceInteractionDataStore by preferencesDataStore(
    name = "silverage_voice_interaction_settings",
)

interface VoiceInteractionSettingsStore {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}
class DataStoreVoiceInteractionSettingsStore(context: Context) : VoiceInteractionSettingsStore {
    private val dataStore = context.applicationContext.voiceInteractionDataStore

    override val enabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[VOICE_INTERACTION_ENABLED] ?: false
    }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VOICE_INTERACTION_ENABLED] = enabled
        }
    }

    private companion object {
        val VOICE_INTERACTION_ENABLED = booleanPreferencesKey("voice_interaction_enabled")
    }
}
