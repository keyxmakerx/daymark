package com.daymark.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.daymark.app.data.ReminderRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Posts a reminder's notification, then re-arms its alarm for the next day. Also fires a "Try later" nudge. */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: ReminderRepository
    @Inject lateinit var scheduler: ReminderScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L)
        if (reminderId < 0) return
        val pending = goAsync()
        scope.launch {
            try {
                val later = intent.getBooleanExtra(ReminderScheduler.EXTRA_LATER, false)
                repository.get(reminderId)?.takeIf { it.enabled }?.let { reminder ->
                    scheduler.showNotification(reminder, later = later)
                    // Re-arm the daily alarm (exact alarms are one-shot). A "Try later" nudge is a
                    // one-off alarm of its own, so the daily one is still armed.
                    if (!later) scheduler.schedule(reminder)
                }
            } catch (_: RuntimeException) {
                // The journal is encrypted at rest, and a phone whose keystore has lost the key
                // cannot open it — the app says so when somebody opens it. From a broadcast
                // receiver there is nothing useful to do and nothing honest to say, and an
                // uncaught exception here is a crash dialog at whatever time of day this person
                // chose for their reminder. Every SQLite failure arrives as a RuntimeException.
            } finally {
                pending.finish()
            }
        }
    }
}
