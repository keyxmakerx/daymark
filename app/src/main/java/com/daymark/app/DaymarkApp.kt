package com.daymark.app

import android.app.Application
import com.daymark.app.notifications.ReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DaymarkApp : Application() {

    @Inject lateinit var reminderScheduler: ReminderScheduler

    override fun onCreate() {
        super.onCreate()
        reminderScheduler.createChannel()
    }
}
