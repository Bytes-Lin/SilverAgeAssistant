package com.example.silverageassistant.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.data.middleserver.ElderBindingRequest
import com.example.silverageassistant.data.middleserver.FamilyOnboardingRequest
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import com.example.silverageassistant.data.middleserver.OnboardingMiddleServerRepository
import com.example.silverageassistant.data.onboarding.OnboardingProfileStore
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val profileStore: OnboardingProfileStore? = null,
    private val middleServerRepository: OnboardingMiddleServerRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        OnboardingUiState(isRestoringProfiles = profileStore != null),
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var elderPersistenceJob: Job? = null
    private var familyPersistenceJob: Job? = null

    init {
        if (profileStore != null) restoreProfiles(profileStore)
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
            onCompleted()
            return true
        }

        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            try {
                val result = middleServerRepository.bindElderDevice(
                    ElderBindingRequest(
                        displayName = draft.displayName.trim(),
                        familyMobileNumber = draft.familyMobileNumber,
                        bindingCode = draft.bindingCode,
                        sharingConsent = draft.sharingConsent,
                    ),
                )
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        elderBindingStatus = BindingPreparationStatus.Bound,
                        familyMobileMasked = result.familyMobileMasked,
                        lastSyncedAt = Instant.now().toString(),
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
            onCompleted()
            return true
        }

        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            try {
                val result = middleServerRepository.registerFamilyAndCreateBindingCode(
                    FamilyOnboardingRequest(
                        displayName = draft.displayName.trim(),
                        mobileNumber = draft.mobileNumber,
                        elderDisplayName = draft.elderDisplayName.trim(),
                        elderMobileNumber = draft.elderMobileNumber,
                        relationship = requireNotNull(draft.relationship).name.uppercase(),
                        emergencyContact = draft.emergencyContact,
                    ),
                )
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        familyBindingStatus = BindingPreparationStatus.CodeGenerated,
                        familyBindingCode = result.bindingCode,
                        familyBindingCodeExpiresAt = result.bindingCodeExpiresAt,
                        familyMobileMasked = result.familyMobileMasked,
                        lastSyncedAt = Instant.now().toString(),
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

    private fun restoreProfiles(store: OnboardingProfileStore) {
        viewModelScope.launch {
            try {
                val saved = store.profiles.first()
                val relationship = saved.familyRelationshipName?.let { name ->
                    FamilyRelationship.entries.firstOrNull { it.name == name }
                }
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
                            relationship = relationship,
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
        elderPersistenceJob = viewModelScope.launch {
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
        familyPersistenceJob = viewModelScope.launch {
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OnboardingViewModel::class.java))
            return OnboardingViewModel(profileStore, middleServerRepository) as T
        }
    }

    private companion object {
        const val PERSISTENCE_DEBOUNCE_MILLIS = 300L
    }
}
