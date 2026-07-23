package com.example.silverageassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.silverageassistant.BuildConfig
import com.example.silverageassistant.MainActivity
import com.example.silverageassistant.R
import com.example.silverageassistant.data.contacts.EncryptedFamilyContactStore
import com.example.silverageassistant.data.middleserver.AndroidKeystoreCredentialStore
import com.example.silverageassistant.data.middleserver.DeviceIdentityProvider
import com.example.silverageassistant.data.middleserver.HttpOnboardingMiddleServerRepository
import com.example.silverageassistant.data.model.AndroidKeystoreModelApiCredentialStore
import com.example.silverageassistant.data.model.JsonModelConfigurationStore
import com.example.silverageassistant.data.model.ModelRuntimeConfiguration
import com.example.silverageassistant.data.model.OpenAiCompatibleDialect
import com.example.silverageassistant.data.reminders.SilverAgeDatabase
import com.example.silverageassistant.data.safety.MockSafetyImageSource
import com.example.silverageassistant.data.safety.OpenAiSafetyVisionAnalyzer
import com.example.silverageassistant.data.safety.SafetyDetectionStateStore
import com.example.silverageassistant.data.safety.JsonSafetyMonitoringConfigurationStore
import com.example.silverageassistant.data.safety.SafetyMonitoringConfiguration
import com.example.silverageassistant.data.usage.RoomModelUsageRecorder
import com.example.silverageassistant.data.usage.ElderUsageRealtimeClient
import com.example.silverageassistant.domain.agent.FamilySituationReporter
import com.example.silverageassistant.domain.safety.SafetyMonitoringAgent
import com.example.silverageassistant.domain.safety.SendFamilyEmergencySmsTool
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 状态监控的 Android 前台调度宿主。
 *
 * 普通 WorkManager 无法稳定执行 1—5 分钟周期，因此这里用带常驻通知的 specialUse
 * 前台服务。家属配置更新会取消旧 Job 并按新间隔立即重排；关闭检测时保留轻量 WebSocket
 * 配置监听，使再次开启无需老人重启应用。
 */
