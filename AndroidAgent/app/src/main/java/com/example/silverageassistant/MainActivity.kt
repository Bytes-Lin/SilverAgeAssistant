package com.example.silverageassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.example.silverageassistant.data.middleserver.AndroidKeystoreCredentialStore
import com.example.silverageassistant.data.middleserver.DeviceIdentityProvider
import com.example.silverageassistant.data.middleserver.HttpOnboardingMiddleServerRepository
import com.example.silverageassistant.data.model.AndroidKeystoreModelApiCredentialStore
import com.example.silverageassistant.data.model.AndroidKeystoreVoiceApiCredentialStore
import com.example.silverageassistant.data.model.ConfiguredChatModelProvider
import com.example.silverageassistant.data.model.JsonModelConfigurationStore
import com.example.silverageassistant.data.model.ModelConfigurationAgentOptionsProvider
import com.example.silverageassistant.data.model.ModelRuntimeConfiguration
import com.example.silverageassistant.data.model.OpenAiCompatibleDialect
import com.example.silverageassistant.data.news.BaiduHotSearchNewsRepository
import com.example.silverageassistant.data.news.CachedNewsRepository
import com.example.silverageassistant.data.memory.MarkdownAgentLongTermMemory
import com.example.silverageassistant.data.contacts.EncryptedFamilyContactStore
import com.example.silverageassistant.data.onboarding.PreferencesOnboardingProfileStore
import com.example.silverageassistant.data.reminders.RoomReminderRepository
import com.example.silverageassistant.data.reminders.RemoteReminderReceivedNotificationPublisher
import com.example.silverageassistant.data.reminders.SilverAgeDatabase
import com.example.silverageassistant.data.reminders.WorkManagerReminderDeadlineScheduler
import com.example.silverageassistant.data.gui.RoomGuiTodoRepository
import com.example.silverageassistant.data.gui.OpenAiGuiVisionPlanner
import com.example.silverageassistant.data.session.PreferencesAppSessionStore
import com.example.silverageassistant.data.weather.AndroidDeviceLocationProvider
import com.example.silverageassistant.data.weather.AndroidLocationNameResolver
import com.example.silverageassistant.data.weather.BigDataCloudLocationNameResolver
import com.example.silverageassistant.data.weather.CachedWeatherRepository
import com.example.silverageassistant.data.weather.FallbackLocationNameResolver
import com.example.silverageassistant.data.weather.OpenMeteoWeatherDataSource
import com.example.silverageassistant.data.usage.ModelUsageReportWorker
import com.example.silverageassistant.data.usage.ElderUsageRealtimeClient
import com.example.silverageassistant.data.usage.LocationTimeZoneStore
import com.example.silverageassistant.data.usage.RoomModelUsageRecorder
import com.example.silverageassistant.data.usage.UsageTrackingChatModelProvider
import com.example.silverageassistant.data.usage.AgentUsageScope
import com.example.silverageassistant.data.safety.FamilySafetyRealtimeClient
import com.example.silverageassistant.data.safety.FamilyEmergencyNotificationPublisher
import com.example.silverageassistant.data.safety.JsonSafetyMonitoringConfigurationStore
import com.example.silverageassistant.data.voice.DataStoreVoiceInteractionSettingsStore
import com.example.silverageassistant.data.voice.QwenRealtimeAsrProvider
import com.example.silverageassistant.data.voice.QwenRealtimeTtsProvider
import com.example.silverageassistant.service.SafetyMonitoringService
import com.example.silverageassistant.service.GuiTaskRuntimeBridge
import com.example.silverageassistant.service.GuiAccessibilityRuntimeBridge
import com.example.silverageassistant.ui.SilverAgeApp
import com.example.silverageassistant.domain.agent.AgentChatCoordinator
import com.example.silverageassistant.domain.agent.AgentContextManager
import com.example.silverageassistant.domain.agent.AgentToolCatalog
import com.example.silverageassistant.domain.agent.AgentToolRegistry
import com.example.silverageassistant.domain.agent.CurrentTimeTool
import com.example.silverageassistant.domain.agent.CallFamilyContactTool
import com.example.silverageassistant.domain.agent.CompositeAgentDeterministicToolRouter
import com.example.silverageassistant.domain.agent.DefaultSystemPromptProvider
import com.example.silverageassistant.domain.agent.ModelAgentContextCompressor
import com.example.silverageassistant.domain.agent.ProcessAgentMemorySnapshotProvider
import com.example.silverageassistant.domain.agent.PendingPhoneCallCoordinator
import com.example.silverageassistant.domain.agent.FamilySituationReporter
import com.example.silverageassistant.domain.agent.ReportFamilySituationTool
import com.example.silverageassistant.domain.agent.WeatherTool
import com.example.silverageassistant.domain.agent.TodayRemindersTool
import com.example.silverageassistant.domain.agent.TodayRemindersMainAgentToolRouter
import com.example.silverageassistant.domain.agent.SharedAgentToolCapabilities
import com.example.silverageassistant.domain.gui.GuiAgentTool
import com.example.silverageassistant.domain.gui.DefaultGuiAgentSystemPromptProvider
import com.example.silverageassistant.domain.gui.FamilyReportingGuiFailureEscalationSink
import com.example.silverageassistant.domain.gui.GuiDebugSettings
import com.example.silverageassistant.domain.gui.GuiMainAgentToolRouter
import com.example.silverageassistant.domain.gui.NoOpGuiFailureEscalationSink
import com.example.silverageassistant.domain.gui.GuiTaskChatFeedbackBus
import com.example.silverageassistant.domain.gui.GuiTaskManager
import com.example.silverageassistant.domain.gui.GuiRunPhase
import com.example.silverageassistant.domain.voice.VoiceInteractionCoordinator
import com.example.silverageassistant.platform.gui.AccessibilityGuiRunExecutor
import com.example.silverageassistant.platform.gui.AndroidGuiTerminalTaskSink
import com.example.silverageassistant.platform.gui.AndroidGuiTargetAppLauncher
import com.example.silverageassistant.platform.phone.AndroidPhoneCallLauncher
import com.example.silverageassistant.ui.conversation.ConversationViewModel
import com.example.silverageassistant.ui.home.HomeWeatherViewModel
import com.example.silverageassistant.ui.news.NewsViewModel
import com.example.silverageassistant.ui.onboarding.OnboardingViewModel
import com.example.silverageassistant.ui.family.FamilyCommunicationViewModel
import com.example.silverageassistant.ui.family.FamilyContactsViewModel
import com.example.silverageassistant.ui.family.FamilyReminderHistoryViewModel
import com.example.silverageassistant.ui.reminders.ReminderViewModel
import com.example.silverageassistant.ui.modelconfig.ModelConfigurationViewModel
import com.example.silverageassistant.ui.settings.ModelApiKeyViewModel
import com.example.silverageassistant.ui.settings.VoiceSettingsViewModel
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import com.example.silverageassistant.ui.usage.FamilyUsageViewModel
import com.example.silverageassistant.ui.safety.SafetyMonitoringViewModel
import kotlinx.coroutines.flow.collect

