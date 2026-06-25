package com.daymark.app.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.daymark.app.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules and cancels the daily "log your day" reminder via [AlarmManager]. */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
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

    /** (Re)schedules the reminder for the next occurrence of the saved time. */
    fun schedule(hour: Int, minute: Int) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val now = LocalDateTime.now()
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val pending = reminderPendingIntent()
        // setExactAndAllowWhileIdle gives reliable daily delivery; the receiver re-arms.
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } catch (_: SecurityException) {
            // Falls back to inexact if the exact-alarm permission is unavailable.
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    /** Re-arms using the persisted settings (used by boot + after firing). */
    fun reschedule() {
        if (settings.reminderEnabled) schedule(settings.reminderHour, settings.reminderMinute)
    }

    fun cancel() {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        alarmManager.cancel(reminderPendingIntent())
    }

    private fun reminderPendingIntent(): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "daily_reminder"
        private const val REQUEST_CODE = 1001
    }
}
