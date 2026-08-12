package com.example.silverageassistant.ui.family

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddAlert
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.silverageassistant.ui.components.ElderScreenScaffold
import com.example.silverageassistant.ui.components.LargeActionButton
import com.example.silverageassistant.ui.theme.ElderSpacing
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FamilyNotificationRoute(
    elderId: String?,
    elderDisplayName: String,
    onBack: () -> Unit,
    viewModel: FamilyCommunicationViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    FamilyNotificationScreen(
        state = state,
        elderDisplayName = elderDisplayName,
        onContentChange = viewModel::updateNotificationContent,
        onSend = { viewModel.sendNotification(elderId) },
        onBack = onBack,
    )
}

@Composable
fun FamilyNotificationScreen(
    state: FamilyCommunicationUiState,
    elderDisplayName: String,
    onContentChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElderScreenScaffold(title = "发送通知", onBack = onBack, modifier = modifier) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(ElderSpacing.medium)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            Text(
                text = "发送给${elderDisplayName.ifBlank { "老人" }}",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "通知会先交给中台。老人手机联网后会接收，并加入今日提醒。",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = state.notificationContent,
                onValueChange = onContentChange,
                label = { Text("通知内容") },
                supportingText = {
                    Text(state.notificationError ?: "最多 200 个字，避免填写验证码等敏感信息")
                },
                isError = state.notificationError != null,
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            ResultMessage(state)
            LargeActionButton(
                text = if (state.isSubmitting) "正在发送" else "发送通知",
                contentDescription = "通过中台向老人发送通知",
                icon = Icons.AutoMirrored.Rounded.Send,
                enabled = !state.isSubmitting,
                onClick = onSend,
            )
            Spacer(modifier = Modifier.height(ElderSpacing.large))
        }
    }
}

@Composable
fun FamilyReminderRoute(
    elderId: String?,
    elderDisplayName: String,
    onBack: () -> Unit,
    viewModel: FamilyCommunicationViewModel,
) {
    val state by viewModel.uiState.collectAsState()
    FamilyReminderScreen(
        state = state,
        elderDisplayName = elderDisplayName,
        onTitleChange = viewModel::updateReminderTitle,
        onContentChange = viewModel::updateReminderContent,
        onDateChange = viewModel::selectReminderDate,
        onTimeChange = viewModel::selectReminderTime,
        onCreate = { viewModel.createReminder(elderId) },
        onBack = onBack,
    )
}

@Composable
fun FamilyReminderScreen(
    state: FamilyCommunicationUiState,
    elderDisplayName: String,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onTimeChange: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val selectedDate = runCatching { LocalDate.parse(state.reminderDate) }
        .getOrDefault(LocalDate.now())
    val selectedTime = runCatching {
        LocalTime.parse(state.reminderTime, DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault(LocalTime.now().plusHours(1))
    val showDatePicker = {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                onDateChange(LocalDate.of(year, month + 1, dayOfMonth).toString())
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth,
        ).apply {
            datePicker.minDate = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.show()
    }
    val showTimePicker = {
        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                onTimeChange(String.format(Locale.ROOT, "%02d:%02d", hourOfDay, minute))
            },
            selectedTime.hour,
            selectedTime.minute,
            true,
        ).show()
    }
    ElderScreenScaffold(title = "创建提醒", onBack = onBack, modifier = modifier) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(ElderSpacing.medium)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ElderSpacing.medium),
        ) {
            Text(
                text = "为${elderDisplayName.ifBlank { "老人" }}创建提醒",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "请设置完成事项的截止时间。老人未确认完成时，截止后每小时提醒一次。",
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedTextField(
                value = state.reminderTitle,
                onValueChange = onTitleChange,
                label = { Text("提醒名称") },
                supportingText = { Text(state.reminderTitleError ?: "例如：量血压") },
                isError = state.reminderTitleError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.reminderContent,
                onValueChange = onContentChange,
                label = { Text("提醒内容") },
                supportingText = { Text(state.reminderContentError ?: "请写清楚老人要做什么") },
                isError = state.reminderContentError != null,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            LargeActionButton(
                text = "截止日期：${state.reminderDate}",
                contentDescription = "选择完成截止日期，当前为${state.reminderDate}",
                icon = Icons.Rounded.CalendarMonth,
                outlined = true,
                onClick = showDatePicker,
            )
            LargeActionButton(
                text = "截止时间：${state.reminderTime}",
                contentDescription = "选择完成截止时间，当前为${state.reminderTime}",
                icon = Icons.Rounded.Schedule,
                outlined = true,
                onClick = showTimePicker,
            )
            state.reminderDateTimeError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ResultMessage(state)
            LargeActionButton(
                text = if (state.isSubmitting) "正在创建" else "创建提醒",
                contentDescription = "通过中台为老人创建提醒",
                icon = Icons.Rounded.AddAlert,
                enabled = !state.isSubmitting,
                onClick = onCreate,
            )
            Spacer(modifier = Modifier.height(ElderSpacing.large))
        }
    }
}

@Composable
private fun ResultMessage(state: FamilyCommunicationUiState) {
    val message = state.resultMessage ?: return
    Surface(
        color = if (state.resultIsError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(ElderSpacing.medium),
        )
    }
}
