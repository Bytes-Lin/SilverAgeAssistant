package com.example.silverageassistant

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import com.example.silverageassistant.data.middleserver.FamilySession
import com.example.silverageassistant.data.middleserver.InMemoryMiddleServerCredentialStore
import com.example.silverageassistant.data.middleserver.OnboardingMiddleServerRepository
import com.example.silverageassistant.data.middleserver.RestoredBinding
import com.example.silverageassistant.data.middleserver.SessionRestoreResult
import com.example.silverageassistant.data.middleserver.SessionRestoreStatus
import com.example.silverageassistant.data.session.AppRole
import com.example.silverageassistant.data.session.InMemoryAppSessionStore
import com.example.silverageassistant.data.session.PersistedAppSession
import com.example.silverageassistant.ui.SilverAgeApp
import com.example.silverageassistant.ui.onboarding.OnboardingViewModel
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import org.junit.Rule
import org.junit.Test

class SessionRestoreNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun savedFamilySession_opensFamilyHomeWithoutShowingSetup() {
        val viewModel = OnboardingViewModel(
            middleServerRepository = RestoredSessionRepository,
            appSessionStore = InMemoryAppSessionStore(
                PersistedAppSession(
                    defaultRole = AppRole.FAMILY,
                    familyOnboardingCompleted = true,
                    lastKnownFamilyBound = true,
                    familyDisplayName = "小林",
                    familyElderDisplayName = "王阿姨",
                    familyRelationshipName = "Child",
                ),
            ),
            credentialStore = InMemoryMiddleServerCredentialStore(
                familySession = FamilySession(
                    accessToken = "test-access-token",
                    refreshToken = "test-refresh-token",
                    accessTokenExpiresAt = "2026-07-16T12:00:00Z",
                ),
            ),
        )
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    SilverAgeApp(onboardingViewModel = viewModel)
                }
            }
        }

        composeRule.onNodeWithText("家属模式").assertIsDisplayed()
        composeRule.onAllNodesWithText("填写家属和老人信息").assertCountEquals(0)
    }

    @Test
    fun savedDeviceCredential_opensElderHomeWithoutShowingSetup() {
        val viewModel = OnboardingViewModel(
            middleServerRepository = RestoredSessionRepository,
            appSessionStore = InMemoryAppSessionStore(
                PersistedAppSession(
                    defaultRole = AppRole.ELDER,
                    elderOnboardingCompleted = true,
                    lastKnownElderBound = true,
                    elderDisplayName = "王阿姨",
                ),
            ),
            credentialStore = InMemoryMiddleServerCredentialStore(
                deviceCredential = "test-device-credential",
            ),
        )
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                SilverAgeAssistantTheme {
                    SilverAgeApp(onboardingViewModel = viewModel)
                }
            }
        }

        composeRule.onNodeWithText("今日提醒").assertIsDisplayed()
        composeRule.onAllNodesWithText("先填写简单信息").assertCountEquals(0)
    }

    private object RestoredSessionRepository : OnboardingMiddleServerRepository {
        override suspend fun registerFamilyAndCreateBindingCode(
            request: com.example.silverageassistant.data.middleserver.FamilyOnboardingRequest,
        ) = error("Registration must not run during restore")

        override suspend fun bindElderDevice(
            request: com.example.silverageassistant.data.middleserver.ElderBindingRequest,
        ) = error("Binding must not run during restore")

        override suspend fun restoreFamilySession() = SessionRestoreResult(
            SessionRestoreStatus.ACTIVE,
            RestoredBinding("王阿姨", "小林", "CHILD"),
        )

        override suspend fun restoreElderSession() = SessionRestoreResult(
            SessionRestoreStatus.ACTIVE,
            RestoredBinding("王阿姨", "小林", "CHILD"),
        )
    }
}
