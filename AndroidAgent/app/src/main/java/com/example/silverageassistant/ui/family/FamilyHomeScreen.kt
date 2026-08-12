package com.example.silverageassistant.ui.family

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AddAlert
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Emergency
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.onboarding.BindingPreparationStatus
import com.example.silverageassistant.ui.onboarding.FamilySetupDraft
import com.example.silverageassistant.ui.onboarding.SessionConnectionStatus
import com.example.silverageassistant.ui.theme.ElderSpacing
import com.example.silverageassistant.data.middleserver.SafetyEvent
import com.example.silverageassistant.data.middleserver.SafetyEventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class FamilyFeatureAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: (() -> Unit)? = null,
    val requiresBinding: Boolean = false,
)

@Composable
fun FamilyHomeScreen(
    profile: FamilySetupDraft,
    bindingStatus: BindingPreparationStatus,
    bindingCode: String?,
    bindingCodeExpiresAt: String?,
    lastSyncedAt: String?,
    sessionConnectionStatus: SessionConnectionStatus,
    sessionMessage: String?,
    isRegeneratingBindingCode: Boolean,
    operationMessage: String?,
    onEditProfile: () -> Unit,
    onRegenerateBindingCode: () -> Unit,
    onSendNotification: () -> Unit,
    onCreateReminder: () -> Unit,
    onReminderHistory: () -> Unit,
    onModelConfiguration: () -> Unit,
    onModelUsage: () -> Unit,
    onTodayStatus: () -> Unit,
    onSafetyMonitoringConfiguration: () -> Unit,
    onEmergencyEvents: () -> Unit,
    onVerifyBinding: ((onVerified: (Boolean) -> Unit) -> Unit),
    latestEmergencyEvent: SafetyEvent?,
    emergencyTimeZone: String?,
    isLoadingEmergencyEvents: Boolean,
    emergencyEventsMessage: String?,
    modifier: Modifier = Modifier,
) {
    var unavailableMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isVerifyingBinding by rememberSaveable { mutableStateOf(false) }
    val actions = listOf(
        FamilyFeatureAction(
            "今日状态",
            "查看老人今天的一般状态通知",
            Icons.Rounded.Favorite,
            onTodayStatus,
            requiresBinding = true,
        ),
        FamilyFeatureAction(
            "发送通知",
            "给老人发送简短消息",
            Icons.AutoMirrored.Rounded.Message,
            onSendNotification,
            requiresBinding = true,
        ),
        FamilyFeatureAction(
            "创建提醒",
            "为老人准备本地提醒指令",
            Icons.Rounded.AddAlert,
            onCreateReminder,
            requiresBinding = true,
        ),
        FamilyFeatureAction(
            "提醒记录",
            "查看全部提醒及完成状态",
            Icons.AutoMirrored.Rounded.EventNote,
            onReminderHistory,
            requiresBinding = true,
        ),
        FamilyFeatureAction(
            "模型配置",
            "为老人设置模型地址和生成参数",
            Icons.Rounded.Settings,
            onModelConfiguration,
            requiresBinding = true,
        ),
        FamilyFeatureAction(
            "模型用量",
            "查看输入、输出与语音调用次数",
            Icons.Rounded.BarChart,
            onModelUsage,
            requiresBinding = true,
        ),
        FamilyFeatureAction(
            "状态检测设置",
            "设置老人手机的安全检测间隔",
            Icons.Rounded.Settings,
            onSafetyMonitoringConfiguration,
            requiresBinding = true,
        ),
        FamilyFeatureAction(
            "紧急事件",
            "查看疑似跌倒、晕倒等紧急通知",
            Icons.Rounded.Emergency,
            onEmergencyEvents,
            requiresBinding = true,
        ),
    )

    ElderScreenScaffold(
        title = "家属模式",
        onBack = null,
        modifier = modifier,
        actions = {
            IconButton(onClick = onEditProfile) {
                Icon(imageVector = Icons.Rounded.Edit, contentDescription = "编辑家属和老人信息")
            }
        },
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
                    text = if (profile.displayName.isBlank()) "您好" else "${profile.displayName}，您好",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            item {
                ConnectionStatusCard(
                    bindingStatus = bindingStatus,
                    bindingCode = bindingCode,
                    bindingCodeExpiresAt = bindingCodeExpiresAt,
                    lastSyncedAt = lastSyncedAt,
                    sessionConnectionStatus = sessionConnectionStatus,
                    sessionMessage = sessionMessage,
                    isRegeneratingBindingCode = isRegeneratingBindingCode,
                    operationMessage = operationMessage,
                    onRegenerateBindingCode = onRegenerateBindingCode,
                )
            }
            item {
                ElderProfileCard(profile = profile, bindingStatus = bindingStatus)
            }
            item {
                LatestEmergencyCard(
                    event = latestEmergencyEvent,
                    timeZone = emergencyTimeZone,
                    isLoading = isLoadingEmergencyEvents,
                    loadMessage = emergencyEventsMessage,
                    onClick = onEmergencyEvents,
                )
            }
            if (unavailableMessage != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(
                            text = unavailableMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(ElderSpacing.medium),
                        )
                    }
                }
            }
            item {
                Text("家庭协助", style = MaterialTheme.typography.headlineMedium)
            }
            items(actions, key = { it.title }) { action ->
                FamilyFeatureCard(
                    action = action,
                    onClick = {
                        if (action.requiresBinding && bindingStatus != BindingPreparationStatus.Bound) {
                            if (!isVerifyingBinding) {
                                isVerifyingBinding = true
                                unavailableMessage = "正在确认老人设备绑定状态…"
                                onVerifyBinding { isBound ->
                                    isVerifyingBinding = false
                                    if (isBound) {
                                        unavailableMessage = null
                                        action.onClick?.invoke()
                                    } else {
                                        unavailableMessage =
                                            "尚未确认老人设备已绑定，请完成绑定后再试。"
                                    }
                                }
                            }
                        } else if (action.onClick != null) {
                            action.onClick.invoke()
                        } else {
                            unavailableMessage = "${action.title}接口尚未进入当前开发阶段。"
                        }
                    },
                )
            }
            item { Spacer(modifier = Modifier.height(ElderSpacing.large)) }
        }
    }
}

