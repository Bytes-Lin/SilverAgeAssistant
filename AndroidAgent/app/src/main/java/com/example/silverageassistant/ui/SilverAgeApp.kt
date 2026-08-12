package com.example.silverageassistant.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Sos
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.silverageassistant.ui.conversation.ConversationRoute
import com.example.silverageassistant.ui.conversation.ConversationViewModel
import com.example.silverageassistant.ui.family.FamilyContactsRoute
import com.example.silverageassistant.ui.family.FamilyContactsViewModel
import com.example.silverageassistant.ui.family.FamilyHomeScreen
import com.example.silverageassistant.ui.family.FamilyCommunicationViewModel
import com.example.silverageassistant.ui.family.FamilyNotificationRoute
import com.example.silverageassistant.ui.family.FamilyReminderRoute
import com.example.silverageassistant.ui.family.FamilyReminderHistoryRoute
import com.example.silverageassistant.ui.family.FamilyReminderHistoryViewModel
import com.example.silverageassistant.ui.home.ElderHomeRoute
import com.example.silverageassistant.ui.home.ElderHomeScreen
import com.example.silverageassistant.ui.home.HomeWeatherViewModel
import com.example.silverageassistant.ui.navigation.AppDestination
import com.example.silverageassistant.ui.news.NewsRoute
import com.example.silverageassistant.ui.news.NewsViewModel
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
import com.example.silverageassistant.ui.settings.VoiceSettingsViewModel
import com.example.silverageassistant.ui.usage.FamilyUsageRoute
import com.example.silverageassistant.ui.usage.FamilyUsageViewModel
import com.example.silverageassistant.ui.safety.FamilyEmergencyAlertHost
import com.example.silverageassistant.ui.safety.FamilySafetyEventsRoute
import com.example.silverageassistant.ui.safety.SafetyMonitoringConfigurationRoute
import com.example.silverageassistant.ui.safety.SafetyMonitoringViewModel
import com.example.silverageassistant.data.session.AppRole
import com.example.silverageassistant.domain.gui.GuiTargetAppLauncher
import com.example.silverageassistant.domain.gui.GuiTaskController
import com.example.silverageassistant.ui.gui.GuiTaskControlHost
import com.example.silverageassistant.ui.gui.GuiDebugPanelHost
import com.example.silverageassistant.BuildConfig
import com.example.silverageassistant.service.GuiTaskNavigationBridge

