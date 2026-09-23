package com.daymark.app.stats

/**
 * **Placement, not more talking.**
 *
 * The gate ([InterruptionBudget]) answers *may I speak at all, now*. This answers a strictly smaller
 * question that comes after it: *given that the gate said yes, is this one of the hours this feature
 * uses?* It can only ever turn a yes into a no ([mayAskNow] is an `&&`, and that is the whole of the
 * end-to-end argument), so the total number of asks is still the frequency setting's to decide and
 * nothing here can raise it.
 *
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §4: *the reception ledger records the hour and weekday of
 * every ask alongside its outcome. Allocation places the allowed asks into hours that have been
 * answered before and out of hours that have not. Same total as the frequency setting. It never
 * learns why an hour goes unanswered.*
 *
 * ## The invariant this file must not break
 *
 * `docs/DECISIONS.md` §D1a:
 *
 * > The arbiter's response to falling reception is **monotonic and one-directional**: it may only
 * > ever ask *less*. No signal, in any combination, may cause it to ask more.
 *
 * This file keeps it structurally rather than arithmetically. Allocation is a **veto**: the only way
 * a placement enters a decision is [mayAskNow], which conjoins it with the gate's answer. There is
 * no path by which a placement grants permission the gate withheld, whatever the ledger says, so
 * "allocation never increases the number of asks" is true by the shape of the expression and not by
 * the weights below being chosen correctly.
 *
 * Note carefully what is counted and what is not. [Placement.hours] is a **window**, not a schedule
 * and not a quota: widening it never produces an extra ask, because the minimum gap still rations
 * them. Narrowing it can only defer or drop one. That is why [hoursFor] may hand out more hours than
 * a caller asked for ([MIN_PLACED_HOURS]) without touching the invariant.
 *
 * ## What it must never do — and the sameness that is the safety property
 *
 * **It never learns *why* an hour goes unanswered.** Asleep, at work, in a meeting, out of battery,
 * and having a week so bad that the phone stays face-down all look **identical** to this file, and
 * all get the same response: the app moves its asking somewhere else and says nothing about it. That
 * sameness is not a simplification, it is the property that keeps this safe (§D1a — the three states
 * take the same action, so nothing here needs, or has, a vocabulary for telling them apart). The
 * moment an unanswered hour could mean something different from another unanswered hour, this file
 * would be inferring a state about a person, which is the thing the whole architecture exists to
 * avoid.
 *
 * It follows that **"answered" means the person was there, not that they liked it.** A dismissal is
 * an answer — someone was awake, saw it and said no — and it counts exactly as much as an
 * acceptance. This is deliberate and it is what stops placement becoming an engagement optimiser by
 * the back door: it cannot prefer the hours where people say yes, because saying no is worth the
 * same.
 *
 * ## Purity
 *
 * Like the rest of `stats/` this is pure and Android-free — no Room types, no `Context`, **and no
 * clock**. It cannot read the hour; it is told one. The caller owns persistence, the time zone and
 * the clock, exactly as [InterruptionBudget] and [DiscussionPrompts] do, and like
 * [InterruptionBudget] this file **imports nothing at all**, so there is no route by which it could
 * acquire a dependency without the diff saying so.
 *
 * ## What the data layer has to store
 *
 * [Ask] is the ledger row as this file needs it, and the mapping from the Room row lives in `data/`
 * for the reason [InterruptionBudget]'s header gives. Two of its fields are new and have to be
 * written at the moment of the ask, because **they cannot be recovered afterwards**: an epoch
 * millisecond only becomes an hour-of-day once a time zone is applied, and the zone the person was
 * in last March is not knowable in October.
 */
object TimingGrid {

    /** Hours in a day. The grid is this wide. */
    const val HOURS_IN_DAY: Int = 24

    /** Days in a week. The grid is this tall. */
    const val DAYS_IN_WEEK: Int = 7

