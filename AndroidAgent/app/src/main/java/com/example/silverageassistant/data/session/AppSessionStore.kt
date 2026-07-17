package com.example.silverageassistant.data.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

enum class AppRole {
    ELDER,
    FAMILY,
}

data class PersistedAppSession(
    val defaultRole: AppRole? = null,
    val elderOnboardingCompleted: Boolean = false,
    val familyOnboardingCompleted: Boolean = false,
    val lastKnownElderBound: Boolean = false,
    val lastKnownFamilyBound: Boolean = false,
    val lastSyncedAt: String? = null,
    val elderDisplayName: String = "",
    val familyDisplayName: String = "",
    val familyElderDisplayName: String = "",
    val familyRelationshipName: String? = null,
    val familyElderId: String? = null,
)

interface AppSessionStore {
    val session: Flow<PersistedAppSession>

    suspend fun update(transform: (PersistedAppSession) -> PersistedAppSession)

    suspend fun clear()
}

class InMemoryAppSessionStore(
    initialState: PersistedAppSession = PersistedAppSession(),
) : AppSessionStore {
    private val state = MutableStateFlow(initialState)
    override val session: Flow<PersistedAppSession> = state

    override suspend fun update(transform: (PersistedAppSession) -> PersistedAppSession) {
        state.value = transform(state.value)
    }

    override suspend fun clear() {
        state.value = PersistedAppSession()
    }
}
