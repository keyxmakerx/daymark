package com.daymark.app

import android.app.Application
import android.content.SharedPreferences
import com.daymark.app.data.RetiredSleepSetup
import com.daymark.app.data.ReminderRepository
import com.daymark.app.data.SettingsRepository
import com.daymark.app.notifications.ReminderScheduler
import com.daymark.app.notifications.TrackerCheckInScheduler
import com.daymark.app.widget.TrackerWidget
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DaymarkApp : Application() {

    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var reminderRepository: ReminderRepository
    @Inject lateinit var trackerCheckIns: TrackerCheckInScheduler
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var prefs: SharedPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        reminderScheduler.createChannel()
        // One-time import of the legacy single reminder for upgrading users.
        scope.launch { reminderRepository.migrateLegacyReminderIfNeeded() }
        // The old sleep setup's answers, which nothing reads (#356).
        scope.launch { RetiredSleepSetup.forget(prefs) }
        trackerCheckIns.createChannels()
        // Each tracker's next check-in and its quick-log notification, which a force-stop or an
        // update can clear. A journal this phone cannot open is said so on screen, not here.
        scope.launch { runCatching { trackerCheckIns.refreshAll() } }
        // The trackers widget names nothing while the app lock is on, so it is redrawn the moment
        // the lock is switched either way (widget/TrackerWidget.kt).
        scope.launch {
            settings.changes().map { settings.lockEnabled }.distinctUntilChanged().collect { TrackerWidget.redraw(this@DaymarkApp) }
        }
    }
}