    /**
     * How many asks an hour needs behind it before this file will say anything about it.
     *
     * **Three, and the number is a judgement call rather than evidence** — the same honesty
     * [SupportOfferFrequency]'s own default is recorded with. The reasoning, so a later reader can
     * disagree with it on the merits:
     *
     *  - **Two is a coin flip.** One answered and one not is the single commonest outcome of no
     *    pattern at all, and a rule that re-plans someone's day on it is the "lopsided guess from
     *    two data points" this was explicitly asked not to be.
     *  - **The asymmetry is what matters, not the number.** An hour needs *one* answer to count as
     *    answered but *three unanswered asks* to be moved away from, so the evidence required to
     *    keep asking somewhere is much weaker than the evidence required to stop. The failure this
     *    guards against is the app quietly abandoning an hour someone actually uses.
     *  - **Being wrong is cheap in one direction only.** Placing an ask in a hole costs one missed
     *    ask. Abandoning a good hour costs every future ask in it, so that is the mistake to make
     *    harder.
     *  - **An hour below the threshold is neutral, never bad** ([Standing.TooLittleEvidence]), and
     *    neutral hours are preferred over hours known to go unanswered. A new install therefore
     *    gets an even spread across the day, which is what "no information" ought to look like,
     *    rather than a preference invented out of nothing.
     */
    const val MIN_ASKS_FOR_EVIDENCE: Int = 3

    /**
     * The narrowest window placement may leave, in hours.
     *
     * A single allowed hour a day is a needle a person can miss forever, and an engine that narrows
     * its own way to effective silence has made the decision §D1a reserves for the person — reception
     * bottoms out at *quiet*, never at *absent*, and this is that same floor expressed in hours.
     * Three is the smallest window someone can realistically meet.
     */
    const val MIN_PLACED_HOURS: Int = 3

    /**
     * The hours the app will place an ask into unless a caller says otherwise — 08:00 to 21:00.
     *
     * A fixed fact about clocks, not an inference about the person: the app does not start a
     * conversation at four in the morning, whoever they are and whatever their ledger says. A
     * caller whose moment the *person* chose (a reminder they set, a save they just made) passes
     * every hour instead — see [hoursFor].
     */
    val DEFAULT_CANDIDATE_HOURS: List<Int> = (8..21).toList()

    /**
     * One line of the reception ledger as placement needs it: the app having asked, when in the
     * week it asked, and whether anyone answered.
     *
     * [hour] is 0..23 and [weekday] is 1..7 with Monday as 1 — `java.time.DayOfWeek.value`, which is
     * what the caller has in hand — **both in the person's own time zone at the moment of the ask**,
     * because that is the only moment either is knowable. Values outside those ranges are dropped
     * rather than clamped: a row this file cannot read is one it must not guess about.
     *
     * [outcome] is the stored outcome key, **raw and unparsed**, exactly as [InterruptionBudget]
     * takes it and for the same reason — a key this version does not recognise is a case to be
     * decided here, quietly, rather than upstream where the sweeps cannot reach it. `null` means
     * something different and narrower: **no response was ever recorded for this ask**. Any recorded
     * outcome, recognised or not, is an answer — someone was there. Only the absence of one is not.
     *
     * There is still no note, no answer text, no dialogue and no mood. Hour and weekday are facts
     * about *when the app spoke*, which is the app's own behaviour; the moment this carries anything
     * about what the person said or felt it stops being a ledger of interruptions.
     */
    data class Ask(
        val kind: String,
        val hour: Int,
        val weekday: Int,
        val outcome: String?,
    )

    /**
     * What this file is willing to say about one hour. Three states and no fourth, and none of them
     * is a statement about the person — see the header on sameness.
     */
    enum class Standing {
        /** At least [MIN_ASKS_FOR_EVIDENCE] asks here, at least one of them answered. */
        Answered,

        /** At least [MIN_ASKS_FOR_EVIDENCE] asks here and none answered. Says nothing about why. */
        Unanswered,

