package com.example.silverageassistant.data.reminders

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reminders",
    indices = [Index(value = ["server_command_id"], unique = true)],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "server_command_id") val serverCommandId: String?,
    @ColumnInfo(name = "server_sequence") val serverSequence: Long?,
    val kind: String,
    val title: String,
    val detail: String,
    @ColumnInfo(name = "scheduled_at_epoch_millis") val scheduledAtEpochMillis: Long,
    @ColumnInfo(name = "timezone_id") val timezoneId: String,
    @ColumnInfo(name = "source_display_name") val sourceDisplayName: String?,
    @ColumnInfo(name = "stored_at_epoch_millis") val storedAtEpochMillis: Long,
    val status: String,
    val acknowledged: Boolean,
)
