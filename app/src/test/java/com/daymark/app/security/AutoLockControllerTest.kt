package com.daymark.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When returning to the foreground re-locks the app.
 *
 * The case that drove these tests: the picker skip used to be resolved on the *outbound* leg, by
 * erasing the record that the app had been backgrounded at all. So a background that started in
 * the photo picker left nothing for the foreground check to look at, and the app never asked for
 * the PIN again — no matter how many hours the phone had been lying somewhere.
 */
class AutoLockControllerTest {

    /** Stands in for SystemClock.elapsedRealtime(), which is not available off-device. */
    private var nowMs: Long = 10_000L

    private val controller = AutoLockController { nowMs }

    private fun advance(ms: Long) {
        nowMs += ms
    }

    private companion object {
        const val MINUTE = 60_000L
        const val PICKER_LAUNCH_MS = 200L // startActivity() to ON_STOP
    }

    @Test
    fun `picking a photo and coming straight back does not re-lock`() {
        controller.suppressNextBackgroundLock()
        advance(PICKER_LAUNCH_MS)
        controller.onBackgrounded()
        advance(30_000L) // scrolled the gallery, tapped a photo
        assertFalse(controller.shouldLockOnForeground(0))
    }

    /**
     * The bug. Backgrounded from inside the picker, phone left on a table, and the next person to
     * pick it up is already past the lock screen. Against the old code this assertion FAILED —
     * shouldLockOnForeground() returned false here however long the wait was.
     */
    @Test
    fun `a phone left sitting after a picker launch still re-locks`() {
        controller.suppressNextBackgroundLock()
        advance(PICKER_LAUNCH_MS)
        controller.onBackgrounded()
        advance(6 * 60 * MINUTE)
        assertTrue(controller.shouldLockOnForeground(0))
    }

    @Test
    fun `the picker exemption ends at the round-trip window`() {
        controller.suppressNextBackgroundLock()
        advance(PICKER_LAUNCH_MS)
        controller.onBackgrounded()
        advance(2 * MINUTE)
        // Still inside the window: this is the exemption doing its job, and it proves the
        // "one millisecond later" assertion below is a real edge and not a broken setup.
        assertFalse(controller.shouldLockOnForeground(0))

        val second = AutoLockController { nowMs }
        second.suppressNextBackgroundLock()
        advance(PICKER_LAUNCH_MS)
        second.onBackgrounded()
        advance(2 * MINUTE + 1)
        assertTrue(second.shouldLockOnForeground(0))
    }

    @Test
    fun `the picker exemption is spent on the return and does not cover the next background`() {
        controller.suppressNextBackgroundLock()
        advance(PICKER_LAUNCH_MS)
        controller.onBackgrounded()
        advance(5_000L)
        assertFalse(controller.shouldLockOnForeground(0))

        // Same session, this time the user leaves the app with no picker involved.
        controller.onBackgrounded()
        advance(5_000L)
        assertTrue(controller.shouldLockOnForeground(0))
    }

    @Test
    fun `an ordinary background re-locks at once when no grace period is configured`() {
        controller.onBackgrounded()
        advance(10L)
        assertTrue(controller.shouldLockOnForeground(0))
    }

    @Test
    fun `the configured grace period still applies to an ordinary background`() {
        controller.onBackgrounded()
        advance(4 * MINUTE)
        assertFalse(controller.shouldLockOnForeground(5))

        val second = AutoLockController { nowMs }
        second.onBackgrounded()
        advance(5 * MINUTE)
        assertTrue(second.shouldLockOnForeground(5))
    }

    /**
     * A picker that never actually launched leaves the arm behind. It must not turn whatever
     * background happens next into an exempt one, which is what SKIP_WINDOW_MS is for.
     */
    @Test
    fun `a stale arm does not exempt a much later background`() {
        controller.suppressNextBackgroundLock()
        advance(61_000L)
        controller.onBackgrounded()
        advance(10L)
        assertTrue(controller.shouldLockOnForeground(0))
    }

    @Test
    fun `foreground with no background recorded is not a lock decision`() {
        // Fresh process, or a second ON_START without an ON_STOP between them.
        assertFalse(controller.shouldLockOnForeground(0))

        controller.onBackgrounded()
        advance(10L)
        assertTrue(controller.shouldLockOnForeground(0))
        assertFalse(controller.shouldLockOnForeground(0))
    }
}
