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
import androidx.compose.material.icons.rounded.Favorite
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.onboarding.BindingPreparationStatus
import com.example.silverageassistant.ui.onboarding.FamilySetupDraft
import com.example.silverageassistant.ui.theme.ElderSpacing

private data class FamilyFeatureAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

@Composable
fun FamilyHomeScreen(
    profile: FamilySetupDraft,
    bindingStatus: BindingPreparationStatus,
    onEditProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var unavailableMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = listOf(
        FamilyFeatureAction("今日状态", "查看老人主动同步的状态", Icons.Rounded.Favorite),
        FamilyFeatureAction("提醒记录", "查看提醒确认时间线", Icons.AutoMirrored.Rounded.EventNote),
        FamilyFeatureAction("发送通知", "给老人发送简短消息", Icons.AutoMirrored.Rounded.Message),
        FamilyFeatureAction("创建提醒", "为老人准备本地提醒指令", Icons.Rounded.AddAlert),
        FamilyFeatureAction("模型用量", "查看客户端上报的估算", Icons.Rounded.BarChart),
        FamilyFeatureAction("紧急事件", "查看并处理老人主动求助", Icons.Rounded.Emergency),
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
                ConnectionStatusCard(bindingStatus = bindingStatus)
            }
            item {
                ElderProfileCard(profile = profile)
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
                        unavailableMessage = "尚未连接中台，${action.title}暂不可用。"
                    },
                )
            }
            item { Spacer(modifier = Modifier.height(ElderSpacing.large)) }
        }
    }
}

@Composable
private fun ConnectionStatusCard(bindingStatus: BindingPreparationStatus) {
    val bindingText = when (bindingStatus) {
        BindingPreparationStatus.NotPrepared -> "尚未准备绑定"
        BindingPreparationStatus.AwaitingCodeGeneration -> "资料已准备，等待中台注册并生成绑定码"
        BindingPreparationStatus.PendingJointVerification -> "等待中台校验家属手机号和绑定码"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "连接状态，尚未连接中台，最后同步，从未同步" },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(ElderSpacing.medium)) {
            Text("尚未连接中台", style = MaterialTheme.typography.titleLarge)
            Text(bindingText, style = MaterialTheme.typography.bodyLarge)
            if (bindingStatus == BindingPreparationStatus.AwaitingCodeGeneration) {
                Text("绑定码：待中台生成", style = MaterialTheme.typography.bodyLarge)
            }
            Text("最后同步：从未同步", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ElderProfileCard(profile: FamilySetupDraft) {
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
                    text = "绑定状态：等待中台确认",
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
