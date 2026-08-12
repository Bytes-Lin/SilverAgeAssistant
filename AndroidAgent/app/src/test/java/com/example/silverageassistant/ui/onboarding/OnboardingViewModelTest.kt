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
import com.example.silverageassistant.domain.agent.AgentLongTermMemory
import com.example.silverageassistant.domain.agent.MemoryFamilyContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    fun boundFamily_regeneratesCodeWithoutLosingBoundStatus() {
        val repository = FakeSessionRepository(
            familyRestore = SessionRestoreResult(
                status = SessionRestoreStatus.ACTIVE,
                binding = RestoredBinding("王阿姨", "小林", "CHILD", elderId = "elder-1"),
            ),
            regeneratedCode = FamilyOnboardingResult(
                bindingCode = "112233",
                bindingCodeExpiresAt = "2026-07-17T12:00:00Z",
                familyMobileMasked = "133****3333",
                elderId = "elder-1",
            ),
        )
        val viewModel = OnboardingViewModel(
            middleServerRepository = repository,
            appSessionStore = InMemoryAppSessionStore(
                PersistedAppSession(
                    defaultRole = AppRole.FAMILY,
                    familyOnboardingCompleted = true,
                    lastKnownFamilyBound = true,
                    familyElderId = "elder-1",
                ),
            ),
            credentialStore = InMemoryMiddleServerCredentialStore(
                familySession = familySession(),
            ),
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(viewModel.regenerateFamilyBindingCode())

        assertEquals(1, repository.regenerationCalls)
        assertEquals("112233", viewModel.uiState.value.familyBindingCode)
        assertEquals(BindingPreparationStatus.Bound, viewModel.uiState.value.familyBindingStatus)
        assertEquals("新绑定码已生成，请在老人手机上填写。", viewModel.uiState.value.networkMessage)
    }

    @Test
    fun savedDeviceCredential_startsAtElderHomeWithoutBindingAgain() {
        val memory = RecordingAgentLongTermMemory()
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
            agentLongTermMemory = memory,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(StartupDestination.ElderHome, viewModel.uiState.value.startupDestination)
        assertEquals(BindingPreparationStatus.Bound, viewModel.uiState.value.elderBindingStatus)
        assertEquals(0, repository.bindingCalls)
        assertEquals(1, repository.elderRestoreCalls)
        assertEquals("王阿姨", memory.elderPreferredName)
        assertEquals("小林", memory.familyContacts.single().displayName)
        assertEquals("CHILD", memory.familyContacts.single().relationship)
    }

    @Test
    fun successfulElderBinding_writesInitialLongTermMemory() {
        val memory = RecordingAgentLongTermMemory()
        val repository = object : OnboardingMiddleServerRepository {
            override suspend fun registerFamilyAndCreateBindingCode(
                request: FamilyOnboardingRequest,
            ): FamilyOnboardingResult = error("Not used")

            override suspend fun bindElderDevice(
                request: ElderBindingRequest,
            ) = ElderBindingResult(
                familyMobileMasked = "138****8000",
                relationship = "CHILD",
                boundAt = "2026-07-18T03:00:00Z",
            )
        }
        val viewModel = OnboardingViewModel(
            middleServerRepository = repository,
            appSessionStore = InMemoryAppSessionStore(),
            agentLongTermMemory = memory,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )
        viewModel.updateElderName("王阿姨")
        viewModel.updateElderFamilyMobileNumber("13800138000")
        viewModel.updateBindingCode("123456")
        viewModel.updateSharingConsent(true)

        assertTrue(viewModel.submitElderSetup())

        assertEquals(BindingPreparationStatus.Bound, viewModel.uiState.value.elderBindingStatus)
        assertEquals("王阿姨", memory.elderPreferredName)
        assertEquals("已在本机安全保存", memory.familyContacts.single().contactHint)
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
    fun invalidDeviceCredential_resetsToRoleSelectionAndClearsLocalSession() = runBlocking {
        val credentials = InMemoryMiddleServerCredentialStore(
            deviceCredential = "invalid-device-credential",
        )
        val appSessionStore = InMemoryAppSessionStore(
            PersistedAppSession(
                defaultRole = AppRole.ELDER,
                elderOnboardingCompleted = true,
                lastKnownElderBound = true,
            ),
        )
        val viewModel = OnboardingViewModel(
            middleServerRepository = FakeSessionRepository(
                elderRestore = SessionRestoreResult(SessionRestoreStatus.INVALID),
            ),
            appSessionStore = appSessionStore,
            credentialStore = credentials,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(StartupDestination.RoleSelection, viewModel.uiState.value.startupDestination)
        assertEquals(SessionConnectionStatus.Invalid, viewModel.uiState.value.sessionConnectionStatus)
        assertFalse(viewModel.uiState.value.hasDeviceCredential)
        assertEquals(null, credentials.deviceCredential)
        assertEquals(PersistedAppSession(), appSessionStore.session.first())
    }

    @Test
    fun revokedFamilyBinding_resetsRoleButKeepsReusableFamilySession() = runBlocking {
        val credentials = InMemoryMiddleServerCredentialStore(
            familySession = familySession(),
        )
        val appSessionStore = InMemoryAppSessionStore(
            PersistedAppSession(
                defaultRole = AppRole.FAMILY,
                familyOnboardingCompleted = true,
                lastKnownFamilyBound = true,
                familyElderId = "elder-1",
            ),
        )
        val viewModel = OnboardingViewModel(
            middleServerRepository = FakeSessionRepository(
                familyRestore = SessionRestoreResult(
                    SessionRestoreStatus.INVALID,
                    RestoredBinding("王阿姨", "小林", "CHILD", elderId = "elder-1"),
                ),
            ),
            appSessionStore = appSessionStore,
            credentialStore = credentials,
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(StartupDestination.RoleSelection, viewModel.uiState.value.startupDestination)
        assertEquals(SessionConnectionStatus.Invalid, viewModel.uiState.value.sessionConnectionStatus)
        assertTrue(viewModel.uiState.value.hasFamilySession)
        assertEquals(familySession(), credentials.familySession)
        val saved = appSessionStore.session.first()
        assertEquals(null, saved.defaultRole)
        assertFalse(saved.familyOnboardingCompleted)
        assertFalse(saved.lastKnownFamilyBound)
        assertEquals("elder-1", saved.familyElderId)
    }

    @Test
    fun selectingRoleAfterInvalidation_leavesRoleSelectionState() {
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
            credentialStore = InMemoryMiddleServerCredentialStore(
                deviceCredential = "invalid-device-credential",
            ),
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertEquals(StartupDestination.RoleSelection, viewModel.uiState.value.startupDestination)

        val destination = viewModel.selectRole(AppRole.ELDER)

        assertEquals(StartupDestination.ElderSetup, destination)
        assertEquals(StartupDestination.ElderSetup, viewModel.uiState.value.startupDestination)
        assertEquals(SessionConnectionStatus.Unknown, viewModel.uiState.value.sessionConnectionStatus)
    }

    @Test
    fun familyRebindAfterRevocation_reusesSessionAndElderProfile() {
        val repository = FakeSessionRepository(
            familyRestore = SessionRestoreResult(
                SessionRestoreStatus.INVALID,
                RestoredBinding("王阿姨", "小林", "CHILD", elderId = "elder-1"),
            ),
            regeneratedCode = FamilyOnboardingResult(
                bindingCode = "223344",
                bindingCodeExpiresAt = "2026-08-12T13:00:00Z",
                familyMobileMasked = "138****8000",
                elderId = "elder-1",
            ),
        )
        val viewModel = OnboardingViewModel(
            middleServerRepository = repository,
            appSessionStore = InMemoryAppSessionStore(
                PersistedAppSession(
                    defaultRole = AppRole.FAMILY,
                    familyOnboardingCompleted = true,
                    lastKnownFamilyBound = true,
                    familyElderId = "elder-1",
                ),
            ),
            credentialStore = InMemoryMiddleServerCredentialStore(
                familySession = familySession(),
            ),
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )
        viewModel.selectRole(AppRole.FAMILY)
        viewModel.updateFamilyName("小林")
        viewModel.updateMobileNumber(validTestMobile())
        viewModel.updateElderDisplayName("王阿姨")
        viewModel.updateFamilyElderMobileNumber(validTestMobile(lastDigit = '1'))
        viewModel.updateRelationship(FamilyRelationship.Child)

        assertTrue(viewModel.submitFamilySetup())
        assertEquals(0, repository.registrationCalls)
        assertEquals(1, repository.regenerationCalls)
        assertEquals("223344", viewModel.uiState.value.familyBindingCode)
        assertEquals(StartupDestination.FamilyHome, viewModel.uiState.value.startupDestination)
    }

    @Test
    fun firstFamilyBinding_canBeVerifiedWithoutRestartingApp() {
        val repository = object : OnboardingMiddleServerRepository {
            override suspend fun registerFamilyAndCreateBindingCode(
                request: FamilyOnboardingRequest,
            ) = FamilyOnboardingResult(
                bindingCode = "334455",
                bindingCodeExpiresAt = "2026-08-12T13:00:00Z",
                familyMobileMasked = "138****8000",
                elderId = "elder-1",
            )

            override suspend fun bindElderDevice(
                request: ElderBindingRequest,
            ): ElderBindingResult = error("Not used")

            override suspend fun restoreFamilySession() = SessionRestoreResult(
                SessionRestoreStatus.ACTIVE,
                RestoredBinding("王阿姨", "小林", "CHILD", elderId = "elder-1"),
            )
        }
        val viewModel = OnboardingViewModel(
            middleServerRepository = repository,
            appSessionStore = InMemoryAppSessionStore(),
            credentialStore = InMemoryMiddleServerCredentialStore(),
            externalScope = CoroutineScope(Dispatchers.Unconfined),
        )
        viewModel.selectRole(AppRole.FAMILY)
        viewModel.updateFamilyName("小林")
        viewModel.updateMobileNumber(validTestMobile())
        viewModel.updateElderDisplayName("王阿姨")
        viewModel.updateFamilyElderMobileNumber(validTestMobile(lastDigit = '1'))
        viewModel.updateRelationship(FamilyRelationship.Child)
        assertTrue(viewModel.submitFamilySetup())
        assertEquals(BindingPreparationStatus.CodeGenerated, viewModel.uiState.value.familyBindingStatus)

        var verified = false
        viewModel.refreshCurrentSession { verified = it }

        assertTrue(verified)
        assertEquals(BindingPreparationStatus.Bound, viewModel.uiState.value.familyBindingStatus)
        assertEquals(StartupDestination.FamilyHome, viewModel.uiState.value.startupDestination)
    }

    @Test
    fun foregroundRefresh_detectsBindingInvalidationAfterSuccessfulRestore() {
        var restoreResult = SessionRestoreResult(
            SessionRestoreStatus.ACTIVE,
            RestoredBinding("王阿姨", "小林", "CHILD", elderId = "elder-1"),
        )
        val repository = object : OnboardingMiddleServerRepository {
            override suspend fun registerFamilyAndCreateBindingCode(
                request: FamilyOnboardingRequest,
            ): FamilyOnboardingResult = error("Not used")

            override suspend fun bindElderDevice(
                request: ElderBindingRequest,
            ): ElderBindingResult = error("Not used")

            override suspend fun restoreFamilySession(): SessionRestoreResult = restoreResult
        }
        val viewModel = OnboardingViewModel(
            middleServerRepository = repository,
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

        restoreResult = SessionRestoreResult(SessionRestoreStatus.INVALID)
        viewModel.refreshCurrentSession()

        assertEquals(StartupDestination.RoleSelection, viewModel.uiState.value.startupDestination)
        assertTrue(viewModel.uiState.value.hasFamilySession)
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
        private val regeneratedCode: FamilyOnboardingResult? = null,
    ) : OnboardingMiddleServerRepository {
        var registrationCalls = 0
        var regenerationCalls = 0
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

        override suspend fun regenerateBindingCode(elderId: String): FamilyOnboardingResult {
            regenerationCalls += 1
            return requireNotNull(regeneratedCode)
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

    private class RecordingAgentLongTermMemory : AgentLongTermMemory {
        var elderPreferredName: String = ""
        var familyContacts: List<MemoryFamilyContact> = emptyList()

        override suspend fun updateElderPreferredName(preferredName: String) {
            elderPreferredName = preferredName
        }

        override suspend fun recordBoundFamily(contact: MemoryFamilyContact) {
            if (familyContacts.isEmpty()) familyContacts = listOf(contact)
        }

        override suspend fun replaceFamilyContacts(contacts: List<MemoryFamilyContact>) {
            familyContacts = contacts
        }

        override suspend fun clearFamilyContacts() {
            familyContacts = emptyList()
        }

        override suspend fun appendMemory(note: String) = Unit

        override suspend fun markdownForPrompt(): String = ""
    }
}
