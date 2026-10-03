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
import com.daymark.app.data.dao.TrackerDao
import com.daymark.app.data.dao.TrackerLogDao
import com.daymark.app.data.entity.OfferKind
import com.daymark.app.data.entity.OfferOutcome
import com.daymark.app.data.entity.Tracker
import com.daymark.app.data.entity.TrackerLog
import com.daymark.app.data.entity.rhythmChoice
import com.daymark.app.data.entity.window
import com.daymark.app.stats.CheckInEngine
import com.daymark.app.stats.InterruptionBudget
import com.daymark.app.stats.TrackerRhythm
import com.daymark.app.widget.TrackerWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tracker's own check-ins, and its quick-log notification.
 *
 * Each tracker asks only at the rhythm the person switched on for it ([TrackerRhythm]); a new
 * tracker asks nothing. The alarm fires at every time the rhythm gives, and the rules engine
 * ([CheckInEngine]) decides whether each one is posted, exactly as for the reminders
 * ([ReminderScheduler]): unanswered check-ins ease it off with no cap, never switch it off, and
 * every change of pace is announced with **Put it back**. Each tracker's ledger rows carry its id,
 * so one tracker's quiet never touches another's.
 *
 * The quick-log notification is separate: a silent, low notification the person switches on per
 * tracker, which only ever opens the tracker or logs what they tap. It is not a check-in, asks
 * nothing, and writes no ledger row.
 */
