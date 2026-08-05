package com.example.silverageassistant.data.gui

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gui_todos")
data class GuiTodoEntity(
    @PrimaryKey val id: String,
    val content: String,
    val status: String,
    @ColumnInfo(name = "failed_run_count") val failedRunCount: Int,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "family_escalation_event_id") val familyEscalationEventId: String?,
)
