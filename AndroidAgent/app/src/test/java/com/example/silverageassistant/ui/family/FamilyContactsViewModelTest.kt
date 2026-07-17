package com.example.silverageassistant.ui.family

import com.example.silverageassistant.data.contacts.InMemoryFamilyContactStore
import com.example.silverageassistant.data.middleserver.ElderFamilyContactsRepository
import com.example.silverageassistant.data.middleserver.FamilyContactProfile
import com.example.silverageassistant.data.middleserver.FamilyContactsSyncResult
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyContactsViewModelTest {
    @Test
    fun cachedContacts_areAvailableBeforeNetworkSync() {
        val cached = snapshot(contact(name = "李女士", mobile = "13800138000"), version = "v1")
        val store = InMemoryFamilyContactStore(cached)

        val viewModel = FamilyContactsViewModel(
            store = store,
            repository = null,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals("李女士", viewModel.uiState.value.contacts.single().displayName)
        assertEquals("13800138000", viewModel.uiState.value.contacts.single().mobileNumber)
        assertFalse(viewModel.uiState.value.isLoadingLocal)
    }

    @Test
    fun successfulSync_replacesAndPersistsContactSnapshot() {
        val store = InMemoryFamilyContactStore()
        val remote = snapshot(contact(name = "王先生", mobile = "13900139000"), version = "v2")
        val viewModel = viewModel(store, FakeRepository(result = remote))

        viewModel.syncContacts()

        assertEquals(remote, store.snapshot)
        assertEquals("王先生", viewModel.uiState.value.contacts.single().displayName)
        assertFalse(viewModel.uiState.value.isSyncing)
        assertFalse(viewModel.uiState.value.isError)
    }

    @Test
    fun failedSync_keepsEncryptedCacheVisible() {
        val cached = snapshot(contact(name = "陈女士", mobile = "13700137000"), version = "v1")
        val store = InMemoryFamilyContactStore(cached)
        val repository = FakeRepository(
            error = MiddleServerRequestException("NETWORK_TIMEOUT", "连接超时，请稍后重试。"),
        )
        val viewModel = viewModel(store, repository)

        viewModel.syncContacts()

        assertEquals(cached, store.snapshot)
        assertEquals("陈女士", viewModel.uiState.value.contacts.single().displayName)
        assertTrue(viewModel.uiState.value.isError)
        assertEquals("连接超时，请稍后重试。", viewModel.uiState.value.message)
    }

    @Test
    fun revokedAccess_clearsCachedContacts() {
        val cached = snapshot(contact(name = "陈女士", mobile = "13700137000"), version = "v1")
        val store = InMemoryFamilyContactStore(cached)
        val repository = FakeRepository(
            error = MiddleServerRequestException(
                "FAMILY_CONTACTS_FORBIDDEN",
                "当前设备无权读取家属联系人。",
            ),
        )
        val viewModel = viewModel(store, repository)

        viewModel.syncContacts()

        assertEquals(null, store.snapshot)
        assertTrue(viewModel.uiState.value.contacts.isEmpty())
        assertTrue(viewModel.uiState.value.isError)
    }

    private fun viewModel(
        store: InMemoryFamilyContactStore,
        repository: ElderFamilyContactsRepository,
    ) = FamilyContactsViewModel(
        store = store,
        repository = repository,
        externalScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun contact(name: String, mobile: String) = FamilyContactProfile(
        bindingId = "binding-1",
        familyAccountId = "family-1",
        displayName = name,
        mobileNumber = mobile,
        relationship = "CHILD",
        permissions = listOf("STATUS_SUMMARY", "EMERGENCY_CONTACT"),
        emergencyContact = true,
        boundAt = "2026-07-17T08:00:00Z",
    )

    private fun snapshot(
        contact: FamilyContactProfile,
        version: String,
    ) = FamilyContactsSyncResult(
        contacts = listOf(contact),
        snapshotVersion = version,
        syncedAt = "2026-07-17T09:00:00Z",
    )

    private class FakeRepository(
        private val result: FamilyContactsSyncResult? = null,
        private val error: MiddleServerRequestException? = null,
    ) : ElderFamilyContactsRepository {
        override suspend fun getFamilyContacts(): FamilyContactsSyncResult {
            error?.let { throw it }
            return requireNotNull(result)
        }
    }
}
