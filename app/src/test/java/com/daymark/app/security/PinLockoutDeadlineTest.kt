package com.daymark.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wrong-PIN cool-down, against a clock the person holding the phone may control.
 *
 * The cool-down used to be one `System.currentTimeMillis()` deadline, so the whole backoff could be
 * skipped from the lock screen: pull down the shade, open date & time, step the date forward, guess
 * again. Several tests below FAIL against that arithmetic and say so.
 *
 * [LockoutDeadline] is pure so it can be exercised here; [PinManager] itself cannot be (it needs
 * EncryptedSharedPreferences and android.util.Base64, and this module has no Robolectric).
 */
class PinLockoutDeadlineTest {

    private companion object {
        const val CAP_MS = 5 * 60_000L
        const val COOLDOWN_MS = 5 * 60_000L
        const val DAY_MS = 86_400_000L

        /** Wall clock at the fifth wrong PIN. */
        const val WALL_AT_LOCKOUT = 1_700_000_000_000L

        /** elapsedRealtime() at the same moment — the phone had been up an hour. */
        const val UPTIME_AT_LOCKOUT = 3_600_000L
    }

    private fun deadline(
        wallUntilMs: Long = WALL_AT_LOCKOUT + COOLDOWN_MS,
        elapsedStartMs: Long = UPTIME_AT_LOCKOUT,
        durationMs: Long = COOLDOWN_MS,
    ) = LockoutDeadline(wallUntilMs, elapsedStartMs, durationMs, CAP_MS)

    @Test
    fun `a fresh cool-down has its whole duration left, and runs out on time`() {
        assertEquals(COOLDOWN_MS, deadline().remainingMs(WALL_AT_LOCKOUT, UPTIME_AT_LOCKOUT))
        assertEquals(
            COOLDOWN_MS / 2,
            deadline().remainingMs(
                WALL_AT_LOCKOUT + COOLDOWN_MS / 2,
                UPTIME_AT_LOCKOUT + COOLDOWN_MS / 2,
            ),
        )
        assertEquals(
            0L,
            deadline().remainingMs(WALL_AT_LOCKOUT + COOLDOWN_MS, UPTIME_AT_LOCKOUT + COOLDOWN_MS),
        )
    }

    /**
     * The bug. A day of wall clock with no real time passed: the old single-deadline code returned
     * 0 here and handed over the next guess immediately, so this assertion FAILED against it.
     */
    @Test
    fun `stepping the wall clock forward does not shorten the cool-down`() {
        assertEquals(
            COOLDOWN_MS,
            deadline().remainingMs(WALL_AT_LOCKOUT + DAY_MS, UPTIME_AT_LOCKOUT),
        )
    }

    /**
     * Rebooting is the other way to try to shake a monotonic clock off, since elapsedRealtime()
     * restarts near zero. It has to cost time, not save it.
     */
    @Test
    fun `a reboot restarts the cool-down instead of clearing it`() {
        val remaining = deadline().remainingMs(WALL_AT_LOCKOUT + 60_000L, 12_000L)
        assertEquals(COOLDOWN_MS, remaining)
        assertTrue(remaining <= CAP_MS)
    }

    /**
     * After a reboot the new uptime can pass the old one — but only by actually being up that
     * long, which is far more than a cool-down. No shortcut, and the wall clock still gets a vote.
     */
    @Test
    fun `uptime that has genuinely overtaken the lockout expires it`() {
        assertEquals(
            0L,
            deadline().remainingMs(WALL_AT_LOCKOUT + 2 * 3_600_000L, 7_200_000L),
        )
        // Same uptime, but the wall clock insists we are still inside the cool-down: stay locked.
        assertEquals(COOLDOWN_MS, deadline().remainingMs(WALL_AT_LOCKOUT, 7_200_000L))
    }

    /**
     * Winding the clock backwards is the failure mode that hurts the person who owns the phone,
     * not an attacker, so its cost is bounded: one full cool-down, never more.
     */
    @Test
    fun `a backwards wall clock cannot lock someone out for longer than one cool-down`() {
        assertEquals(COOLDOWN_MS, deadline().remainingMs(WALL_AT_LOCKOUT - DAY_MS, UPTIME_AT_LOCKOUT))
        assertEquals(
            COOLDOWN_MS,
            deadline().remainingMs(WALL_AT_LOCKOUT - DAY_MS, UPTIME_AT_LOCKOUT + COOLDOWN_MS),
        )
    }

    /**
     * A phone that was mid-lockout when it updated to this version has only the old wall deadline
     * stored. Honour it for that one cool-down rather than dropping the lockout on the floor.
     */
    @Test
    fun `a lockout carried over from the previous version still counts down`() {
        val carried = LockoutDeadline(
            wallUntilMs = WALL_AT_LOCKOUT + 60_000L,
            elapsedStartMs = 0L,
            durationMs = 0L,
            capMs = CAP_MS,
        )
        assertEquals(60_000L, carried.remainingMs(WALL_AT_LOCKOUT, 999L))
        assertEquals(0L, carried.remainingMs(WALL_AT_LOCKOUT + 60_000L, 999L))
    }

    @Test
    fun `a carried-over lockout cannot outlive the cap`() {
        val absurd = LockoutDeadline(
            wallUntilMs = WALL_AT_LOCKOUT + DAY_MS,
            elapsedStartMs = 0L,
            durationMs = 0L,
            capMs = CAP_MS,
        )
        assertEquals(CAP_MS, absurd.remainingMs(WALL_AT_LOCKOUT, 999L))
    }

    @Test
    fun `no lockout on record means no wait`() {
        val none = LockoutDeadline(0L, 0L, 0L, CAP_MS)
        assertEquals(0L, none.remainingMs(WALL_AT_LOCKOUT, UPTIME_AT_LOCKOUT))
    }
}
