package com.carequeue.plus.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.carequeue.plus.MainActivity
import com.carequeue.plus.R

object NotificationHelper {
    private const val CHANNEL_ID = "carequeue_channel"
    private const val CHANNEL_NAME = "CareQueue Notifications"

    private const val PREFS_NAME = "carequeue_prefs"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for queue updates and return reminders"
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /** The user's preference from Settings; notifications are on by default. */
    fun areNotificationsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATIONS_ENABLED, true)

    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun showQueueNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = System.currentTimeMillis().toInt()
    ) {
        // Respect the Settings toggle: a switched-off user must not be notified.
        if (!areNotificationsEnabled(context)) return

        // Tapping the notification opens the app instead of silently dismissing:
        // without a content intent a notification is a dead end, which defeats
        // the whole point of a "come back now" reminder.
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }
}