@Singleton
class TrackerCheckInScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackerDao: TrackerDao,
    private val trackerLogDao: TrackerLogDao,
    private val offerLedger: OfferLedgerRepository,
    private val checkInState: CheckInStateStore,
) {
    fun createChannels() {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHECKIN_CHANNEL_ID, context.getString(R.string.tracker_checkin_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(QUICKLOG_CHANNEL_ID, context.getString(R.string.tracker_quicklog_channel), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) },
        )
    }

    /** Re-arms every tracker and redraws every quick-log notification: after a boot, a restore, a start. */
    suspend fun refreshAll(nowMillis: Long = System.currentTimeMillis()) {
        trackerDao.getAll().forEach { refresh(it, nowMillis) }
        redrawWidget()
    }

    /** Redraws the home-screen tracker widget, after a tracker is added, changed or archived. */
    suspend fun redrawWidget() = TrackerWidget.redraw(context)

    /** Arms [tracker]'s next check-in, or cancels it, and shows or clears its quick-log notification. */
    fun refresh(tracker: Tracker, nowMillis: Long = System.currentTimeMillis()) {
        arm(tracker, nowMillis)
        if (tracker.quickLog && !tracker.archived) showQuickLog(tracker, lastLoggedAt = null) else clearQuickLog(tracker.id)
    }

    /** Cancels everything [trackerId] has armed or shown. */
    fun cancel(trackerId: Long) {
        context.getSystemService<AlarmManager>()?.cancel(alarmIntent(trackerId, later = false))
        context.getSystemService<AlarmManager>()?.cancel(alarmIntent(trackerId, later = true))
        val notifications = NotificationManagerCompat.from(context)
        notifications.cancel(checkInId(trackerId))
        notifications.cancel(noticeId(trackerId))
        clearQuickLog(trackerId)
    }

    private fun arm(tracker: Tracker, nowMillis: Long) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        val pending = alarmIntent(tracker.id, later = false)
        val at = nextDueMillis(tracker, nowMillis)
        if (at == null || tracker.archived) {
            alarmManager.cancel(pending)
            return
        }
        setAlarm(alarmManager, at, pending)
    }

    /** The next time [tracker]'s rhythm asks, in the person's own zone, or null when it never asks. */
    private fun nextDueMillis(tracker: Tracker, nowMillis: Long): Long? {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var minute = now.hour * 60 + now.minute
        var day = now.toLocalDate().toEpochDay()
        // Twice at most: a clock change can put a local time at or before now.
        repeat(2) {
            val due = TrackerRhythm.nextDue(
                tracker.rhythmChoice, tracker.id, day, minute,
                tracker.onceAtMinute, tracker.fewCount, tracker.window,
            ) ?: return null
            val at = LocalDate.ofEpochDay(due.epochDay).atTime(due.minuteOfDay / 60, due.minuteOfDay % 60)
                .atZone(zone).toInstant().toEpochMilli()
            if (at > nowMillis) return at
            day = due.epochDay
            minute = due.minuteOfDay
        }
        return null
    }

    /**
     * A check-in for [trackerId] fell due. Re-arms the next one, then posts this one if the engine
     * allows. [later] is the person's own **Try later**: posted without asking the engine, and not
     * re-armed, because the regular alarm is still set.
     */
    suspend fun onDue(trackerId: Long, nowMillis: Long = System.currentTimeMillis(), later: Boolean = false) {
        val tracker = trackerDao.getById(trackerId) ?: return
        if (tracker.archived || !tracker.rhythmChoice.asks) return
        if (!later) arm(tracker, nowMillis)
        if (!mayNotify()) return

        // Read before the new line is written, or it would find itself.
        val previous = offerLedger.lastOfferedAt(OfferKind.TRACKER, trackerId)
        if (!later) {
            val state = checkInState.tracker(trackerId)
            val pace = currentPace(tracker, state, previous, nowMillis)
            announce(tracker, state, pace, trial = false)
            if (!CheckInEngine.shouldPost(pace, previous, nowMillis)) return
        }

        val notification = NotificationCompat.Builder(context, CHECKIN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .lockedAway(context, CHECKIN_CHANNEL_ID)
            .setContentTitle(tracker.name)
            .setContentText(context.getString(R.string.tracker_checkin_text))
            .setContentIntent(openTracker(trackerId))
            .addAction(NotificationPrivacy.unlockedAction(context.getString(R.string.reminder_action_log), openTracker(trackerId)))
            .addAction(NotificationPrivacy.unlockedAction(context.getString(R.string.reminder_action_later), action(TrackerCheckInReceiver.ACTION_TRY_LATER, trackerId)))
            .addAction(NotificationPrivacy.unlockedAction(context.getString(R.string.reminder_action_stop), action(TrackerCheckInReceiver.ACTION_STOP_ASKING, trackerId)))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(checkInId(trackerId), notification)

        offerLedger.record(
            kind = OfferKind.TRACKER,
            outcome = if (later) OfferOutcome.SNOOZED else outcomeCarriedBy(trackerId, previous, nowMillis),
            offeredAtMillis = nowMillis,
            subject = trackerId,
        )
        if (!later) maybeTryLonger(tracker, nowMillis)
    }

    /**
     * The pace in force for [tracker] now. A log written since the last check-in counts as an
     * answer at once, so coming back to a tracker restores its rhythm now, not a post later.
     */
    private suspend fun currentPace(
        tracker: Tracker,
        state: CheckInStateStore.State,
        lastPostedAt: Long,
        nowMillis: Long,
    ): CheckInEngine.Pace {
        val rows = offerLedger.checkInRows(OfferKind.TRACKER, tracker.id).toMutableList()
        if (lastPostedAt in 1 until nowMillis && trackerLogDao.countBetween(tracker.id, lastPostedAt, nowMillis) > 0) {
            rows += InterruptionBudget.Offer(InterruptionBudget.Kind.TRACKER.key, nowMillis, InterruptionBudget.Outcome.ACCEPTED.key)
        }
        return CheckInEngine.paceOf(
            kind = InterruptionBudget.Kind.TRACKER,
            recent = rows,
            // "Stop asking" switches the tracker to a rhythm that asks nothing, a setting the
            // person sees and changes back on the tracker; no ledger row carries it.
            saidStop = false,
            countFrom = state.countFrom,
            trialSteps = state.trialSteps,
            setSpacingMillis = TrackerRhythm.setSpacingMillis(tracker.rhythmChoice, tracker.fewCount, tracker.window),
            keepAsSet = tracker.keepAsSet,
        )
    }

    /** After a long run of answered check-ins, tries one step longer a wait, and says so. */
    private suspend fun maybeTryLonger(tracker: Tracker, nowMillis: Long) {
        val state = checkInState.tracker(tracker.id)
        val rows = offerLedger.checkInRows(OfferKind.TRACKER, tracker.id)
        val since = maxOf(state.countFrom, state.trialSince)
        if (!CheckInEngine.mayTryLonger(InterruptionBudget.Kind.TRACKER, rows, since, state.trialDeclined, tracker.keepAsSet)) return
        val tried = state.copy(trialSteps = state.trialSteps + 1, trialSince = nowMillis)
        checkInState.writeTracker(tracker.id, tried)
        val pace = CheckInEngine.paceOf(
            kind = InterruptionBudget.Kind.TRACKER,
            recent = rows,
            saidStop = false,
            countFrom = tried.countFrom,
            trialSteps = tried.trialSteps,
            setSpacingMillis = TrackerRhythm.setSpacingMillis(tracker.rhythmChoice, tracker.fewCount, tracker.window),
            keepAsSet = tracker.keepAsSet,
        )
        announce(tracker, tried, pace, trial = true)
    }

    /** Tells the person about a change of pace for [tracker], once, with **Put it back** (§D1). */
    private fun announce(tracker: Tracker, state: CheckInStateStore.State, pace: CheckInEngine.Pace, trial: Boolean) {
        val change = CheckInEngine.changeBetween(state.announced, pace) ?: return
        checkInState.writeTracker(tracker.id, state.copy(announcedGap = pace.gapMillis, lastChangeWasTrial = trial && change.quieter))
        val builder = NotificationCompat.Builder(context, CHECKIN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .lockedAway(context, CHECKIN_CHANNEL_ID)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (pace != CheckInEngine.Pace.AsSet) {
            val days = pace.days.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            builder
                .setContentTitle(context.getString(R.string.tracker_quieter_title, tracker.name))
                .setContentText(context.resources.getQuantityString(R.plurals.tracker_quieter_text, days, days))
                .addAction(NotificationPrivacy.unlockedAction(context.getString(R.string.checkin_put_back), action(TrackerCheckInReceiver.ACTION_PUT_BACK, tracker.id)))
        } else {
            builder.setContentTitle(context.getString(R.string.tracker_back_title, tracker.name))
        }
        if (mayNotify()) NotificationManagerCompat.from(context).notify(noticeId(tracker.id), builder.build())
    }

    /**
     * What this check-in's ledger line carries: whether the tracker was logged in the interval before
     * it. As for the reminders, the line is written when the check-in is posted and describes the
     * one before it. The window is the wider of "since the last check-in" and a day, so a
     * few-times-a-day tracker logged once that day counts every check-in that day as answered: it
     * eases off only after a whole day with no log, and then only to once a day.
     */
    private suspend fun outcomeCarriedBy(trackerId: Long, previousOfferedAt: Long, nowMillis: Long): OfferOutcome {
        if (previousOfferedAt <= 0L || previousOfferedAt >= nowMillis) return OfferOutcome.ACCEPTED
        val from = minOf(previousOfferedAt, nowMillis - ANSWERED_WINDOW_MILLIS)
        return if (trackerLogDao.countBetween(trackerId, from, nowMillis) > 0) OfferOutcome.ACCEPTED else OfferOutcome.DISMISSED
    }

    /** One more nudge for [trackerId] in an hour, because the person said so. */
    fun scheduleLater(trackerId: Long, nowMillis: Long = System.currentTimeMillis()) {
        val alarmManager = context.getSystemService<AlarmManager>() ?: return
        setAlarm(alarmManager, nowMillis + TRY_LATER_MILLIS, alarmIntent(trackerId, later = true))
        NotificationManagerCompat.from(context).cancel(checkInId(trackerId))
    }

    /** The person's **Stop asking**: the tracker goes back to asking nothing. They can switch it on again on the tracker. */
    suspend fun stopAsking(trackerId: Long) {
        val tracker = trackerDao.getById(trackerId) ?: return
        val quiet = tracker.copy(rhythm = TrackerRhythm.Rhythm.WHEN_IT_HAPPENS.key)
        trackerDao.update(quiet)
        NotificationManagerCompat.from(context).cancel(checkInId(trackerId))
        refresh(quiet)
    }

    /**
     * The person switched [trackerId] between easing off and keeping their times: the engine starts
     * over from their schedule, and a notice about a pace that no longer applies is cleared.
     */
    fun easingChanged(trackerId: Long, nowMillis: Long = System.currentTimeMillis()) {
        checkInState.restartTracker(trackerId, nowMillis)
        NotificationManagerCompat.from(context).cancel(noticeId(trackerId))
    }

    /** The person's **Put it back** on a notice: this tracker's check-ins return to the times they set. */
    fun putBack(trackerId: Long, nowMillis: Long = System.currentTimeMillis()) {
        checkInState.putBackTracker(trackerId, nowMillis)
        NotificationManagerCompat.from(context).cancel(noticeId(trackerId))
    }

    /** Logs [value] for [trackerId] from a notification button, and says when on the quick-log notification. */
    suspend fun logFromNotification(trackerId: Long, value: Double, nowMillis: Long = System.currentTimeMillis()) {
        val tracker = trackerDao.getById(trackerId) ?: return
        trackerLogDao.insert(TrackerLog(trackerId = trackerId, value = value, dateTime = nowMillis))
        NotificationManagerCompat.from(context).cancel(checkInId(trackerId))
        if (tracker.quickLog && !tracker.archived) showQuickLog(tracker, lastLoggedAt = nowMillis)
    }

    private fun showQuickLog(tracker: Tracker, lastLoggedAt: Long?) {
        if (!mayNotify()) return
        val text = if (lastLoggedAt == null) {
            context.getString(R.string.tracker_quicklog_text)
        } else {
            context.getString(R.string.tracker_quicklog_logged, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastLoggedAt)))
        }
        val builder = NotificationCompat.Builder(context, QUICKLOG_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .lockedAway(context, QUICKLOG_CHANNEL_ID)
            .setContentTitle(tracker.name)
            .setContentText(text)
            .setContentIntent(openTracker(tracker.id))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
        // A yes/no tracker logs straight from the notification; any other opens the tracker.
        if (tracker.type == Tracker.BOOLEAN) {
            builder
                .addAction(NotificationPrivacy.unlockedAction(context.getString(R.string.tracker_log_yes), logAction(tracker.id, 1.0)))
                .addAction(NotificationPrivacy.unlockedAction(context.getString(R.string.tracker_log_no), logAction(tracker.id, 0.0)))
        }
        NotificationManagerCompat.from(context).notify(quickLogId(tracker.id), builder.build())
    }

    private fun clearQuickLog(trackerId: Long) = NotificationManagerCompat.from(context).cancel(quickLogId(trackerId))

    private fun mayNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun setAlarm(alarmManager: AlarmManager, at: Long, pending: PendingIntent) {
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun alarmIntent(trackerId: Long, later: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context,
        (if (later) LATER_REQUEST_BASE else ALARM_REQUEST_BASE) + trackerId.toInt(),
        Intent(context, TrackerCheckInReceiver::class.java)
            .setAction(if (later) TrackerCheckInReceiver.ACTION_LATER_DUE else TrackerCheckInReceiver.ACTION_DUE)
            .putExtra(EXTRA_TRACKER_ID, trackerId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(action: String, trackerId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        (action.hashCode() * 31) + trackerId.toInt(),
        Intent(context, TrackerCheckInReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_TRACKER_ID, trackerId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun logAction(trackerId: Long, value: Double): PendingIntent = PendingIntent.getBroadcast(
        context,
        (TrackerCheckInReceiver.ACTION_LOG.hashCode() * 31) + trackerId.toInt() * 2 + value.toInt(),
        Intent(context, TrackerCheckInReceiver::class.java)
            .setAction(TrackerCheckInReceiver.ACTION_LOG)
            .putExtra(EXTRA_TRACKER_ID, trackerId)
            .putExtra(EXTRA_VALUE, value),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openTracker(trackerId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        OPEN_REQUEST_BASE + trackerId.toInt(),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_TRACKER, trackerId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun checkInId(trackerId: Long) = CHECKIN_ID_BASE + trackerId.toInt()
    private fun noticeId(trackerId: Long) = NOTICE_ID_BASE + trackerId.toInt()
    private fun quickLogId(trackerId: Long) = QUICKLOG_ID_BASE + trackerId.toInt()

    companion object {
        const val CHECKIN_CHANNEL_ID = "tracker_checkin"
        const val QUICKLOG_CHANNEL_ID = "tracker_quicklog"
        const val EXTRA_TRACKER_ID = "tracker_id"
        const val EXTRA_VALUE = "tracker_value"

        // Bases far from the reminders' (2000 + id, 500000 + id) so no id can collide.
        private const val CHECKIN_ID_BASE = 1_000_000
        private const val NOTICE_ID_BASE = 2_000_000
        private const val QUICKLOG_ID_BASE = 3_000_000
        private const val ALARM_REQUEST_BASE = 4_000_000
        private const val LATER_REQUEST_BASE = 5_000_000
        private const val OPEN_REQUEST_BASE = 6_000_000

        private const val TRY_LATER_MILLIS = 60L * 60 * 1000
        private const val ANSWERED_WINDOW_MILLIS = 24L * 60 * 60 * 1000
    }
}
