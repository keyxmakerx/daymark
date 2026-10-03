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
import com.daymark.app.notifications.NotificationPrivacy.lockedAway
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.daymark.app.MainActivity
import com.daymark.app.R
import com.daymark.app.data.CheckInStateStore
import com.daymark.app.data.OfferLedgerRepository
import com.daymark.app.data.dao.EntryDao
import com.daymark.app.data.dao.ReminderDao
import com.daymark.app.data.entity.OfferKind
import com.daymark.app.data.entity.OfferOutcome
import com.daymark.app.data.entity.Reminder
import com.daymark.app.stats.CheckInEngine
import com.daymark.app.stats.InterruptionBudget
import com.daymark.app.stats.PhrasePool
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
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
 * What the rules engine gates is the far cheaper thing to undo: whether [showNotification]
 * actually posts. A suppressed firing costs nothing and leaves the schedule intact.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offerLedger: OfferLedgerRepository,
    private val entryDao: EntryDao,
    private val checkInState: CheckInStateStore,
    private val reminderDao: ReminderDao,
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
     * Posts the notification for a fired reminder, if the rules engine lets it through, and writes
     * one ledger line for each one posted (`docs/DECISIONS.md` §D1, §D1a).
     *
     * The engine only ever lets through a subset of the times the person set: every one of them
     * while check-ins are being answered, then, as a run of them goes unanswered, at most one per a
     * wait that doubles with each step. It never adds a time and never switches a reminder off; only
     * the person does that, with **Stop asking** or the switch in Settings → Reminders. Every change
     * of pace is announced with **Put it back** ([announce]).
     *
     * [later] is a firing the person asked for with **Try later**. It is their own request for one
     * more nudge, so it is posted without asking the engine, and it is not re-armed.
     */
    suspend fun showNotification(
        reminder: Reminder,
        nowMillis: Long = System.currentTimeMillis(),
        later: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Read before the new line is written, or it would find itself.
        val previousOfferedAt = offerLedger.lastOfferedAt(OfferKind.REMINDER)
        var state = checkInState.reminders()

        if (!later) {
            val pace = currentPace(state, previousOfferedAt, nowMillis)
            announce(state, pace, trial = false)
            state = checkInState.reminders()
            if (!CheckInEngine.shouldPost(pace, previousOfferedAt, nowMillis)) return
        }

        val openEditor = PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_EDITOR, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // One of the fixed, human-written lines, in turn. The draw is blind to how the person
        // seemed: it takes the hour and a counter, and nothing else (stats/PhrasePool.kt).
        val hour = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).hour
        val line = PhrasePool.openerForHour(hour, state.rotation)
        checkInState.write(state.copy(rotation = PhrasePool.nextRotation(state.rotation)))

        val title = reminder.label.ifBlank { context.getString(R.string.reminder_title) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .lockedAway(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(line)
            .setContentIntent(openEditor)
            .addAction(NotificationPrivacy.unlockedAction(context.getString(R.string.reminder_action_log), openEditor))
            .addAction(NotificationPrivacy.unlockedAction(context.getString(R.string.reminder_action_later), actionIntent(ReminderActionReceiver.ACTION_TRY_LATER, reminder.id)))
            .addAction(NotificationPrivacy.unlockedAction(context.getString(R.string.reminder_action_stop), actionIntent(ReminderActionReceiver.ACTION_STOP_ASKING, reminder.id)))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(reminder.id), notification)
        offerLedger.record(
            kind = OfferKind.REMINDER,
            // A later nudge follows the person's own "Try later", which is them engaging.
            outcome = if (later) OfferOutcome.SNOOZED else outcomeCarriedBy(previousOfferedAt, nowMillis),
            offeredAtMillis = nowMillis,
        )

        if (!later) maybeTryLonger(nowMillis)
    }

    /**
     * The pace in force now. The ledger's newest line describes the interval before the last post,
     * so whether anything was written since then is read here too: a person who comes back to
     * Daymark between two widely spaced reminders gets their own schedule back now, not at the next
     * post. That only ever returns toward the schedule they set, never past it.
     */
    private suspend fun currentPace(
        state: CheckInStateStore.State,
        lastPostedAt: Long,
        nowMillis: Long,
    ): CheckInEngine.Pace {
        val rows = offerLedger.checkInRows(OfferKind.REMINDER).toMutableList()
        if (lastPostedAt in 1 until nowMillis && entryDao.getBetween(lastPostedAt, nowMillis).isNotEmpty()) {
            rows += InterruptionBudget.Offer(InterruptionBudget.Kind.REMINDER.key, nowMillis, InterruptionBudget.Outcome.ACCEPTED.key)
        }
        return CheckInEngine.paceOf(
            kind = InterruptionBudget.Kind.REMINDER,
            recent = rows,
            saidStop = offerLedger.saidStop(OfferKind.REMINDER),
            countFrom = state.countFrom,
            trialSteps = state.trialSteps,
            setSpacingMillis = setSpacingMillis(),
        )
    }

    /**
     * The shortest spacing between the reminder times the person has switched on, around the clock:
     * a day for a single reminder, twelve hours for 9:00 and 21:00. Steps of quiet the schedule
     * already keeps are skipped, so no notice announces a change that changes nothing.
     */
    private suspend fun setSpacingMillis(): Long {
        val minutes = reminderDao.getAll().filter { it.enabled }.map { it.hour * 60 + it.minute }.distinct().sorted()
        if (minutes.size <= 1) return DAY_MILLIS
        val gaps = minutes.zipWithNext { a, b -> b - a } + (minutes.first() + 24 * 60 - minutes.last())
        return gaps.min() * 60_000L
    }

    /** After a long run of answered reminders, tries one step longer a wait, and says so. */
    private suspend fun maybeTryLonger(nowMillis: Long) {
        val state = checkInState.reminders()
        val rows = offerLedger.checkInRows(OfferKind.REMINDER)
        val since = maxOf(state.countFrom, state.trialSince)
        if (!CheckInEngine.mayTryLonger(InterruptionBudget.Kind.REMINDER, rows, since, state.trialDeclined)) return
        val tried = state.copy(trialSteps = state.trialSteps + 1, trialSince = nowMillis)
        checkInState.write(tried)
        val pace = CheckInEngine.paceOf(
            kind = InterruptionBudget.Kind.REMINDER,
            recent = rows,
            saidStop = offerLedger.saidStop(OfferKind.REMINDER),
            countFrom = tried.countFrom,
            trialSteps = tried.trialSteps,
            setSpacingMillis = setSpacingMillis(),
        )
        announce(tried, pace, trial = true)
    }

    /**
     * Tells the person about a change of pace, once: how often it now is, with **Put it back**, or
     * that it is back to the times they set. Never silent, and never a word about anything missed
     * (§D1a).
     */
    private fun announce(state: CheckInStateStore.State, pace: CheckInEngine.Pace, trial: Boolean) {
        val change = CheckInEngine.changeBetween(state.announced, pace) ?: return
        checkInState.write(state.copy(announcedGap = pace.gapMillis, lastChangeWasTrial = trial && change.quieter))
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .lockedAway(context, CHANNEL_ID)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        // Worded by where the pace is, not which way it moved: coming back from a long quiet
        // stretch to a longer wait the person kept is still less often than they set.
        if (pace != CheckInEngine.Pace.AsSet) {
            val days = pace.days.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            builder
                .setContentTitle(context.getString(R.string.checkin_quieter_title))
                .setContentText(context.resources.getQuantityString(R.plurals.checkin_quieter_text, days, days))
                .addAction(NotificationPrivacy.unlockedAction(context.getString(R.string.checkin_put_back), actionIntent(ReminderActionReceiver.ACTION_PUT_BACK, 0L)))
        } else {
            builder.setContentTitle(context.getString(R.string.checkin_back_title))
        }
        NotificationManagerCompat.from(context).notify(NOTICE_NOTIFICATION_ID, builder.build())
    }

    /** Asks for one more nudge for [reminder] in [TRY_LATER_MILLIS], because the person said so. */
    fun scheduleLater(reminderId: Long, nowMillis: Long = System.currentTimeMillis()) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            LATER_REQUEST_BASE + reminderId.toInt(),
            Intent(context, ReminderReceiver::class.java)
                .putExtra(EXTRA_REMINDER_ID, reminderId)
                .putExtra(EXTRA_LATER, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nowMillis + TRY_LATER_MILLIS, pending)
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, nowMillis + TRY_LATER_MILLIS, pending)
        }
        NotificationManagerCompat.from(context).cancel(notificationId(reminderId))
    }

    /** Clears the change notice, after the person has answered it. */
    fun cancelNotice() = NotificationManagerCompat.from(context).cancel(NOTICE_NOTIFICATION_ID)

    private fun actionIntent(action: String, reminderId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        (action.hashCode() * 31) + reminderId.toInt(),
        Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_REMINDER_ID, reminderId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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
     * explicitly *not* "did they open the app" — `docs/DECISIONS.md` §D6 rules that one out,
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

    fun notificationId(reminderId: Long): Int = NOTIFICATION_ID_BASE + reminderId.toInt()

    companion object {
        const val CHANNEL_ID = "daily_reminder"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_LATER = "reminder_later"
        private const val NOTIFICATION_ID_BASE = 2000

        /** The one notice of a change of pace. Below the reminders' range, so it never collides. */
        private const val NOTICE_NOTIFICATION_ID = 1999

        /** Request codes for "Try later" alarms, apart from the daily alarms' codes. */
        private const val LATER_REQUEST_BASE = 500_000

        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /** How long "Try later" waits: one hour. */
        private const val TRY_LATER_MILLIS = 60L * 60 * 1000

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