@Composable
private fun LatestEmergencyCard(
    event: SafetyEvent?,
    timeZone: String?,
    isLoading: Boolean,
    loadMessage: String?,
    onClick: () -> Unit,
) {
    val hasEmergency = event != null
    val title = when {
        hasEmergency -> "最新紧急事件"
        isLoading -> "紧急状态正在更新"
        loadMessage != null -> "紧急状态暂未更新"
        else -> "当前没有紧急事件"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (hasEmergency) {
                    "最新紧急事件，${event?.eventSummary.orEmpty()}，点击查看详情"
                } else {
                    title
                }
            }
            .clickable(onClickLabel = "查看紧急事件", onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (hasEmergency) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ElderSpacing.medium),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            Icon(
                imageVector = if (hasEmergency) Icons.Rounded.Emergency else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (hasEmergency) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                modifier = Modifier.size(40.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (hasEmergency) {
                        Text(
                            "紧急",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (event != null) {
                    Text(event.eventType.familyDisplayName(), style = MaterialTheme.typography.titleMedium)
                    Text(event.eventSummary, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        event.occurredAt.toFamilyDisplayTime(timeZone),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("点击查看并处理", style = MaterialTheme.typography.bodyMedium)
                } else if (loadMessage != null && !isLoading) {
                    Text("请点击卡片进入紧急事件页面刷新。", style = MaterialTheme.typography.bodyMedium)
                } else if (!isLoading) {
                    Text("老人今天没有未处理的紧急状况。", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun SafetyEventType.familyDisplayName(): String = when (this) {
    SafetyEventType.HEALTH_DISCOMFORT_REPORTED -> "老人报告身体不适"
    SafetyEventType.FAMILY_REQUEST -> "老人希望联系家属"
    SafetyEventType.FALL_SUSPECTED -> "疑似跌倒"
    SafetyEventType.UNCONSCIOUSNESS_SUSPECTED -> "疑似晕倒或失去意识"
    SafetyEventType.OTHER_ABNORMALITY -> "其他异常状态"
    SafetyEventType.GUI_ORDER_ASSISTANCE_REQUIRED -> "外卖或网购需要协助"
}

private fun String.toFamilyDisplayTime(timeZone: String?): String = runCatching {
    val zone = timeZone?.let(ZoneId::of) ?: ZoneId.systemDefault()
    FAMILY_EVENT_TIME_FORMATTER.format(Instant.parse(this).atZone(zone))
}.getOrDefault(this)

private val FAMILY_EVENT_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM月dd日 HH:mm")

@Composable
private fun ConnectionStatusCard(
    bindingStatus: BindingPreparationStatus,
    bindingCode: String?,
    bindingCodeExpiresAt: String?,
    lastSyncedAt: String?,
    sessionConnectionStatus: SessionConnectionStatus,
    sessionMessage: String?,
    isRegeneratingBindingCode: Boolean,
    operationMessage: String?,
    onRegenerateBindingCode: () -> Unit,
) {
    val isConnected = bindingStatus == BindingPreparationStatus.CodeGenerated ||
        bindingStatus == BindingPreparationStatus.Bound
    val bindingText = when (bindingStatus) {
        BindingPreparationStatus.NotPrepared -> "尚未准备绑定"
        BindingPreparationStatus.AwaitingCodeGeneration -> "资料已准备，等待中台注册并生成绑定码"
        BindingPreparationStatus.PendingJointVerification -> "等待中台校验家属手机号和绑定码"
        BindingPreparationStatus.CodeGenerated -> "绑定码已生成，请在老人手机上填写"
        BindingPreparationStatus.Bound -> "老人设备已完成绑定"
    }
    val connectionTitle = when (sessionConnectionStatus) {
        SessionConnectionStatus.Syncing -> "正在同步中台"
        SessionConnectionStatus.Online -> "已连接中台"
        SessionConnectionStatus.Offline -> "暂时离线"
        SessionConnectionStatus.Invalid -> "登录已失效"
        SessionConnectionStatus.Unknown -> if (isConnected) "已连接中台" else "尚未连接中台"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "连接状态，$connectionTitle"
            },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(ElderSpacing.medium)) {
            Text(connectionTitle, style = MaterialTheme.typography.titleLarge)
            Text(bindingText, style = MaterialTheme.typography.bodyLarge)
            if (sessionMessage != null) {
                Text(sessionMessage, style = MaterialTheme.typography.bodyMedium)
            }
            if (bindingCode != null) {
                Text("绑定码：$bindingCode", style = MaterialTheme.typography.headlineMedium)
                if (bindingCodeExpiresAt != null) {
                    Text("请尽快使用，过期后需重新生成。", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (bindingStatus == BindingPreparationStatus.AwaitingCodeGeneration) {
                Text("绑定码：正在生成", style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                if (lastSyncedAt == null) "最后同步：从未同步" else "最后同步：刚刚",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(ElderSpacing.small))
            LargeActionButton(
                text = if (isRegeneratingBindingCode) {
                    "正在生成新绑定码"
                } else {
                    "重新生成绑定码"
                },
                onClick = onRegenerateBindingCode,
                icon = Icons.Rounded.Refresh,
                contentDescription = "向中台重新申请老人绑定码",
                enabled = !isRegeneratingBindingCode &&
                    sessionConnectionStatus != SessionConnectionStatus.Invalid,
                outlined = true,
            )
            if (operationMessage != null) {
                Text(
                    text = operationMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(top = ElderSpacing.small)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun ElderProfileCard(
    profile: FamilySetupDraft,
    bindingStatus: BindingPreparationStatus,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(ElderSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            Icon(
                imageVector = Icons.Rounded.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(
                    text = profile.elderDisplayName.ifBlank { "尚未填写老人称呼" },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = profile.relationship?.displayName ?: "关系尚未填写",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = when (bindingStatus) {
                        BindingPreparationStatus.CodeGenerated -> "绑定状态：等待老人输入绑定码"
                        BindingPreparationStatus.Bound -> "绑定状态：已绑定"
                        else -> "绑定状态：等待中台确认"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FamilyFeatureCard(action: FamilyFeatureAction, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 96.dp)
            .semantics { contentDescription = "${action.title}，${action.subtitle}" }
            .clickable(onClickLabel = "打开${action.title}", onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ElderSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Column {
                Text(action.title, style = MaterialTheme.typography.titleLarge)
                Text(action.subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
