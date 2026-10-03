package com.daymark.app.stats


/**
 * The rules engine's core: how often a check-in may actually reach the person, given how its own
 * recent check-ins were received. `docs/DECISIONS.md` §D1 and §D1a are the specification.
 *
 * Pure and Android-free, like the rest of `stats/`: no Room types, no clock, no time zone. The caller hands in
 * the ledger rows (as [InterruptionBudget.Offer]), the moment a check-in is due, and when it last
 * reached the person, all as epoch milliseconds. `tools/jvm-tests.sh stats` runs its tests.
 *
 * ## What it decides, and what it never touches
 *
 * A check-in's schedule is the person's. The alarms keep firing at exactly the times they set, and
 * this engine never adds a time, moves one, or invents one. What it decides is the cheaper thing to
 * undo: whether a due check-in is **posted** or quietly let pass. So everything it can do is a
 * subset of what the person asked for, by construction.
 *
 * It reads one thing about the person: a run of check-ins at the end of the ledger that nobody
 * answered. Every two of them double the wait ([paceAfter]), with no cap. One answered check-in puts the
 * pace straight back to as set, which is still never more than the person set (§D1a's ceiling).
 * Nothing here knows what a mood, an entry or a reminder is, and nothing here guesses why a check-in
 * went unanswered: struggling, annoyed and busy all get the same answer, ask less.
 *
 * ## The rules the tests hold it to
 *
 * `CheckInEngineTest` checks each separately, because each breaks separately:
 *  1. it posts only at a time the person set, and never more often than they set;
 *  2. one more unanswered check-in never makes the pace louder;
 *  3. the person's own silence, however long, never switches a check-in off: only they can, by
 *     saying stop ([Pace.Off] is reachable only through [saidStop] or a STOP row);
 *  4. no run of missed check-ins, simulated day by day, ever posts more in a week than the week
 *     before it (§D1's "a test that no sequence of missed check-ins ever produces a more frequent
 *     schedule"), and the next check-in is always still to come, however far off.
 *
 * ## Saying so
 *
 * §D1 says a change is never silent. [changeBetween] reports every change of pace, quieter or back
 * to as set, so the caller can send the notice with **Put it back**. Putting it back is a
 * `countFrom` moment the caller stores: ledger rows before it no longer count, so the pace returns
 * to as set at once. The wording of the notice lives with the caller and is human-written (§D1a: it
 * may say what changed, never that anything was missed).
 */
object CheckInEngine {

    private const val HOUR_MILLIS = 3_600_000L

    /**
     * Slack taken off every gap, so a daily check-in still fires on the day a clock change makes
     * 23 hours long, and one that fired a minute late yesterday is not held back today.
     */
    private const val SLACK_MILLIS = 2 * HOUR_MILLIS

    private const val DAY_MILLIS = 24 * HOUR_MILLIS

    /**
     * How much of the person's own schedule is posted: at most one check-in per [gapMillis].
     *
     * A longer gap is quieter. Nothing is louder than [AsSet], which posts every time the person
     * set: a pace louder than their own schedule is exactly what would make this an engagement
     * optimiser, and the ceiling test fails if one appears.
     */
    data class Pace(val gapMillis: Long) {
        /** True when this pace asks less than [other]. */
        fun quieterThan(other: Pace): Boolean = gapMillis > other.gapMillis

        /** The gap in whole days, for the notice's wording. Zero for [AsSet]. */
        val days: Long get() = if (gapMillis == Long.MAX_VALUE) Long.MAX_VALUE else (gapMillis + SLACK_MILLIS) / DAY_MILLIS

        companion object {
            /** Every time the person set. */
            val AsSet = Pace(0L)

            /** Nothing. Only the person reaches this, by saying stop. */
            val Off = Pace(Long.MAX_VALUE)
        }
    }

    /**
     * How many unanswered check-ins in a row each step takes. Two, so one stray miss changes
     * nothing. A judgement call, recorded as one.
     */
    const val MISSES_PER_STEP = 2

