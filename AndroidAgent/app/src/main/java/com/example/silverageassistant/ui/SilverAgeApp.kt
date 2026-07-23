package com.example.silverageassistant.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.silverageassistant.ui.conversation.ConversationRoute
import com.example.silverageassistant.ui.conversation.ConversationViewModel
import com.example.silverageassistant.ui.family.FamilyContactsRoute
import com.example.silverageassistant.ui.family.FamilyContactsViewModel
import com.example.silverageassistant.ui.family.FamilyHomeScreen
import com.example.silverageassistant.ui.family.FamilyCommunicationViewModel
import com.example.silverageassistant.ui.family.FamilyNotificationRoute
import com.example.silverageassistant.ui.family.FamilyReminderRoute
import com.example.silverageassistant.ui.home.ElderHomeRoute
import com.example.silverageassistant.ui.home.ElderHomeScreen
import com.example.silverageassistant.ui.home.HomeWeatherViewModel
import com.example.silverageassistant.ui.navigation.AppDestination
import com.example.silverageassistant.ui.modelconfig.ModelConfigurationRoute
import com.example.silverageassistant.ui.modelconfig.ModelConfigurationViewModel
import com.example.silverageassistant.ui.onboarding.ElderSetupRoute
import com.example.silverageassistant.ui.onboarding.FamilySetupRoute
import com.example.silverageassistant.ui.onboarding.OnboardingViewModel
import com.example.silverageassistant.ui.onboarding.StartupDestination
import com.example.silverageassistant.ui.placeholder.FeaturePlaceholderScreen
import com.example.silverageassistant.ui.reminders.ReminderRoute
import com.example.silverageassistant.ui.reminders.ReminderViewModel
import com.example.silverageassistant.ui.role.RoleSelectionScreen
import com.example.silverageassistant.ui.settings.ModelApiKeyRoute
import com.example.silverageassistant.ui.settings.ModelApiKeyViewModel
import com.example.silverageassistant.ui.usage.FamilyUsageRoute
import com.example.silverageassistant.ui.usage.FamilyUsageViewModel
import com.example.silverageassistant.ui.safety.FamilyEmergencyAlertHost
import com.example.silverageassistant.ui.safety.FamilySafetyEventsRoute
import com.example.silverageassistant.ui.safety.SafetyMonitoringConfigurationRoute
import com.example.silverageassistant.ui.safety.SafetyMonitoringViewModel
import com.example.silverageassistant.data.session.AppRole

