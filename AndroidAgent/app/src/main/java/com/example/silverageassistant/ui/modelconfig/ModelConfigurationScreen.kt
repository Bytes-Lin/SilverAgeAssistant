package com.example.silverageassistant.ui.modelconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.silverageassistant.data.model.OpenAiCompatibleDialect
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing

@Composable
fun ModelConfigurationRoute(
    elderId: String?,
    onBack: () -> Unit,
    viewModel: ModelConfigurationViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(elderId) {
        viewModel.loadForFamily(elderId)
    }
    ModelConfigurationScreen(
        state = state,
        onBack = onBack,
        onBaseUrlChanged = viewModel::updateBaseUrl,
        onModelChanged = viewModel::updateModel,
        onDialectChanged = viewModel::updateDialect,
        onContextWindowTokensChanged = viewModel::updateContextWindowTokens,
        onMaxTokensChanged = viewModel::updateMaxOutputTokens,
        onTemperatureChanged = viewModel::updateTemperature,
        onTopPChanged = viewModel::updateTopP,
        onTopKChanged = viewModel::updateTopK,
        onSave = { viewModel.saveForFamily(elderId) },
    )
}

@Composable
fun ModelConfigurationScreen(
    state: ModelConfigurationUiState,
    onBack: () -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onDialectChanged: (OpenAiCompatibleDialect) -> Unit,
    onContextWindowTokensChanged: (String) -> Unit,
    onMaxTokensChanged: (String) -> Unit,
    onTemperatureChanged: (String) -> Unit,
    onTopPChanged: (String) -> Unit,
    onTopKChanged: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElderScreenScaffold(
        title = "模型配置",
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
                Text(
                    "为老人设置模型服务",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "保存后由中台下发，老人端联网时自动更新。",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = onBaseUrlChanged,
                    label = { Text("模型服务地址") },
                    supportingText = { Text("例如：https://api.example.com/v1") },
                    singleLine = true,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = state.model,
                    onValueChange = onModelChanged,
                    label = { Text("模型名称") },
                    singleLine = true,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text("接口兼容模式", style = MaterialTheme.typography.titleLarge)
                DialectOption(
                    label = "llama.cpp",
                    selected = state.dialect == OpenAiCompatibleDialect.LlamaCpp,
                    onClick = { onDialectChanged(OpenAiCompatibleDialect.LlamaCpp) },
                    enabled = !state.isSaving,
                )
                DialectOption(
                    label = "标准 OpenAI 兼容",
                    selected = state.dialect == OpenAiCompatibleDialect.Standard,
                    onClick = { onDialectChanged(OpenAiCompatibleDialect.Standard) },
                    enabled = !state.isSaving,
                )
            }
            item {
                NumberField(
                    value = state.contextWindowTokens,
                    onValueChange = onContextWindowTokensChanged,
                    label = "上下文长度 Token",
                    supportingText = "1024—2000000，且不能小于最大生成 Token",
                    enabled = !state.isSaving,
                )
            }
            item {
                NumberField(
                    value = state.maxOutputTokens,
                    onValueChange = onMaxTokensChanged,
                    label = "最大生成 Token",
                    supportingText = "64—8192",
                    enabled = !state.isSaving,
                )
            }
            item {
                Text("采样参数", style = MaterialTheme.typography.titleLarge)
                NumberField(
                    value = state.temperature,
                    onValueChange = onTemperatureChanged,
                    label = "Temperature",
                    supportingText = "0—2",
                    enabled = !state.isSaving,
                    decimal = true,
                )
                Spacer(modifier = Modifier.height(ElderSpacing.small))
                NumberField(
                    value = state.topP,
                    onValueChange = onTopPChanged,
                    label = "Top-p",
                    supportingText = "0—1",
                    enabled = !state.isSaving,
                    decimal = true,
                )
                Spacer(modifier = Modifier.height(ElderSpacing.small))
                NumberField(
                    value = state.topK,
                    onValueChange = onTopKChanged,
                    label = "Top-k",
                    supportingText = "0—1000",
                    enabled = !state.isSaving,
                )
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(ElderSpacing.medium)) {
                        Text("API Key 安全说明", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "API Key 不会经过中台，也不能在此页面填写。它只能加密保存在老人设备中。开发用的无密钥 llama-server 可以直接使用以上配置。",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "日常聊天思考模式固定关闭。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            state.fieldError?.let { message ->
                item { ResultMessage(message = message, isError = true) }
            }
            state.resultMessage?.let { message ->
                item { ResultMessage(message = message, isError = state.resultIsError) }
            }
            item {
                LargeActionButton(
                    text = if (state.isSaving) "正在保存" else "保存并下发",
                    onClick = onSave,
                    icon = Icons.Rounded.CloudUpload,
                    enabled = !state.isLoading && !state.isSaving,
                    contentDescription = "保存模型配置并通过中台下发给老人端",
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DialectOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String,
    enabled: Boolean,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ResultMessage(message: String, isError: Boolean) {
    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(ElderSpacing.medium),
        )
    }
}
