package com.example.silverageassistant

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.silverageassistant.data.middleserver.AndroidKeystoreCredentialStore
import com.example.silverageassistant.data.middleserver.DeviceIdentityProvider
import com.example.silverageassistant.data.middleserver.HttpOnboardingMiddleServerRepository
import com.example.silverageassistant.data.contacts.EncryptedFamilyContactStore
import com.example.silverageassistant.data.onboarding.PreferencesOnboardingProfileStore
import com.example.silverageassistant.data.reminders.RoomReminderRepository
import com.example.silverageassistant.data.reminders.SilverAgeDatabase
import com.example.silverageassistant.data.session.PreferencesAppSessionStore
import com.example.silverageassistant.ui.SilverAgeApp
import com.example.silverageassistant.ui.onboarding.OnboardingViewModel
import com.example.silverageassistant.ui.family.FamilyCommunicationViewModel
import com.example.silverageassistant.ui.family.FamilyContactsViewModel
import com.example.silverageassistant.ui.reminders.ReminderViewModel
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val profileStore = remember {
                PreferencesOnboardingProfileStore(applicationContext)
            }
            val appSessionStore = remember {
                PreferencesAppSessionStore(applicationContext)
            }
            val credentialStore = remember {
                AndroidKeystoreCredentialStore(applicationContext)
            }
            val reminderRepository = remember {
                RoomReminderRepository(
                    SilverAgeDatabase.getInstance(applicationContext).reminderDao(),
                )
            }
            val familyContactStore = remember {
                EncryptedFamilyContactStore(applicationContext)
            }
            val middleServerRepository = remember {
                BuildConfig.MIDDLE_SERVER_BASE_URL.takeIf(String::isNotBlank)?.let { baseUrl ->
                    HttpOnboardingMiddleServerRepository(
                        serverBaseUrl = baseUrl,
                        credentialStore = credentialStore,
                        deviceId = DeviceIdentityProvider(applicationContext).getOrCreate(),
                        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    )
                }
            }
            val onboardingViewModel: OnboardingViewModel = viewModel(
                factory = OnboardingViewModel.Factory(
                    profileStore = profileStore,
                    middleServerRepository = middleServerRepository,
                    appSessionStore = appSessionStore,
                    credentialStore = credentialStore,
                ),
            )
            val familyCommunicationViewModel: FamilyCommunicationViewModel = viewModel(
                factory = FamilyCommunicationViewModel.Factory(middleServerRepository),
            )
            val reminderViewModel: ReminderViewModel = viewModel(
                factory = ReminderViewModel.Factory(
                    reminderRepository = reminderRepository,
                    commandRepository = middleServerRepository,
                ),
            )
            val familyContactsViewModel: FamilyContactsViewModel = viewModel(
                factory = FamilyContactsViewModel.Factory(
                    store = familyContactStore,
                    repository = middleServerRepository,
                ),
            )
            SilverAgeAssistantTheme {
                SilverAgeApp(
                    onboardingViewModel = onboardingViewModel,
                    familyCommunicationViewModel = familyCommunicationViewModel,
                    familyContactsViewModel = familyContactsViewModel,
                    reminderViewModel = reminderViewModel,
                )
            }
        }
    }
}
