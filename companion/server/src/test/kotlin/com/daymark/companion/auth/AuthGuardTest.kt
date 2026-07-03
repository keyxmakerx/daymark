package com.daymark.companion.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class AuthGuardTest {

    @Test
    fun `rotate takes effect immediately and invalidates the old token`() {
        val guard = AuthGuard("old-token", lockoutThreshold = 8, lockoutMillis = 1000, ratePerSecond = 100)
        assertEquals(AuthGuard.Result.OK, guard.authorize("peer-a", "old-token"))

        guard.rotate("new-token")

        assertEquals(AuthGuard.Result.BAD_TOKEN, guard.authorize("peer-b", "old-token"))
        assertEquals(AuthGuard.Result.OK, guard.authorize("peer-b", "new-token"))
    }

    @Test
    fun `rotate does not reset an in-progress lockout for a source`() {
        var now = 0L
        val guard = AuthGuard("old-token", lockoutThreshold = 2, lockoutMillis = 60_000, ratePerSecond = 100, clock = { now })
        assertEquals(AuthGuard.Result.BAD_TOKEN, guard.authorize("peer", "wrong"))
        assertEquals(AuthGuard.Result.BAD_TOKEN, guard.authorize("peer", "wrong"))
        // Now locked out for "peer".
        assertEquals(AuthGuard.Result.LOCKED, guard.authorize("peer", "old-token"))

        guard.rotate("new-token")

        // Rotation does not clear the pre-existing lockout for this source.
        assertEquals(AuthGuard.Result.LOCKED, guard.authorize("peer", "new-token"))

        now += 61_000
        assertEquals(AuthGuard.Result.OK, guard.authorize("peer", "new-token"))
    }
}
