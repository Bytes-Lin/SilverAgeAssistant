package com.example.silverageassistant.data.reminders

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ReminderEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SilverAgeDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile
        private var instance: SilverAgeDatabase? = null

        fun getInstance(context: Context): SilverAgeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SilverAgeDatabase::class.java,
                "silverage.db",
            ).build().also { instance = it }
        }
    }
}
