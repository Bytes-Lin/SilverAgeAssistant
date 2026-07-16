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
import com.example.silverageassistant.data.onboarding.PreferencesOnboardingProfileStore
import com.example.silverageassistant.ui.SilverAgeApp
import com.example.silverageassistant.ui.onboarding.OnboardingViewModel
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val profileStore = remember {
                PreferencesOnboardingProfileStore(applicationContext)
            }
            val middleServerRepository = remember {
                BuildConfig.MIDDLE_SERVER_BASE_URL.takeIf(String::isNotBlank)?.let { baseUrl ->
                    HttpOnboardingMiddleServerRepository(
                        serverBaseUrl = baseUrl,
                        credentialStore = AndroidKeystoreCredentialStore(applicationContext),
                        deviceId = DeviceIdentityProvider(applicationContext).getOrCreate(),
                        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    )
                }
            }
            val onboardingViewModel: OnboardingViewModel = viewModel(
                factory = OnboardingViewModel.Factory(profileStore, middleServerRepository),
            )
            SilverAgeAssistantTheme {
                SilverAgeApp(onboardingViewModel = onboardingViewModel)
            }
        }
    }
}
