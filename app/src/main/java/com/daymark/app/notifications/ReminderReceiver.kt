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

/** Posts a reminder's notification, then re-arms its alarm for the next day. */
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
                repository.get(reminderId)?.takeIf { it.enabled }?.let { reminder ->
                    scheduler.showNotification(reminder)
                    scheduler.schedule(reminder) // re-arm (exact alarms are one-shot)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
