package com.daymark.companion.auth

import com.daymark.companion.auth.AuthStore.Companion.ATTEMPT_WINDOWS_MAX_PER_SCOPE
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The attempt-window table must not be a hole an attacker can pour disk into.
 *
 * A row in `attempt_windows` costs the attacker one request from one distinct source, and
 * "distinct source" is the cheapest thing an attacker varies. The amortised prune only removes
 * windows that have AGED OUT, so under source rotation the table grew without bound — and SQLite's
 * DELETE hands pages to the freelist without shrinking the file, so the growth was permanent, in
 * auth.db, the same file that holds the invites and the TOTP seeds. Hence the hard per-scope cap,
 * enforced at insert time by evicting the OLDEST-started window first.
 *
 * These tests pin the three properties that make the cap correct rather than merely present:
 * the table cannot be pushed past the cap by any number of distinct sources; eviction takes the
 * oldest window (relaxing the budget of the source quiet longest — the safe direction) and never
 * the newest (which would forget the sources currently mid-attack); and a spent window that was
 * NOT evicted keeps refusing exactly as before, because the cap must not weaken the limiter for
 * anyone still on the books.
 */
class AttemptWindowCapTest {

    private var now = 1_000_000L
    private fun store() = AuthStore(Files.createTempDirectory("attempt-cap").toString(), clock = { now })

    @Test
    fun `a flood of distinct sources cannot push the table past the cap`() {
        val auth = store()
        // One row seeded in a different scope, to prove the eviction's blast radius is one scope:
        // the pairing surface's flood must never spend another budget's bookkeeping.
        assertTrue(auth.allowAttempt("other", "innocent-bystander", 3, 300_000L))

        // Well past the cap, every request from a source never seen before — the rotation attack
        // itself. Every attempt is within its own per-source budget, so every one is allowed;
        // what must NOT happen is the table keeping a row for each.
        repeat(ATTEMPT_WINDOWS_MAX_PER_SCOPE + 100) { i ->
            assertTrue(auth.allowAttempt("pair", "rotated-source-$i", 3, 300_000L))
        }

        assertEquals(
            ATTEMPT_WINDOWS_MAX_PER_SCOPE, auth.attemptWindowCountFor("pair"),
            "live rows are capped at insert time — table size is a constant, not a function of attacker patience",
        )
        assertEquals(1, auth.attemptWindowCountFor("other"), "eviction stays inside the flooded scope")
        auth.close()
    }

    @Test
    fun `eviction removes the oldest window, not the newest`() {
        val auth = store()
        val max = 3
        val window = 300_000L

        // The OLDEST window: a source that spent its whole budget first, then went quiet.
        repeat(max) { assertTrue(auth.allowAttempt("pair", "oldest-spent", max, window)) }
        assertFalse(auth.allowAttempt("pair", "oldest-spent", max, window))

        // A NEWER spent window, one second later. This is the row eviction must NOT take.
        now += 1_000
        repeat(max) { assertTrue(auth.allowAttempt("pair", "newer-spent", max, window)) }
        assertFalse(auth.allowAttempt("pair", "newer-spent", max, window))

        // Fill to the cap and exactly one past it: the final insert is the one that forces an
        // eviction, and the row it takes decides this test.
        now += 1_000
        repeat(ATTEMPT_WINDOWS_MAX_PER_SCOPE - 1) { i ->
            assertTrue(auth.allowAttempt("pair", "filler-$i", max, window))
        }
        assertEquals(ATTEMPT_WINDOWS_MAX_PER_SCOPE, auth.attemptWindowCountFor("pair"))

        // Order matters here: the surviving spent window is checked FIRST, because checking the
        // evicted one writes a fresh row and forces the next eviction as a side effect.
        assertFalse(
            auth.allowAttempt("pair", "newer-spent", max, window),
            "a spent window that was not evicted keeps refusing — the cap must not weaken the limiter for sources on the books",
        )
        assertTrue(
            auth.allowAttempt("pair", "oldest-spent", max, window),
            "the OLDEST window is the one that was evicted: the source quiet longest gets the relaxation, " +
                "which is the documented safe direction — the attacker paid one insert for it and gained " +
                "nothing they could not have had by spending that address's own budget directly",
        )
        assertEquals(
            ATTEMPT_WINDOWS_MAX_PER_SCOPE, auth.attemptWindowCountFor("pair"),
            "and the table is still exactly at the cap",
        )
        auth.close()
    }

    @Test
    fun `the cap never fires on traffic that stays under it`() {
        // The other half of the sizing argument: honest use — a handful of sources, one row each,
        // however many attempts they spend — must never come near an eviction, so nobody's live
        // window is forgotten while the table is quiet.
        val auth = store()
        repeat(50) { i ->
            repeat(3) { assertTrue(auth.allowAttempt("pair", "clinic-machine-$i", 3, 300_000L)) }
            assertFalse(auth.allowAttempt("pair", "clinic-machine-$i", 3, 300_000L))
        }
        assertEquals(50, auth.attemptWindowCountFor("pair"))
        // Every one of the fifty spent windows is still on the books and still refusing.
        repeat(50) { i -> assertFalse(auth.allowAttempt("pair", "clinic-machine-$i", 3, 300_000L)) }
        auth.close()
    }
}
