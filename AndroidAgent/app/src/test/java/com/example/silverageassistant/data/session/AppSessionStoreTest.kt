package com.example.silverageassistant.data.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSessionStoreTest {
    @Test
    fun completedRoleAndLastKnownBinding_arePersisted() = runBlocking {
        val store = InMemoryAppSessionStore()

        store.update {
            it.copy(
                defaultRole = AppRole.FAMILY,
                familyOnboardingCompleted = true,
                lastKnownFamilyBound = true,
                familyDisplayName = "小林",
            )
        }

        val saved = store.session.first()
        assertEquals(AppRole.FAMILY, saved.defaultRole)
        assertTrue(saved.familyOnboardingCompleted)
        assertTrue(saved.lastKnownFamilyBound)
        assertEquals("小林", saved.familyDisplayName)
    }
}
