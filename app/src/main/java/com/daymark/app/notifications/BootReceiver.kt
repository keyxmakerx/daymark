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

/** Re-arms all reminders after a device reboot (alarms don't survive boot). */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: ReminderRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        scope.launch {
            try {
                repository.rescheduleAll()
            } catch (_: RuntimeException) {
                // Same reason as ReminderReceiver: a journal this phone can no longer open must
                // not crash a boot receiver. The reminders are re-armed when the app is next
                // opened, which is also when the person is told what happened.
            } finally {
                pending.finish()
            }
        }
    }
}
