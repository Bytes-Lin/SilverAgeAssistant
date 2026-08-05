package com.example.silverageassistant.data.reminders

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY scheduled_at_epoch_millis ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ReminderEntity): Long

    @Query("SELECT COALESCE(MAX(server_sequence), 0) FROM reminders")
    suspend fun maxServerSequence(): Long

    @Query("SELECT * FROM reminders WHERE server_command_id IS NOT NULL AND acknowledged = 0")
    suspend fun pendingAcknowledgements(): List<ReminderEntity>

    @Query("UPDATE reminders SET acknowledged = 1 WHERE server_command_id = :commandId")
    suspend fun markAcknowledged(commandId: String)

    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query(
        """UPDATE reminders SET voice_announcement_state = :state,
        voice_announced_at_epoch_millis = :announcedAt,
        voice_attempt_count = voice_attempt_count + 1 WHERE id = :id""",
    )
    suspend fun updateVoiceAnnouncement(id: String, state: String, announcedAt: Long?)

    @Query("SELECT * FROM reminders WHERE voice_announcement_state = 'PENDING'")
    suspend fun pendingVoiceAnnouncements(): List<ReminderEntity>
}
