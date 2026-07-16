package com.example.silverageassistant.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
fun FamilySetupRoute(
    onBack: () -> Unit,
    onCompleted: () -> Unit,
    viewModel: OnboardingViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    FamilySetupScreen(
        draft = uiState.familyDraft,
        errors = uiState.familyErrors,
        persistenceMessage = uiState.persistenceMessage,
        networkMessage = uiState.networkMessage,
        isSubmitting = uiState.isSubmitting,
        onNameChange = viewModel::updateFamilyName,
        onMobileNumberChange = viewModel::updateMobileNumber,
        onElderNameChange = viewModel::updateElderDisplayName,
        onElderMobileNumberChange = viewModel::updateFamilyElderMobileNumber,
        onRelationshipChange = viewModel::updateRelationship,
        onEmergencyContactChange = viewModel::updateEmergencyContact,
        onContinue = { viewModel.submitFamilySetup(onCompleted) },
        onBack = onBack,
    )
}

@Composable
fun FamilySetupScreen(
    draft: FamilySetupDraft,
    errors: FamilySetupErrors,
    persistenceMessage: String?,
    networkMessage: String?,
    isSubmitting: Boolean,
    onNameChange: (String) -> Unit,
    onMobileNumberChange: (String) -> Unit,
    onElderNameChange: (String) -> Unit,
    onElderMobileNumberChange: (String) -> Unit,
    onRelationshipChange: (FamilyRelationship) -> Unit,
    onEmergencyContactChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElderScreenScaffold(title = "家属信息", onBack = onBack, modifier = modifier) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            item {
                Text(
                    text = "填写家属和老人信息",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "中台接入后将注册家属、创建老人档案并生成一次性绑定码。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (persistenceMessage != null) {
                    Text(
                        text = persistenceMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (networkMessage != null) {
                    Text(
                        text = networkMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                SetupTextField(
                    value = draft.displayName,
                    onValueChange = onNameChange,
                    label = "怎么称呼您",
                    placeholder = "例如：小林",
                    error = errors.displayName,
                    testTag = OnboardingTestTags.FAMILY_NAME_INPUT,
                )
            }
            item {
                SetupTextField(
                    value = draft.mobileNumber,
                    onValueChange = onMobileNumberChange,
                    label = "家属手机号",
                    placeholder = "仅用于家属登录和联系",
                    error = errors.mobileNumber,
                    keyboardType = KeyboardType.Phone,
                    testTag = OnboardingTestTags.FAMILY_MOBILE_INPUT,
                )
            }
            item {
                SetupTextField(
                    value = draft.elderDisplayName,
                    onValueChange = onElderNameChange,
                    label = "老人的称呼",
                    placeholder = "例如：王阿姨",
                    error = errors.elderDisplayName,
                    testTag = OnboardingTestTags.FAMILY_ELDER_NAME_INPUT,
                )
            }
            item {
                SetupTextField(
                    value = draft.elderMobileNumber,
                    onValueChange = onElderMobileNumberChange,
                    label = "老人手机号",
                    placeholder = "用于创建和识别老人档案",
                    error = errors.elderMobileNumber,
                    keyboardType = KeyboardType.Phone,
                    testTag = OnboardingTestTags.FAMILY_ELDER_MOBILE_INPUT,
                )
            }
            item {
                Text("您与老人的关系", style = MaterialTheme.typography.titleLarge)
                FamilyRelationship.entries.forEach { relationship ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = draft.relationship == relationship,
                                role = Role.RadioButton,
                                onClick = { onRelationshipChange(relationship) },
                            )
                            .padding(vertical = ElderSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = draft.relationship == relationship,
                            onClick = null,
                        )
                        Text(
                            text = relationship.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = ElderSpacing.small),
                        )
                    }
                }
                if (errors.relationship != null) {
                    Text(
                        text = errors.relationship,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = draft.emergencyContact,
                                role = Role.Checkbox,
                                onValueChange = onEmergencyContactChange,
                            )
                            .padding(ElderSpacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
                    ) {
                        Checkbox(checked = draft.emergencyContact, onCheckedChange = null)
                        Column {
                            Text("作为紧急联系人", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "绑定完成后，可接收老人主动发出的紧急事件。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    text = "手机号只用于注册、创建老人档案和绑定，不会写入日志。开发测试暂用 HTTP，正式版本必须使用 HTTPS。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(ElderSpacing.medium))
                LargeActionButton(
                    text = if (isSubmitting) "正在注册并生成绑定码…" else "保存并进入家属模式",
                    onClick = onContinue,
                    enabled = !isSubmitting,
                )
                Spacer(modifier = Modifier.height(ElderSpacing.large))
            }
        }
    }
}

@Composable
private fun SetupTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    error: String?,
    keyboardType: KeyboardType = KeyboardType.Text,
    testTag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
}
