package com.daymark.app.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.daymark.app.MainActivity
import com.daymark.app.R
import com.daymark.app.data.entity.Reminder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules, cancels and posts the daily check-in reminders via [AlarmManager]. Each [Reminder]
 * gets its own alarm and notification id derived from its database id, so multiple reminders can
 * coexist without colliding.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun createChannel() {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily reminder",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Reminds you to log your day" }
        manager.createNotificationChannel(channel)
    }

    /** (Re)schedules a single reminder for the next occurrence of its time. */
    fun schedule(reminder: Reminder) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val now = LocalDateTime.now()
        var next = now.withHour(reminder.hour).withMinute(reminder.minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val pending = alarmPendingIntent(reminder.id)
        // setExactAndAllowWhileIdle gives reliable daily delivery; the receiver re-arms.
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } catch (_: SecurityException) {
            // Falls back to inexact if the exact-alarm permission is unavailable.
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(reminderId: Long) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        alarmManager.cancel(alarmPendingIntent(reminderId))
        NotificationManagerCompat.from(context).cancel(notificationId(reminderId))
    }

    /** Posts the notification for a fired reminder, with a one-tap "Log" action. */
    fun showNotification(reminder: Reminder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openEditor = PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_EDITOR, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = reminder.label.ifBlank { context.getString(R.string.reminder_title) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.reminder_text))
            .setContentIntent(openEditor)
            .addAction(0, "Log now", openEditor)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(reminder.id), notification)
    }

    private fun alarmPendingIntent(reminderId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_REMINDER_ID, reminderId)
        return PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(reminderId: Long): Int = NOTIFICATION_ID_BASE + reminderId.toInt()

    companion object {
        const val CHANNEL_ID = "daily_reminder"
        const val EXTRA_REMINDER_ID = "reminder_id"
        private const val NOTIFICATION_ID_BASE = 2000
    }
}