@Composable
fun SilverAgeApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    onboardingViewModel: OnboardingViewModel = viewModel(),
    familyCommunicationViewModel: FamilyCommunicationViewModel = viewModel(),
    familyReminderHistoryViewModel: FamilyReminderHistoryViewModel? = null,
    familyContactsViewModel: FamilyContactsViewModel = viewModel(),
    reminderViewModel: ReminderViewModel = viewModel(),
    conversationViewModel: ConversationViewModel = viewModel(),
    modelConfigurationViewModel: ModelConfigurationViewModel? = null,
    modelApiKeyViewModel: ModelApiKeyViewModel? = null,
    voiceSettingsViewModel: VoiceSettingsViewModel? = null,
    homeWeatherViewModel: HomeWeatherViewModel? = null,
    newsViewModel: NewsViewModel? = null,
    familyUsageViewModel: FamilyUsageViewModel? = null,
    safetyMonitoringViewModel: SafetyMonitoringViewModel? = null,
    guiTaskController: GuiTaskController? = null,
    guiTargetAppLauncher: GuiTargetAppLauncher? = null,
    onElderModeActiveChanged: (Boolean) -> Unit = {},
) {
    val onboardingState by onboardingViewModel.uiState.collectAsState()
    val todayReminders by reminderViewModel.reminders.collectAsState()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(currentRoute) {
        onElderModeActiveChanged(currentRoute in elderModeRoutes)
    }
    DisposableEffect(lifecycleOwner, currentRoute) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    onElderModeActiveChanged(currentRoute in elderModeRoutes)
                }
                Lifecycle.Event.ON_STOP -> onElderModeActiveChanged(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(
        lifecycleOwner,
        onboardingState.hasDeviceCredential,
        onboardingState.hasFamilySession,
        onboardingState.familyElderId,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                onboardingViewModel.refreshCurrentSession()
                if (onboardingState.hasDeviceCredential) {
                    reminderViewModel.syncRemoteCommands()
                }
                if (onboardingState.hasFamilySession) {
                    familyReminderHistoryViewModel?.refresh(onboardingState.familyElderId)
                    safetyMonitoringViewModel?.refreshCurrentEvents()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val guiConversationRequest by GuiTaskNavigationBridge.conversationRequest.collectAsState()
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
    LaunchedEffect(onboardingState.startupDestination, currentRoute) {
        if (
            onboardingState.startupDestination == StartupDestination.RoleSelection &&
            currentRoute != null &&
            currentRoute != AppDestination.RoleSelection.route
        ) {
            navController.navigate(AppDestination.RoleSelection.route) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(onboardingState.hasDeviceCredential) {
        if (onboardingState.hasDeviceCredential) {
            reminderViewModel.syncRemoteCommands()
            familyContactsViewModel.syncContacts()
            modelConfigurationViewModel?.syncElderConfiguration()
            safetyMonitoringViewModel?.syncElderConfiguration()
        }
    }
    LaunchedEffect(onboardingState.hasFamilySession, onboardingState.familyElderId) {
        if (onboardingState.hasFamilySession) {
            familyReminderHistoryViewModel?.refresh(onboardingState.familyElderId)
            safetyMonitoringViewModel?.loadForFamily(onboardingState.familyElderId)
        }
    }
    LaunchedEffect(guiConversationRequest) {
        val requestId = guiConversationRequest ?: return@LaunchedEffect
        navController.navigate(AppDestination.Conversation.route) {
            launchSingleTop = true
            popUpTo(AppDestination.ElderHome.route)
        }
        GuiTaskNavigationBridge.consumeConversationRequest(requestId)
    }
    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = onboardingState.startupDestination.toAppDestination().route,
            modifier = Modifier.fillMaxSize(),
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
            val onFamilyContacts = {
                navController.navigateOnce(AppDestination.FamilyContacts)
            }
            val onNews = { navController.navigateOnce(AppDestination.News) }
            val onSettings = { navController.navigateOnce(AppDestination.Settings) }
            if (homeWeatherViewModel == null) {
                ElderHomeScreen(
                    elderName = onboardingState.elderDraft.displayName.trim(),
                    sessionConnectionStatus = onboardingState.sessionConnectionStatus,
                    sessionMessage = onboardingState.sessionMessage,
                    todayReminders = todayReminders,
                    onConversation = onConversation,
                    onReminders = onReminders,
                    onFamilyContacts = onFamilyContacts,
                    onNews = onNews,
                    onSettings = onSettings,
                )
            } else {
                ElderHomeRoute(
                    elderName = onboardingState.elderDraft.displayName.trim(),
                    sessionConnectionStatus = onboardingState.sessionConnectionStatus,
                    sessionMessage = onboardingState.sessionMessage,
                    todayReminders = todayReminders,
                    onConversation = onConversation,
                    onReminders = onReminders,
                    onFamilyContacts = onFamilyContacts,
                    onNews = onNews,
                    onSettings = onSettings,
                    weatherViewModel = homeWeatherViewModel,
                )
            }
        }
        composable(AppDestination.FamilyHome.route) {
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
                onReminderHistory = {
                    navController.navigateOnce(AppDestination.FamilyReminderHistory)
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
                onVerifyBinding = onboardingViewModel::refreshCurrentSession,
                latestEmergencyEvent = safetyState.latestEmergencyEvent,
                emergencyTimeZone = safetyState.timeZone,
                isLoadingEmergencyEvents = safetyState.isLoadingEvents,
                emergencyEventsMessage = safetyState.eventsMessage,
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
        composable(AppDestination.FamilyReminderHistory.route) {
            if (familyReminderHistoryViewModel == null) {
                FeaturePlaceholderScreen(
                    title = "提醒记录",
                    message = "提醒记录组件尚未初始化。",
                    icon = Icons.AutoMirrored.Rounded.Article,
                    onBack = { navController.popBackStack() },
                )
            } else {
                FamilyReminderHistoryRoute(
                    elderId = onboardingState.familyElderId,
                    onBack = { navController.popBackStack() },
                    viewModel = familyReminderHistoryViewModel,
                )
            }
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
        composable(AppDestination.News.route) {
            if (newsViewModel == null) {
                FeaturePlaceholderScreen(
                    title = "新闻播报",
                    message = "新闻组件尚未初始化。",
                    icon = Icons.AutoMirrored.Rounded.Article,
                    onBack = { navController.popBackStack() },
                )
            } else {
                NewsRoute(
                    onBack = { navController.popBackStack() },
                    viewModel = newsViewModel,
                )
            }
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
            if (modelApiKeyViewModel == null || voiceSettingsViewModel == null) {
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
                    voiceSettingsViewModel = voiceSettingsViewModel,
                )
            }
        }
        }
        if (guiTaskController != null && guiTargetAppLauncher != null) {
            GuiTaskControlHost(
                controller = guiTaskController,
                targetAppLauncher = guiTargetAppLauncher,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (BuildConfig.GUI_DEBUG_ENABLED) {
            GuiDebugPanelHost(
                modifier = Modifier.align(Alignment.BottomEnd),
            )
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
}

private val elderModeRoutes = setOf(
    AppDestination.ElderSetup.route,
    AppDestination.ElderHome.route,
    AppDestination.Conversation.route,
    AppDestination.Reminders.route,
    AppDestination.LifeAssistant.route,
    AppDestination.FamilyContacts.route,
    AppDestination.News.route,
    AppDestination.Sos.route,
    AppDestination.Settings.route,
)

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
