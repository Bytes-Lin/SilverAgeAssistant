package com.example.silverageassistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.example.silverageassistant.ui.theme.ElderSpacing

@Composable
fun VoiceSettingsSection(
    state: VoiceSettingsUiState,
    onEnabledChanged: (Boolean) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onSaveApiKey: () -> Unit,
    onClearApiKey: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("语音交互", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (state.enabled) "已开启：聊天、通知和新闻可以播报" else "已关闭：仍可正常打字和查看文字",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onEnabledChanged,
                    enabled = !state.isLoading,
                )
            }

            Text(
                "语音 API Key 只加密保存在这台老人手机，不会发送给家属或中台。",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.apiKeyConfigured) {
                Text("当前密钥：${state.maskedApiKey ?: "已配置"}")
            }
            OutlinedTextField(
                value = state.apiKeyDraft,
                onValueChange = onApiKeyChanged,
                label = { Text(if (state.apiKeyConfigured) "输入新的语音 API Key" else "输入语音 API Key") },
                singleLine = true,
                visualTransformation = if (state.apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = onToggleApiKeyVisibility) {
                        Icon(
                            if (state.apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (state.apiKeyVisible) "隐藏语音密钥" else "显示语音密钥",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !state.isLoading && !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ElderSpacing.small),
            ) {
                Button(
                    onClick = onSaveApiKey,
                    enabled = state.apiKeyDraft.isNotBlank() && !state.isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存语音密钥")
                }
                if (state.apiKeyConfigured) {
                    OutlinedButton(
                        onClick = onClearApiKey,
                        enabled = !state.isSaving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                        Text("删除")
                    }
                }
            }
            state.message?.let { message ->
                Text(
                    message,
                    color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
