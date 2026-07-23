package com.example.silverageassistant.ui.safety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.silverageassistant.data.middleserver.FamilySafetyConfigurationUpdateRequest
import com.example.silverageassistant.data.middleserver.FamilySafetyMonitoringRepository
import com.example.silverageassistant.data.middleserver.MiddleServerRequestException
import com.example.silverageassistant.data.middleserver.SafetyEvent
import com.example.silverageassistant.data.middleserver.SafetyEventSeverity
import com.example.silverageassistant.data.middleserver.ElderSafetyMonitoringRepository
import com.example.silverageassistant.data.safety.SafetyMonitoringConfiguration
import com.example.silverageassistant.data.safety.SafetyMonitoringConfigurationStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SafetyMonitoringUiState(
    val monitoringEnabled: Boolean = true,
    val intervalMinutes: Int = SafetyMonitoringConfiguration.DEFAULT_INTERVAL_MINUTES,
    val revision: Long? = null,
    val currentDate: String? = null,
    val timeZone: String? = null,
    val events: List<SafetyEvent> = emptyList(),
    val isLoadingConfiguration: Boolean = false,
    val isSavingConfiguration: Boolean = false,
    val isLoadingEvents: Boolean = false,
    val configurationMessage: String? = null,
    val eventsMessage: String? = null,
    val eventThumbnails: Map<String, ByteArray> = emptyMap(),
    val openedImageEventId: String? = null,
    val openedImageBytes: ByteArray? = null,
    val isLoadingOpenedImage: Boolean = false,
    val locallyDismissedEmergencyIds: Set<String> = emptySet(),
) {
    val generalEvents: List<SafetyEvent>
        get() = events.filter { it.severity == SafetyEventSeverity.GENERAL }

    val emergencyEvents: List<SafetyEvent>
        get() = events.filter { it.severity == SafetyEventSeverity.EMERGENCY }

    val pendingEmergencyAlert: SafetyEvent?
        get() = emergencyEvents.firstOrNull {
            it.acknowledgedAt == null && it.eventId !in locallyDismissedEmergencyIds
        }
}

/**
 * 家属安全状态页面与老人远程配置同步的共享 ViewModel。
 *
 * 事件、缩略图和原图都通过有权限的 Repository 按需获取。WebSocket 连续提示只设置
 * pending 标记并串行刷新，避免并发请求覆盖较新的事件或“图片已就绪”状态。
 */
