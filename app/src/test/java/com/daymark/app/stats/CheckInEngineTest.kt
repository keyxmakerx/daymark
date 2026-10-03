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

    private fun pace(ledger: List<Offer>, saidStop: Boolean = false, countFrom: Long = 0L) =
        CheckInEngine.paceOf(Kind.REMINDER, ledger, saidStop, countFrom)

    // ---- Rule 1: only at a time the person set, never more often than they set ----

    @Test
    fun `nothing is ever louder than the schedule the person set`() {
        // The ceiling. AsSet is the loudest rung there is, and it posts exactly what is due.
        assertEquals(Pace.AsSet, Pace.entries.minByOrNull { it.gapMillis })
        assertEquals(0L, Pace.AsSet.gapMillis)
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
                assertTrue("$ledger + $miss went from $before to $after", after.ordinal >= before.ordinal)
                compared++
            }
            // Replacing any one row with a worse one.
            for (i in ledger.indices) {
                for (w in worse[ledger[i].outcome].orEmpty()) {
                    val changed = ledger.toMutableList().also { it[i] = it[i].copy(outcome = w) }
                    check(changed != ledger)
                    val after = pace(changed)
                    assertTrue("$ledger, row $i to $w, went from $before to $after", after.ordinal >= before.ordinal)
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
            assertTrue("$n misses: $before, then $after", after.ordinal >= before.ordinal)
        }
    }

    @Test
    fun `a quieter pace never posts where a louder one would not`() {
        val due = dueTimes(listOf(8, 12, 17, 22), days = 21)
        for (louder in Pace.entries) for (quieter in Pace.entries) {
            if (quieter.ordinal < louder.ordinal) continue
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
        assertEquals(CheckInEngine.QUIETEST_INFERRED, pace(years))
        // Positive control: the person's own stop does switch it off, by either route.
        assertEquals(Pace.Off, pace(years, saidStop = true))
        assertEquals(Pace.Off, pace(listOf(row(1, Outcome.STOP.key))))
        assertFalse(CheckInEngine.shouldPost(Pace.Off, 0L, start))
    }

    // ---- Rule 4: no run of missed check-ins ever posts more in a week ----

    @Test
    fun `missing every check-in posts fewer each week and never none`() {
        val schedules = listOf(listOf(21), listOf(9, 21), listOf(9, 13, 21), listOf(7, 10, 13, 16, 19, 22))
        for (hours in schedules) {
            val weekly = simulateSilence(hours, weeks = 8)
            for (w in 1 until weekly.size) {
                assertTrue("$hours: week $w posted ${weekly[w]} after ${weekly[w - 1]}", weekly[w] <= weekly[w - 1])
            }
            assertTrue("$hours: week 1 posted more than was set", weekly[0] <= hours.size * 7)
            // Quiet, not absent: still at least one a week.
            assertTrue("$hours: $weekly", weekly.all { it >= 1 })
            // Positive control: the silence really did ease it off, or this test proves nothing.
            assertTrue("$hours: $weekly", weekly.last() < weekly.first())
        }
    }

    /** Posts per week when every posted check-in goes unanswered. */
    private fun simulateSilence(hours: List<Int>, weeks: Int): List<Int> {
        val ledger = mutableListOf<Offer>()
        var last = 0L
        val counts = IntArray(weeks)
        for (t in dueTimes(hours, days = weeks * 7)) {
            val p = pace(ledger)
            if (CheckInEngine.shouldPost(p, last, t)) {
                counts[((t - start) / (7 * day)).toInt()]++
                ledger += Offer(Kind.REMINDER.key, t, Outcome.DISMISSED.key)
                last = t
            }
        }
        return counts.toList()
    }

    // ---- Put it back, kinds, clocks, and saying so ----

    @Test
    fun `put it back ignores everything before it and returns to as set`() {
        val quiet = (1..20).map { row(it, Outcome.DISMISSED.key) }
        assertEquals(CheckInEngine.QUIETEST_INFERRED, pace(quiet))
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
        assertEquals(CheckInEngine.QUIETEST_INFERRED, pace(others.map { it.copy(kind = Kind.REMINDER.key) }))
    }

    @Test
    fun `a clock set back cannot lock someone out of their own schedule`() {
        for (p in Pace.entries) {
            if (p == Pace.Off) continue
            assertTrue(CheckInEngine.shouldPost(p, lastPostedAt = start + 30 * day, dueAt = start))
        }
    }

    @Test
    fun `every change of pace is reported, both ways, except the person's own stop`() {
        for (a in Pace.entries) for (b in Pace.entries) {
            val change = CheckInEngine.changeBetween(a, b)
            when {
                a == b || b == Pace.Off -> assertNull("$a to $b", change)
                else -> {
                    assertNotNull("$a to $b", change)
                    assertEquals(b.ordinal > a.ordinal, change!!.quieter)
                }
            }
        }
    }

    /** The times a schedule is due over [days], in order: the alarm side, which never changes. */
    private fun dueTimes(hours: List<Int>, days: Int): List<Long> =
        (0 until days).flatMap { d -> hours.sorted().map { h -> start + d * day + h * hour } }

    @Test
    fun `the ledger sweep covers every outcome`() {
        val known = InterruptionBudget.Outcome.entries.map { it.key }.toSet() - Outcome.STOP.key
        assertTrue(outcomes.containsAll(known))
    }
}
