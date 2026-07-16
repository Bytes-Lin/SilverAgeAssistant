package com.example.silverageassistant.ui.reminders

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderViewModelTest {
    @Test
    fun markCompleted_updatesOnlySelectedReminder() {
        val viewModel = ReminderViewModel()

        viewModel.markCompleted("medicine")

        val reminders = viewModel.reminders.value
        assertEquals(ReminderStatus.Completed, reminders.first { it.id == "medicine" }.status)
        assertEquals(ReminderStatus.Pending, reminders.first { it.id == "water" }.status)
    }

    @Test
    fun snooze_marksReminderAsSnoozed() {
        val viewModel = ReminderViewModel()

        viewModel.snooze("water")

        assertEquals(
            ReminderStatus.Snoozed,
            viewModel.reminders.value.first { it.id == "water" }.status,
        )
    }
}
