package com.daymark.companion.auth

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pairing attempt budget has to survive the process.
 *
 * `AttemptLimiter` keeps its windows in a `HashMap` in process memory, and that was a defensible
 * trade while rate limiting was hardening piled on top of controls that stood without it. It stops
 * being defensible the moment a limiter IS the security argument: a password-authenticated exchange
 * claims "one online guess per attempt, and no offline attack", and that claim is worth exactly as
 * much as the thing counting attempts. An in-memory counter is cleared by a crash, a deploy, an OOM
 * or a `docker compose up -d` — so an attacker who can provoke a restart, or who is simply willing
 * to wait for one, is handed a fresh budget for free and nothing anywhere records that it happened.
 * Two instances behind a load balancer are the same bug standing still.
 *
 * Hence the shape of the first test, which is the only shape that tests anything here: burn the
 * budget through one store, CLOSE it, open a second store over the same database, and require the
 * refusal to still be there. A test that only ever touches one live instance passes just as well
 * against the HashMap it is supposed to be replacing.
 */
class PersistentAttemptLimiterTest {

    private var now = 1_000_000L
    private fun dir() = Files.createTempDirectory("attempt-persist").toString()
    private fun store(dir: String) = AuthStore(dir, clock = { now })
    private fun limiter(store: AuthStore, max: Int = 3, window: Long = 60_000L) =
        PersistentAttemptLimiter(store, scope = "pair", maxPerWindow = max, windowMs = window)

    @Test
    fun `a spent budget survives a restart`() {
        val dir = dir()

        val first = store(dir)
        val before = limiter(first)
        repeat(3) { assertTrue(before.allow("1.2.3.4"), "the first three attempts are within budget") }
        assertFalse(before.allow("1.2.3.4"), "the fourth is not")
        first.close()

        // The restart. Everything the old process held in memory is gone; only the database is
        // left, which is the whole point of moving the counter into it.
        val second = store(dir)
        val after = limiter(second)
        assertFalse(
            after.allow("1.2.3.4"),
            "a restart must not hand the attacker a fresh guessing budget — this is the finding",
        )
        second.close()
    }

    @Test
    fun `a partly spent budget survives a restart with the right amount left`() {
        // Not just "still locked": the count itself has to carry over, or a restart would still be
        // worth provoking as a way of buying attempts back a couple at a time.
        val dir = dir()

        val first = store(dir)
        repeat(2) { assertTrue(limiter(first).allow("203.0.113.9")) }
        first.close()

        val second = store(dir)
        val after = limiter(second)
        assertTrue(after.allow("203.0.113.9"), "the third attempt of the window is still owed")
        assertFalse(after.allow("203.0.113.9"), "and the fourth is not")
        second.close()
    }

    @Test
    fun `the window still ages out across a restart rather than locking a source forever`() {
        // Durability must not turn a five-minute budget into a permanent ban: the stored window is
        // a timestamp, not a flag, so the honest therapist who came back later starts clean.
        val dir = dir()

        val first = store(dir)
        repeat(3) { limiter(first).allow("1.2.3.4") }
        assertFalse(limiter(first).allow("1.2.3.4"))
        first.close()

        now += 60_000L
        val second = store(dir)
        assertTrue(limiter(second).allow("1.2.3.4"))
        second.close()
    }

    @Test
    fun `reset clears a source across a restart too`() {
        val dir = dir()
        val first = store(dir)
        val l1 = limiter(first)
        repeat(3) { l1.allow("1.2.3.4") }
        assertFalse(l1.allow("1.2.3.4"))
        l1.reset("1.2.3.4")
        first.close()

        val second = store(dir)
        assertTrue(limiter(second).allow("1.2.3.4"), "a success clears the debt, and that clearing persists")
        second.close()
    }

    @Test
    fun `scopes and sources are independent so one caller cannot spend another's budget`() {
        val dir = dir()
        val s = store(dir)
        val pair = PersistentAttemptLimiter(s, scope = "pair", maxPerWindow = 3, windowMs = 60_000L)
        val other = PersistentAttemptLimiter(s, scope = "other", maxPerWindow = 3, windowMs = 60_000L)

        repeat(3) { assertTrue(pair.allow("6.6.6.6")) }
        assertFalse(pair.allow("6.6.6.6"))
        assertTrue(pair.allow("203.0.113.9"), "a different source is unaffected — the whole point of keying on it")
        assertTrue(other.allow("6.6.6.6"), "and a different scope is a different budget")
        s.close()
    }

    @Test
    fun `a refused attempt does not extend its own window`() {
        // The in-memory limiter neither counts nor re-dates a refused attempt, so hammering a
        // locked-out source cannot keep it locked out. Reproduce that exactly, or "persisted"
        // would quietly mean "harsher".
        val dir = dir()
        val s = store(dir)
        val l = limiter(s, max = 1, window = 60_000L)

        assertTrue(l.allow("1.2.3.4"))
        now += 30_000L
        repeat(5) { assertFalse(l.allow("1.2.3.4")) }
        now += 30_000L // 60 s after the window STARTED, not after the last refusal
        assertTrue(l.allow("1.2.3.4"), "the window is dated from the attempt that opened it")
        s.close()
    }

    @Test
    fun `it agrees with the in-memory limiter attempt for attempt`() {
        // The two implementations are meant to be interchangeable at a call site, with only the
        // durability differing. Drive both with one sequence and require the same answers, so a
        // future edit to either cannot drift the semantics apart unnoticed.
        val dir = dir()
        val s = store(dir)
        val durable = limiter(s, max = 3, window = 60_000L)
        val inMemory = AttemptLimiter(3, 60_000L) { now }

        val sources = listOf("1.2.3.4", "1.2.3.4", "1.2.3.4", "1.2.3.4", "5.6.7.8", "1.2.3.4")
        for ((i, src) in sources.withIndex()) {
            assertEquals(inMemory.allow(src), durable.allow(src), "attempt $i for $src")
        }
        now += 60_000L
        assertEquals(inMemory.allow("1.2.3.4"), durable.allow("1.2.3.4"), "after the window rolls")
        s.close()
    }
}
