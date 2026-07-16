package com.example.silverageassistant.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing
import com.example.silverageassistant.ui.theme.SilverAgeAssistantTheme

@Composable
fun ConversationRoute(
    onBack: () -> Unit,
    viewModel: ConversationViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    ConversationScreen(
        uiState = uiState,
        onPrimaryAction = viewModel::onPrimaryAction,
        onCancel = viewModel::cancel,
        onReplay = viewModel::replay,
        onBack = onBack,
    )
}

@Composable
fun ConversationScreen(
    uiState: ConversationUiState,
    onPrimaryAction: () -> Unit,
    onCancel: () -> Unit,
    onReplay: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElderScreenScaffold(title = "和我说话", onBack = onBack, modifier = modifier) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            item {
                StatusCard(uiState = uiState)
            }
            if (uiState.transcript.isNotBlank()) {
                item {
                    MessageCard(title = "我听到您说", message = uiState.transcript)
                }
            }
            if (uiState.reply.isNotBlank()) {
                item {
                    MessageCard(title = "银龄助手回答", message = uiState.reply, isReply = true)
                }
            }
            item {
                ConversationActions(
                    uiState = uiState,
                    onPrimaryAction = onPrimaryAction,
                    onCancel = onCancel,
                    onReplay = onReplay,
                )
                Spacer(modifier = Modifier.height(ElderSpacing.large))
            }
        }
    }
}

@Composable
private fun StatusCard(uiState: ConversationUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ElderSpacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            if (uiState.isProcessing) {
                CircularProgressIndicator()
            } else {
                Icon(
                    imageVector = if (uiState.phase == ConversationPhase.Listening) {
                        Icons.Rounded.Mic
                    } else {
                        Icons.Rounded.GraphicEq
                    },
                    contentDescription = null,
                )
            }
            Text(
                text = uiState.phase.statusText,
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = uiState.phase.guidanceText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MessageCard(title: String, message: String, isReply: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isReply) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(ElderSpacing.medium)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(ElderSpacing.small))
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ConversationActions(
    uiState: ConversationUiState,
    onPrimaryAction: () -> Unit,
    onCancel: () -> Unit,
    onReplay: () -> Unit,
) {
    val primaryLabel = when (uiState.phase) {
        ConversationPhase.Idle -> "开始说话"
        ConversationPhase.Listening -> "我说完了"
        ConversationPhase.Transcribing,
        ConversationPhase.Thinking -> "请稍候"
        ConversationPhase.Speaking -> "停止播放"
    }
    val primaryIcon = when (uiState.phase) {
        ConversationPhase.Speaking -> Icons.Rounded.Stop
        else -> Icons.Rounded.Mic
    }

    Column(verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium)) {
        LargeActionButton(
            text = primaryLabel,
            icon = primaryIcon,
            enabled = !uiState.isProcessing,
            onClick = onPrimaryAction,
        )
        if (uiState.reply.isNotBlank() && uiState.phase == ConversationPhase.Idle) {
            LargeActionButton(
                text = "再听一遍",
                icon = Icons.Rounded.Replay,
                outlined = true,
                onClick = onReplay,
            )
        }
        if (uiState.phase != ConversationPhase.Idle) {
            LargeActionButton(
                text = "取消这次任务",
                outlined = true,
                onClick = onCancel,
            )
        }
        Text(
            text = "当前是界面演示，不会录音或连接网络。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 850)
@Composable
private fun ConversationPreview() {
    SilverAgeAssistantTheme(darkTheme = false) {
        ConversationScreen(
            uiState = ConversationUiState(),
            onPrimaryAction = {},
            onCancel = {},
            onReplay = {},
            onBack = {},
        )
    }
}