/**
 * Android 进程的组合根。
 *
 * 这里负责实例化共享的数据库、凭证仓库、模型 Provider、Tool 和 ViewModel，并把依赖注入
 * Compose 导航。业务规则应留在 Domain/ViewModel 中，避免 Activity 随功能增长成为业务层。
 * WebSocket、周期用量上报和状态监控服务也在这里根据已恢复的角色凭证统一启停。
 */
class MainActivity : ComponentActivity() {
    private val reminderNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        getSharedPreferences(REMINDER_PERMISSION_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(REMINDER_PERMISSION_REQUESTED, true)
            .apply()
    }
    private val safetyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        getSharedPreferences(SAFETY_PERMISSION_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(SAFETY_PERMISSION_REQUESTED, true)
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 唯一周期任务由 WorkManager 去重；重复进入 Activity 不会创建多个小时上报任务。
        ModelUsageReportWorker.schedule(applicationContext)
        enableEdgeToEdge()
        setContent {
            val guiTaskScope = rememberCoroutineScope()
            val profileStore = remember {
                PreferencesOnboardingProfileStore(applicationContext)
            }
            val appSessionStore = remember {
                PreferencesAppSessionStore(applicationContext)
            }
            val credentialStore = remember {
                AndroidKeystoreCredentialStore(applicationContext)
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
            val familySituationReporter = remember(middleServerRepository) {
                middleServerRepository?.let { FamilySituationReporter(it) }
            }
            val database = remember {
                SilverAgeDatabase.getInstance(applicationContext)
            }
            val reminderRepository = remember {
                RoomReminderRepository(
                    database.reminderDao(),
                    deadlineScheduler = WorkManagerReminderDeadlineScheduler(applicationContext),
                    receivedNotifier =
                        RemoteReminderReceivedNotificationPublisher(applicationContext),
                )
            }
            val guiTodoRepository = remember {
                RoomGuiTodoRepository(database.guiTodoDao())
            }
            val modelUsageRecorder = remember {
                RoomModelUsageRecorder(database.modelUsageDao())
            }
            val familyContactStore = remember {
                EncryptedFamilyContactStore(applicationContext)
            }
            val pendingPhoneCallCoordinator = remember {
                PendingPhoneCallCoordinator()
            }
            val sharedAgentToolCatalog = remember {
                AgentToolCatalog(listOf(CurrentTimeTool()))
            }
            val guiTargetAppLauncher = remember {
                AndroidGuiTargetAppLauncher(applicationContext)
            }
            val guiTerminalTaskSink = remember {
                AndroidGuiTerminalTaskSink(applicationContext)
            }
            val guiTaskChatFeedbackBus = remember {
                GuiTaskChatFeedbackBus()
            }
            val phoneCallLauncher = remember {
                AndroidPhoneCallLauncher(applicationContext)
            }
            val agentLongTermMemory = remember {
                MarkdownAgentLongTermMemory(applicationContext)
            }
            val modelCredentialStore = remember {
                AndroidKeystoreModelApiCredentialStore(applicationContext)
            }
            val voiceCredentialStore = remember {
                AndroidKeystoreVoiceApiCredentialStore(applicationContext)
            }
            val voiceSettingsStore = remember {
                DataStoreVoiceInteractionSettingsStore(applicationContext)
            }
            val modelConfigurationStore = remember {
                JsonModelConfigurationStore(
                    context = applicationContext,
                    defaults = ModelRuntimeConfiguration(
                        baseUrl = BuildConfig.MODEL_BASE_URL,
                        model = BuildConfig.CHAT_MODEL,
                        dialect = OpenAiCompatibleDialect.LlamaCpp,
                    ),
                    allowCleartextHttp = BuildConfig.DEBUG,
                )
            }
            val voiceCoordinator = remember {
                VoiceInteractionCoordinator(
                    settingsStore = voiceSettingsStore,
                    asrProvider = QwenRealtimeAsrProvider(
                        context = applicationContext,
                        configurationStore = modelConfigurationStore,
                        credentialStore = voiceCredentialStore,
                        usageRecorder = modelUsageRecorder,
                    ),
                    ttsProvider = QwenRealtimeTtsProvider(
                        context = applicationContext,
                        configurationStore = modelConfigurationStore,
                        credentialStore = voiceCredentialStore,
                        usageRecorder = modelUsageRecorder,
                    ),
                    applicationScope = guiTaskScope,
                )
            }
            val guiVisionPlanner = remember {
                OpenAiGuiVisionPlanner(
                    configurationStore = modelConfigurationStore,
                    credentialStore = modelCredentialStore,
                    systemPromptProvider = DefaultGuiAgentSystemPromptProvider(),
                    usageRecorder = modelUsageRecorder,
                    groundingModeProvider = GuiDebugSettings::currentGroundingMode,
                )
            }
            val guiTaskManager = remember {
                GuiTaskManager(
                    repository = guiTodoRepository,
                    executor = AccessibilityGuiRunExecutor(
                        launcher = guiTargetAppLauncher,
                        controllerProvider = GuiAccessibilityRuntimeBridge.provider,
                        planner = guiVisionPlanner,
                        voiceCoordinator = voiceCoordinator,
                    ),
                    sharedTools = AgentToolRegistry(
                        sharedAgentToolCatalog.toolsFor(
                            SharedAgentToolCapabilities.GuiAgent,
                        ),
                    ),
                    scope = guiTaskScope,
                    escalationSink = familySituationReporter?.let {
                        FamilyReportingGuiFailureEscalationSink(it)
                    } ?: NoOpGuiFailureEscalationSink,
                    terminalTaskSink = guiTerminalTaskSink,
                    chatFeedbackSink = guiTaskChatFeedbackBus,
                )
            }
            val safetyMonitoringConfigurationStore = remember {
                JsonSafetyMonitoringConfigurationStore(applicationContext)
            }
            val elderUsageRealtimeClient = remember {
                BuildConfig.MIDDLE_SERVER_BASE_URL.takeIf(String::isNotBlank)?.let { baseUrl ->
                    ElderUsageRealtimeClient(
                        serverBaseUrl = baseUrl,
                        credentialStore = credentialStore,
                        onUsageReportRequested = {
                            ModelUsageReportWorker.enqueueImmediate(applicationContext)
                        },
                    )
                }
            }
            val locationTimeZoneStore = remember {
                LocationTimeZoneStore(applicationContext)
            }
            val weatherRepository = remember {
                CachedWeatherRepository(
                    locationProvider = AndroidDeviceLocationProvider(applicationContext),
                    remoteDataSource = OpenMeteoWeatherDataSource(),
                    locationNameResolver = FallbackLocationNameResolver(
                        AndroidLocationNameResolver(applicationContext),
                        BigDataCloudLocationNameResolver(),
                    ),
                    onLocationTimeZoneResolved = locationTimeZoneStore::saveLocationTimeZone,
                )
            }
            val newsRepository = remember {
                CachedNewsRepository(BaiduHotSearchNewsRepository())
            }
            val chatCoordinator = remember {
                val configuredProvider = ConfiguredChatModelProvider(
                    configurationStore = modelConfigurationStore,
                    credentialStore = modelCredentialStore,
                )
                val mainChatProvider = UsageTrackingChatModelProvider(
                    delegate = configuredProvider,
                    configurationStore = modelConfigurationStore,
                    recorder = modelUsageRecorder,
                    feature = AgentUsageScope.MAIN_CHAT.feature,
                )
                val compressionProvider = UsageTrackingChatModelProvider(
                    delegate = configuredProvider,
                    configurationStore = modelConfigurationStore,
                    recorder = modelUsageRecorder,
                    feature = AgentUsageScope.MAIN_CHAT_CONTEXT_COMPRESSION.feature,
                )
                val systemPromptProvider = DefaultSystemPromptProvider(
                    memorySnapshotProvider = ProcessAgentMemorySnapshotProvider(
                        owner = AgentUsageScope.MAIN_CHAT.feature,
                        loader = agentLongTermMemory::markdownForPrompt,
                    ),
                )
                val toolRegistry = AgentToolRegistry(
                    buildList {
                        addAll(
                            sharedAgentToolCatalog.toolsFor(
                                SharedAgentToolCapabilities.MainChat,
                            ),
                        )
                        add(WeatherTool(weatherRepository))
                        add(TodayRemindersTool(reminderRepository))
                        add(GuiAgentTool(guiTaskManager))
                        add(
                            CallFamilyContactTool(
                                contactStore = familyContactStore,
                                pendingCallCoordinator = pendingPhoneCallCoordinator,
                            ),
                        )
                        familySituationReporter?.let { reporter ->
                            add(ReportFamilySituationTool(reporter))
                        }
                    },
                )
                AgentChatCoordinator(
                    provider = mainChatProvider,
                    toolRegistry = toolRegistry,
                    systemPromptProvider = systemPromptProvider,
                    optionsProvider = ModelConfigurationAgentOptionsProvider(
                        modelConfigurationStore,
                    ),
                    deterministicToolRouter = CompositeAgentDeterministicToolRouter(
                        listOf(
                            GuiMainAgentToolRouter(),
                            TodayRemindersMainAgentToolRouter(),
                        ),
                    ),
                    contextManager = AgentContextManager(
                        systemPromptProvider = systemPromptProvider,
                        compressor = ModelAgentContextCompressor(compressionProvider),
                        longTermMemory = agentLongTermMemory,
                    ),
                )
            }
            val onboardingViewModel: OnboardingViewModel = viewModel(
                factory = OnboardingViewModel.Factory(
                    profileStore = profileStore,
                    middleServerRepository = middleServerRepository,
                    appSessionStore = appSessionStore,
                    credentialStore = credentialStore,
                    agentLongTermMemory = agentLongTermMemory,
                ),
            )
            val familyCommunicationViewModel: FamilyCommunicationViewModel = viewModel(
                factory = FamilyCommunicationViewModel.Factory(middleServerRepository),
            )
            val familyReminderHistoryViewModel: FamilyReminderHistoryViewModel = viewModel(
                factory = FamilyReminderHistoryViewModel.Factory(middleServerRepository),
            )
            val reminderViewModel: ReminderViewModel = viewModel(
                factory = ReminderViewModel.Factory(
                    reminderRepository = reminderRepository,
                    commandRepository = middleServerRepository,
                    voiceCoordinator = voiceCoordinator,
                ),
            )
            val familyContactsViewModel: FamilyContactsViewModel = viewModel(
                factory = FamilyContactsViewModel.Factory(
                    store = familyContactStore,
                    repository = middleServerRepository,
                    agentLongTermMemory = agentLongTermMemory,
                ),
            )
            val conversationViewModel: ConversationViewModel = viewModel(
                factory = ConversationViewModel.Factory(
                    coordinator = chatCoordinator,
                    pendingPhoneCallCoordinator = pendingPhoneCallCoordinator,
                    phoneCallLauncher = phoneCallLauncher,
                    guiTaskChatFeedbackSource = guiTaskChatFeedbackBus,
                    voiceCoordinator = voiceCoordinator,
                ),
            )
            val modelConfigurationViewModel: ModelConfigurationViewModel = viewModel(
                factory = ModelConfigurationViewModel.Factory(
                    store = modelConfigurationStore,
                    familyRepository = middleServerRepository,
                    elderRepository = middleServerRepository,
                    allowCleartextHttp = BuildConfig.DEBUG,
                ),
            )
            val modelApiKeyViewModel: ModelApiKeyViewModel = viewModel(
                factory = ModelApiKeyViewModel.Factory(modelCredentialStore),
            )
            val voiceSettingsViewModel: VoiceSettingsViewModel = viewModel(
                factory = VoiceSettingsViewModel.Factory(
                    settingsStore = voiceSettingsStore,
                    credentialStore = voiceCredentialStore,
                ),
            )
            val homeWeatherViewModel: HomeWeatherViewModel = viewModel(
                factory = HomeWeatherViewModel.Factory(weatherRepository),
            )
            val newsViewModel: NewsViewModel = viewModel(
                factory = NewsViewModel.Factory(newsRepository, voiceCoordinator),
            )
            val familyUsageViewModel: FamilyUsageViewModel = viewModel(
                factory = FamilyUsageViewModel.Factory(middleServerRepository),
            )
            val safetyMonitoringViewModel: SafetyMonitoringViewModel = viewModel(
                factory = SafetyMonitoringViewModel.Factory(
                    store = safetyMonitoringConfigurationStore,
                    familyRepository = middleServerRepository,
                    elderRepository = middleServerRepository,
                    emergencyNotifier = remember {
                        FamilyEmergencyNotificationPublisher(applicationContext)
                    },
                ),
            )
            val familySafetyRealtimeClient = remember {
                BuildConfig.MIDDLE_SERVER_BASE_URL.takeIf(String::isNotBlank)?.let { baseUrl ->
                    FamilySafetyRealtimeClient(
                        serverBaseUrl = baseUrl,
                        credentialStore = credentialStore,
                        onSafetyEventAvailable = safetyMonitoringViewModel::refreshCurrentEvents,
                        onReminderStatusAvailable =
                            familyReminderHistoryViewModel::refreshCurrent,
                    )
                }
            }
            val onboardingState by onboardingViewModel.uiState.collectAsState()
            val safetyConfiguration by safetyMonitoringConfigurationStore.configuration.collectAsState()
            DisposableEffect(guiTaskManager, guiTargetAppLauncher, voiceCoordinator) {
                GuiTaskRuntimeBridge.bind(
                    guiTaskManager,
                    guiTargetAppLauncher,
                    voiceCoordinator,
                )
                onDispose { GuiTaskRuntimeBridge.unbind(guiTaskManager) }
            }
            LaunchedEffect(guiTaskManager) {
                guiTaskManager.recoverInterruptedTodos()
            }
            LaunchedEffect(guiTaskManager, voiceCoordinator) {
                guiTaskManager.activeTask.collect { task ->
                    voiceCoordinator.setGuiAgentActive(
                        task != null && task.phase !in setOf(
                            GuiRunPhase.COMPLETED,
                            GuiRunPhase.FAILED,
                            GuiRunPhase.CANCELLED,
                            GuiRunPhase.UNAVAILABLE,
                        ),
                    )
                }
            }
            LaunchedEffect(chatCoordinator) {
                // Cold-start warm-up fixes the main Agent's MEMORY.md snapshot for this process.
                chatCoordinator.initializeContext()
            }
            LaunchedEffect(
                onboardingState.hasDeviceCredential,
                safetyConfiguration.enabled,
                safetyConfiguration.intervalMinutes,
            ) {
                // 状态监控只属于已绑定的老人设备。家属热更新配置时直接重排前台服务，
                // 不要求用户重启应用。
                if (!onboardingState.hasDeviceCredential) {
                    SafetyMonitoringService.stop(applicationContext)
                    return@LaunchedEffect
                }
                if (safetyConfiguration.enabled) {
                    requestSafetyPermissionsOnce()
                }
                SafetyMonitoringService.startOrUpdate(
                    applicationContext,
                    safetyConfiguration.enabled,
                    safetyConfiguration.intervalMinutes,
                )
            }
            LaunchedEffect(
                elderUsageRealtimeClient,
                safetyMonitoringViewModel,
                modelConfigurationViewModel,
            ) {
                elderUsageRealtimeClient?.setSafetyMonitoringConfigurationListener(
                    safetyMonitoringViewModel::syncElderConfiguration,
                )
                elderUsageRealtimeClient?.setModelConfigurationListener(
                    modelConfigurationViewModel::syncElderConfiguration,
                )
                elderUsageRealtimeClient?.setCommandAvailableListener(
                    reminderViewModel::syncRemoteCommands,
                )
            }
            LaunchedEffect(onboardingState.hasDeviceCredential, elderUsageRealtimeClient) {
                if (onboardingState.hasDeviceCredential) {
                    requestReminderNotificationPermissionOnce()
                    elderUsageRealtimeClient?.start()
                } else {
                    elderUsageRealtimeClient?.stop()
                }
            }
            LaunchedEffect(onboardingState.hasFamilySession, familySafetyRealtimeClient) {
                if (onboardingState.hasFamilySession) {
                    requestReminderNotificationPermissionOnce()
                    familySafetyRealtimeClient?.start()
                } else {
                    familySafetyRealtimeClient?.stop()
                }
            }
            DisposableEffect(elderUsageRealtimeClient) {
                onDispose { elderUsageRealtimeClient?.close() }
            }
            DisposableEffect(familySafetyRealtimeClient) {
                onDispose { familySafetyRealtimeClient?.close() }
            }
            SilverAgeAssistantTheme {
                SilverAgeApp(
                    onboardingViewModel = onboardingViewModel,
                    familyCommunicationViewModel = familyCommunicationViewModel,
                    familyReminderHistoryViewModel = familyReminderHistoryViewModel,
                    familyContactsViewModel = familyContactsViewModel,
                    reminderViewModel = reminderViewModel,
                    conversationViewModel = conversationViewModel,
                    modelConfigurationViewModel = modelConfigurationViewModel,
                    modelApiKeyViewModel = modelApiKeyViewModel,
                    voiceSettingsViewModel = voiceSettingsViewModel,
                    homeWeatherViewModel = homeWeatherViewModel,
                    newsViewModel = newsViewModel,
                    familyUsageViewModel = familyUsageViewModel,
                    safetyMonitoringViewModel = safetyMonitoringViewModel,
                    guiTaskController = guiTaskManager,
                    guiTargetAppLauncher = guiTargetAppLauncher,
                    onElderModeActiveChanged = voiceCoordinator::setElderModeActive,
                )
            }
        }
    }

    private fun requestSafetyPermissionsOnce() {
        val preferences = getSharedPreferences(SAFETY_PERMISSION_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(SAFETY_PERMISSION_REQUESTED, false)) return
        val permissions = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.SEND_SMS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.SEND_SMS)
        }
        if (permissions.isEmpty()) {
            preferences.edit().putBoolean(SAFETY_PERMISSION_REQUESTED, true).apply()
        } else {
            safetyPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun requestReminderNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val preferences = getSharedPreferences(REMINDER_PERMISSION_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(REMINDER_PERMISSION_REQUESTED, false)) return
        reminderNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val SAFETY_PERMISSION_PREFERENCES = "safety_permission_state"
        const val SAFETY_PERMISSION_REQUESTED = "requested_once"
        const val REMINDER_PERMISSION_PREFERENCES = "reminder_permission_state"
        const val REMINDER_PERMISSION_REQUESTED = "requested_once"
    }
}
