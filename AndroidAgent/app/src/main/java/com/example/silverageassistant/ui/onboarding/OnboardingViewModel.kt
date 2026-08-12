package com.example.silverageassistant.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.data.middleserver.ElderBindingRequest
import com.example.silverageassistant.data.middleserver.FamilyOnboardingRequest
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import com.example.silverageassistant.data.middleserver.MiddleServerCredentialStore
import com.example.silverageassistant.data.middleserver.OnboardingMiddleServerRepository
import com.example.silverageassistant.data.middleserver.SessionRestoreStatus
import com.example.silverageassistant.data.onboarding.OnboardingProfileStore
import com.example.silverageassistant.data.onboarding.PersistedOnboardingProfiles
import com.example.silverageassistant.data.session.AppRole
import com.example.silverageassistant.data.session.AppSessionStore
import com.example.silverageassistant.data.session.PersistedAppSession
import com.example.silverageassistant.domain.agent.AgentLongTermMemory
import com.example.silverageassistant.domain.agent.MemoryFamilyContact
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 双角色初始化与会话恢复状态机。
 *
 * 表单草稿、正式角色状态和加密凭证分别存储。启动时优先恢复既有会话，只有凭证缺失或
 * 服务端明确拒绝时才回到注册/绑定页；普通断网不会清除已经完成的老人或家属身份。
 */