        /** Fewer than [MIN_ASKS_FOR_EVIDENCE] asks here. Neutral: neither preferred nor avoided. */
        TooLittleEvidence,
    }

    /** One hour of the day, as the debug screen needs to show it. A count of asks, never of people. */
    data class HourReading(
        val hour: Int,
        val asks: Int,
        val answered: Int,
        val standing: Standing,
        /** Whether the caller offered this hour as a place an ask could go. */
        val candidate: Boolean,
        /** Whether this hour is one of [Placement.hours]. */
        val placed: Boolean,
    )

    /** One cell of the hour × weekday grid. */
    data class Cell(
        val weekday: Int,
        val hour: Int,
        val asks: Int,
        val answered: Int,
    )

    /**
     * The whole hour × weekday grid, always [DAYS_IN_WEEK] × [HOURS_IN_DAY] cells in weekday-then-hour
     * order, so a screen never has to invent the empty ones.
     *
     * The grid is the app's own behaviour laid out on a clock. `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md`
     * §4 is explicit that it is **never shared with a clinician** — when someone answers the app is
     * the app's business with them.
     */
    data class Grid(
        val kind: String,
        val cells: List<Cell>,
    ) {
        /** The cell for one slot, or null if [weekday] or [hour] is outside the grid. */
        fun cell(weekday: Int, hour: Int): Cell? {
            if (weekday < 1 || weekday > DAYS_IN_WEEK) return null
            if (hour < 0 || hour >= HOURS_IN_DAY) return null
            return cells[(weekday - 1) * HOURS_IN_DAY + hour]
        }
    }

    /** Why [Placement.hours] is what it is. Descriptive only; nothing reads it back. */
    enum class Basis {
        /** The day is not narrowed at all — the caller wanted every hour. */
        EveryHour,

        /** Nothing is placed: no hours were wanted, or no candidate hours were offered. */
        NoHoursAtAll,

        /** No candidate hour has [MIN_ASKS_FOR_EVIDENCE] asks behind it yet. An even spread. */
        TooLittleEvidence,

        /** At least one candidate hour has been answered in before. Those are used first. */
        AnsweredHours,

        /** Every candidate hour with evidence went unanswered. An even spread again, never silence. */
        NoHourAnswered,
    }

    /**
     * Where this kind's asks may go.
     *
     * [hours] is ascending, distinct, and every entry is one of the candidate hours the caller
     * offered (or every hour, under [Basis.EveryHour]). It is a **permission window**: [allows] is
     * the only thing that reads it, and [mayAskNow] is the only way it reaches a decision.
     *
     * [readings] always holds all [HOURS_IN_DAY] hours, in order, so a screen can draw the row
     * without filling gaps itself.
     */
    data class Placement(
        val kind: String,
        val hoursWanted: Int,
        val hours: List<Int>,
        val basis: Basis,
        val readings: List<HourReading>,
    ) {
        /** Whether an ask may be made in this hour. Out-of-range hours are not allowed. */
        fun allows(hour: Int): Boolean = hours.contains(hour)
    }

    /**
     * How wide a window a frequency wants, in hours.
     *
     * **This is a window width and never a gap.** [InterruptionBudget.minimumGapMillis] remains the
     * single definition of *how often* the app may speak, and nothing here may contradict it: these
     * numbers decide only *where in the day* the asks the gate already permitted are allowed to
     * land.
     *
     * [SupportOfferFrequency.EveryTime] gets every hour, which is placement declining to act. That
     * setting means "every qualifying save" — the person chose the moment by doing something, so
     * there is no moment for this file to choose, and narrowing it would be the app overruling an
     * action the person had just taken.
     */
    fun hoursFor(frequency: SupportOfferFrequency): Int = when (frequency) {
        SupportOfferFrequency.Never -> 0
        SupportOfferFrequency.OncePerWeek -> MIN_PLACED_HOURS
        SupportOfferFrequency.OncePerDay -> MIN_PLACED_HOURS
        SupportOfferFrequency.EveryTime -> HOURS_IN_DAY
    }

