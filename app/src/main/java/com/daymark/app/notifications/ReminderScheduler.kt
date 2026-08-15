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
import com.daymark.app.data.OfferLedgerRepository
import com.daymark.app.data.dao.EntryDao
import com.daymark.app.data.entity.OfferKind
import com.daymark.app.data.entity.OfferOutcome
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
 *
 * ## The alarm and the notification are two different decisions
 *
 * [schedule] is untouched by any of what follows. The alarm keeps firing at exactly the time the
 * person set, every day, and the receiver keeps re-arming it — a reminder that stopped scheduling
 * itself would be a reminder that never came back, which is not "asking less", it is breaking.
 * What the decision engine gates is the far cheaper thing to undo: whether [showNotification]
 * actually posts. A suppressed firing costs nothing and leaves the schedule intact.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offerLedger: OfferLedgerRepository,
    private val entryDao: EntryDao,
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

    /**
     * Posts the notification for a fired reminder, with a one-tap "Log" action — if the decision
     * engine permits an interruption right now.
     *
     * A reminder that keeps going unanswered ends up asking less often, and that is the only
     * direction available: [OfferKind.REMINDER]'s declared frequency is
     * [OfferLedgerRepository.defaultFrequency] — every firing, because the person chose these times
     * themselves and nothing here has any business second-guessing a schedule they set — and
     * reception can only step that down. There is no combination of rows that posts more
     * notifications than the schedule already asks for.
     *
     * A suppressed firing writes nothing: a ledger line means the app asked, and it did not.
     */
    suspend fun showNotification(reminder: Reminder, nowMillis: Long = System.currentTimeMillis()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Read before the new line is written, or it would find itself.
        val previousOfferedAt = offerLedger.lastOfferedAt(OfferKind.REMINDER)
        // NOT GATED ON THE ARBITER, deliberately — this was wired and then unwired after the
        // consequence was traced. A reminder is not an unsolicited offer: the person picked this
        // time and asked for it, and that request IS their declared frequency. Rationing it by
        // inferred reception meant two unanswered firings dropped Kind.REMINDER to OncePerWeek, so
        // a 9am/1pm/9pm schedule collapsed to a single notification a week — silently, with no
        // reminder-frequency setting anywhere for the person to turn back up, and roughly five
        // weeks to recover. D1a's monotonic rule holds only because its escape hatch is "the person
        // changes a setting"; where there is no setting, an inference that quiets something they
        // explicitly scheduled is not restraint, it is overriding them.
        //
        // The ledger still RECORDS every firing and whether it was acted on. That is the signal the
        // clinician asked for and the substrate the companion reads. Record, do not ration.

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
        offerLedger.record(
            kind = OfferKind.REMINDER,
            outcome = outcomeCarriedBy(previousOfferedAt, nowMillis),
            offeredAtMillis = nowMillis,
        )
    }

    /**
     * What this firing's ledger line carries — the app's reading of whether the reminder before it
     * was answered.
     *
     * **Why the line describes the firing before it.** A line has to be written the moment the
     * notification is posted, because `offeredAt` is what the minimum-gap timer counts from and an
     * offer with no row is an offer that never happened — the next one would be permitted sooner,
     * which is the one direction this system may not move. But at that moment nothing is yet known
     * about *this* notification, and a ledger line is never edited afterwards. So each line records
     * one firing and carries the evidence about the interval that preceded it. Reception is read
     * from the last several lines together (`InterruptionBudget.RECENT_WINDOW`), so a one-line lag
     * changes when the app goes quiet by one reminder, and changes nothing else.
     *
     * **What counts as answered, and what deliberately does not.** A check-in the person wrote.
     * That is the thing the reminder asks for, and it is them doing something on purpose. It is
     * explicitly *not* "did they open the app" — `docs/DECISIONS_2026-08.md` §D6 rules that one out,
     * and it is right to: a notification raises the odds of an app open ~3.66× with nothing
     * underneath it improving, so it is the metric that would move most easily while meaning least.
     *
     * **The window is deliberately generous** — the wider of *since the previous reminder* and
     * [ANSWERED_WINDOW_MILLIS], never the narrower. One missed day is not a reminder going
     * unanswered; it is a Saturday. Reading the strict interval instead would take a person who
     * logs on weekdays down to a weekly reminder by Monday, which is the app punishing an ordinary
     * week. Nothing came back *at all* in three days is a different statement, and it is the only
     * one this asks.
     *
     * Note which way the generosity runs: a wider window can only ever return
     * [OfferOutcome.ACCEPTED] where a narrower one would have said otherwise, and
     * [OfferOutcome.ACCEPTED] is what leaves the schedule exactly as the person set it. Being
     * forgiving here cannot make the app ask more than the schedule already asks — nothing can.
     *
     * The first firing after an install, a restore or a clock that jumped backwards has no interval
     * to read and returns [OfferOutcome.ACCEPTED] for the same reason. Absence of evidence quiets
     * nothing.
     */
    private suspend fun outcomeCarriedBy(previousOfferedAt: Long, nowMillis: Long): OfferOutcome {
        if (previousOfferedAt <= 0L || previousOfferedAt >= nowMillis) return OfferOutcome.ACCEPTED
        val from = minOf(previousOfferedAt, nowMillis - ANSWERED_WINDOW_MILLIS)
        val answered = entryDao.getBetween(from, nowMillis).isNotEmpty()
        return if (answered) OfferOutcome.ACCEPTED else OfferOutcome.DISMISSED
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

        /**
         * How far back [outcomeCarriedBy] will look for a check-in before it reads a reminder as
         * having gone unanswered — a floor on the window, never a cap on it.
         *
         * Three days is a judgement call and is recorded as one, the way
         * `SupportOfferFrequency.DEFAULT` is. It is short enough that a genuinely unanswered
         * reminder is noticed within a few firings, and long enough that a weekend, a holiday or a
         * bad week does not read as one.
         */
        private const val ANSWERED_WINDOW_MILLIS = 3L * 24 * 60 * 60 * 1000
    }
}
