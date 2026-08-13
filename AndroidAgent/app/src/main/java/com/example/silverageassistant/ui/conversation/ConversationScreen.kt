package com.example.silverageassistant.ui.conversation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.example.silverageassistant.domain.voice.VoiceListeningState
import com.example.silverageassistant.domain.voice.VoiceSpeakingState
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme
import kotlinx.coroutines.delay

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
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onMicrophonePermissionResult(granted)
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onPageClosed() }
    }
    ConversationScreen(
        uiState = uiState,
        onDraftChange = viewModel::updateDraft,
        onInputModeChange = viewModel::selectInputMode,
        onSendText = viewModel::sendTextMessage,
        onCancel = viewModel::cancelResponse,
        onRetry = viewModel::retryLastMessage,
        onStartVoice = {
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.startVoiceRecording()
            } else {
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onStopVoice = viewModel::stopVoiceRecording,
        onCancelVoice = viewModel::cancelVoiceRecording,
        onStopVoicePlayback = viewModel::stopVoicePlayback,
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
    onStartVoice: () -> Unit = {},
    onStopVoice: () -> Unit = {},
    onCancelVoice: () -> Unit = {},
    onStopVoicePlayback: () -> Unit = {},
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
                onStartVoice = onStartVoice,
                onStopVoice = onStopVoice,
                onCancelVoice = onCancelVoice,
                onStopVoicePlayback = onStopVoicePlayback,
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
    val composeView = LocalView.current
    var inputActivationRequest by remember { mutableIntStateOf(0) }
    var requestedInputMode by remember { mutableStateOf(uiState.inputMode) }

    // requestFocus、显示输入法和启动原生手写不能挤在按钮的同一个点击帧中：
    // Compose 此时可能还没有建立 InputConnection，系统会静默忽略手写请求。
    // 这里等待编辑器真正激活，让老人一次点击即可进入设备支持的输入模式。
    LaunchedEffect(inputActivationRequest) {
        if (inputActivationRequest == 0 || uiState.phase != ConversationPhase.Idle) return@LaunchedEffect
        focusRequester.requestFocus()
        withFrameNanos { }
        when (requestedInputMode) {
            ConversationInputMode.Keyboard -> {
                restartKeyboardInput(composeView)
                withFrameNanos { }
                keyboardController?.show()
            }
            ConversationInputMode.Handwriting -> {
                keyboardController?.show()
                requestNativeHandwritingWhenReady(composeView)
            }
        }
    }

    fun activateInput(mode: ConversationInputMode) {
        requestedInputMode = mode
        onInputModeChange(mode)
        inputActivationRequest += 1
    }

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
                onClick = { activateInput(ConversationInputMode.Keyboard) },
                modifier = Modifier.weight(1f),
            )
            InputModeButton(
                text = "手写",
                selected = uiState.inputMode == ConversationInputMode.Handwriting,
                icon = Icons.Rounded.Draw,
                onClick = { activateInput(ConversationInputMode.Handwriting) },
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
                        "系统输入法已打开；支持原生手写时会直接进入手写。"
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

/**
 * 切回打字时重启 InputConnection。Android 框架会结束当前 stylus handwriting
 * session，避免老人点击“打字”后仍停留在上一轮手写会话。
 */
private fun restartKeyboardInput(view: View) {
    val inputMethodManager = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
        as? InputMethodManager ?: return
    runCatching { inputMethodManager.restartInput(view) }
}

/**
 * Android 不允许普通应用强制切换第三方输入法的私有“拼音/手写”子模式。
 * Android 13+ 且当前输入法支持系统 stylus handwriting 协议时，可以请求原生
 * 手写会话。请求前最多等待约 500ms，直到 Compose 根视图已取得窗口焦点且
 * InputConnection 激活，避免过早调用被系统静默忽略。
 */
private suspend fun requestNativeHandwritingWhenReady(view: View) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val inputMethodManager = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
        as? InputMethodManager ?: return

    var inputConnectionReady = view.hasWindowFocus() && inputMethodManager.isActive(view)
    repeat(INPUT_CONNECTION_WAIT_ATTEMPTS) {
        if (inputConnectionReady) return@repeat
        delay(INPUT_CONNECTION_WAIT_MILLIS)
        inputConnectionReady = view.hasWindowFocus() && inputMethodManager.isActive(view)
    }

    if (!inputConnectionReady) return
    val handwritingAvailable = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        inputMethodManager.isStylusHandwritingAvailable
    if (handwritingAvailable) {
        runCatching { inputMethodManager.startStylusHandwriting(view) }
    }
}

private const val INPUT_CONNECTION_WAIT_ATTEMPTS = 10
private const val INPUT_CONNECTION_WAIT_MILLIS = 50L

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
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onStopVoicePlayback: () -> Unit,
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
        uiState.voiceMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = ElderSpacing.small),
            )
        }
        if (uiState.voiceEnabled) {
            HoldToTalkButton(
                listeningState = uiState.voiceListeningState,
                enabled = uiState.canStartVoice ||
                    uiState.voiceListeningState != VoiceListeningState.IDLE,
                onStart = onStartVoice,
                onStop = onStopVoice,
                onCancel = onCancelVoice,
            )
            if (uiState.voiceSpeakingState == VoiceSpeakingState.SPEAKING) {
                TextButton(onClick = onStopVoicePlayback) {
                    Text("停止播报")
                }
            }
        } else {
            LargeActionButton(
                text = "语音交互已关闭",
                icon = Icons.Rounded.Mic,
                enabled = false,
                onClick = {},
            )
        }
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

@Composable
private fun HoldToTalkButton(
    listeningState: VoiceListeningState,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val label = when (listeningState) {
        VoiceListeningState.IDLE -> "按住说话"
        VoiceListeningState.LISTENING -> "正在听，松开发送"
        VoiceListeningState.PROCESSING -> "正在识别"
    }
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = label }
            // 录音启动会依次经过 PROCESSING 和 LISTENING。这里不能把状态作为
            // pointerInput 的 key，否则重组会在手指仍按下时取消手势，ACTION_UP 丢失。
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            onStart()
                            if (tryAwaitRelease()) onStop() else onCancel()
                        },
                    )
                }
            },
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(Icons.Rounded.Mic, contentDescription = null)
            Spacer(Modifier.size(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
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
