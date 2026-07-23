package com.example.silverageassistant.ui.settings

import com.example.silverageassistant.data.model.InMemoryModelApiCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelApiKeyViewModelTest {
    @Test
    fun existingKey_isLoadedAsMaskedStatusWithoutPuttingSecretInDraft() {
        val credentialStore = InMemoryModelApiCredentialStore("test-model-key-1234")

        val viewModel = viewModel(credentialStore)

        assertTrue(viewModel.uiState.value.isConfigured)
        assertEquals("••••1234", viewModel.uiState.value.maskedKey)
        assertEquals("", viewModel.uiState.value.draft)
        assertFalse(viewModel.uiState.value.toString().contains("test-model-key-1234"))
    }

    @Test
    fun save_trimsAndStoresKeyThenClearsPlaintextDraft() = runBlocking {
        val credentialStore = InMemoryModelApiCredentialStore()
        val viewModel = viewModel(credentialStore)
        viewModel.updateDraft("  test-model-key-5678\n")
        assertFalse(viewModel.uiState.value.toString().contains("test-model-key-5678"))

        assertTrue(viewModel.saveApiKey())

        assertEquals("test-model-key-5678", credentialStore.loadApiKey())
        assertEquals("", viewModel.uiState.value.draft)
        assertEquals("••••5678", viewModel.uiState.value.maskedKey)
        assertTrue(viewModel.uiState.value.isConfigured)
    }

    @Test
    fun blankKey_isRejectedWithoutChangingCredentialStore() = runBlocking {
        val credentialStore = InMemoryModelApiCredentialStore()
        val viewModel = viewModel(credentialStore)
        viewModel.updateDraft(" \n ")

        assertFalse(viewModel.saveApiKey())

        assertNull(credentialStore.loadApiKey())
        assertTrue(viewModel.uiState.value.isError)
    }

    @Test
    fun delete_requiresConfirmationAndClearsSavedKey() = runBlocking {
        val credentialStore = InMemoryModelApiCredentialStore("test-model-key-9999")
        val viewModel = viewModel(credentialStore)

        viewModel.requestDelete()
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)
        assertEquals("test-model-key-9999", credentialStore.loadApiKey())

        viewModel.confirmDelete()

        assertNull(credentialStore.loadApiKey())
        assertFalse(viewModel.uiState.value.isConfigured)
        assertFalse(viewModel.uiState.value.showDeleteConfirmation)
    }

    private fun viewModel(
        credentialStore: InMemoryModelApiCredentialStore,
    ) = ModelApiKeyViewModel(
        credentialStore = credentialStore,
        externalScope = CoroutineScope(Dispatchers.Unconfined),
    )
}
