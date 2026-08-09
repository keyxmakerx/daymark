package com.daymark.companion.auth

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Serving a lockout must clear the debt that caused it.
 *
 * `redeemInvite` bumped `fail_count` on every wrong secret and never reset it, so the counter only
 * climbed. Once a credential had crossed the threshold once, the *first* failure after each expired
 * lockout immediately re-armed another — and since the backoff is `base shl (fails - threshold)`,
 * each one was longer than the last until it pinned at the one-hour cap. A therapist who mistyped
 * one character past the threshold could not enrol at all, and the only remedy was the owner
 * minting a fresh invite.
 *
 * `recordTotpFailure` has carried the correct reset, with a comment explaining exactly this, since
 * it was written 130 lines further down the same file.
 */
class InviteLockoutTest {

    private fun store(clock: () -> Long) =
        AuthStore(Files.createTempDirectory("invite-lockout").toString(), clock = clock)

    @Test
    fun `an expired lockout resets the counter instead of escalating`() {
        var now = 1_000_000L
        val auth = store { now }
        val minted = auth.mintInvite("relA", listOf("notes"), ttlSeconds = 3_600L)

        val fails = 2
        val base = 1_000L

        // Cross the threshold: two wrong secrets, then locked.
        repeat(fails) {
            assertEquals(AuthStore.RedeemStatus.WRONG_SECRET, auth.redeemInvite(minted.inviteId, "nope", fails, base).status)
        }
        assertEquals(AuthStore.RedeemStatus.LOCKED, auth.redeemInvite(minted.inviteId, "nope", fails, base).status)

        // Sit out the lockout. The next wrong secret is failure number ONE again, so it must not
        // re-lock — under the old code it was failure number three and locked immediately, for
        // twice as long.
        now += 10_000
        assertEquals(AuthStore.RedeemStatus.WRONG_SECRET, auth.redeemInvite(minted.inviteId, "nope", fails, base).status)
        assertEquals(
            AuthStore.RedeemStatus.WRONG_SECRET,
            auth.redeemInvite(minted.inviteId, "nope", fails, base).status,
            "the second failure of the new window is what re-locks, not the first",
        )
        assertEquals(AuthStore.RedeemStatus.LOCKED, auth.redeemInvite(minted.inviteId, "nope", fails, base).status)
    }

    @Test
    fun `the correct secret still works after sitting out a lockout`() {
        // The point of the reset, from the therapist's side: enrolment is recoverable.
        var now = 1_000_000L
        val auth = store { now }
        val minted = auth.mintInvite("relA", listOf("notes"), ttlSeconds = 3_600L)

        repeat(2) { auth.redeemInvite(minted.inviteId, "nope", 2, 1_000L) }
        assertEquals(AuthStore.RedeemStatus.LOCKED, auth.redeemInvite(minted.inviteId, minted.secret, 2, 1_000L).status)

        now += 10_000
        assertEquals(AuthStore.RedeemStatus.OK, auth.redeemInvite(minted.inviteId, minted.secret, 2, 1_000L).status)
    }

    @Test
    fun `a live lockout is still enforced`() {
        // The reset must key on expiry, not merely on the counter — otherwise it would hand an
        // attacker a fresh budget on every attempt and remove the lockout entirely.
        var now = 1_000_000L
        val auth = store { now }
        val minted = auth.mintInvite("relA", listOf("notes"), ttlSeconds = 3_600L)

        repeat(2) { auth.redeemInvite(minted.inviteId, "nope", 2, 60_000L) }
        now += 100 // well inside the 60 s lockout
        assertEquals(AuthStore.RedeemStatus.LOCKED, auth.redeemInvite(minted.inviteId, "nope", 2, 60_000L).status)
        assertEquals(AuthStore.RedeemStatus.LOCKED, auth.redeemInvite(minted.inviteId, minted.secret, 2, 60_000L).status)
    }
}
