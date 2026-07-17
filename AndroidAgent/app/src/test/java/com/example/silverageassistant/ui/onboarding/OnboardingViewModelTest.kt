package com.example.silverageassistant.ui.onboarding

import com.example.silverageassistant.data.middleserver.ElderBindingRequest
import com.example.silverageassistant.data.middleserver.ElderBindingResult
import com.example.silverageassistant.data.middleserver.FamilyOnboardingRequest
import com.example.silverageassistant.data.middleserver.FamilyOnboardingResult
import com.example.silverageassistant.data.middleserver.FamilySession
import com.example.silverageassistant.data.middleserver.InMemoryMiddleServerCredentialStore
import com.example.silverageassistant.data.middleserver.OnboardingMiddleServerRepository
import com.example.silverageassistant.data.middleserver.RestoredBinding
import com.example.silverageassistant.data.middleserver.SessionRestoreResult
import com.example.silverageassistant.data.middleserver.SessionRestoreStatus
import com.example.silverageassistant.data.session.AppRole
import com.example.silverageassistant.data.session.InMemoryAppSessionStore
import com.example.silverageassistant.data.session.PersistedAppSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingViewModelTest {
    @Test
    fun savedFamilySession_startsAtFamilyHomeWithoutRegisteringAgain() {
        val repository = FakeSessionRepository(
            familyRestore = SessionRestoreResult(
                status = SessionRestoreStatus.ACTIVE,
                binding = RestoredBinding("王阿姨", "小林", "CHILD"),
            ),
        )
        val viewModel = OnboardingViewModel(
            middleServerRepository = repository,
            appSessionStore = InMemoryAppSessionStore(
                PersistedAppSession(
                    defaultRole = AppRole.FAMILY,
                    familyOnboardingCompleted = true,
                ),
            ),
            credentialStore = InMemoryMiddleServerCredentialStore(
                familySession = familySession(),
            ),
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(StartupDestination.FamilyHome, viewModel.uiState.value.startupDestination)
        assertEquals(BindingPreparationStatus.Bound, viewModel.uiState.value.familyBindingStatus)
        assertEquals(0, repository.registrationCalls)
        assertEquals(1, repository.familyRestoreCalls)
    }

    @Test
    fun savedDeviceCredential_startsAtElderHomeWithoutBindingAgain() {
        val repository = FakeSessionRepository(
            elderRestore = SessionRestoreResult(
                status = SessionRestoreStatus.ACTIVE,
                binding = RestoredBinding("王阿姨", "小林", "CHILD"),
            ),
        )
        val viewModel = OnboardingViewModel(
            middleServerRepository = repository,
            appSessionStore = InMemoryAppSessionStore(
                PersistedAppSession(
                    defaultRole = AppRole.ELDER,
                    elderOnboardingCompleted = true,
                    lastKnownElderBound = true,
                ),
            ),
            credentialStore = InMemoryMiddleServerCredentialStore(
                deviceCredential = "test-device-credential",
            ),
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(StartupDestination.ElderHome, viewModel.uiState.value.startupDestination)
        assertEquals(BindingPreparationStatus.Bound, viewModel.uiState.value.elderBindingStatus)
        assertEquals(0, repository.bindingCalls)
        assertEquals(1, repository.elderRestoreCalls)
    }

    @Test
    fun legacyCredentials_withoutAppState_migrateToLastBoundElderRole() {
        val viewModel = OnboardingViewModel(
            middleServerRepository = FakeSessionRepository(
                elderRestore = SessionRestoreResult(
                    SessionRestoreStatus.ACTIVE,
                    RestoredBinding("王阿姨", "小林", "CHILD"),
                ),
            ),
            appSessionStore = InMemoryAppSessionStore(),
            credentialStore = InMemoryMiddleServerCredentialStore(
                familySession = familySession(),
                deviceCredential = "test-device-credential",
            ),
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(StartupDestination.ElderHome, viewModel.uiState.value.startupDestination)
    }

    @Test
    fun offlineRestore_keepsCompletedFamilyOnHome() {
        val viewModel = OnboardingViewModel(
            middleServerRepository = FakeSessionRepository(
                familyRestore = SessionRestoreResult(SessionRestoreStatus.OFFLINE),
            ),
            appSessionStore = InMemoryAppSessionStore(
                PersistedAppSession(
                    defaultRole = AppRole.FAMILY,
                    familyOnboardingCompleted = true,
                    lastKnownFamilyBound = true,
                ),
            ),
            credentialStore = InMemoryMiddleServerCredentialStore(
                familySession = familySession(),
            ),
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(StartupDestination.FamilyHome, viewModel.uiState.value.startupDestination)
        assertEquals(SessionConnectionStatus.Offline, viewModel.uiState.value.sessionConnectionStatus)
        assertEquals(BindingPreparationStatus.Bound, viewModel.uiState.value.familyBindingStatus)
    }

    @Test
    fun invalidDeviceCredential_keepsElderHomeAndShowsRebindState() {
        val credentials = InMemoryMiddleServerCredentialStore(
            deviceCredential = "invalid-device-credential",
        )
        val viewModel = OnboardingViewModel(
            middleServerRepository = FakeSessionRepository(
                elderRestore = SessionRestoreResult(SessionRestoreStatus.INVALID),
            ),
            appSessionStore = InMemoryAppSessionStore(
                PersistedAppSession(
                    defaultRole = AppRole.ELDER,
                    elderOnboardingCompleted = true,
                    lastKnownElderBound = true,
                ),
            ),
            credentialStore = credentials,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(StartupDestination.ElderHome, viewModel.uiState.value.startupDestination)
        assertEquals(SessionConnectionStatus.Invalid, viewModel.uiState.value.sessionConnectionStatus)
        assertFalse(viewModel.uiState.value.hasDeviceCredential)
    }

    @Test
    fun bothCredentials_followPersistedDefaultRole() {
        val viewModel = OnboardingViewModel(
            middleServerRepository = FakeSessionRepository(
                familyRestore = SessionRestoreResult(
                    SessionRestoreStatus.ACTIVE,
                    RestoredBinding("王阿姨", "小林", "CHILD"),
                ),
            ),
            appSessionStore = InMemoryAppSessionStore(
                PersistedAppSession(
                    defaultRole = AppRole.FAMILY,
                    elderOnboardingCompleted = true,
                    familyOnboardingCompleted = true,
                ),
            ),
            credentialStore = InMemoryMiddleServerCredentialStore(
                familySession = familySession(),
                deviceCredential = "test-device-credential",
            ),
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(StartupDestination.FamilyHome, viewModel.uiState.value.startupDestination)
    }

    @Test
    fun validFamilySetup_isPreparedForServerBinding() {
        val viewModel = OnboardingViewModel()
        viewModel.updateFamilyName("小林")
        viewModel.updateMobileNumber(validTestMobile())
        viewModel.updateElderDisplayName("王阿姨")
        viewModel.updateFamilyElderMobileNumber(validTestMobile(lastDigit = '1'))
        viewModel.updateRelationship(FamilyRelationship.Child)

        assertTrue(viewModel.submitFamilySetup())
        assertEquals(
            BindingPreparationStatus.AwaitingCodeGeneration,
            viewModel.uiState.value.familyBindingStatus,
        )
    }

    @Test
    fun elderBindingPair_isPreparedForJointServerVerification() {
        val viewModel = OnboardingViewModel()
        viewModel.updateElderName("王阿姨")
        viewModel.updateElderFamilyMobileNumber(validTestMobile())
        viewModel.updateBindingCode("654321")
        viewModel.updateSharingConsent(true)

        assertTrue(viewModel.submitElderSetup())
        assertEquals(
            BindingPreparationStatus.PendingJointVerification,
            viewModel.uiState.value.elderBindingStatus,
        )
    }

    @Test
    fun elderCanEnterWithoutBindingCode_butIsNotMarkedPrepared() {
        val viewModel = OnboardingViewModel()
        viewModel.updateElderName("王阿姨")

        assertTrue(viewModel.submitElderSetup())
        assertEquals(
            BindingPreparationStatus.NotPrepared,
            viewModel.uiState.value.elderBindingStatus,
        )
    }

    @Test
    fun invalidFamilySetup_doesNotPrepareBinding() {
        val viewModel = OnboardingViewModel()

        assertFalse(viewModel.submitFamilySetup())
        assertEquals(
            BindingPreparationStatus.NotPrepared,
            viewModel.uiState.value.familyBindingStatus,
        )
    }

    private fun validTestMobile(lastDigit: Char = '0'): String =
        "1" + "3".repeat(9) + lastDigit

    private fun familySession() = FamilySession(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token",
        accessTokenExpiresAt = "2026-07-16T12:00:00Z",
    )

    private class FakeSessionRepository(
        private val familyRestore: SessionRestoreResult =
            SessionRestoreResult(SessionRestoreStatus.MISSING),
        private val elderRestore: SessionRestoreResult =
            SessionRestoreResult(SessionRestoreStatus.MISSING),
    ) : OnboardingMiddleServerRepository {
        var registrationCalls = 0
        var bindingCalls = 0
        var familyRestoreCalls = 0
        var elderRestoreCalls = 0

        override suspend fun registerFamilyAndCreateBindingCode(
            request: FamilyOnboardingRequest,
        ): FamilyOnboardingResult {
            registrationCalls += 1
            error("Registration should not be called during restore")
        }

        override suspend fun bindElderDevice(request: ElderBindingRequest): ElderBindingResult {
            bindingCalls += 1
            error("Binding should not be called during restore")
        }

        override suspend fun restoreFamilySession(): SessionRestoreResult {
            familyRestoreCalls += 1
            return familyRestore
        }

        override suspend fun restoreElderSession(): SessionRestoreResult {
            elderRestoreCalls += 1
            return elderRestore
        }
    }
}
