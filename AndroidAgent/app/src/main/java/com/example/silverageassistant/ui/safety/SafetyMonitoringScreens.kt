package com.example.silverageassistant.ui.safety

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.silverageassistant.data.middleserver.SafetyEvent
import com.example.silverageassistant.data.middleserver.SafetyEventSeverity
import com.example.silverageassistant.data.middleserver.SafetyEventType
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SafetyMonitoringConfigurationRoute(
    elderId: String?,
    onBack: () -> Unit,
    viewModel: SafetyMonitoringViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(elderId) { viewModel.loadForFamily(elderId) }
    SafetyMonitoringConfigurationScreen(
        state = state,
        onIntervalSelected = viewModel::selectInterval,
        onDisable = viewModel::disableMonitoring,
        onSave = viewModel::saveFamilyConfiguration,
        onBack = onBack,
    )
}

@Composable
fun FamilySafetyEventsRoute(
    elderId: String?,
    emergencyOnly: Boolean,
    onBack: () -> Unit,
    viewModel: SafetyMonitoringViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(elderId) { viewModel.loadForFamily(elderId) }
    FamilySafetyEventsScreen(
        state = state,
        emergencyOnly = emergencyOnly,
        onRefresh = viewModel::refreshCurrentEvents,
        onOpenImage = viewModel::openEventImage,
        onCloseImage = viewModel::closeEventImage,
        onBack = onBack,
    )
}

@Composable
fun FamilyEmergencyAlertHost(
    state: SafetyMonitoringUiState,
    onAcknowledged: (SafetyEvent) -> Unit,
) {
    state.pendingEmergencyAlert?.let { event ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("收到紧急事件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(ElderSpacing.small)) {
                    Text(event.eventSummary, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "检测时间：${event.occurredAt.toDisplayTime(state.timeZone)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text("请尽快联系老人并核实现场情况。")
                }
            },
            confirmButton = {
                Button(onClick = { onAcknowledged(event) }) {
                    Text("我知道了")
                }
            },
        )
    }
}

@Composable
private fun SafetyMonitoringConfigurationScreen(
    state: SafetyMonitoringUiState,
    onIntervalSelected: (Int) -> Unit,
    onDisable: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    ElderScreenScaffold(title = "状态检测设置", onBack = onBack) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            item {
                Text("检测时间间隔", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "默认每 5 分钟检查一次。间隔越短，模型用量和耗电量越高。",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = !state.isSavingConfiguration,
                            onClick = onDisable,
                        )
                        .padding(vertical = ElderSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = !state.monitoringEnabled,
                        onClick = onDisable,
                        enabled = !state.isSavingConfiguration,
                    )
                    Text("关闭状态检测", style = MaterialTheme.typography.titleLarge)
                }
            }
            items(SafetyMonitoringViewModel.SUPPORTED_INTERVALS) { minutes ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = !state.isSavingConfiguration,
                            onClick = { onIntervalSelected(minutes) },
                        )
                        .padding(vertical = ElderSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.monitoringEnabled && state.intervalMinutes == minutes,
                        onClick = { onIntervalSelected(minutes) },
                        enabled = !state.isSavingConfiguration,
                    )
                    Text("每 $minutes 分钟", style = MaterialTheme.typography.titleLarge)
                }
            }
            state.configurationMessage?.let { message ->
                item { StatusMessage(message) }
            }
            item {
                LargeActionButton(
                    text = if (state.isSavingConfiguration) "正在保存" else "保存并下发",
                    onClick = onSave,
                    icon = Icons.Rounded.Save,
                    enabled = !state.isLoadingConfiguration && !state.isSavingConfiguration,
                    contentDescription = "保存检测间隔并通过中台下发给老人手机",
                )
            }
            item { Spacer(Modifier.height(ElderSpacing.large)) }
        }
    }
}

