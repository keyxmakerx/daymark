package com.daymark.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A tracker's check-in falling due, and the answers a person can give it without opening Daymark.
 * Each does exactly what the person said and nothing is inferred from it:
 *  - **Try later**: one more nudge for this tracker, an hour from now.
 *  - **Stop asking**: this tracker goes back to asking nothing; its own screen switches it on again.
 *  - **Put it back**: this tracker's check-ins return to the times the person set, at once.
 *  - **Yes** / **No** on a quick-log notification: that value is logged, now.
 */
@AndroidEntryPoint
class TrackerCheckInReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: TrackerCheckInScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val trackerId = intent.getLongExtra(TrackerCheckInScheduler.EXTRA_TRACKER_ID, -1L)
        if (trackerId < 0) return
        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    ACTION_DUE -> scheduler.onDue(trackerId)
                    ACTION_LATER_DUE -> scheduler.onDue(trackerId, later = true)
                    ACTION_TRY_LATER -> scheduler.scheduleLater(trackerId)
                    ACTION_STOP_ASKING -> scheduler.stopAsking(trackerId)
                    ACTION_PUT_BACK -> scheduler.putBack(trackerId)
                    ACTION_LOG -> if (intent.hasExtra(TrackerCheckInScheduler.EXTRA_VALUE)) {
                        scheduler.logFromNotification(trackerId, intent.getDoubleExtra(TrackerCheckInScheduler.EXTRA_VALUE, 0.0))
                    }
                }
            } catch (_: RuntimeException) {
                // As in ReminderReceiver: a journal this phone can no longer open must not crash a
                // notification. The app says what happened when it is next opened.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DUE = "com.daymark.app.tracker.DUE"
        const val ACTION_LATER_DUE = "com.daymark.app.tracker.LATER_DUE"
        const val ACTION_TRY_LATER = "com.daymark.app.tracker.TRY_LATER"
        const val ACTION_STOP_ASKING = "com.daymark.app.tracker.STOP_ASKING"
        const val ACTION_PUT_BACK = "com.daymark.app.tracker.PUT_BACK"
        const val ACTION_LOG = "com.daymark.app.tracker.LOG"
    }
}