class SafetyMonitoringViewModel(
    private val store: SafetyMonitoringConfigurationStore,
    private val familyRepository: FamilySafetyMonitoringRepository? = null,
    private val elderRepository: ElderSafetyMonitoringRepository? = null,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = externalScope ?: viewModelScope
    private val _uiState = MutableStateFlow(SafetyMonitoringUiState())
    val uiState: StateFlow<SafetyMonitoringUiState> = _uiState.asStateFlow()
    private var currentElderId: String? = null
    private var configurationJob: Job? = null
    private var eventJob: Job? = null
    private var eventRefreshPending = false
    private var elderSyncJob: Job? = null
    private var elderSyncPending = false
    private var pendingConfigurationRequestId: String? = null

    init {
        workScope.launch {
            store.initialize()
            applyConfiguration(store.configuration.value)
        }
    }

    fun loadForFamily(elderId: String?) {
        val id = elderId?.takeIf(String::isNotBlank) ?: run {
            _uiState.update { it.copy(eventsMessage = "请先完成老人设备绑定。") }
            return
        }
        currentElderId = id
        loadFamilyConfiguration(id)
        refreshEvents(id)
    }

    fun selectInterval(minutes: Int) {
        if (minutes !in SUPPORTED_INTERVALS) return
        pendingConfigurationRequestId = null
        _uiState.update {
            it.copy(monitoringEnabled = true, intervalMinutes = minutes, configurationMessage = null)
        }
    }

    fun disableMonitoring() {
        pendingConfigurationRequestId = null
        _uiState.update { it.copy(monitoringEnabled = false, configurationMessage = null) }
    }

    fun saveFamilyConfiguration() {
        val elderId = currentElderId ?: run {
            _uiState.update { it.copy(configurationMessage = "请先完成老人设备绑定。") }
            return
        }
        val repository = familyRepository ?: run {
            _uiState.update { it.copy(configurationMessage = "中台检测配置接口尚未接入。") }
            return
        }
        if (configurationJob?.isActive == true) return
        val requestId = pendingConfigurationRequestId ?: UUID.randomUUID().toString().also {
            pendingConfigurationRequestId = it
        }
        configurationJob = workScope.launch {
            _uiState.update { it.copy(isSavingConfiguration = true, configurationMessage = null) }
            try {
                val saved = repository.updateSafetyMonitoringConfiguration(
                    FamilySafetyConfigurationUpdateRequest(
                        elderId = elderId,
                        enabled = _uiState.value.monitoringEnabled,
                        intervalMinutes = _uiState.value.intervalMinutes,
                        expectedRevision = _uiState.value.revision,
                        clientRequestId = requestId,
                    ),
                )
                pendingConfigurationRequestId = null
                applyConfiguration(saved, "已交给中台，老人手机联网后会自动更新。")
            } catch (error: MiddleServerRequestException) {
                configurationFailure(error.userMessage)
            } catch (_: Exception) {
                configurationFailure("检测间隔保存失败，请稍后再试。")
            }
        }
    }

    fun refreshCurrentEvents() {
        currentElderId?.let(::refreshEvents)
    }

    fun refreshEvents(elderId: String) {
        val repository = familyRepository ?: return
        eventRefreshPending = true
        if (eventJob?.isActive == true) return
        currentElderId = elderId
        eventJob = workScope.launch {
            do {
                eventRefreshPending = false
                _uiState.update { it.copy(isLoadingEvents = true, eventsMessage = null) }
                try {
                    val snapshot = repository.getTodaySafetyEvents(elderId)
                    val sorted = snapshot.events.sortedByDescending(SafetyEvent::occurredAt)
                    _uiState.update {
                        it.copy(
                            currentDate = snapshot.currentDate,
                            timeZone = snapshot.timeZone,
                            events = sorted,
                            eventThumbnails = it.eventThumbnails.filterKeys { id ->
                                sorted.any { event -> event.eventId == id && event.imageAvailable }
                            },
                            isLoadingEvents = false,
                            eventsMessage = null,
                        )
                    }
                    sorted.filter(SafetyEvent::imageAvailable).forEach { event ->
                        if (event.eventId !in _uiState.value.eventThumbnails) {
                            runCatching {
                                repository.getSafetyEventImage(elderId, event.eventId, thumbnail = true)
                            }.getOrNull()?.let { bytes ->
                                _uiState.update {
                                    it.copy(eventThumbnails = it.eventThumbnails + (event.eventId to bytes))
                                }
                            }
                        }
                    }
                } catch (error: MiddleServerRequestException) {
                    eventsFailure(error.userMessage)
                } catch (_: Exception) {
                    eventsFailure("安全状态加载失败，请稍后刷新。")
                }
            } while (eventRefreshPending)
        }
    }

    fun openEventImage(event: SafetyEvent) {
        if (!event.imageAvailable) return
        val elderId = currentElderId ?: return
        val repository = familyRepository ?: return
        _uiState.update {
            it.copy(
                openedImageEventId = event.eventId,
                openedImageBytes = it.eventThumbnails[event.eventId],
                isLoadingOpenedImage = true,
            )
        }
        workScope.launch {
            val image = runCatching {
                repository.getSafetyEventImage(elderId, event.eventId, thumbnail = false)
            }.getOrNull()
            _uiState.update {
                if (it.openedImageEventId != event.eventId) it else it.copy(
                    openedImageBytes = image ?: it.openedImageBytes,
                    isLoadingOpenedImage = false,
                )
            }
        }
    }

    fun closeEventImage() {
        _uiState.update {
            it.copy(
                openedImageEventId = null,
                openedImageBytes = null,
                isLoadingOpenedImage = false,
            )
        }
    }

    fun acknowledgeEmergency(event: SafetyEvent) {
        _uiState.update {
            it.copy(locallyDismissedEmergencyIds = it.locallyDismissedEmergencyIds + event.eventId)
        }
        val elderId = currentElderId ?: return
        val repository = familyRepository ?: return
        workScope.launch {
            runCatching {
                repository.acknowledgeSafetyEvent(
                    elderId = elderId,
                    eventId = event.eventId,
                    clientRequestId = UUID.randomUUID().toString(),
                )
            }.onSuccess { acknowledged ->
                _uiState.update { state ->
                    state.copy(
                        events = state.events.map {
                            if (it.eventId == acknowledged.eventId) acknowledged else it
                        },
                    )
                }
            }.onFailure {
                _uiState.update { state ->
                    state.copy(eventsMessage = "已在本机关闭提醒，中台确认暂未成功。")
                }
            }
        }
    }

    fun syncElderConfiguration() {
        val repository = elderRepository ?: return
        elderSyncPending = true
        if (elderSyncJob?.isActive == true) return
        elderSyncJob = workScope.launch {
            do {
                elderSyncPending = false
                runCatching { store.initialize() }
                runCatching { repository.getSafetyMonitoringConfiguration() }
                    .getOrNull()
                    ?.let { remote -> runCatching { store.save(remote) } }
                applyConfiguration(store.configuration.value)
            } while (elderSyncPending)
        }
    }

    private fun loadFamilyConfiguration(elderId: String) {
        val repository = familyRepository ?: return
        if (configurationJob?.isActive == true) return
        configurationJob = workScope.launch {
            _uiState.update { it.copy(isLoadingConfiguration = true, configurationMessage = null) }
            try {
                val remote = repository.getSafetyMonitoringConfiguration(elderId)
                applyConfiguration(
                    remote ?: SafetyMonitoringConfiguration(),
                    if (remote == null) "尚未下发过配置，当前使用默认 5 分钟。" else null,
                )
            } catch (error: MiddleServerRequestException) {
                configurationFailure(error.userMessage)
            } catch (_: Exception) {
                configurationFailure("检测配置加载失败，当前显示本地默认值。")
            }
        }
    }

    private fun applyConfiguration(
        configuration: SafetyMonitoringConfiguration,
        message: String? = null,
    ) {
        _uiState.update {
            it.copy(
                intervalMinutes = configuration.intervalMinutes,
                monitoringEnabled = configuration.enabled,
                revision = configuration.revision.takeIf { revision -> revision > 0 },
                isLoadingConfiguration = false,
                isSavingConfiguration = false,
                configurationMessage = message,
            )
        }
    }

    private fun configurationFailure(message: String) {
        _uiState.update {
            it.copy(
                isLoadingConfiguration = false,
                isSavingConfiguration = false,
                configurationMessage = message,
            )
        }
    }

    private fun eventsFailure(message: String) {
        _uiState.update { it.copy(isLoadingEvents = false, eventsMessage = message) }
    }

    class Factory(
        private val store: SafetyMonitoringConfigurationStore,
        private val familyRepository: FamilySafetyMonitoringRepository?,
        private val elderRepository: ElderSafetyMonitoringRepository?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SafetyMonitoringViewModel::class.java))
            return SafetyMonitoringViewModel(store, familyRepository, elderRepository) as T
        }
    }

    companion object {
        val SUPPORTED_INTERVALS = listOf(1, 5, 10, 15, 30, 60)
    }
}