@Composable
private fun FamilySafetyEventsScreen(
    state: SafetyMonitoringUiState,
    emergencyOnly: Boolean,
    onRefresh: () -> Unit,
    onOpenImage: (SafetyEvent) -> Unit,
    onCloseImage: () -> Unit,
    onBack: () -> Unit,
) {
    val events = if (emergencyOnly) state.emergencyEvents else state.generalEvents
    if (state.openedImageEventId != null) {
        SafetyEventImageDialog(
            imageBytes = state.openedImageBytes,
            isLoading = state.isLoadingOpenedImage,
            onClose = onCloseImage,
        )
    }
    ElderScreenScaffold(
        title = if (emergencyOnly) "紧急事件" else "今日状态",
        onBack = onBack,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            item {
                Text(
                    if (emergencyOnly) {
                        "显示今天需要尽快核实的异常状态。"
                    } else {
                        "显示今天的一般状态通知。"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            state.eventsMessage?.let { message -> item { StatusMessage(message) } }
            if (!state.isLoadingEvents && events.isEmpty()) {
                item {
                    StatusMessage(
                        if (emergencyOnly) "今天暂无紧急事件。" else "今天暂无一般状态通知。",
                    )
                }
            }
            items(events, key = SafetyEvent::eventId) { event ->
                SafetyEventCard(
                    event = event,
                    timeZone = state.timeZone,
                    thumbnailBytes = state.eventThumbnails[event.eventId],
                    onOpenImage = { onOpenImage(event) },
                )
            }
            item {
                LargeActionButton(
                    text = if (state.isLoadingEvents) "正在刷新" else "刷新",
                    onClick = onRefresh,
                    icon = Icons.Rounded.Refresh,
                    enabled = !state.isLoadingEvents,
                    outlined = true,
                )
            }
            item { Spacer(Modifier.height(ElderSpacing.large)) }
        }
    }
}

@Composable
private fun SafetyEventCard(
    event: SafetyEvent,
    timeZone: String?,
    thumbnailBytes: ByteArray?,
    onOpenImage: () -> Unit,
) {
    val emergency = event.severity == SafetyEventSeverity.EMERGENCY
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (emergency) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(event.eventType.displayName(), style = MaterialTheme.typography.titleLarge)
                Text(
                    if (emergency) "紧急" else "一般",
                    color = if (emergency) MaterialTheme.colorScheme.error else Color.Unspecified,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(event.eventSummary, style = MaterialTheme.typography.bodyLarge)
            if (event.imageAvailable) {
                val bitmap = remember(thumbnailBytes) {
                    thumbnailBytes?.decodeForDisplay(MAX_THUMBNAIL_DIMENSION)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "异常状态图像，点击查看大图",
                        modifier = Modifier
                            .size(144.dp)
                            .clickable(onClick = onOpenImage),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        "图像正在加载，点击消息后可重试查看",
                        modifier = Modifier.clickable(onClick = onOpenImage),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                event.occurredAt.toDisplayTime(timeZone),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (event.acknowledgedAt != null) {
                Text("家属已确认收到", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SafetyEventImageDialog(
    imageBytes: ByteArray?,
    isLoading: Boolean,
    onClose: () -> Unit,
) {
    val bitmap = remember(imageBytes) {
        imageBytes?.decodeForDisplay(MAX_FULL_IMAGE_DIMENSION)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("异常状态图像") },
        text = {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "老人异常状态原图",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
                isLoading -> Text("正在加载图像……")
                else -> Text("图像暂时无法加载，请稍后刷新。")
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        },
    )
}

@Composable
private fun StatusMessage(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(message, modifier = Modifier.padding(ElderSpacing.medium))
    }
}

private fun SafetyEventType.displayName(): String = when (this) {
    SafetyEventType.HEALTH_DISCOMFORT_REPORTED -> "老人报告身体不适"
    SafetyEventType.FAMILY_REQUEST -> "家庭事项"
    SafetyEventType.FALL_SUSPECTED -> "疑似跌倒"
    SafetyEventType.UNCONSCIOUSNESS_SUSPECTED -> "疑似晕倒或失去意识"
    SafetyEventType.OTHER_ABNORMALITY -> "其他异常状态"
}

private fun String.toDisplayTime(timeZone: String?): String = runCatching {
    val zone = timeZone?.let(ZoneId::of) ?: ZoneId.systemDefault()
    DISPLAY_TIME_FORMATTER.format(Instant.parse(this).atZone(zone))
}.getOrDefault(this)

private fun ByteArray.decodeForDisplay(maxDimension: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(this, 0, size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > maxDimension ||
        bounds.outHeight / sampleSize > maxDimension
    ) {
        sampleSize *= 2
    }
    BitmapFactory.decodeByteArray(
        this,
        0,
        size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}.getOrNull()

private const val MAX_THUMBNAIL_DIMENSION = 512
private const val MAX_FULL_IMAGE_DIMENSION = 2_048
private val DISPLAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM月dd日 HH:mm")
