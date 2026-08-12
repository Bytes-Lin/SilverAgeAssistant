package com.example.silverageassistant.data.reminders

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.silverageassistant.data.gui.GuiTodoDao
import com.example.silverageassistant.data.gui.GuiTodoEntity
import com.example.silverageassistant.data.usage.ModelUsageDao
import com.example.silverageassistant.data.usage.ModelUsageEntity

@Database(
    entities = [
        ReminderEntity::class,
        ModelUsageEntity::class,
        GuiTodoEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class SilverAgeDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    abstract fun modelUsageDao(): ModelUsageDao

    abstract fun guiTodoDao(): GuiTodoDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `model_usage_records` (
                        `id` TEXT NOT NULL,
                        `modality` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `model` TEXT,
                        `feature` TEXT NOT NULL,
                        `started_at_epoch_millis` INTEGER NOT NULL,
                        `finished_at_epoch_millis` INTEGER NOT NULL,
                        `request_count` INTEGER NOT NULL,
                        `success_count` INTEGER NOT NULL,
                        `input_tokens` INTEGER NOT NULL,
                        `output_tokens` INTEGER NOT NULL,
                        `asr_audio_duration_millis` INTEGER NOT NULL,
                        `tts_character_count` INTEGER NOT NULL,
                        `tts_audio_duration_millis` INTEGER NOT NULL,
                        `is_estimated` INTEGER NOT NULL,
                        `reported_at_epoch_millis` INTEGER,
                        `report_batch_id` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_model_usage_records_reported_at_epoch_millis`
                    ON `model_usage_records` (`reported_at_epoch_millis`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS
                    `index_model_usage_records_started_at_epoch_millis`
                    ON `model_usage_records` (`started_at_epoch_millis`)
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `gui_todos` (
                        `id` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `failed_run_count` INTEGER NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        `family_escalation_event_id` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `reminders` ADD COLUMN `voice_announcement_state` TEXT NOT NULL DEFAULT 'NONE'",
                )
                db.execSQL(
                    "ALTER TABLE `reminders` ADD COLUMN `voice_announced_at_epoch_millis` INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE `reminders` ADD COLUMN `voice_attempt_count` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `reminders` ADD COLUMN `completed_at_epoch_millis` INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE `reminders` ADD COLUMN `completion_sync_state` TEXT NOT NULL DEFAULT 'NOT_REQUIRED'",
                )
                db.execSQL(
                    "ALTER TABLE `reminders` ADD COLUMN `completion_request_id` TEXT",
                )
            }
        }

        @Volatile
        private var instance: SilverAgeDatabase? = null

        fun getInstance(context: Context): SilverAgeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SilverAgeDatabase::class.java,
                "silverage.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { instance = it }
        }
    }
}
