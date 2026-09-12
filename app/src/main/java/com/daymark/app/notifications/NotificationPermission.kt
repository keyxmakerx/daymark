package com.daymark.app.notifications

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Whether Daymark's notifications will actually show right now, and where to send someone to fix
 * it if not.
 *
 * Deliberately holds no state of its own. [areEnabled] reads the live system permission on every
 * call — there is no stored "silent" flag to go stale when a person flips the switch in system
 * settings and comes back. [NotificationManagerCompat.areNotificationsEnabled] folds in both the
 * runtime `POST_NOTIFICATIONS` grant (Android 13+) and the older per-app notification toggle, so
 * one call is the single source of truth across every OS version this app supports.
 */
object NotificationPermission {
    fun areEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Deep-links straight to this app's notification settings. This is the only route back once
     * a permission dialog has been denied once — Android stops showing it again after that — so
     * nothing in this app re-prompts; every "fix it" action goes here instead.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
