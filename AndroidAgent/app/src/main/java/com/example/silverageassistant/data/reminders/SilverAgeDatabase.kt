package com.example.silverageassistant.data.reminders

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.silverageassistant.data.usage.ModelUsageDao
import com.example.silverageassistant.data.usage.ModelUsageEntity

@Database(
    entities = [
        ReminderEntity::class,
        ModelUsageEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class SilverAgeDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    abstract fun modelUsageDao(): ModelUsageDao

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

        @Volatile
        private var instance: SilverAgeDatabase? = null

        fun getInstance(context: Context): SilverAgeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SilverAgeDatabase::class.java,
                "silverage.db",
            ).addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
