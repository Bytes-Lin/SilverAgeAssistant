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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.example.silverageassistant.data.middleserver.AndroidKeystoreCredentialStore
import com.example.silverageassistant.data.middleserver.DeviceIdentityProvider
import com.example.silverageassistant.data.middleserver.HttpOnboardingMiddleServerRepository
import com.example.silverageassistant.data.model.AndroidKeystoreModelApiCredentialStore
import com.example.silverageassistant.data.model.ConfiguredChatModelProvider
import com.example.silverageassistant.data.model.JsonModelConfigurationStore
import com.example.silverageassistant.data.model.ModelConfigurationAgentOptionsProvider
import com.example.silverageassistant.data.model.ModelRuntimeConfiguration
import com.example.silverageassistant.data.model.OpenAiCompatibleDialect
import com.example.silverageassistant.data.memory.MarkdownAgentLongTermMemory
import com.example.silverageassistant.data.contacts.EncryptedFamilyContactStore
import com.example.silverageassistant.data.onboarding.PreferencesOnboardingProfileStore
import com.example.silverageassistant.data.reminders.RoomReminderRepository
import com.example.silverageassistant.data.reminders.SilverAgeDatabase
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
import com.example.silverageassistant.data.safety.FamilySafetyRealtimeClient
import com.example.silverageassistant.data.safety.JsonSafetyMonitoringConfigurationStore
import com.example.silverageassistant.service.SafetyMonitoringService
import com.example.silverageassistant.ui.SilverAgeApp
import com.example.silverageassistant.domain.agent.AgentChatCoordinator
import com.example.silverageassistant.domain.agent.AgentToolRegistry
import com.example.silverageassistant.domain.agent.CurrentTimeTool
import com.example.silverageassistant.domain.agent.CallFamilyContactTool
import com.example.silverageassistant.domain.agent.DefaultSystemPromptProvider
import com.example.silverageassistant.domain.agent.PendingPhoneCallCoordinator
import com.example.silverageassistant.domain.agent.FamilySituationReporter
import com.example.silverageassistant.domain.agent.ReportFamilySituationTool
import com.example.silverageassistant.domain.agent.WeatherTool
import com.example.silverageassistant.platform.phone.AndroidPhoneCallLauncher
import com.example.silverageassistant.ui.conversation.ConversationViewModel
import com.example.silverageassistant.ui.home.HomeWeatherViewModel
import com.example.silverageassistant.ui.onboarding.OnboardingViewModel
import com.example.silverageassistant.ui.family.FamilyCommunicationViewModel
import com.example.silverageassistant.ui.family.FamilyContactsViewModel
import com.example.silverageassistant.ui.reminders.ReminderViewModel
import com.example.silverageassistant.ui.modelconfig.ModelConfigurationViewModel
import com.example.silverageassistant.ui.settings.ModelApiKeyViewModel
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import com.example.silverageassistant.ui.usage.FamilyUsageViewModel
import com.example.silverageassistant.ui.safety.SafetyMonitoringViewModel

/**
 * Android 进程的组合根。
 *
 * 这里负责实例化共享的数据库、凭证仓库、模型 Provider、Tool 和 ViewModel，并把依赖注入
 * Compose 导航。业务规则应留在 Domain/ViewModel 中，避免 Activity 随功能增长成为业务层。
 * WebSocket、周期用量上报和状态监控服务也在这里根据已恢复的角色凭证统一启停。
 */