class SafetyMonitoringService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scheduleJob: Job? = null
    private lateinit var agent: SafetyMonitoringAgent
    private lateinit var configurationStore: JsonSafetyMonitoringConfigurationStore
    private var realtimeClient: ElderUsageRealtimeClient? = null
    private var middleRepository: HttpOnboardingMiddleServerRepository? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground("正在准备状态检测")
        configurationStore = JsonSafetyMonitoringConfigurationStore(applicationContext)
        val repository = createMiddleRepository()
        middleRepository = repository
        agent = createAgent(repository)
        realtimeClient = BuildConfig.MIDDLE_SERVER_BASE_URL.takeIf(String::isNotBlank)?.let { baseUrl ->
            ElderUsageRealtimeClient(
                serverBaseUrl = baseUrl,
                credentialStore = AndroidKeystoreCredentialStore(applicationContext),
                onUsageReportRequested = {},
            ).also { client ->
                client.setSafetyMonitoringConfigurationListener(::refreshRemoteConfiguration)
                client.start()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_INTERVAL_MINUTES) == true) {
            serviceScope.launch {
                applyConfiguration(
                    SafetyMonitoringConfiguration(
                        enabled = intent.getBooleanExtra(EXTRA_ENABLED, true),
                        intervalMinutes = intent.getIntExtra(
                            EXTRA_INTERVAL_MINUTES,
                            DEFAULT_INTERVAL_MINUTES,
                        ).coerceIn(1, 60),
                    ),
                )
            }
        } else {
            serviceScope.launch {
                configurationStore.initialize()
                applyConfiguration(configurationStore.configuration.value)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scheduleJob?.cancel()
        realtimeClient?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reschedule(intervalMinutes: Int) {
        // 先取消旧循环，保证从 1 分钟切换到 5 分钟时不会有两个调度器并行上报。
        scheduleJob?.cancel()
        updateNotification("每 $intervalMinutes 分钟检测一次")
        scheduleJob = serviceScope.launch {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(intervalMinutes.toLong()))
                try {
                    updateNotification("正在分析老人状态")
                    val result = agent.runOnce()
                    val label = if (result.result.state.name == "NORMAL") "最近检测：状态正常" else "最近检测：发现疑似异常"
                    updateNotification("$label · 每 $intervalMinutes 分钟")
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    updateNotification("本次检测未完成，将按计划重试")
                }
            }
        }
    }

    private fun createMiddleRepository(): HttpOnboardingMiddleServerRepository =
        HttpOnboardingMiddleServerRepository(
            serverBaseUrl = BuildConfig.MIDDLE_SERVER_BASE_URL,
            credentialStore = AndroidKeystoreCredentialStore(applicationContext),
            deviceId = DeviceIdentityProvider(applicationContext).getOrCreate(),
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        )

    private fun createAgent(middleRepository: HttpOnboardingMiddleServerRepository): SafetyMonitoringAgent {
        val modelStore = JsonModelConfigurationStore(
            context = applicationContext,
            defaults = ModelRuntimeConfiguration(
                baseUrl = BuildConfig.MODEL_BASE_URL,
                model = BuildConfig.CHAT_MODEL,
                dialect = OpenAiCompatibleDialect.LlamaCpp,
            ),
            allowCleartextHttp = BuildConfig.DEBUG,
        )
        serviceScope.launch { modelStore.initialize() }
        val contacts = EncryptedFamilyContactStore(applicationContext)
        val usageRecorder = RoomModelUsageRecorder(
            SilverAgeDatabase.getInstance(applicationContext).modelUsageDao(),
        )
        return SafetyMonitoringAgent(
            imageSource = MockSafetyImageSource(applicationContext),
            analyzer = OpenAiSafetyVisionAnalyzer(
                configurationStore = modelStore,
                credentialStore = AndroidKeystoreModelApiCredentialStore(applicationContext),
                usageRecorder = usageRecorder,
            ),
            stateStore = SafetyDetectionStateStore(applicationContext),
            familyReporter = FamilySituationReporter(middleRepository),
            smsTool = SendFamilyEmergencySmsTool(applicationContext, contacts),
        )
    }

    private fun refreshRemoteConfiguration() {
        val repository = middleRepository ?: return
        serviceScope.launch {
            runCatching { repository.getSafetyMonitoringConfiguration() }
                .getOrNull()
                ?.let { configuration ->
                    runCatching { configurationStore.save(configuration) }
                    applyConfiguration(configuration)
                }
        }
    }

    private suspend fun applyConfiguration(configuration: SafetyMonitoringConfiguration) {
        if (configuration.enabled) {
            reschedule(configuration.intervalMinutes)
        } else {
            scheduleJob?.cancel()
            scheduleJob = null
            agent.clearState()
            updateNotification("家属已关闭状态检测，正在等待远程配置")
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "老人安全状态监控",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示老人安全状态检测是否正在运行"
                setShowBadge(false)
            },
        )
    }

    private fun notification(content: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("银龄助手正在进行安全状态监控")
        .setContentText(content)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun startAsForeground(content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(content), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification(content))
        }
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(content))
    }

    companion object {
        private const val CHANNEL_ID = "safety_monitoring"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_INTERVAL_MINUTES = "interval_minutes"
        private const val EXTRA_ENABLED = "enabled"
        private const val DEFAULT_INTERVAL_MINUTES = 5

        fun startOrUpdate(context: Context, enabled: Boolean, intervalMinutes: Int) {
            val intent = Intent(context, SafetyMonitoringService::class.java)
                .putExtra(EXTRA_ENABLED, enabled)
                .putExtra(EXTRA_INTERVAL_MINUTES, intervalMinutes)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SafetyMonitoringService::class.java))
        }
    }
}
