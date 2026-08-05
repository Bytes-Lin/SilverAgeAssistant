package com.example.silverageassistant.data.gui

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GuiTodoDao {
    @Query("SELECT * FROM gui_todos ORDER BY updated_at_epoch_millis DESC")
    fun observeAll(): Flow<List<GuiTodoEntity>>

    @Query("SELECT * FROM gui_todos WHERE id = :todoId LIMIT 1")
    suspend fun get(todoId: String): GuiTodoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: GuiTodoEntity)

    @Query(
        """
        UPDATE gui_todos
        SET status = 'INTERRUPTED', updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE status IN ('RUNNING', 'PAUSED')
        """,
    )
    suspend fun markUnfinishedAsInterrupted(updatedAtEpochMillis: Long)
}
