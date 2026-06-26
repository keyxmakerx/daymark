package com.daymark.app.security

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets the app suppress a single "re-lock on background" event when it intentionally
 * launches another app (e.g. the system file picker for export/import), so returning
 * from that flow doesn't bounce the user to the lock screen — and doesn't cancel the
 * in-flight work by tearing down the current screen.
 */
@Singleton
class AutoLockController @Inject constructor() {

    @Volatile
    private var skip = false

    /** Call right before launching a file picker / external activity. */
    fun suppressNextBackgroundLock() {
        skip = true
    }

    /** Returns true (once) if the next background lock should be skipped. */
    fun consumeSkip(): Boolean {
        if (skip) {
            skip = false
            return true
        }
        return false
    }
}
