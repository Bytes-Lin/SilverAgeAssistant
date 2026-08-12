package com.example.silverageassistant.data.safety

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
import com.example.silverageassistant.data.middleserver.SafetyEvent

fun interface FamilyEmergencyNotifier {
    /** Returns true only when Android accepted the notification for display. */
    fun show(event: SafetyEvent): Boolean
}

/** Publishes each family emergency once; repeated REST refreshes update instead of duplicating it. */
class FamilyEmergencyNotificationPublisher(
    private val context: Context,
) : FamilyEmergencyNotifier {
    override fun show(event: SafetyEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "家属紧急事件",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "提醒家属及时核实老人紧急状况"
                enableVibration(true)
            },
        )
        val openEmergencyEvents = PendingIntent.getActivity(
            context,
            event.eventId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle("老人紧急事件")
            .setContentText(event.eventSummary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.eventSummary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openEmergencyEvents)
            .build()
        manager.notify(event.eventId.hashCode() and Int.MAX_VALUE, notification)
        return true
    }

    companion object {
        private const val CHANNEL_ID = "family_emergency_events"
    }
}