@Composable
fun SilverAgeApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    onboardingViewModel: OnboardingViewModel = viewModel(),
    familyCommunicationViewModel: FamilyCommunicationViewModel = viewModel(),
    familyContactsViewModel: FamilyContactsViewModel = viewModel(),
    reminderViewModel: ReminderViewModel = viewModel(),
    conversationViewModel: ConversationViewModel = viewModel(),
    modelConfigurationViewModel: ModelConfigurationViewModel? = null,
    modelApiKeyViewModel: ModelApiKeyViewModel? = null,
    homeWeatherViewModel: HomeWeatherViewModel? = null,
    familyUsageViewModel: FamilyUsageViewModel? = null,
    safetyMonitoringViewModel: SafetyMonitoringViewModel? = null,
) {
    val onboardingState by onboardingViewModel.uiState.collectAsState()
    val safetyState = if (safetyMonitoringViewModel != null) {
        val state by safetyMonitoringViewModel.uiState.collectAsState()
        state
    } else {
        com.example.silverageassistant.ui.safety.SafetyMonitoringUiState()
    }
    if (onboardingState.isStartupLoading) {
        RoleSelectionScreen(
            isLoading = true,
            onElderSelected = {},
            onFamilySelected = {},
            modifier = modifier,
        )
        return
    }
    LaunchedEffect(onboardingState.hasDeviceCredential) {
        if (onboardingState.hasDeviceCredential) {
            reminderViewModel.syncRemoteCommands()
            familyContactsViewModel.syncContacts()
            modelConfigurationViewModel?.syncElderConfiguration()
            safetyMonitoringViewModel?.syncElderConfiguration()
        }
    }
    NavHost(
        navController = navController,
        startDestination = onboardingState.startupDestination.toAppDestination().route,
        modifier = modifier,
    ) {
        composable(AppDestination.RoleSelection.route) {
            RoleSelectionScreen(
                isLoading = onboardingState.isRestoringProfiles,
                onElderSelected = {
                    navController.navigateOnce(
                        onboardingViewModel.selectRole(AppRole.ELDER).toAppDestination(),
                    )
                },
                onFamilySelected = {
                    navController.navigateOnce(
                        onboardingViewModel.selectRole(AppRole.FAMILY).toAppDestination(),
                    )
                },
            )
        }
        composable(AppDestination.ElderSetup.route) {
            ElderSetupRoute(
                onBack = { navController.popBackStack() },
                onCompleted = {
                    navController.navigate(AppDestination.ElderHome.route) {
                        popUpTo(AppDestination.RoleSelection.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                viewModel = onboardingViewModel,
            )
        }
        composable(AppDestination.FamilySetup.route) {
            val isEditing = navController.previousBackStackEntry?.destination?.route ==
                AppDestination.FamilyHome.route
            FamilySetupRoute(
                onBack = { navController.popBackStack() },
                onCompleted = {
                    if (isEditing) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(AppDestination.FamilyHome.route) {
                            popUpTo(AppDestination.RoleSelection.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
                viewModel = onboardingViewModel,
            )
        }
        composable(AppDestination.ElderHome.route) {
            val onConversation = {
                navController.navigateOnce(AppDestination.Conversation)
            }
            val onReminders = {
                navController.navigateOnce(AppDestination.Reminders)
            }
            val onLifeAssistant = {
                navController.navigateOnce(AppDestination.LifeAssistant)
            }
            val onFamilyContacts = {
                navController.navigateOnce(AppDestination.FamilyContacts)
            }
            val onMusic = { navController.navigateOnce(AppDestination.Music) }
            val onSos = { navController.navigateOnce(AppDestination.Sos) }
            val onSettings = { navController.navigateOnce(AppDestination.Settings) }
            if (homeWeatherViewModel == null) {
                ElderHomeScreen(
                    elderName = onboardingState.elderDraft.displayName.trim(),
                    sessionConnectionStatus = onboardingState.sessionConnectionStatus,
                    sessionMessage = onboardingState.sessionMessage,
                    onConversation = onConversation,
                    onReminders = onReminders,
                    onLifeAssistant = onLifeAssistant,
                    onFamilyContacts = onFamilyContacts,
                    onMusic = onMusic,
                    onSos = onSos,
                    onSettings = onSettings,
                )
            } else {
                ElderHomeRoute(
                    elderName = onboardingState.elderDraft.displayName.trim(),
                    sessionConnectionStatus = onboardingState.sessionConnectionStatus,
                    sessionMessage = onboardingState.sessionMessage,
                    onConversation = onConversation,
                    onReminders = onReminders,
                    onLifeAssistant = onLifeAssistant,
                    onFamilyContacts = onFamilyContacts,
                    onMusic = onMusic,
                    onSos = onSos,
                    onSettings = onSettings,
                    weatherViewModel = homeWeatherViewModel,
                )
            }
        }
        composable(AppDestination.FamilyHome.route) {
            LaunchedEffect(onboardingState.familyElderId) {
                safetyMonitoringViewModel?.loadForFamily(onboardingState.familyElderId)
            }
            FamilyHomeScreen(
                profile = onboardingState.familyDraft,
                bindingStatus = onboardingState.familyBindingStatus,
                bindingCode = onboardingState.familyBindingCode,
                bindingCodeExpiresAt = onboardingState.familyBindingCodeExpiresAt,
                lastSyncedAt = onboardingState.lastSyncedAt,
                sessionConnectionStatus = onboardingState.sessionConnectionStatus,
                sessionMessage = onboardingState.sessionMessage,
                isRegeneratingBindingCode = onboardingState.isSubmitting,
                operationMessage = onboardingState.networkMessage,
                onEditProfile = { navController.navigateOnce(AppDestination.FamilySetup) },
                onRegenerateBindingCode = onboardingViewModel::regenerateFamilyBindingCode,
                onSendNotification = {
                    navController.navigateOnce(AppDestination.FamilyNotification)
                },
                onCreateReminder = {
                    navController.navigateOnce(AppDestination.FamilyReminder)
                },
                onModelConfiguration = {
                    navController.navigateOnce(AppDestination.FamilyModelConfiguration)
                },
                onModelUsage = {
                    navController.navigateOnce(AppDestination.FamilyModelUsage)
                },
                onTodayStatus = {
                    navController.navigateOnce(AppDestination.FamilyTodayStatus)
                },
                onSafetyMonitoringConfiguration = {
                    navController.navigateOnce(AppDestination.FamilySafetyMonitoringConfiguration)
                },
                onEmergencyEvents = {
                    navController.navigateOnce(AppDestination.FamilyEmergencyEvents)
                },
            )
        }
        composable(AppDestination.FamilyNotification.route) {
            FamilyNotificationRoute(
                elderId = onboardingState.familyElderId,
                elderDisplayName = onboardingState.familyDraft.elderDisplayName,
                onBack = { navController.popBackStack() },
                viewModel = familyCommunicationViewModel,
            )
        }
        composable(AppDestination.FamilyReminder.route) {
            FamilyReminderRoute(
                elderId = onboardingState.familyElderId,
                elderDisplayName = onboardingState.familyDraft.elderDisplayName,
                onBack = { navController.popBackStack() },
                viewModel = familyCommunicationViewModel,
            )
        }
        composable(AppDestination.FamilyModelConfiguration.route) {
            if (modelConfigurationViewModel == null) {
                FeaturePlaceholderScreen(
                    title = "模型配置",
                    message = "模型配置组件尚未初始化。",
                    icon = Icons.Rounded.Settings,
                    onBack = { navController.popBackStack() },
                )
            } else {
                ModelConfigurationRoute(
                    elderId = onboardingState.familyElderId,
                    onBack = { navController.popBackStack() },
                    viewModel = modelConfigurationViewModel,
                )
            }
        }
        composable(AppDestination.FamilyModelUsage.route) {
            if (familyUsageViewModel == null) {
                FeaturePlaceholderScreen(
                    title = "模型用量",
                    message = "模型用量组件尚未初始化。",
                    icon = Icons.Rounded.Settings,
                    onBack = { navController.popBackStack() },
                )
            } else {
                FamilyUsageRoute(
                    elderId = onboardingState.familyElderId,
                    onBack = { navController.popBackStack() },
                    viewModel = familyUsageViewModel,
                )
            }
        }
        composable(AppDestination.FamilyTodayStatus.route) {
            if (safetyMonitoringViewModel == null) {
                FeaturePlaceholderScreen(
                    title = "今日状态",
                    message = "安全状态组件尚未初始化。",
                    icon = Icons.Rounded.Settings,
                    onBack = { navController.popBackStack() },
                )
            } else {
                FamilySafetyEventsRoute(
                    elderId = onboardingState.familyElderId,
                    emergencyOnly = false,
                    onBack = { navController.popBackStack() },
                    viewModel = safetyMonitoringViewModel,
                )
            }
        }
        composable(AppDestination.FamilySafetyMonitoringConfiguration.route) {
            if (safetyMonitoringViewModel == null) {
                FeaturePlaceholderScreen(
                    title = "状态检测设置",
                    message = "安全状态组件尚未初始化。",
                    icon = Icons.Rounded.Settings,
                    onBack = { navController.popBackStack() },
                )
            } else {
                SafetyMonitoringConfigurationRoute(
                    elderId = onboardingState.familyElderId,
                    onBack = { navController.popBackStack() },
                    viewModel = safetyMonitoringViewModel,
                )
            }
        }
        composable(AppDestination.FamilyEmergencyEvents.route) {
            if (safetyMonitoringViewModel == null) {
                FeaturePlaceholderScreen(
                    title = "紧急事件",
                    message = "安全状态组件尚未初始化。",
                    icon = Icons.Rounded.Settings,
                    onBack = { navController.popBackStack() },
                )
            } else {
                FamilySafetyEventsRoute(
                    elderId = onboardingState.familyElderId,
                    emergencyOnly = true,
                    onBack = { navController.popBackStack() },
                    viewModel = safetyMonitoringViewModel,
                )
            }
        }
        composable(AppDestination.Conversation.route) {
            LaunchedEffect(Unit) {
                modelConfigurationViewModel?.syncElderConfiguration()
            }
            ConversationRoute(
                onBack = { navController.popBackStack() },
                viewModel = conversationViewModel,
            )
        }
        composable(AppDestination.Reminders.route) {
            ReminderRoute(
                onBack = { navController.popBackStack() },
                onContactFamily = { navController.navigateOnce(AppDestination.FamilyContacts) },
                viewModel = reminderViewModel,
            )
        }
        composable(AppDestination.FamilyContacts.route) {
            FamilyContactsRoute(
                onBack = { navController.popBackStack() },
                viewModel = familyContactsViewModel,
            )
        }
        composable(AppDestination.LifeAssistant.route) {
            FeaturePlaceholderScreen(
                title = "生活助手",
                message = "以后可以在这里用语音查询生活信息，并在确认后协助完成操作。",
                icon = Icons.Rounded.ShoppingBag,
                onBack = { navController.popBackStack() },
            )
        }
        composable(AppDestination.Music.route) {
            FeaturePlaceholderScreen(
                title = "听音乐",
                message = "以后可以在这里播放手机中已授权的本地音乐，断网时也能使用。",
                icon = Icons.Rounded.MusicNote,
                onBack = { navController.popBackStack() },
            )
        }
        composable(AppDestination.Sos.route) {
            FeaturePlaceholderScreen(
                title = "紧急求助",
                message = "紧急求助的本地拨号、短信和取消倒计时将在安全里程碑中接入。",
                icon = Icons.Rounded.Sos,
                importantMessage = "如现在需要急救，请直接拨打 120。",
                onBack = { navController.popBackStack() },
            )
        }
        composable(AppDestination.Settings.route) {
            if (modelApiKeyViewModel == null) {
                FeaturePlaceholderScreen(
                    title = "模型服务设置",
                    message = "模型密钥设置组件尚未初始化。",
                    icon = Icons.Rounded.Settings,
                    onBack = { navController.popBackStack() },
                )
            } else {
                ModelApiKeyRoute(
                    onBack = { navController.popBackStack() },
                    viewModel = modelApiKeyViewModel,
                )
            }
        }
    }
    if (onboardingState.hasFamilySession) {
        FamilyEmergencyAlertHost(
            state = safetyState,
            onAcknowledged = { event ->
                safetyMonitoringViewModel?.acknowledgeEmergency(event)
            },
        )
    }
}

private fun StartupDestination.toAppDestination(): AppDestination = when (this) {
    StartupDestination.RoleSelection -> AppDestination.RoleSelection
    StartupDestination.ElderSetup -> AppDestination.ElderSetup
    StartupDestination.FamilySetup -> AppDestination.FamilySetup
    StartupDestination.ElderHome -> AppDestination.ElderHome
    StartupDestination.FamilyHome -> AppDestination.FamilyHome
}

private fun NavHostController.navigateOnce(destination: AppDestination) {
    navigate(destination.route) { launchSingleTop = true }
}
