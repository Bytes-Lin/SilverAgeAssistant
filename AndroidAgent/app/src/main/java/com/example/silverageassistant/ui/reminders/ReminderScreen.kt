package com.example.silverageassistant.ui.reminders

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
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing

@Composable
fun ReminderRoute(
    onBack: () -> Unit,
    onContactFamily: () -> Unit,
    viewModel: ReminderViewModel = viewModel(),
) {
    val reminders by viewModel.reminders.collectAsState()
    ReminderScreen(
        reminders = reminders,
        onCompleted = viewModel::markCompleted,
        onSnooze = viewModel::snooze,
        onContactFamily = onContactFamily,
        onBack = onBack,
    )
}

@Composable
fun ReminderScreen(
    reminders: List<ReminderItemUi>,
    onCompleted: (String) -> Unit,
    onSnooze: (String) -> Unit,
    onContactFamily: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElderScreenScaffold(title = "今日提醒", onBack = onBack, modifier = modifier) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = ElderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            item {
                Text(
                    text = "今天有 ${reminders.size} 条提醒",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            items(reminders, key = { it.id }) { reminder ->
                ReminderCard(
                    reminder = reminder,
                    onCompleted = { onCompleted(reminder.id) },
                    onSnooze = { onSnooze(reminder.id) },
                )
            }
            item {
                LargeActionButton(
                    text = "联系家人",
                    outlined = true,
                    onClick = onContactFamily,
                )
                Spacer(modifier = Modifier.height(ElderSpacing.large))
            }
        }
    }
}

@Composable
private fun ReminderCard(
    reminder: ReminderItemUi,
    onCompleted: () -> Unit,
    onSnooze: () -> Unit,
) {
    val statusText = when (reminder.status) {
        ReminderStatus.Pending -> "等待确认"
        ReminderStatus.Completed -> "已确认完成"
        ReminderStatus.Snoozed -> "已设置稍后提醒"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${reminder.time}，${reminder.title}，$statusText"
            },
        colors = CardDefaults.cardColors(
            containerColor = if (reminder.status == ReminderStatus.Pending) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(ElderSpacing.medium)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = reminder.time,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                )
            }
            Spacer(modifier = Modifier.height(ElderSpacing.small))
            Text(text = reminder.title, style = MaterialTheme.typography.titleLarge)
            Text(text = reminder.detail, style = MaterialTheme.typography.bodyLarge)
            if (reminder.status == ReminderStatus.Pending) {
                Spacer(modifier = Modifier.height(ElderSpacing.medium))
                LargeActionButton(
                    text = "我已完成",
                    icon = Icons.Rounded.CheckCircle,
                    onClick = onCompleted,
                )
                Spacer(modifier = Modifier.height(ElderSpacing.small))
                LargeActionButton(
                    text = "稍后提醒",
                    icon = Icons.Rounded.Schedule,
                    outlined = true,
                    onClick = onSnooze,
                )
            }
        }
    }
}