    /** What this file is willing to say about an hour with this many asks and this many answers. */
    fun standingOf(asks: Int, answered: Int): Standing = when {
        asks < MIN_ASKS_FOR_EVIDENCE -> Standing.TooLittleEvidence
        answered > 0 -> Standing.Answered
        else -> Standing.Unanswered
    }

    /**
     * Whether an ask may be made right now — the gate's answer, narrowed by where in the day it is.
     *
     * The `&&` is the point. Everything this file computes reaches a decision through this one
     * expression, so a placement can withhold an ask the gate permitted and can never grant one the
     * gate refused. *Allocation never increases the number of asks* is therefore a property of the
     * shape of this function, not of the arithmetic above it, and it holds under every ledger
     * including ones written to attack it.
     */
    fun mayAskNow(budgetAllows: Boolean, placement: Placement, hour: Int): Boolean =
        budgetAllows && placement.allows(hour)

    /**
     * Place this kind's allowed asks into the hours it has been answered in, and out of the hours it
     * has not.
     *
     * Pure: the same [asks], [hoursWanted] and [candidateHours] always give the same [Placement],
     * with no clock and no random source anywhere in the path.
     *
     * The ranking is three tiers, and the order between the tiers carries the whole policy:
     *
     *  1. **Answered hours**, best-answered first — hours with evidence that someone was there.
     *  2. **Hours with too little evidence**, spread evenly — an unexplored hour is preferred over
     *     an hour known to go unanswered, so the app keeps finding out rather than narrowing onto
     *     whatever it happened to try first.
     *  3. **Unanswered hours**, spread evenly — used only when the first two cannot fill the window,
     *     because the window is never allowed to close (see [MIN_PLACED_HOURS]).
     *
     * Rows of other kinds, and rows whose hour is outside 0..23, are ignored. A kind key this
     * version has never heard of belongs to nobody here and spends nobody's window.
     */
    fun allocate(
        kind: String,
        asks: List<Ask>,
        hoursWanted: Int,
        candidateHours: List<Int> = DEFAULT_CANDIDATE_HOURS,
    ): Placement {
        val everyHour: List<Int> = (0 until HOURS_IN_DAY).toList()
        val asksAt = IntArray(HOURS_IN_DAY)
        val answeredAt = IntArray(HOURS_IN_DAY)
        for (ask in asks) {
            if (ask.kind != kind) continue
            if (ask.hour < 0 || ask.hour >= HOURS_IN_DAY) continue
            asksAt[ask.hour] = asksAt[ask.hour] + 1
            if (ask.outcome != null) {
                answeredAt[ask.hour] = answeredAt[ask.hour] + 1
            }
        }

        val candidates: List<Int> = candidateHours
            .filter { it >= 0 && it < HOURS_IN_DAY }
            .distinct()
            .sorted()

        val hours: List<Int>
        val basis: Basis
        if (hoursWanted >= HOURS_IN_DAY) {
            // The caller wants the day left alone. Placement declines to act rather than falling
            // back to the default window, which would otherwise refuse a moment the person chose.
            hours = everyHour
            basis = Basis.EveryHour
        } else if (hoursWanted <= 0 || candidates.isEmpty()) {
            hours = emptyList()
            basis = Basis.NoHoursAtAll
        } else {
            val width = if (hoursWanted < MIN_PLACED_HOURS) MIN_PLACED_HOURS else hoursWanted
            val answered = candidates.filter {
                standingOf(asksAt[it], answeredAt[it]) == Standing.Answered
            }
            val unexplored = candidates.filter {
                standingOf(asksAt[it], answeredAt[it]) == Standing.TooLittleEvidence
            }
            val unanswered = candidates.filter {
                standingOf(asksAt[it], answeredAt[it]) == Standing.Unanswered
            }
            val ranked: List<Int> = answered.sortedWith(
                compareByDescending<Int> { answeredPerThousand(asksAt[it], answeredAt[it]) }
                    .thenByDescending { answeredAt[it] }
                    .thenBy { it },
            )
            val picked = ArrayList<Int>()
            picked.addAll(ranked.take(width))
            if (picked.size < width) {
                picked.addAll(evenSpread(unexplored, width - picked.size))
            }
            if (picked.size < width) {
                picked.addAll(evenSpread(unanswered, width - picked.size))
            }
            hours = picked.distinct().sorted()
            basis = when {
                answered.isNotEmpty() -> Basis.AnsweredHours
                unanswered.isEmpty() -> Basis.TooLittleEvidence
                else -> Basis.NoHourAnswered
            }
        }

        val placedSet = hours.toSet()
        val candidateSet = candidates.toSet()
        val readings: List<HourReading> = everyHour.map { hour ->
            HourReading(
                hour = hour,
                asks = asksAt[hour],
                answered = answeredAt[hour],
                standing = standingOf(asksAt[hour], answeredAt[hour]),
                candidate = candidateSet.contains(hour),
                placed = placedSet.contains(hour),
            )
        }
        return Placement(
            kind = kind,
            hoursWanted = hoursWanted,
            hours = hours,
            basis = basis,
            readings = readings,
        )
    }