class OnboardingViewModel(
    private val profileStore: OnboardingProfileStore? = null,
    private val middleServerRepository: OnboardingMiddleServerRepository? = null,
    private val appSessionStore: AppSessionStore? = null,
    private val credentialStore: MiddleServerCredentialStore? = null,
    private val agentLongTermMemory: AgentLongTermMemory? = null,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val shouldRestoreStartup = appSessionStore != null || credentialStore != null
    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            isRestoringProfiles = profileStore != null,
            isStartupLoading = shouldRestoreStartup,
        ),
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var elderPersistenceJob: Job? = null
    private var familyPersistenceJob: Job? = null
    private var sessionRefreshJob: Job? = null

    init {
        when {
            shouldRestoreStartup -> restoreStartup()
            profileStore != null -> restoreProfilesOnly(profileStore)
        }
    }

    fun selectRole(role: AppRole): StartupDestination {
        val destination = if (role == AppRole.ELDER) {
            StartupDestination.ElderSetup
        } else {
            StartupDestination.FamilySetup
        }
        // Navigation observes this state as well as the NavController. Update it synchronously so
        // the invalid-session redirect cannot mistake a deliberate role selection for stale state.
        _uiState.update {
            it.copy(
                startupDestination = destination,
                sessionConnectionStatus = SessionConnectionStatus.Unknown,
                sessionMessage = null,
                networkMessage = null,
            )
        }
        workScope.launch {
            appSessionStore?.update { it.copy(defaultRole = role) }
        }
        return destination
    }

    /**
     * Revalidates the active bound session when the app returns to the foreground.
     * Offline failures keep the current role usable; only an explicit invalid result resets onboarding.
     */
    fun refreshCurrentSession(onCompleted: (Boolean) -> Unit = {}) {
        if (_uiState.value.isStartupLoading) {
            onCompleted(false)
            return
        }
        val activeRefresh = sessionRefreshJob
        if (activeRefresh?.isActive == true) {
            workScope.launch {
                activeRefresh.join()
                onCompleted(
                    _uiState.value.sessionConnectionStatus == SessionConnectionStatus.Online &&
                        _uiState.value.familyBindingStatus == BindingPreparationStatus.Bound,
                )
            }
            return
        }
        val destination = _uiState.value.startupDestination
        sessionRefreshJob = workScope.launch {
            when (destination) {
                StartupDestination.FamilyHome -> refreshFamilySession()
                StartupDestination.ElderHome -> refreshElderSession()
                else -> Unit
            }
            onCompleted(
                _uiState.value.sessionConnectionStatus == SessionConnectionStatus.Online &&
                    _uiState.value.familyBindingStatus == BindingPreparationStatus.Bound,
            )
        }
    }

    fun updateElderName(value: String) = updateElderDraft { copy(displayName = value.take(20)) }

    fun updateElderFamilyMobileNumber(value: String) = updateElderDraft {
        copy(familyMobileNumber = value.filter(Char::isDigit).take(20))
    }

    fun updateBindingCode(value: String) {
        _uiState.update { state ->
            state.copy(
                elderDraft = state.elderDraft.copy(
                    bindingCode = value.filter(Char::isDigit).take(6),
                ),
                elderErrors = ElderSetupErrors(),
                networkMessage = null,
            )
        }
    }

    fun updateSharingConsent(value: Boolean) = updateElderDraft { copy(sharingConsent = value) }

    fun updateFamilyName(value: String) = updateFamilyDraft { copy(displayName = value.take(20)) }

    fun updateMobileNumber(value: String) = updateFamilyDraft {
        copy(mobileNumber = value.filter(Char::isDigit).take(20))
    }

    fun updateElderDisplayName(value: String) = updateFamilyDraft {
        copy(elderDisplayName = value.take(20))
    }

    fun updateFamilyElderMobileNumber(value: String) = updateFamilyDraft {
        copy(elderMobileNumber = value.filter(Char::isDigit).take(20))
    }

    fun updateRelationship(value: FamilyRelationship) = updateFamilyDraft { copy(relationship = value) }

    fun updateEmergencyContact(value: Boolean) = updateFamilyDraft { copy(emergencyContact = value) }

    fun submitElderSetup(onCompleted: () -> Unit = {}): Boolean {
        if (_uiState.value.isSubmitting) return false
        val errors = OnboardingValidator.validateElder(_uiState.value.elderDraft)
        val draft = _uiState.value.elderDraft
        val hasBindingCredentials = draft.bindingCode.isNotBlank() &&
            draft.familyMobileNumber.isNotBlank()
        _uiState.update { state ->
            state.copy(
                elderErrors = errors,
                elderBindingStatus = if (
                    !errors.hasErrors &&
                    hasBindingCredentials
                ) {
                    BindingPreparationStatus.PendingJointVerification
                } else {
                    BindingPreparationStatus.NotPrepared
                },
                networkMessage = null,
            )
        }
        if (errors.hasErrors) return false
        scheduleElderPersistence(delayMillis = 0)
        if (!hasBindingCredentials || middleServerRepository == null) {
            if (appSessionStore == null) {
                onCompleted()
            } else {
                workScope.launch {
                    appSessionStore.update {
                        it.copy(
                            defaultRole = AppRole.ELDER,
                            elderDisplayName = draft.displayName.trim(),
                        )
                    }
                    onCompleted()
                }
            }
            return true
        }

        _uiState.update { it.copy(isSubmitting = true) }
        workScope.launch {
            try {
                val result = middleServerRepository.bindElderDevice(
                    ElderBindingRequest(
                        displayName = draft.displayName.trim(),
                        familyMobileNumber = draft.familyMobileNumber,
                        bindingCode = draft.bindingCode,
                        sharingConsent = draft.sharingConsent,
                    ),
                )
                val syncedAt = Instant.now().toString()
                appSessionStore?.update {
                    it.copy(
                        defaultRole = AppRole.ELDER,
                        elderOnboardingCompleted = true,
                        lastKnownElderBound = true,
                        lastKnownFamilyBound = it.familyOnboardingCompleted || it.lastKnownFamilyBound,
                        lastSyncedAt = syncedAt,
                        elderDisplayName = draft.displayName.trim(),
                    )
                }
                _uiState.update {
                    it.copy(
                        startupDestination = StartupDestination.ElderHome,
                        isSubmitting = false,
                        elderBindingStatus = BindingPreparationStatus.Bound,
                        familyMobileMasked = result.familyMobileMasked,
                        lastSyncedAt = syncedAt,
                        hasDeviceCredential = true,
                        sessionConnectionStatus = SessionConnectionStatus.Online,
                    )
                }
                runCatching {
                    agentLongTermMemory?.updateElderPreferredName(draft.displayName.trim())
                    agentLongTermMemory?.recordBoundFamily(
                        MemoryFamilyContact.fromSensitiveContact(
                            displayName = "已绑定家属",
                            relationship = result.relationship,
                            mobileNumber = result.familyMobileMasked,
                            emergencyContact = false,
                        ),
                    )
                }
                onCompleted()
            } catch (error: MiddleServerRequestException) {
                showNetworkFailure(error.userMessage)
            } catch (_: Exception) {
                showNetworkFailure("绑定信息保存失败，请稍后重试。")
            }
        }
        return true
    }

    fun submitFamilySetup(onCompleted: () -> Unit = {}): Boolean {
        if (_uiState.value.isSubmitting) return false
        val errors = OnboardingValidator.validateFamily(_uiState.value.familyDraft)
        val draft = _uiState.value.familyDraft
        _uiState.update { state ->
            state.copy(
                familyErrors = errors,
                familyBindingStatus = if (errors.hasErrors) {
                    BindingPreparationStatus.NotPrepared
                } else {
                    BindingPreparationStatus.AwaitingCodeGeneration
                },
                networkMessage = null,
            )
        }
        if (errors.hasErrors) return false
        scheduleFamilyPersistence(delayMillis = 0)
        if (middleServerRepository == null) {
            if (appSessionStore == null) {
                onCompleted()
            } else {
                workScope.launch {
                    appSessionStore.update {
                        it.copy(
                            defaultRole = AppRole.FAMILY,
                            familyDisplayName = draft.displayName.trim(),
                            familyElderDisplayName = draft.elderDisplayName.trim(),
                            familyRelationshipName = draft.relationship?.name,
                        )
                    }
                    onCompleted()
                }
            }
            return true
        }

        _uiState.update { it.copy(isSubmitting = true) }
        workScope.launch {
            try {
                val request = FamilyOnboardingRequest(
                        displayName = draft.displayName.trim(),
                        mobileNumber = draft.mobileNumber,
                        elderDisplayName = draft.elderDisplayName.trim(),
                        elderMobileNumber = draft.elderMobileNumber,
                        relationship = requireNotNull(draft.relationship).name.uppercase(),
                        emergencyContact = draft.emergencyContact,
                    )
                // A revoked binding does not delete the family account or its authenticated
                // session. Reuse the known elder profile to generate a new code instead of trying
                // to register the same mobile number again.
                val reusableElderId = _uiState.value.familyElderId
                    ?.takeIf { _uiState.value.hasFamilySession && it.isNotBlank() }
                val result = if (reusableElderId == null) {
                    middleServerRepository.registerFamilyAndCreateBindingCode(request)
                } else {
                    middleServerRepository.regenerateBindingCode(reusableElderId)
                }
                val syncedAt = Instant.now().toString()
                appSessionStore?.update {
                    it.copy(
                        defaultRole = AppRole.FAMILY,
                        familyOnboardingCompleted = true,
                        lastKnownFamilyBound = false,
                        lastSyncedAt = syncedAt,
                        familyDisplayName = draft.displayName.trim(),
                        familyElderDisplayName = draft.elderDisplayName.trim(),
                        familyRelationshipName = draft.relationship?.name,
                        familyElderId = result.elderId,
                    )
                }
                _uiState.update {
                    it.copy(
                        startupDestination = StartupDestination.FamilyHome,
                        isSubmitting = false,
                        familyBindingStatus = BindingPreparationStatus.CodeGenerated,
                        familyBindingCode = result.bindingCode,
                        familyBindingCodeExpiresAt = result.bindingCodeExpiresAt,
                        familyMobileMasked = result.familyMobileMasked,
                        lastSyncedAt = syncedAt,
                        familyElderId = result.elderId,
                        hasFamilySession = true,
                        sessionConnectionStatus = SessionConnectionStatus.Online,
                    )
                }
                onCompleted()
            } catch (error: MiddleServerRequestException) {
                showNetworkFailure(error.userMessage)
            } catch (_: Exception) {
                showNetworkFailure("中台凭证保存失败，请稍后重试。")
            }
        }
        return true
    }

    fun regenerateFamilyBindingCode(): Boolean {
        val state = _uiState.value
        if (state.isSubmitting) return false
        val repository = middleServerRepository
        val elderId = state.familyElderId
        if (repository == null || elderId.isNullOrBlank()) {
            _uiState.update {
                it.copy(networkMessage = "缺少老人档案信息，请先同步中台后重试。")
            }
            return false
        }

        _uiState.update {
            it.copy(
                isSubmitting = true,
                networkMessage = "正在重新生成绑定码…",
            )
        }
        workScope.launch {
            try {
                val result = repository.regenerateBindingCode(elderId)
                val syncedAt = Instant.now().toString()
                appSessionStore?.update {
                    it.copy(
                        lastSyncedAt = syncedAt,
                        familyElderId = result.elderId ?: elderId,
                    )
                }
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        familyBindingStatus = if (
                            it.familyBindingStatus == BindingPreparationStatus.Bound
                        ) {
                            BindingPreparationStatus.Bound
                        } else {
                            BindingPreparationStatus.CodeGenerated
                        },
                        familyBindingCode = result.bindingCode,
                        familyBindingCodeExpiresAt = result.bindingCodeExpiresAt,
                        familyMobileMasked = result.familyMobileMasked,
                        lastSyncedAt = syncedAt,
                        familyElderId = result.elderId ?: elderId,
                        sessionConnectionStatus = SessionConnectionStatus.Online,
                        sessionMessage = null,
                        networkMessage = "新绑定码已生成，请在老人手机上填写。",
                    )
                }
            } catch (error: MiddleServerRequestException) {
                showNetworkFailure(error.userMessage)
            } catch (_: Exception) {
                showNetworkFailure("重新生成绑定码失败，请稍后重试。")
            }
        }
        return true
    }

    private fun restoreStartup() {
        workScope.launch {
            var persistenceMessage: String? = null
            val profiles = try {
                profileStore?.profiles?.first() ?: PersistedOnboardingProfiles()
            } catch (_: IOException) {
                persistenceMessage = "读取本机测试资料失败，请重新填写。"
                PersistedOnboardingProfiles()
            }
            var appSession = try {
                appSessionStore?.session?.first() ?: PersistedAppSession()
            } catch (_: IOException) {
                PersistedAppSession()
            }
            val familySession = try {
                credentialStore?.loadFamilySession()
            } catch (_: Exception) {
                credentialStore?.clearFamilySession()
                null
            }
            val deviceCredential = try {
                credentialStore?.loadDeviceCredential()
            } catch (_: Exception) {
                credentialStore?.clearDeviceCredential()
                null
            }

            val migratedSession = appSession.copy(
                defaultRole = appSession.defaultRole ?: when {
                    deviceCredential != null -> AppRole.ELDER
                    familySession != null -> AppRole.FAMILY
                    else -> null
                },
                elderOnboardingCompleted = appSession.elderOnboardingCompleted ||
                    deviceCredential != null,
                familyOnboardingCompleted = appSession.familyOnboardingCompleted ||
                    familySession != null,
                lastKnownElderBound = appSession.lastKnownElderBound || deviceCredential != null,
                lastKnownFamilyBound = appSession.lastKnownFamilyBound ||
                    (deviceCredential != null && familySession != null),
            )
            if (migratedSession != appSession) {
                appSessionStore?.update { migratedSession }
                appSession = migratedSession
            }

            val relationshipName = profiles.familyRelationshipName
                ?: appSession.familyRelationshipName
            val destination = determineStartupDestination(
                appSession = appSession,
                hasFamilySession = familySession != null,
                hasDeviceCredential = deviceCredential != null,
            )
            _uiState.update { state ->
                state.copy(
                    elderDraft = ElderSetupDraft(
                        displayName = profiles.elderDisplayName.ifBlank {
                            appSession.elderDisplayName
                        },
                        familyMobileNumber = profiles.elderFamilyMobileNumber,
                        sharingConsent = profiles.elderSharingConsent,
                    ),
                    familyDraft = FamilySetupDraft(
                        displayName = profiles.familyDisplayName.ifBlank {
                            appSession.familyDisplayName
                        },
                        mobileNumber = profiles.familyMobileNumber,
                        elderDisplayName = profiles.familyElderDisplayName.ifBlank {
                            appSession.familyElderDisplayName
                        },
                        elderMobileNumber = profiles.familyElderMobileNumber,
                        relationship = relationshipName?.let(::relationshipFromSavedName),
                        emergencyContact = profiles.familyEmergencyContact,
                    ),
                    elderBindingStatus = if (appSession.lastKnownElderBound) {
                        BindingPreparationStatus.Bound
                    } else {
                        BindingPreparationStatus.NotPrepared
                    },
                    familyBindingStatus = if (appSession.lastKnownFamilyBound) {
                        BindingPreparationStatus.Bound
                    } else if (appSession.familyOnboardingCompleted) {
                        BindingPreparationStatus.CodeGenerated
                    } else {
                        BindingPreparationStatus.NotPrepared
                    },
                    isRestoringProfiles = false,
                    isStartupLoading = false,
                    startupDestination = destination,
                    hasFamilySession = familySession != null,
                    hasDeviceCredential = deviceCredential != null,
                    lastSyncedAt = appSession.lastSyncedAt,
                    familyElderId = appSession.familyElderId,
                    persistenceMessage = persistenceMessage,
                )
            }

            when (destination) {
                StartupDestination.FamilyHome -> refreshFamilySession()
                StartupDestination.ElderHome -> refreshElderSession()
                else -> Unit
            }
        }
    }

    private fun restoreProfilesOnly(store: OnboardingProfileStore) {
        workScope.launch {
            try {
                val saved = store.profiles.first()
                _uiState.update { state ->
                    state.copy(
                        elderDraft = ElderSetupDraft(
                            displayName = saved.elderDisplayName,
                            familyMobileNumber = saved.elderFamilyMobileNumber,
                            sharingConsent = saved.elderSharingConsent,
                        ),
                        familyDraft = FamilySetupDraft(
                            displayName = saved.familyDisplayName,
                            mobileNumber = saved.familyMobileNumber,
                            elderDisplayName = saved.familyElderDisplayName,
                            elderMobileNumber = saved.familyElderMobileNumber,
                            relationship = saved.familyRelationshipName?.let(::relationshipFromSavedName),
                            emergencyContact = saved.familyEmergencyContact,
                        ),
                        isRestoringProfiles = false,
                    )
                }
            } catch (_: IOException) {
                _uiState.update {
                    it.copy(
                        isRestoringProfiles = false,
                        persistenceMessage = "读取本机测试资料失败，请重新填写。",
                    )
                }
            }
        }
    }

    private fun determineStartupDestination(
        appSession: PersistedAppSession,
        hasFamilySession: Boolean,
        hasDeviceCredential: Boolean,
    ): StartupDestination = when (appSession.defaultRole) {
        null -> StartupDestination.RoleSelection
        AppRole.ELDER -> if (appSession.elderOnboardingCompleted && hasDeviceCredential) {
            StartupDestination.ElderHome
        } else {
            StartupDestination.ElderSetup
        }
        AppRole.FAMILY -> if (appSession.familyOnboardingCompleted && hasFamilySession) {
            StartupDestination.FamilyHome
        } else {
            StartupDestination.FamilySetup
        }
    }

    private suspend fun refreshFamilySession() {
        val repository = middleServerRepository ?: return
        _uiState.update {
            it.copy(
                sessionConnectionStatus = SessionConnectionStatus.Syncing,
                sessionMessage = "正在同步家庭绑定状态…",
            )
        }
        val result = try {
            repository.restoreFamilySession()
        } catch (_: Exception) {
            _uiState.update {
                it.copy(
                    sessionConnectionStatus = SessionConnectionStatus.Offline,
                    sessionMessage = "暂时无法连接中台，正在使用上次保存的状态。",
                )
            }
            return
        }
        val syncedAt = Instant.now().toString()
        when (result.status) {
            SessionRestoreStatus.ACTIVE -> {
                val binding = result.binding
                if (
                    binding == null &&
                    _uiState.value.familyBindingStatus == BindingPreparationStatus.Bound
                ) {
                    val canReuseFamilySession = runCatching {
                        credentialStore?.loadFamilySession() != null
                    }.getOrDefault(false)
                    resetAfterBindingInvalidation(
                        role = AppRole.FAMILY,
                        preserveFamilySession = canReuseFamilySession,
                    )
                    return
                }
                appSessionStore?.update {
                    it.copy(
                        lastKnownFamilyBound = binding != null,
                        lastSyncedAt = syncedAt,
                        familyDisplayName = binding?.familyDisplayName ?: it.familyDisplayName,
                        familyElderDisplayName = binding?.elderDisplayName
                            ?: it.familyElderDisplayName,
                        familyRelationshipName = binding?.relationship?.let(::relationshipNameFromApi)
                            ?: it.familyRelationshipName,
                        familyElderId = binding?.elderId?.takeIf(String::isNotBlank)
                            ?: it.familyElderId,
                    )
                }
                _uiState.update { state ->
                    state.copy(
                        familyDraft = state.familyDraft.copy(
                            displayName = binding?.familyDisplayName
                                ?: state.familyDraft.displayName,
                            elderDisplayName = binding?.elderDisplayName
                                ?: state.familyDraft.elderDisplayName,
                            relationship = binding?.relationship?.let(::relationshipFromApi)
                                ?: state.familyDraft.relationship,
                        ),
                        familyBindingStatus = if (binding == null) {
                            BindingPreparationStatus.CodeGenerated
                        } else {
                            BindingPreparationStatus.Bound
                        },
                        lastSyncedAt = syncedAt,
                        familyElderId = binding?.elderId?.takeIf(String::isNotBlank)
                            ?: state.familyElderId,
                        sessionConnectionStatus = SessionConnectionStatus.Online,
                        sessionMessage = null,
                    )
                }
            }
            SessionRestoreStatus.OFFLINE -> showOfflineSession()
            SessionRestoreStatus.INVALID, SessionRestoreStatus.MISSING -> {
                val canReuseFamilySession = runCatching {
                    credentialStore?.loadFamilySession() != null
                }.getOrDefault(false)
                resetAfterBindingInvalidation(
                    role = AppRole.FAMILY,
                    preserveFamilySession = canReuseFamilySession,
                    recoveredFamilyElderId = result.binding?.elderId,
                    familyBindingWasRevoked = result.binding != null,
                )
            }
        }
    }

    private suspend fun refreshElderSession() {
        val repository = middleServerRepository ?: return
        _uiState.update {
            it.copy(
                sessionConnectionStatus = SessionConnectionStatus.Syncing,
                sessionMessage = "正在确认家人绑定状态…",
            )
        }
        val result = try {
            repository.restoreElderSession()
        } catch (_: Exception) {
            showOfflineSession()
            return
        }
        val syncedAt = Instant.now().toString()
        when (result.status) {
            SessionRestoreStatus.ACTIVE -> {
                val binding = result.binding
                appSessionStore?.update {
                    it.copy(
                        lastKnownElderBound = binding != null,
                        lastSyncedAt = syncedAt,
                        elderDisplayName = binding?.elderDisplayName ?: it.elderDisplayName,
                    )
                }
                _uiState.update { state ->
                    state.copy(
                        elderDraft = state.elderDraft.copy(
                            displayName = binding?.elderDisplayName ?: state.elderDraft.displayName,
                        ),
                        elderBindingStatus = if (binding == null) {
                            BindingPreparationStatus.NotPrepared
                        } else {
                            BindingPreparationStatus.Bound
                        },
                        lastSyncedAt = syncedAt,
                        sessionConnectionStatus = SessionConnectionStatus.Online,
                        sessionMessage = null,
                    )
                }
                runCatching {
                    if (binding == null) {
                        agentLongTermMemory?.clearFamilyContacts()
                    } else {
                        agentLongTermMemory?.updateElderPreferredName(binding.elderDisplayName)
                        agentLongTermMemory?.recordBoundFamily(
                            MemoryFamilyContact.fromSensitiveContact(
                                displayName = binding.familyDisplayName,
                                relationship = binding.relationship,
                                mobileNumber = "",
                                emergencyContact = false,
                            ),
                        )
                    }
                }
            }
            SessionRestoreStatus.OFFLINE -> showOfflineSession()
            SessionRestoreStatus.INVALID, SessionRestoreStatus.MISSING -> {
                resetAfterBindingInvalidation(role = AppRole.ELDER)
            }
        }
    }

    /**
     * A revoked binding ends the local role session. Credentials and role-completion flags are removed
     * together so the next screen and the next cold start both begin at role selection.
     */
    private suspend fun resetAfterBindingInvalidation(
        role: AppRole,
        preserveFamilySession: Boolean = false,
        recoveredFamilyElderId: String? = null,
        familyBindingWasRevoked: Boolean = role == AppRole.FAMILY,
    ) {
        if (role == AppRole.ELDER) {
            runCatching { credentialStore?.clearDeviceCredential() }
            runCatching { agentLongTermMemory?.clearFamilyContacts() }
        } else if (!preserveFamilySession) {
            runCatching { credentialStore?.clearFamilySession() }
        }
        runCatching {
            appSessionStore?.update { session ->
                when (role) {
                    AppRole.ELDER -> session.copy(
                        defaultRole = null,
                        elderOnboardingCompleted = false,
                        lastKnownElderBound = false,
                        lastSyncedAt = null,
                    )
                    AppRole.FAMILY -> session.copy(
                        defaultRole = null,
                        familyOnboardingCompleted = false,
                        lastKnownFamilyBound = false,
                        lastSyncedAt = null,
                        familyElderId = if (familyBindingWasRevoked) {
                            recoveredFamilyElderId
                                ?.takeIf(String::isNotBlank)
                                ?: session.familyElderId
                        } else {
                            null
                        },
                    )
                }
            }
        }
        _uiState.update {
            it.copy(
                startupDestination = StartupDestination.RoleSelection,
                hasFamilySession = if (role == AppRole.FAMILY) {
                    preserveFamilySession
                } else {
                    it.hasFamilySession
                },
                hasDeviceCredential = if (role == AppRole.ELDER) {
                    false
                } else {
                    it.hasDeviceCredential
                },
                elderBindingStatus = BindingPreparationStatus.NotPrepared,
                familyBindingStatus = BindingPreparationStatus.NotPrepared,
                familyBindingCode = null,
                familyBindingCodeExpiresAt = null,
                familyMobileMasked = null,
                familyElderId = if (role == AppRole.FAMILY && !familyBindingWasRevoked) {
                    null
                } else {
                    recoveredFamilyElderId
                        ?.takeIf(String::isNotBlank)
                        ?: it.familyElderId
                },
                lastSyncedAt = null,
                sessionConnectionStatus = SessionConnectionStatus.Invalid,
                sessionMessage = "绑定已失效，请重新选择使用身份并完成绑定。",
            )
        }
    }

    private fun showOfflineSession() {
        _uiState.update {
            it.copy(
                sessionConnectionStatus = SessionConnectionStatus.Offline,
                sessionMessage = "暂时无法连接中台，正在使用上次保存的状态。",
            )
        }
    }

    private fun relationshipFromSavedName(name: String): FamilyRelationship? =
        FamilyRelationship.entries.firstOrNull { it.name == name } ?: relationshipFromApi(name)

    private fun relationshipFromApi(value: String): FamilyRelationship? =
        FamilyRelationship.entries.firstOrNull { it.name.uppercase() == value.uppercase() }

    private fun relationshipNameFromApi(value: String): String? = relationshipFromApi(value)?.name

    private fun updateElderDraft(transform: ElderSetupDraft.() -> ElderSetupDraft) {
        _uiState.update { state ->
            state.copy(
                elderDraft = state.elderDraft.transform(),
                elderErrors = ElderSetupErrors(),
                networkMessage = null,
                persistenceMessage = null,
            )
        }
        scheduleElderPersistence()
    }

    private fun updateFamilyDraft(transform: FamilySetupDraft.() -> FamilySetupDraft) {
        _uiState.update { state ->
            state.copy(
                familyDraft = state.familyDraft.transform(),
                familyErrors = FamilySetupErrors(),
                networkMessage = null,
                persistenceMessage = null,
            )
        }
        scheduleFamilyPersistence()
    }

    private fun scheduleElderPersistence(delayMillis: Long = PERSISTENCE_DEBOUNCE_MILLIS) {
        val store = profileStore ?: return
        elderPersistenceJob?.cancel()
        elderPersistenceJob = workScope.launch {
            delay(delayMillis)
            val draft = _uiState.value.elderDraft
            try {
                store.saveElderProfile(
                    displayName = draft.displayName,
                    familyMobileNumber = draft.familyMobileNumber,
                    sharingConsent = draft.sharingConsent,
                )
            } catch (_: IOException) {
                showPersistenceFailure()
            }
        }
    }

    private fun scheduleFamilyPersistence(delayMillis: Long = PERSISTENCE_DEBOUNCE_MILLIS) {
        val store = profileStore ?: return
        familyPersistenceJob?.cancel()
        familyPersistenceJob = workScope.launch {
            delay(delayMillis)
            val draft = _uiState.value.familyDraft
            try {
                store.saveFamilyProfile(
                    displayName = draft.displayName,
                    mobileNumber = draft.mobileNumber,
                    elderDisplayName = draft.elderDisplayName,
                    elderMobileNumber = draft.elderMobileNumber,
                    relationshipName = draft.relationship?.name,
                    emergencyContact = draft.emergencyContact,
                )
            } catch (_: IOException) {
                showPersistenceFailure()
            }
        }
    }

    private fun showPersistenceFailure() {
        _uiState.update {
            it.copy(persistenceMessage = "测试资料保存失败，下次启动可能需要重新填写。")
        }
    }

    private fun showNetworkFailure(message: String) {
        _uiState.update {
            it.copy(
                isSubmitting = false,
                networkMessage = message,
            )
        }
    }

    class Factory(
        private val profileStore: OnboardingProfileStore,
        private val middleServerRepository: OnboardingMiddleServerRepository? = null,
        private val appSessionStore: AppSessionStore? = null,
        private val credentialStore: MiddleServerCredentialStore? = null,
        private val agentLongTermMemory: AgentLongTermMemory? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OnboardingViewModel::class.java))
            return OnboardingViewModel(
                profileStore = profileStore,
                middleServerRepository = middleServerRepository,
                appSessionStore = appSessionStore,
                credentialStore = credentialStore,
                agentLongTermMemory = agentLongTermMemory,
            ) as T
        }
    }

    private companion object {
        const val PERSISTENCE_DEBOUNCE_MILLIS = 300L
    }
}
