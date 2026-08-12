package com.example.silverageassistant.ui.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.silverageassistant.data.middleserver.FamilyReminderHistoryItem
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FamilyReminderHistoryRoute(
    elderId: String?,
    onBack: () -> Unit,
    viewModel: FamilyReminderHistoryViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(elderId) { viewModel.refresh(elderId) }
    FamilyReminderHistoryScreen(
        state = state,
        onRefresh = { viewModel.refresh(elderId) },
        onClear = viewModel::clearReminder,
        onBack = onBack,
    )
}

@Composable
fun FamilyReminderHistoryScreen(
    state: FamilyReminderHistoryUiState,
    onRefresh: () -> Unit,
    onClear: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElderScreenScaffold(title = "提醒记录", onBack = onBack, modifier = modifier) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            state.message?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!state.isLoading && state.reminders.isEmpty() && state.message == null) {
                item { Text("目前没有提醒记录", style = MaterialTheme.typography.titleMedium) }
            }
            items(state.reminders, key = FamilyReminderHistoryItem::commandId) { reminder ->
                FamilyReminderHistoryCard(
                    item = reminder,
                    isClearing = reminder.commandId in state.archivingReminderIds,
                    onClear = { onClear(reminder.commandId) },
                )
            }
            item {
                LargeActionButton(
                    text = if (state.isLoading) "正在刷新" else "刷新记录",
                    contentDescription = "从中台刷新老人提醒记录",
                    icon = Icons.Rounded.Refresh,
                    enabled = !state.isLoading,
                    outlined = true,
                    onClick = onRefresh,
                )
                Spacer(Modifier.height(ElderSpacing.large))
            }
        }
    }
}

@Composable
private fun FamilyReminderHistoryCard(
    item: FamilyReminderHistoryItem,
    isClearing: Boolean,
    onClear: () -> Unit,
) {
    val completed = item.completionStatus == "COMPLETED"
    val statusText = if (completed) "已完成" else "未完成"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${item.title}，$statusText" },
        colors = CardDefaults.cardColors(
            containerColor = if (completed) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            Modifier.padding(ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.title, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (completed) Icons.Rounded.CheckCircle else Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = if (completed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(statusText, style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(item.content, style = MaterialTheme.typography.bodyLarge)
            Text("截止时间：${formatReminderTime(item.scheduledAt)}", style = MaterialTheme.typography.bodyMedium)
            item.completedAt?.let {
                Text("确认时间：${formatReminderTime(it)}", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(
                onClick = onClear,
                enabled = !isClearing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                Text(if (isClearing) "正在清除" else "清除")
            }
        }
    }
}

private fun formatReminderTime(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(
        DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA),
    )
}.getOrDefault(value)