    /**
     * The hour × weekday grid for one kind — every slot, in weekday-then-hour order.
     *
     * Rows of another kind, or with an hour or weekday outside the grid, are dropped. Dropping is
     * the honest failure here: a row whose slot cannot be read is not evidence about any slot, and
     * clamping it into 0 or Monday would invent evidence that nothing recorded.
     */
    fun grid(kind: String, asks: List<Ask>): Grid {
        val slots = DAYS_IN_WEEK * HOURS_IN_DAY
        val asksAt = IntArray(slots)
        val answeredAt = IntArray(slots)
        for (ask in asks) {
            if (ask.kind != kind) continue
            if (ask.hour < 0 || ask.hour >= HOURS_IN_DAY) continue
            if (ask.weekday < 1 || ask.weekday > DAYS_IN_WEEK) continue
            val index = (ask.weekday - 1) * HOURS_IN_DAY + ask.hour
            asksAt[index] = asksAt[index] + 1
            if (ask.outcome != null) {
                answeredAt[index] = answeredAt[index] + 1
            }
        }
        val cells = ArrayList<Cell>(slots)
        for (weekday in 1..DAYS_IN_WEEK) {
            for (hour in 0 until HOURS_IN_DAY) {
                val index = (weekday - 1) * HOURS_IN_DAY + hour
                cells.add(
                    Cell(
                        weekday = weekday,
                        hour = hour,
                        asks = asksAt[index],
                        answered = answeredAt[index],
                    ),
                )
            }
        }
        return Grid(kind = kind, cells = cells)
    }

    /**
     * Answers per thousand asks, as an integer so the ordering never depends on floating point.
     * Only called for hours with at least [MIN_ASKS_FOR_EVIDENCE] asks, so there is no divide by zero.
     */
    private fun answeredPerThousand(asks: Int, answered: Int): Long =
        if (asks <= 0) 0L else (answered.toLong() * 1000L) / asks.toLong()

    /**
     * [count] entries from [pool], spread evenly across it rather than taken from the front.
     *
     * Taking the front is what makes a no-information answer look like a lopsided guess — three
     * hours of history and the app would decide the person is a morning person. The offsets are
     * centred, so three hours out of 08:00–21:00 come back as late morning, mid-afternoon and
     * evening instead of 08:00, 09:00 and 10:00.
     *
     * [pool] is expected ascending; the result keeps that order.
     */
    private fun evenSpread(pool: List<Int>, count: Int): List<Int> {
        if (pool.isEmpty() || count <= 0) return emptyList()
        if (count >= pool.size) return pool
        val picked = ArrayList<Int>(count)
        for (i in 0 until count) {
            val index = ((2 * i + 1) * pool.size) / (2 * count)
            picked.add(pool[index])
        }
        return picked
    }
}