class MainActivity : ComponentActivity() {
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
            val profileStore = remember {
                PreferencesOnboardingProfileStore(applicationContext)
            }
            val appSessionStore = remember {
                PreferencesAppSessionStore(applicationContext)
            }
            val credentialStore = remember {
                AndroidKeystoreCredentialStore(applicationContext)
            }
            val database = remember {
                SilverAgeDatabase.getInstance(applicationContext)
            }
            val reminderRepository = remember {
                RoomReminderRepository(
                    database.reminderDao(),
                )
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
            val phoneCallLauncher = remember {
                AndroidPhoneCallLauncher(applicationContext)
            }
            val agentLongTermMemory = remember {
                MarkdownAgentLongTermMemory(applicationContext)
            }
            val modelCredentialStore = remember {
                AndroidKeystoreModelApiCredentialStore(applicationContext)
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
            val safetyMonitoringConfigurationStore = remember {
                JsonSafetyMonitoringConfigurationStore(applicationContext)
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
            val chatCoordinator = remember {
                val configuredProvider = ConfiguredChatModelProvider(
                    configurationStore = modelConfigurationStore,
                    credentialStore = modelCredentialStore,
                )
                AgentChatCoordinator(
                    provider = UsageTrackingChatModelProvider(
                        delegate = configuredProvider,
                        configurationStore = modelConfigurationStore,
                        recorder = modelUsageRecorder,
                        feature = "conversation",
                    ),
                    toolRegistry = AgentToolRegistry(
                        buildList {
                            add(CurrentTimeTool())
                            add(WeatherTool(weatherRepository))
                            add(
                                CallFamilyContactTool(
                                    contactStore = familyContactStore,
                                    pendingCallCoordinator = pendingPhoneCallCoordinator,
                                ),
                            )
                            middleServerRepository?.let { repository ->
                                add(
                                    ReportFamilySituationTool(
                                        FamilySituationReporter(repository),
                                    ),
                                )
                            }
                        },
                    ),
                    systemPromptProvider = DefaultSystemPromptProvider(agentLongTermMemory),
                    optionsProvider = ModelConfigurationAgentOptionsProvider(
                        modelConfigurationStore,
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
                    agentLongTermMemory = agentLongTermMemory,
                ),
            )
            val conversationViewModel: ConversationViewModel = viewModel(
                factory = ConversationViewModel.Factory(
                    coordinator = chatCoordinator,
                    pendingPhoneCallCoordinator = pendingPhoneCallCoordinator,
                    phoneCallLauncher = phoneCallLauncher,
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
            val homeWeatherViewModel: HomeWeatherViewModel = viewModel(
                factory = HomeWeatherViewModel.Factory(weatherRepository),
            )
            val familyUsageViewModel: FamilyUsageViewModel = viewModel(
                factory = FamilyUsageViewModel.Factory(middleServerRepository),
            )
            val safetyMonitoringViewModel: SafetyMonitoringViewModel = viewModel(
                factory = SafetyMonitoringViewModel.Factory(
                    store = safetyMonitoringConfigurationStore,
                    familyRepository = middleServerRepository,
                    elderRepository = middleServerRepository,
                ),
            )
            val familySafetyRealtimeClient = remember {
                BuildConfig.MIDDLE_SERVER_BASE_URL.takeIf(String::isNotBlank)?.let { baseUrl ->
                    FamilySafetyRealtimeClient(
                        serverBaseUrl = baseUrl,
                        credentialStore = credentialStore,
                        onSafetyEventAvailable = safetyMonitoringViewModel::refreshCurrentEvents,
                    )
                }
            }
            val onboardingState by onboardingViewModel.uiState.collectAsState()
            val safetyConfiguration by safetyMonitoringConfigurationStore.configuration.collectAsState()
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
            LaunchedEffect(elderUsageRealtimeClient, safetyMonitoringViewModel) {
                elderUsageRealtimeClient?.setSafetyMonitoringConfigurationListener(
                    safetyMonitoringViewModel::syncElderConfiguration,
                )
            }
            LaunchedEffect(onboardingState.hasDeviceCredential, elderUsageRealtimeClient) {
                if (onboardingState.hasDeviceCredential) {
                    elderUsageRealtimeClient?.start()
                } else {
                    elderUsageRealtimeClient?.stop()
                }
            }
            LaunchedEffect(onboardingState.hasFamilySession, familySafetyRealtimeClient) {
                if (onboardingState.hasFamilySession) {
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
                    familyContactsViewModel = familyContactsViewModel,
                    reminderViewModel = reminderViewModel,
                    conversationViewModel = conversationViewModel,
                    modelConfigurationViewModel = modelConfigurationViewModel,
                    modelApiKeyViewModel = modelApiKeyViewModel,
                    homeWeatherViewModel = homeWeatherViewModel,
                    familyUsageViewModel = familyUsageViewModel,
                    safetyMonitoringViewModel = safetyMonitoringViewModel,
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isEmpty()) {
            preferences.edit().putBoolean(SAFETY_PERMISSION_REQUESTED, true).apply()
        } else {
            safetyPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private companion object {
        const val SAFETY_PERMISSION_PREFERENCES = "safety_permission_state"
        const val SAFETY_PERMISSION_REQUESTED = "requested_once"
    }
}
