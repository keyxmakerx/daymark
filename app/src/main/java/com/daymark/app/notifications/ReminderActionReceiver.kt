package com.daymark.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.daymark.app.data.CheckInStateStore
import com.daymark.app.data.ReminderRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The answers a person can give a reminder without opening Daymark (#195), and **Put it back** on
 * the rules engine's notice (`docs/DECISIONS.md` §D1).
 *
 * Each does exactly what the person said and nothing is inferred from it:
 *  - **Try later**: one more nudge for this reminder, an hour from now.
 *  - **Stop asking**: this reminder is switched off. Switching it back on in Settings → Reminders
 *    undoes it.
 *  - **Put it back**: the reminders return to the times the person set, at once.
 */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: ReminderRepository
    @Inject lateinit var scheduler: ReminderScheduler
    @Inject lateinit var checkInState: CheckInStateStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val reminderId = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L)
        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    ACTION_TRY_LATER -> if (reminderId >= 0) scheduler.scheduleLater(reminderId)
                    ACTION_STOP_ASKING -> repository.get(reminderId)?.let { reminder ->
                        repository.update(reminder.copy(enabled = false))
                    }
                    ACTION_PUT_BACK -> {
                        checkInState.putBack(System.currentTimeMillis())
                        scheduler.cancelNotice()
                    }
                }
            } catch (_: RuntimeException) {
                // As in ReminderReceiver: a journal this phone can no longer open must not crash a
                // notification action. The app says what happened when it is next opened.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TRY_LATER = "com.daymark.app.reminder.TRY_LATER"
        const val ACTION_STOP_ASKING = "com.daymark.app.reminder.STOP_ASKING"
        const val ACTION_PUT_BACK = "com.daymark.app.reminder.PUT_BACK"
    }
}
