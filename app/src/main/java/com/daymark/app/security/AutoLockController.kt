package com.daymark.app.security

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether returning to the foreground should re-lock the app.
 *
 * Two responsibilities:
 *  - **Skip:** suppress the "re-lock on background" for one bounded round trip when the app
 *    intentionally launches another activity (e.g. the system file/photo picker), so returning
 *    from that flow doesn't bounce the user to the lock screen or cancel in-flight work. Bounded
 *    on both legs — see [PICKER_RETURN_WINDOW_MS] for why the return leg needs its own limit.
 *  - **Timeout:** honour the user's auto-lock grace period — only re-lock if the app was in the
 *    background longer than the chosen number of minutes (0 = lock immediately).
 *
 * Time is measured with [SystemClock.elapsedRealtime] (monotonic) so wall-clock changes can't
 * be used to dodge the lock.
 */
@Singleton
class AutoLockController internal constructor(
    private val elapsedRealtimeMs: () -> Long,
) {
    @Inject constructor() : this({ SystemClock.elapsedRealtime() })

    /** elapsedRealtime() when a skip was armed, or -1 if none. Skips expire so an abandoned
     *  picker launch can't silently swallow a later, genuine re-lock. */
    @Volatile
    private var skipArmedAtMs: Long = -1L

    /** elapsedRealtime() at the moment we backgrounded, or -1 if there's nothing to evaluate. */
    @Volatile
    private var backgroundedAtMs: Long = -1L

    /** Whether the background recorded in [backgroundedAtMs] was a picker launch we armed for. */
    @Volatile
    private var backgroundWasPickerLaunch: Boolean = false

    /** Call right before launching a file/photo picker or other external activity. */
    fun suppressNextBackgroundLock() {
        skipArmedAtMs = elapsedRealtimeMs()
    }

    /**
     * Record that the app went to the background, and whether that background was the picker
     * launch we armed for. The picker case is only *flagged* here, not acted on: it used to be
     * resolved by throwing the background time away, which meant the app had no record that it had
     * ever been backgrounded. If the process then went to the background from inside the picker
     * and came straight back to Daymark, [shouldLockOnForeground] saw nothing to evaluate and the
     * app never re-locked — you could pick a photo, leave the phone on a café table all afternoon,
     * and anyone who picked it up was already inside the journal. The expiry now lives on the
     * return leg, where the waiting actually happens.
     */
    fun onBackgrounded() {
        val armed = skipArmedAtMs
        skipArmedAtMs = -1L
        val now = elapsedRealtimeMs()
        backgroundWasPickerLaunch = armed >= 0 && now - armed <= SKIP_WINDOW_MS
        backgroundedAtMs = now
    }

    /**
     * Returns true if coming back to the foreground should require unlocking again, given the
     * configured [timeoutMinutes] (0 = always). Consumes the recorded background time.
     */
    fun shouldLockOnForeground(timeoutMinutes: Int): Boolean {
        val bg = backgroundedAtMs
        val wasPicker = backgroundWasPickerLaunch
        backgroundedAtMs = -1L
        backgroundWasPickerLaunch = false
        if (bg < 0L) return false
        val away = elapsedRealtimeMs() - bg
        // Choosing a photo shouldn't bounce you to the lock screen mid-entry — but only for as
        // long as that plausibly IS someone choosing a photo. Past the window the skip is dropped
        // and the normal timeout decides, so an abandoned phone still re-locks.
        if (wasPicker && away <= PICKER_RETURN_WINDOW_MS) return false
        if (timeoutMinutes <= 0) return true
        return away >= timeoutMinutes * 60_000L
    }

    private companion object {
        /** How long an armed picker-skip stays valid before it's ignored. Covers arm-to-background
         *  only, which is the few hundred ms between startActivity() and ON_STOP. */
        const val SKIP_WINDOW_MS = 60_000L

        /** How long the app may stay backgrounded in a picker and still return without re-locking.
         *  Long enough to scroll a gallery and pick a photo; far short of walking away. */
        const val PICKER_RETURN_WINDOW_MS = 2 * 60_000L
    }
}
