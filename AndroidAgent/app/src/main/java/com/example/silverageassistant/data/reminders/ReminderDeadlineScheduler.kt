package com.example.silverageassistant.data.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.silverageassistant.MainActivity
import com.example.silverageassistant.R
import java.util.concurrent.TimeUnit

interface ReminderDeadlineScheduler {
    fun schedule(reminderId: String, deadlineEpochMillis: Long)
    fun cancel(reminderId: String)
}

object NoOpReminderDeadlineScheduler : ReminderDeadlineScheduler {
    override fun schedule(reminderId: String, deadlineEpochMillis: Long) = Unit
    override fun cancel(reminderId: String) = Unit
}

class WorkManagerReminderDeadlineScheduler(
    private val context: Context,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ReminderDeadlineScheduler {
    override fun schedule(reminderId: String, deadlineEpochMillis: Long) {
        val delay = (deadlineEpochMillis - nowEpochMillis()).coerceAtLeast(0L)
        val request = PeriodicWorkRequestBuilder<ReminderDeadlineWorker>(
            REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(KEY_REMINDER_ID, reminderId).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName(reminderId),
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancel(reminderId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(reminderId))
    }

    companion object {
        const val KEY_REMINDER_ID = "reminder_id"
        const val REPEAT_INTERVAL_HOURS = 1L
        fun workName(reminderId: String) = "reminder-deadline-$reminderId"
    }
}

class ReminderDeadlineWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val reminderId = inputData.getString(WorkManagerReminderDeadlineScheduler.KEY_REMINDER_ID)
            ?: return Result.failure()
        val entity = SilverAgeDatabase.getInstance(applicationContext)
            .reminderDao()
            .findById(reminderId)
        if (entity == null || entity.kind != "REMOTE_REMINDER" ||
            entity.status == StoredReminderStatus.COMPLETED.name
        ) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(
                WorkManagerReminderDeadlineScheduler.workName(reminderId),
            )
            return Result.success()
        }
        if (entity.scheduledAtEpochMillis > System.currentTimeMillis()) return Result.success()
        ReminderNotificationPublisher(applicationContext).show(entity)
        return Result.success()
    }
}

private class ReminderNotificationPublisher(
    private val context: Context,
) {
    fun show(reminder: ReminderEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "待办提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "提醒老人处理尚未确认完成的家属待办事项"
            },
        )
        val openApp = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle("待办提醒：${reminder.title}")
            .setContentText(reminder.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        manager.notify(reminder.id.hashCode() and Int.MAX_VALUE, notification)
    }

    private companion object {
        const val CHANNEL_ID = "elder_pending_reminders"
    }
}
