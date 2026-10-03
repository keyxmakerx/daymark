package com.daymark.app

import android.app.Application
import com.daymark.app.data.ReminderRepository
import com.daymark.app.notifications.ReminderScheduler
import com.daymark.app.notifications.TrackerCheckInScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DaymarkApp : Application() {

    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var reminderRepository: ReminderRepository
    @Inject lateinit var trackerCheckIns: TrackerCheckInScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        reminderScheduler.createChannel()
        // One-time import of the legacy single reminder for upgrading users.
        scope.launch { reminderRepository.migrateLegacyReminderIfNeeded() }
        trackerCheckIns.createChannels()
        // Each tracker's next check-in and its quick-log notification, which a force-stop or an
        // update can clear. A journal this phone cannot open is said so on screen, not here.
        scope.launch { runCatching { trackerCheckIns.refreshAll() } }
    }
}