    /**
     * Steps past this stop doubling, only so the arithmetic cannot overflow. At 2^12 days the gap
     * is over eleven years: there is no limit anyone will meet, and the gap stays finite, so the
     * engine never switches anything off on its own (§D1a: only the person may).
     */
    private const val MAX_DOUBLINGS = 12

    /**
     * The pace after [steps] steps of quiet: as set, then at most once a day, then the gap doubles
     * with every step (2 days, 4, 8, 16, and on). There is no fixed quietest pace. However long
     * someone stays quiet, the app keeps getting quieter, because people differ and a floor chosen
     * in advance would be wrong for most of them.
     */
    fun paceAfter(steps: Int): Pace {
        if (steps <= 0) return Pace.AsSet
        val doublings = minOf(steps - 1, MAX_DOUBLINGS)
        return Pace(DAY_MILLIS * (1L shl doublings) - SLACK_MILLIS)
    }

    /**
     * How many check-ins of [kind] at the end of the ledger went unanswered in a row.
     *
     * Rows before [countFrom] are ignored: that is how **Put it back** starts afresh. A snoozed
     * check-in ("try later") is the person engaging, so it neither counts as a miss nor ends the
     * run. An outcome this version does not know counts as a miss, so version drift fails toward
     * asking less.
     */
    fun unansweredRun(
        kind: InterruptionBudget.Kind,
        recent: List<InterruptionBudget.Offer>,
        countFrom: Long,
    ): Int {
        var run = 0
        for (row in recent.filter { it.kind == kind.key && it.offeredAt >= countFrom }
            .sortedByDescending { it.offeredAt }) {
            when (InterruptionBudget.Outcome.fromKey(row.outcome)) {
                InterruptionBudget.Outcome.ACCEPTED -> return run
                InterruptionBudget.Outcome.SNOOZED -> Unit
                InterruptionBudget.Outcome.DISMISSED,
                InterruptionBudget.Outcome.STOP,
                null -> run++
            }
        }
        return run
    }

    /**
     * The pace in force for [kind]. [saidStop] is the person's standing "stop asking", read
     * separately because it outlives any window; it has no default so nobody can forget it.
     */
    fun paceOf(
        kind: InterruptionBudget.Kind,
        recent: List<InterruptionBudget.Offer>,
        saidStop: Boolean,
        countFrom: Long,
    ): Pace {
        if (saidStop) return Pace.Off
        val mine = recent.filter { it.kind == kind.key && it.offeredAt >= countFrom }
        if (mine.any { InterruptionBudget.Outcome.fromKey(it.outcome) == InterruptionBudget.Outcome.STOP }) {
            return Pace.Off
        }
        return paceAfter(unansweredRun(kind, recent, countFrom) / MISSES_PER_STEP)
    }

    /**
     * Whether a check-in that is due now, at a time the person set, is posted.
     *
     * [lastPostedAt] is when this kind last reached the person, 0 if never. One in the future (a
     * clock set back) is read as no record, as `InterruptionBudget` does, so a clock change cannot
     * lock someone out of a schedule they set.
     */
    fun shouldPost(pace: Pace, lastPostedAt: Long, dueAt: Long): Boolean {
        if (pace == Pace.Off) return false
        if (pace.gapMillis == 0L) return true
        if (lastPostedAt <= 0L || lastPostedAt > dueAt) return true
        return dueAt - lastPostedAt >= pace.gapMillis
    }

    /** A change of pace the person must be told about, with the way back. */
    data class Change(val from: Pace, val to: Pace) {
        /** True when the app now asks less than before. False when it is back toward as set. */
        val quieter: Boolean get() = to.quieterThan(from)
    }

    /**
     * The change between the pace last announced and the pace now, or null when there is none.
     * Every change is reported, both ways, because §D1 allows no silent move. A change to [Pace.Off]
     * is the person's own "stop asking", already said by them, so it is not announced back to them.
     */
    fun changeBetween(announced: Pace, now: Pace): Change? =
        if (announced == now || now == Pace.Off) null else Change(announced, now)
}
