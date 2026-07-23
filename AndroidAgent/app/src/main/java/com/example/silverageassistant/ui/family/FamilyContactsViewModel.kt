package com.example.silverageassistant.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.data.contacts.FamilyContactStore
import com.example.silverageassistant.data.middleserver.ElderFamilyContactsRepository
import com.example.silverageassistant.data.middleserver.FamilyContactProfile
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import com.example.silverageassistant.domain.agent.AgentLongTermMemory
import com.example.silverageassistant.domain.agent.MemoryFamilyContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FamilyContactsUiState(
    val contacts: List<FamilyContactProfile> = emptyList(),
    val isLoadingLocal: Boolean = true,
    val isSyncing: Boolean = false,
    val lastSyncedAt: String? = null,
    val message: String? = null,
    val isError: Boolean = false,
)

class FamilyContactsViewModel(
    private val store: FamilyContactStore? = null,
    private val repository: ElderFamilyContactsRepository? = null,
    externalScope: CoroutineScope? = null,
    private val agentLongTermMemory: AgentLongTermMemory? = null,
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val _uiState = MutableStateFlow(FamilyContactsUiState(isLoadingLocal = store != null))
    val uiState: StateFlow<FamilyContactsUiState> = _uiState.asStateFlow()
    private var localLoadJob: Job? = null
    private var syncJob: Job? = null

    init {
        if (store != null) {
            localLoadJob = workScope.launch {
                val cached = runCatching { store.load() }.getOrNull()
                if (cached != null) {
                    runCatching {
                        agentLongTermMemory?.replaceFamilyContacts(
                            cached.contacts.map(FamilyContactProfile::toMemoryContact),
                        )
                    }
                }
                _uiState.value = _uiState.value.copy(
                    contacts = cached?.contacts.orEmpty(),
                    lastSyncedAt = cached?.syncedAt,
                    isLoadingLocal = false,
                )
            }
        }
    }

    fun syncContacts() {
        val localStore = store ?: return
        val remoteRepository = repository ?: return
        if (syncJob?.isActive == true) return
        syncJob = workScope.launch {
            localLoadJob?.join()
            _uiState.value = _uiState.value.copy(
                isSyncing = true,
                message = "正在同步家属信息…",
                isError = false,
            )
            try {
                val snapshot = remoteRepository.getFamilyContacts()
                localStore.save(snapshot)
                runCatching {
                    agentLongTermMemory?.replaceFamilyContacts(
                        snapshot.contacts.map(FamilyContactProfile::toMemoryContact),
                    )
                }
                _uiState.value = _uiState.value.copy(
                    contacts = snapshot.contacts,
                    isLoadingLocal = false,
                    isSyncing = false,
                    lastSyncedAt = snapshot.syncedAt,
                    message = if (snapshot.contacts.isEmpty()) {
                        "当前没有可联系的已绑定家属。"
                    } else {
                        null
                    },
                    isError = false,
                )
            } catch (error: MiddleServerRequestException) {
                if (error.code == "AUTHENTICATION_REQUIRED" ||
                    error.code == "FAMILY_CONTACTS_FORBIDDEN" ||
                    error.code == "BINDING_REVOKED"
                ) {
                    localStore.clear()
                    runCatching { agentLongTermMemory?.clearFamilyContacts() }
                    _uiState.value = _uiState.value.copy(
                        contacts = emptyList(),
                        isLoadingLocal = false,
                        isSyncing = false,
                        lastSyncedAt = null,
                        message = error.userMessage,
                        isError = true,
                    )
                } else {
                    showSyncFailure(error.userMessage)
                }
            } catch (_: Exception) {
                showSyncFailure("暂时无法同步家属信息，正在显示上次保存的联系人。")
            }
        }
    }

    private fun showSyncFailure(message: String) {
        _uiState.value = _uiState.value.copy(
            isLoadingLocal = false,
            isSyncing = false,
            message = message,
            isError = true,
        )
    }

    class Factory(
        private val store: FamilyContactStore,
        private val repository: ElderFamilyContactsRepository?,
        private val agentLongTermMemory: AgentLongTermMemory? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FamilyContactsViewModel::class.java))
            return FamilyContactsViewModel(
                store = store,
                repository = repository,
                agentLongTermMemory = agentLongTermMemory,
            ) as T
        }
    }
}

private fun FamilyContactProfile.toMemoryContact() = MemoryFamilyContact.fromSensitiveContact(
    displayName = displayName,
    relationship = relationship,
    mobileNumber = mobileNumber,
    emergencyContact = emergencyContact,
)
