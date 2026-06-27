package com.daymark.app.security

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether returning to the foreground should re-lock the app.
 *
 * Two responsibilities:
 *  - **Skip:** suppress a single "re-lock on background" when the app intentionally launches
 *    another activity (e.g. the system file/photo picker), so returning from that flow doesn't
 *    bounce the user to the lock screen or cancel in-flight work.
 *  - **Timeout:** honour the user's auto-lock grace period — only re-lock if the app was in the
 *    background longer than the chosen number of minutes (0 = lock immediately).
 *
 * Time is measured with [SystemClock.elapsedRealtime] (monotonic) so wall-clock changes can't
 * be used to dodge the lock.
 */
@Singleton
class AutoLockController @Inject constructor() {

    @Volatile
    private var skip = false

    /** elapsedRealtime() at the moment we backgrounded, or -1 if there's nothing to evaluate. */
    @Volatile
    private var backgroundedAtMs: Long = -1L

    /** Call right before launching a file/photo picker or other external activity. */
    fun suppressNextBackgroundLock() {
        skip = true
    }

    /**
     * Record that the app went to the background. If a skip was armed it is consumed here and
     * the background is treated as intentional (no re-lock on return).
     */
    fun onBackgrounded() {
        if (skip) {
            skip = false
            backgroundedAtMs = -1L
            return
        }
        backgroundedAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * Returns true if coming back to the foreground should require unlocking again, given the
     * configured [timeoutMinutes] (0 = always). Consumes the recorded background time.
     */
    fun shouldLockOnForeground(timeoutMinutes: Int): Boolean {
        val bg = backgroundedAtMs
        backgroundedAtMs = -1L
        if (bg < 0L) return false
        if (timeoutMinutes <= 0) return true
        val elapsed = SystemClock.elapsedRealtime() - bg
        return elapsed >= timeoutMinutes * 60_000L
    }
}
