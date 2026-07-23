package com.example.silverageassistant.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme

object ConversationTestTags {
    const val MESSAGE_LIST = "conversation_message_list"
    const val TEXT_INPUT = "conversation_text_input"
}

@Composable
fun ConversationRoute(
    onBack: () -> Unit,
    viewModel: ConversationViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.confirmPendingPhoneCall(direct = granted)
    }
    ConversationScreen(
        uiState = uiState,
        onDraftChange = viewModel::updateDraft,
        onInputModeChange = viewModel::selectInputMode,
        onSendText = viewModel::sendTextMessage,
        onCancel = viewModel::cancelResponse,
        onRetry = viewModel::retryLastMessage,
        onDismissPhoneCall = viewModel::dismissPendingPhoneCall,
        onConfirmPhoneCall = {
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.confirmPendingPhoneCall(direct = true)
            } else {
                callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            }
        },
        onBack = onBack,
    )
}

@Composable
fun ConversationScreen(
    uiState: ConversationUiState,
    onDraftChange: (String) -> Unit,
    onInputModeChange: (ConversationInputMode) -> Unit,
    onSendText: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismissPhoneCall: () -> Unit = {},
    onConfirmPhoneCall: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val latestMessageLength = uiState.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(uiState.messages.size, latestMessageLength) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }
    ElderScreenScaffold(
        title = "和我说话",
        onBack = onBack,
        modifier = modifier,
        actions = {
            ContextUsageIndicator(
                usedTokens = uiState.contextTokens,
                totalTokens = uiState.contextWindowTokens,
                progress = uiState.contextUsageFraction,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
        ) {
            ConversationMessageList(
                messages = uiState.messages,
                listState = listState,
                modifier = Modifier.weight(1f),
            )
            ConversationComposer(
                uiState = uiState,
                onDraftChange = onDraftChange,
                onInputModeChange = onInputModeChange,
                onSendText = onSendText,
            )
            VoiceActions(
                uiState = uiState,
                onCancel = onCancel,
                onRetry = onRetry,
            )
        }
    }
    uiState.pendingPhoneCall?.let { pendingCall ->
        AlertDialog(
            onDismissRequest = onDismissPhoneCall,
            title = {
                Text(text = "确认打电话")
            },
            text = {
                Text(
                    text = "现在给${pendingCall.relationship}“${pendingCall.displayName}”打电话吗？",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                Button(onClick = onConfirmPhoneCall) {
                    Text(text = "确认拨打")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPhoneCall) {
                    Text(text = "取消")
                }
            },
        )
    }
}

@Composable
private fun ContextUsageIndicator(
    usedTokens: Long,
    totalTokens: Long,
    progress: Float,
) {
    val percentage = (progress * 100).toInt()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.semantics {
            contentDescription = "上下文已使用百分之$percentage，$usedTokens 个 Token，共 $totalTokens 个"
        }.size(40.dp),
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 3.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            text = "$percentage%",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ConversationMessageList(
    messages: List<ConversationMessage>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ElderSpacing.medium)
            .testTag(ConversationTestTags.MESSAGE_LIST),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = ElderSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
    ) {
        items(messages, key = ConversationMessage::id) { message ->
            MessageBubble(message = message)
        }
    }
}

@Composable
private fun MessageBubble(message: ConversationMessage) {
    val isElder = message.speaker == ConversationSpeaker.Elder
    val speakerLabel = if (isElder) "我" else "银龄助手"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$speakerLabel：${message.text}"
            },
        contentAlignment = if (isElder) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 330.dp),
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = if (isElder) 22.dp else 6.dp,
                bottomEnd = if (isElder) 6.dp else 22.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isElder) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (isElder) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            ),
        ) {
            Column(modifier = Modifier.padding(ElderSpacing.medium)) {
                Text(
                    text = speakerLabel,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.text.ifBlank {
                        if (message.status == ConversationMessageStatus.Streaming) {
                            "正在准备回答…"
                        } else {
                            ""
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (message.status == ConversationMessageStatus.Interrupted) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "回答已停止",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationComposer(
    uiState: ConversationUiState,
    onDraftChange: (String) -> Unit,
    onInputModeChange: (ConversationInputMode) -> Unit,
    onSendText: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ElderSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(ElderSpacing.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ElderSpacing.small),
        ) {
            InputModeButton(
                text = "打字",
                selected = uiState.inputMode == ConversationInputMode.Keyboard,
                icon = Icons.Rounded.Keyboard,
                onClick = {
                    onInputModeChange(ConversationInputMode.Keyboard)
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
                modifier = Modifier.weight(1f),
            )
            InputModeButton(
                text = "手写",
                selected = uiState.inputMode == ConversationInputMode.Handwriting,
                icon = Icons.Rounded.Draw,
                onClick = {
                    onInputModeChange(ConversationInputMode.Handwriting)
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = uiState.draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(ConversationTestTags.TEXT_INPUT),
            enabled = uiState.phase == ConversationPhase.Idle,
            minLines = 2,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyLarge,
            label = {
                Text(
                    if (uiState.inputMode == ConversationInputMode.Handwriting) {
                        "在系统手写键盘中书写"
                    } else {
                        "输入想说的话"
                    },
                )
            },
            supportingText = {
                Text(
                    if (uiState.inputMode == ConversationInputMode.Handwriting) {
                        "请在手机键盘中切换到“手写”，识别结果会显示在这里。"
                    } else {
                        "输入完成后，点右边的发送按钮。"
                    },
                )
            },
            trailingIcon = {
                Button(
                    onClick = {
                        onSendText()
                        keyboardController?.hide()
                    },
                    enabled = uiState.canSendText,
                    modifier = Modifier.size(width = 72.dp, height = 56.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "发送文字消息",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (uiState.canSendText) {
                        onSendText()
                        keyboardController?.hide()
                    }
                },
            ),
        )
    }
}

@Composable
private fun InputModeButton(
    text: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Icon(imageVector = icon, contentDescription = null)
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(56.dp)) {
            content()
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(56.dp)) {
            content()
        }
    }
}

@Composable
private fun VoiceActions(
    uiState: ConversationUiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ElderSpacing.medium,
                end = ElderSpacing.medium,
                top = ElderSpacing.small,
                bottom = ElderSpacing.medium,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (uiState.errorMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(ElderSpacing.medium),
                )
            }
            Spacer(modifier = Modifier.height(ElderSpacing.small))
        }
        LargeActionButton(
            text = "语音功能稍后接入",
            icon = Icons.Rounded.Mic,
            enabled = false,
            onClick = {},
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            if (uiState.canRetry && !uiState.isProcessing) {
                TextButton(onClick = onRetry) {
                    Icon(imageVector = Icons.Rounded.Replay, contentDescription = null)
                    Text("重新生成回答")
                }
            }
            if (uiState.isProcessing) {
                TextButton(onClick = onCancel) {
                    Icon(imageVector = Icons.Rounded.Replay, contentDescription = null)
                    Text("停止回答")
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 850)
@Composable
private fun ConversationPreview() {
    SilverAgeAssistantTheme(darkTheme = false) {
        ConversationScreen(
            uiState = ConversationUiState(),
            onDraftChange = {},
            onInputModeChange = {},
            onSendText = {},
            onCancel = {},
            onRetry = {},
            onBack = {},
        )
    }
}
