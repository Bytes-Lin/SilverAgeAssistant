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
import com.example.silverageassistant.MainActivity
import com.example.silverageassistant.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun interface RemoteReminderReceivedNotifier {
    fun show(reminder: ReminderEntity)
}

object NoOpRemoteReminderReceivedNotifier : RemoteReminderReceivedNotifier {
    override fun show(reminder: ReminderEntity) = Unit
}

/** Publishes a system notification when a new family reminder is first stored on the elder device. */
class RemoteReminderReceivedNotificationPublisher(
    private val context: Context,
) : RemoteReminderReceivedNotifier {
    override fun show(reminder: ReminderEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "家属新提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "家属向老人发送的新待办提醒"
                enableVibration(true)
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
        val deadline = formatDeadline(reminder)
        val detail = buildString {
            append("截止：")
            append(deadline)
            if (reminder.detail.isNotBlank()) {
                append('\n')
                append(reminder.detail)
            }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle("家人发来提醒：${reminder.title}")
            .setContentText("截止：$deadline")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        // Keep this distinct from the deadline notification, which uses reminder.id directly.
        manager.notify("received:${reminder.id}".hashCode() and Int.MAX_VALUE, notification)
    }

    private fun formatDeadline(reminder: ReminderEntity): String {
        val zone = reminder.timezoneId
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
        return Instant.ofEpochMilli(reminder.scheduledAtEpochMillis)
            .atZone(zone)
            .format(DEADLINE_FORMATTER)
    }

    private companion object {
        const val CHANNEL_ID = "elder_received_reminders"
        val DEADLINE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    }
}
