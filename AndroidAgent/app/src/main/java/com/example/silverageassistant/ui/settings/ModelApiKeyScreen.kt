package com.example.silverageassistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing

@Composable
fun ModelApiKeyRoute(
    onBack: () -> Unit,
    viewModel: ModelApiKeyViewModel,
    voiceSettingsViewModel: VoiceSettingsViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    val voiceState by voiceSettingsViewModel.uiState.collectAsState()
    ModelApiKeyScreen(
        state = state,
        voiceState = voiceState,
        onBack = onBack,
        onDraftChanged = viewModel::updateDraft,
        onToggleVisibility = viewModel::toggleKeyVisibility,
        onSave = { viewModel.saveApiKey() },
        onRequestDelete = viewModel::requestDelete,
        onCancelDelete = viewModel::cancelDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onVoiceEnabledChanged = voiceSettingsViewModel::setEnabled,
        onVoiceApiKeyChanged = voiceSettingsViewModel::updateApiKeyDraft,
        onToggleVoiceApiKeyVisibility = voiceSettingsViewModel::toggleApiKeyVisibility,
        onSaveVoiceApiKey = voiceSettingsViewModel::saveApiKey,
        onClearVoiceApiKey = voiceSettingsViewModel::clearApiKey,
    )
}

@Composable
fun ModelApiKeyScreen(
    state: ModelApiKeyUiState,
    voiceState: VoiceSettingsUiState = VoiceSettingsUiState(isLoading = false),
    onBack: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onSave: () -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onVoiceEnabledChanged: (Boolean) -> Unit = {},
    onVoiceApiKeyChanged: (String) -> Unit = {},
    onToggleVoiceApiKeyVisibility: () -> Unit = {},
    onSaveVoiceApiKey: () -> Unit = {},
    onClearVoiceApiKey: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ElderScreenScaffold(
        title = "模型服务设置",
        onBack = onBack,
        modifier = modifier,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            item {
                VoiceSettingsSection(
                    state = voiceState,
                    onEnabledChanged = onVoiceEnabledChanged,
                    onApiKeyChanged = onVoiceApiKeyChanged,
                    onToggleApiKeyVisibility = onToggleVoiceApiKeyVisibility,
                    onSaveApiKey = onSaveVoiceApiKey,
                    onClearApiKey = onClearVoiceApiKey,
                )
            }
            item {
                Text("API Key", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "用于连接您自己的云端模型服务。",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                StatusCard(state)
            }
            item {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChanged,
                    label = { Text(if (state.isConfigured) "输入新的 API Key" else "输入 API Key") },
                    supportingText = { Text("可以长按输入框粘贴；保存后不会显示完整内容。") },
                    visualTransformation = if (state.isKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = onToggleVisibility,
                            enabled = !state.isSaving,
                        ) {
                            Icon(
                                imageVector = if (state.isKeyVisible) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = if (state.isKeyVisible) {
                                    "隐藏 API Key"
                                } else {
                                    "显示 API Key"
                                },
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !state.isLoading && !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                LargeActionButton(
                    text = if (state.isSaving) "正在保存" else "加密保存到本机",
                    onClick = onSave,
                    icon = Icons.Rounded.Key,
                    enabled = state.canSave,
                    contentDescription = "将 API Key 加密保存在老人设备",
                )
            }
            if (state.isConfigured) {
                item {
                    LargeActionButton(
                        text = "删除本机 API Key",
                        onClick = onRequestDelete,
                        icon = Icons.Rounded.Delete,
                        enabled = !state.isSaving,
                        outlined = true,
                        contentDescription = "删除老人设备保存的模型 API Key",
                    )
                }
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(ElderSpacing.medium)) {
                        Text("隐私说明", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "API Key 只会加密保存在这部手机中，不会上传中台、同步给家属或写入聊天记录。",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "模型地址、模型名称和生成参数可由家属远程设置。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("删除 API Key？") },
            text = { Text("删除后，在重新设置密钥前可能无法使用云端模型聊天。") },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) { Text("暂不删除") }
            },
        )
    }
}

@Composable
private fun StatusCard(state: ModelApiKeyUiState) {
    val containerColor = when {
        state.isError -> MaterialTheme.colorScheme.errorContainer
        state.isConfigured -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(ElderSpacing.medium)) {
            Text(
                text = when {
                    state.isLoading -> "正在检查本机密钥"
                    state.isConfigured -> "本机已配置 API Key"
                    else -> "本机尚未配置 API Key"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            state.maskedKey?.let {
                Text("当前密钥：$it", style = MaterialTheme.typography.bodyLarge)
            }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
