package com.example.silverageassistant.ui.reminders

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ReminderStatus {
    Pending,
    Completed,
    Snoozed,
}

data class ReminderItemUi(
    val id: String,
    val time: String,
    val title: String,
    val detail: String,
    val status: ReminderStatus = ReminderStatus.Pending,
)

class ReminderViewModel : ViewModel() {
    private val _reminders = MutableStateFlow(mockReminders)
    val reminders: StateFlow<List<ReminderItemUi>> = _reminders.asStateFlow()

    fun markCompleted(id: String) = updateStatus(id, ReminderStatus.Completed)

    fun snooze(id: String) = updateStatus(id, ReminderStatus.Snoozed)

    private fun updateStatus(id: String, status: ReminderStatus) {
        _reminders.update { reminders ->
            reminders.map { reminder ->
                if (reminder.id == id) reminder.copy(status = status) else reminder
            }
        }
    }

    private companion object {
        val mockReminders = listOf(
            ReminderItemUi("medicine", "上午 8:00", "服药提醒", "请按家人设置的计划服药。"),
            ReminderItemUi("water", "上午 10:00", "喝水提醒", "喝一杯温水。"),
            ReminderItemUi("appointment", "下午 3:00", "复诊准备", "准备好就诊卡和随身物品。"),
        )
    }
}
