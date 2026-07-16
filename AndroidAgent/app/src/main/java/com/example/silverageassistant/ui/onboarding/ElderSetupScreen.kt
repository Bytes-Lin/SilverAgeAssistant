package com.example.silverageassistant.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing

@Composable
fun ElderSetupRoute(
    onBack: () -> Unit,
    onCompleted: () -> Unit,
    viewModel: OnboardingViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    ElderSetupScreen(
        draft = uiState.elderDraft,
        errors = uiState.elderErrors,
        persistenceMessage = uiState.persistenceMessage,
        onNameChange = viewModel::updateElderName,
        onFamilyMobileNumberChange = viewModel::updateElderFamilyMobileNumber,
        onBindingCodeChange = viewModel::updateBindingCode,
        onSharingConsentChange = viewModel::updateSharingConsent,
        onContinue = {
            if (viewModel.submitElderSetup()) onCompleted()
        },
        onBack = onBack,
    )
}

@Composable
fun ElderSetupScreen(
    draft: ElderSetupDraft,
    errors: ElderSetupErrors,
    persistenceMessage: String?,
    onNameChange: (String) -> Unit,
    onFamilyMobileNumberChange: (String) -> Unit,
    onBindingCodeChange: (String) -> Unit,
    onSharingConsentChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElderScreenScaffold(title = "老人信息", onBack = onBack, modifier = modifier) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            item {
                Text(
                    text = "先填写简单信息",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "这些信息用于识别您的设备并准备绑定家人。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (persistenceMessage != null) {
                    Text(
                        text = persistenceMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = draft.familyMobileNumber,
                    onValueChange = onFamilyMobileNumberChange,
                    label = { Text("家人手机号（可以稍后填写）") },
                    placeholder = { Text("需要和绑定码一起填写") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    isError = errors.familyMobileNumber != null,
                    supportingText = {
                        Text(errors.familyMobileNumber ?: "请输入生成绑定码的家属手机号")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(OnboardingTestTags.ELDER_FAMILY_MOBILE_INPUT),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.displayName,
                    onValueChange = onNameChange,
                    label = { Text("怎么称呼您") },
                    placeholder = { Text("例如：王阿姨") },
                    singleLine = true,
                    isError = errors.displayName != null,
                    supportingText = errors.displayName?.let { error -> { Text(error) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(OnboardingTestTags.ELDER_NAME_INPUT),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.bindingCode,
                    onValueChange = onBindingCodeChange,
                    label = { Text("家人绑定码（可以稍后填写）") },
                    placeholder = { Text("6位数字") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = errors.bindingCode != null,
                    supportingText = {
                        Text(errors.bindingCode ?: "由家属注册后通过中台生成，需要和手机号一起校验")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(OnboardingTestTags.ELDER_BINDING_CODE_INPUT),
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = draft.sharingConsent,
                                role = Role.Checkbox,
                                onValueChange = onSharingConsentChange,
                            )
                            .padding(ElderSpacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
                    ) {
                        Checkbox(checked = draft.sharingConsent, onCheckedChange = null)
                        Column {
                            Text("同意绑定后共享状态摘要", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "只共享提醒确认、报平安和紧急事件等授权信息，不共享完整聊天和模型密钥。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (errors.sharingConsent != null) {
                    Text(
                        text = errors.sharingConsent,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Text(
                    text = "家人手机号属于敏感信息。本阶段不会上传或写入日志；中台接入后只用于绑定校验。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(ElderSpacing.medium))
                LargeActionButton(text = "保存并进入老人模式", onClick = onContinue)
                Spacer(modifier = Modifier.height(ElderSpacing.large))
            }
        }
    }
}
