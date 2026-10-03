package com.daymark.app.stats

import com.daymark.app.stats.CheckInEngine.Pace
import com.daymark.app.stats.InterruptionBudget.Kind
import com.daymark.app.stats.InterruptionBudget.Offer
import com.daymark.app.stats.InterruptionBudget.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules engine's core, held to `docs/DECISIONS.md` §D1a: going quiet never makes the app
 * louder. The four rules in [CheckInEngine]'s header each get their own test, because each breaks
 * in a way the others cannot see. The ledgers are swept exhaustively up to a length, not sampled,
 * so no draw can make a test pass or fail by luck.
 */
class CheckInEngineTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val start = 1_700_000_000_000L - (1_700_000_000_000L % day)

    /** A key no version of the app knows: a later outcome, or a hand-edited row. */
    private val unknownKey = "vanished_in_a_later_version"

    /** Every outcome a row can carry, the unknown one included, except the person's own STOP. */
    private val outcomes = listOf(
        Outcome.ACCEPTED.key, Outcome.SNOOZED.key, Outcome.DISMISSED.key, unknownKey,
    )

    /** Every ledger of [Kind.REMINDER] rows up to [maxLength] long, oldest first, an hour apart. */
    private fun ledgers(maxLength: Int): List<List<Offer>> {
        var level = listOf(emptyList<Offer>())
        val all = mutableListOf<List<Offer>>()
        all += level
        for (n in 1..maxLength) {
            level = level.flatMap { ledger -> outcomes.map { ledger + row(n, it) } }
            all += level
        }
        return all
    }

    private fun row(n: Int, outcome: String, kind: Kind = Kind.REMINDER) =
        Offer(kind = kind.key, offeredAt = start + n * hour, outcome = outcome)

    /** Every pace the engine can produce, loudest first, and the person's own Off. */
    private val paces = (0..CheckInEngine.MISSES_PER_STEP * 20).map { CheckInEngine.paceAfter(it) }.distinct() +
        Pace.Off

    private fun louder(a: Pace, b: Pace) = b.quieterThan(a)

    private fun pace(ledger: List<Offer>, saidStop: Boolean = false, countFrom: Long = 0L) =
        CheckInEngine.paceOf(Kind.REMINDER, ledger, saidStop, countFrom)

    // ---- Rule 1: only at a time the person set, never more often than they set ----

    @Test
    fun `nothing is ever louder than the schedule the person set`() {
        // The ceiling. AsSet is the loudest rung there is, and it posts exactly what is due.
        assertEquals(Pace.AsSet, paces.minByOrNull { it.gapMillis })
        assertEquals(0L, Pace.AsSet.gapMillis)
        for (n in 0..200) assertTrue(CheckInEngine.paceAfter(n).gapMillis >= 0L)
        for (ledger in ledgers(6)) {
            assertTrue(pace(ledger).gapMillis >= Pace.AsSet.gapMillis)
        }
        // Positive control: an answered ledger really is as set, so the sweep is not all one value.
        assertEquals(Pace.AsSet, pace(listOf(row(1, Outcome.ACCEPTED.key))))
    }

    @Test
    fun `at the person's own pace every due check-in is posted`() {
        val due = dueTimes(listOf(9, 13, 21), days = 14)
        var last = 0L
        for (t in due) {
            assertTrue(CheckInEngine.shouldPost(Pace.AsSet, last, t))
            last = t
        }
    }

    // ---- Rule 2: one more unanswered check-in never makes it louder ----

    @Test
    fun `a worse ledger never makes the pace louder`() {
        val worse = mapOf(
            Outcome.ACCEPTED.key to listOf(Outcome.SNOOZED.key, Outcome.DISMISSED.key, unknownKey),
            Outcome.SNOOZED.key to listOf(Outcome.DISMISSED.key, unknownKey),
        )
        var compared = 0
        for (ledger in ledgers(6)) {
            val before = pace(ledger)
            // Appending a miss.
            for (miss in listOf(Outcome.DISMISSED.key, unknownKey)) {
                val after = pace(ledger + row(ledger.size + 1, miss))
                assertFalse("$ledger + $miss went from $before to $after", louder(after, before))
                compared++
            }
            // Replacing any one row with a worse one.
            for (i in ledger.indices) {
                for (w in worse[ledger[i].outcome].orEmpty()) {
                    val changed = ledger.toMutableList().also { it[i] = it[i].copy(outcome = w) }
                    check(changed != ledger)
                    val after = pace(changed)
                    assertFalse("$ledger, row $i to $w, went from $before to $after", louder(after, before))
                    compared++
                }
            }
        }
        assertTrue(compared > 10_000)
        // The exhaustive sweep stops at six rows, so a rule that turns loud again only after a
        // long silence would pass it. Long runs, one more miss at a time.
        for (n in 0..200) {
            val run = (1..n).map { row(it, Outcome.DISMISSED.key) }
            val before = pace(run)
            val after = pace(run + row(n + 1, Outcome.DISMISSED.key))
            assertFalse("$n misses: $before, then $after", louder(after, before))
        }
    }

    @Test
    fun `a quieter pace never posts where a louder one would not`() {
        val due = dueTimes(listOf(8, 12, 17, 22), days = 21)
        for (louder in paces) for (quieter in paces) {
            if (quieter.gapMillis < louder.gapMillis) continue
            for (last in listOf(0L) + due) for (t in due) {
                if (CheckInEngine.shouldPost(quieter, last, t)) {
                    assertTrue("$quieter posts at $t after $last but $louder does not",
                        CheckInEngine.shouldPost(louder, last, t))
                }
            }
        }
    }

    // ---- Rule 3: silence quiets, only the person switches off ----

    @Test
    fun `however long the silence, it never switches a check-in off`() {
        for (ledger in ledgers(6)) assertTrue(pace(ledger) != Pace.Off)
        val years = (1..2_000).map { row(it, Outcome.DISMISSED.key) }
        assertTrue(pace(years) != Pace.Off)
        assertTrue(pace(years).gapMillis < Long.MAX_VALUE)
        // No fixed quietest pace: a longer silence is always at least as quiet, and past a week.
        assertTrue(pace(years).quieterThan(pace(years.take(8))))
        assertTrue(pace(years).days > 7)
        // Positive control: the person's own stop does switch it off, by either route.
        assertEquals(Pace.Off, pace(years, saidStop = true))
        assertEquals(Pace.Off, pace(listOf(row(1, Outcome.STOP.key))))
        assertFalse(CheckInEngine.shouldPost(Pace.Off, 0L, start))
    }

    // ---- Rule 4: no run of missed check-ins ever posts more in a week ----

    @Test
    fun `missing every check-in only ever lengthens the wait, and a next one is always still to come`() {
        // Measured as the time between posts, not posts per week: once the wait passes a week, a
        // weekly count is zero, then one, then zero, and says nothing about direction.
        val schedules = listOf(listOf(21), listOf(9, 21), listOf(9, 13, 21), listOf(7, 10, 13, 16, 19, 22))
        for (hours in schedules) {
            val posts = simulateSilence(hours, days = 400)
            val eased = posts.zipWithNext().filter { (prev, _) -> prev.second != Pace.AsSet }
            val waits = eased.map { (prev, next) -> next.first - prev.first }
            for (i in 1 until waits.size) {
                assertTrue("$hours: wait ${i + 1} was ${waits[i] / hour}h after ${waits[i - 1] / hour}h", waits[i] >= waits[i - 1])
            }
            // Never more than the person set: every post is at one of their times (dueTimes), and
            // the first week posts no more than they asked for.
            assertTrue(posts.count { it.first < start + 7 * day } <= hours.size * 7)
            // Quiet, not absent: the pace after all that silence is still finite.
            assertTrue(pace(posts.map { Offer(Kind.REMINDER.key, it.first, Outcome.DISMISSED.key) }).gapMillis < Long.MAX_VALUE)
            // Positive control: the silence really did stretch the wait, past a week.
            assertTrue("$hours: $waits", waits.size >= 3 && waits.last() > 7 * day && waits.last() > waits.first())
        }
    }

    /** When each check-in was posted, and the pace it was posted at, when every one goes unanswered. */
    private fun simulateSilence(hours: List<Int>, days: Int): List<Pair<Long, Pace>> {
        val ledger = mutableListOf<Offer>()
        var last = 0L
        val posts = mutableListOf<Pair<Long, Pace>>()
        for (t in dueTimes(hours, days)) {
            val p = pace(ledger)
            if (CheckInEngine.shouldPost(p, last, t)) {
                posts += t to p
                ledger += Offer(Kind.REMINDER.key, t, Outcome.DISMISSED.key)
                last = t
            }
        }
        return posts
    }

    // ---- Put it back, kinds, clocks, and saying so ----

    @Test
    fun `put it back ignores everything before it and returns to as set`() {
        val quiet = (1..20).map { row(it, Outcome.DISMISSED.key) }
        assertTrue(pace(quiet).quieterThan(Pace.AsSet))
        assertEquals(Pace.AsSet, pace(quiet, countFrom = start + 21 * hour))
    }

    @Test
    fun `one answered check-in puts the pace back to as set`() {
        val quiet = (1..20).map { row(it, Outcome.DISMISSED.key) }
        assertEquals(Pace.AsSet, pace(quiet + row(21, Outcome.ACCEPTED.key)))
    }

    @Test
    fun `try later is neither a miss nor an answer`() {
        val run = (1..4).map { row(it, Outcome.DISMISSED.key) }
        val snoozed = run + row(5, Outcome.SNOOZED.key)
        assertEquals(pace(run), pace(snoozed))
        assertEquals(4, CheckInEngine.unansweredRun(Kind.REMINDER, snoozed, 0L))
    }

    @Test
    fun `another kind's rows never change this kind's pace`() {
        val others = (1..20).map { row(it, Outcome.DISMISSED.key, Kind.SUPPORT) }
        assertEquals(Pace.AsSet, pace(others))
        // Positive control: the same rows as reminders do.
        assertEquals(CheckInEngine.paceAfter(10), pace(others.map { it.copy(kind = Kind.REMINDER.key) }))
    }

    @Test
    fun `a clock set back cannot lock someone out of their own schedule`() {
        for (p in paces) {
            if (p == Pace.Off) continue
            assertTrue(CheckInEngine.shouldPost(p, lastPostedAt = start + 30 * day, dueAt = start))
        }
    }

    @Test
    fun `every change of pace is reported, both ways, except the person's own stop`() {
        for (a in paces) for (b in paces) {
            val change = CheckInEngine.changeBetween(a, b)
            when {
                a == b || b == Pace.Off -> assertNull("$a to $b", change)
                else -> {
                    assertNotNull("$a to $b", change)
                    assertEquals(b.gapMillis > a.gapMillis, change!!.quieter)
                }
            }
        }
    }

    /** The times a schedule is due over [days], in order: the alarm side, which never changes. */
    private fun dueTimes(hours: List<Int>, days: Int): List<Long> =
        (0 until days).flatMap { d -> hours.sorted().map { h -> start + d * day + h * hour } }

    @Test
    fun `the wait doubles with every step, from once a day`() {
        assertEquals(listOf(0L, 1L, 2L, 4L, 8L, 16L, 32L), (0..6).map { CheckInEngine.paceAfter(it).days })
    }

    // ---- Trying a longer wait ----

    @Test
    fun `a kept trial only ever adds quiet, and an answer leaves it in place`() {
        for (ledger in ledgers(5)) for (steps in 0..6) {
            val before = CheckInEngine.paceOf(Kind.REMINDER, ledger, false, 0L, steps)
            val after = CheckInEngine.paceOf(Kind.REMINDER, ledger, false, 0L, steps + 1)
            assertFalse("$ledger: trial $steps to ${steps + 1} went from $before to $after", louder(after, before))
        }
        val quiet = (1..6).map { row(it, Outcome.DISMISSED.key) }
        val answered = quiet + row(7, Outcome.ACCEPTED.key)
        assertEquals(CheckInEngine.paceAfter(2), CheckInEngine.paceOf(Kind.REMINDER, answered, false, 0L, 2))
        // Positive control: the run of misses really did add quiet on top of the trial.
        assertTrue(CheckInEngine.paceOf(Kind.REMINDER, quiet, false, 0L, 2).quieterThan(CheckInEngine.paceAfter(2)))
    }

    private fun answers(n: Int, from: Int = 1) = (from until from + n).map { row(it, Outcome.ACCEPTED.key) }

    private fun mayTry(ledger: List<Offer>, since: Long = 0L, declined: Boolean = false) =
        CheckInEngine.mayTryLonger(Kind.REMINDER, ledger, since, declined)

    @Test
    fun `a longer wait is tried only after a long run of answered check-ins`() {
        val n = CheckInEngine.ANSWERS_BEFORE_TRIAL
        assertTrue(mayTry(answers(n)))
        assertFalse(mayTry(answers(n - 1)))
        for (i in 0 until n) for (other in listOf(Outcome.SNOOZED.key, Outcome.DISMISSED.key, unknownKey)) {
            val broken = answers(n).toMutableList().also { it[i] = it[i].copy(outcome = other) }
            assertFalse("row $i as $other", mayTry(broken))
        }
        // Only the newest run counts: an old miss before it does not block a trial.
        assertTrue(mayTry(listOf(row(0, Outcome.DISMISSED.key)) + answers(n)))
    }

    @Test
    fun `rows before the last trial or put it back do not count toward the next`() {
        val n = CheckInEngine.ANSWERS_BEFORE_TRIAL
        val ledger = answers(n)
        assertFalse(mayTry(ledger, since = start + 2 * hour))
        assertTrue(mayTry(ledger, since = start + 1 * hour))
    }

    @Test
    fun `once the person puts a trial back, no longer wait is ever tried again`() {
        val n = CheckInEngine.ANSWERS_BEFORE_TRIAL
        for (len in listOf(n, 3 * n, 100)) assertFalse(mayTry(answers(len), declined = true))
        // Positive control: the same ledger does earn a trial when nothing was declined.
        assertTrue(mayTry(answers(100)))
    }

    @Test
    fun `a step of quiet is always quieter than the schedule the person set`() {
        val misses = (1..2).map { row(it, Outcome.DISMISSED.key) }
        for (spacingHours in listOf(0L, 4L, 12L, 24L, 48L, 24L * 7)) {
            val spacing = spacingHours * hour
            val p = CheckInEngine.paceOf(Kind.REMINDER, misses, false, 0L, setSpacingMillis = spacing)
            assertTrue("spacing ${spacingHours}h gave $p", p.gapMillis >= spacing)
            for (trial in 1..3) {
                val t = CheckInEngine.paceOf(Kind.REMINDER, emptyList(), false, 0L, trial, spacing)
                assertTrue("spacing ${spacingHours}h, trial $trial gave $t", t.gapMillis >= spacing)
            }
        }
        // One reminder a day: the first step is every two days. Positive control: with several a
        // day, the first step is once a day.
        assertEquals(2L, CheckInEngine.paceOf(Kind.REMINDER, misses, false, 0L, setSpacingMillis = day).days)
        assertEquals(1L, CheckInEngine.paceOf(Kind.REMINDER, misses, false, 0L, setSpacingMillis = 4 * hour).days)
        // And nothing answered stays exactly as set, whatever the spacing.
        assertEquals(Pace.AsSet, CheckInEngine.paceOf(Kind.REMINDER, emptyList(), false, 0L, 0, day))
    }

    @Test
    fun `the ledger sweep covers every outcome`() {
        val known = InterruptionBudget.Outcome.entries.map { it.key }.toSet() - Outcome.STOP.key
        assertTrue(outcomes.containsAll(known))
    }
}
