package com.daymark.app.notifications

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.daymark.app.R

/**
 * What a Daymark notification shows on a locked phone: the word "Daymark" and nothing else. A
 * tracker's or a reminder's own name ("Urges", "Took meds") says something private to anyone
 * holding the phone, so every notification Daymark posts is built through [lockedAway] and every
 * button through [unlockedAction].
 */
object NotificationPrivacy {

    /** Hides this notification's own words on the lock screen, which shows only "Daymark". */
    fun NotificationCompat.Builder.lockedAway(context: Context, channelId: String): NotificationCompat.Builder =
        setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build(),
            )

    /**
     * A notification button that does nothing until the phone is unlocked (Android 12 and later;
     * earlier versions have no such switch, and the lock screen there hides the buttons with the
     * words). Without it, a Yes on a locked phone writes into the journal past the app lock.
     */
    fun unlockedAction(title: String, intent: PendingIntent): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, title, intent)
            .setAuthenticationRequired(true)
            .build()
}
