package com.daymark.app.stats

import com.daymark.app.data.entity.OfferKind
import com.daymark.app.data.entity.OfferOutcome
import com.daymark.app.data.entity.OfferRecord

/**
 * The permission gate, generalised — one budget per calling feature.
 *
 * [SupportOffer] is the prototype and keeps its own setting and its own wording; this is the same
 * rule with the two additions `docs/DECISIONS_2026-08.md` §D1/§D1a describe, and nothing else:
 *
 *  - a **kind** ([OfferKind]), so the companion, reminders, assignments and the support space each
 *    spend a separate budget and one talkative feature cannot use up another's;
 *  - the engine's **own reception ledger** ([OfferRecord]), so a feature that is being waved away
 *    asks less.
 *
 * It is deliberately the same size as the prototype, and the rule that keeps it that way is §D1's:
 * **features ask a yes/no question, they do not push context in.** Nothing here knows what a goal,
 * a mood, an entry or a check-in is, and the moment something needs to hand this file domain state,
 * that logic belongs in the feature. Pure and Android-free like the rest of `stats/` — the caller
 * owns persistence and the clock.
 *
 * ## What it must never do
 *
 * Nothing here infers anything about the person (§D1a). "Struggling", "annoyed by the app" and
 * "doing fine and not in the mood" all take the same action — ask less — so the engine never has to
 * tell them apart, and it has no vocabulary for trying. A quiet ledger means the app was quiet, and
 * carries no clinical reading whatever.
 *
 * ## The invariant, which is the reason this file has tests
 *
 * > The response to falling reception is **monotonic and one-directional**: it may only ever ask
 * > *less*. No combination of inputs may cause it to ask more.
 *
 * That is three separable properties, and `InterruptionBudgetTest` checks each one because each
 * fails independently of the others:
 *
 *  1. **The person's setting is a ceiling.** No ledger, in any combination, produces a shorter gap
 *     than the frequency they chose. Reception spends that budget down; it can never widen it, so
 *     there is no path by which being agreeable earns someone more interruptions.
 *  2. **Worse reception never asks more.** Replace any outcome with a worse one and the permitted
 *     gap can only grow or stay the same.
 *  3. **An inference may quiet the app; only the person may silence it.** Reception alone bottoms
 *     out at [SupportOfferFrequency.OncePerWeek]. Reaching [SupportOfferFrequency.Never] takes the
 *     person's own setting or their own [OfferOutcome.STOP].
 *
 * Escalation stays where §D1a puts it: the person asks for more, in a setting, and never as
 * something this file worked out about them.
 */
object InterruptionBudget {

    /**
     * How the offers of one kind are landing.
     *
     * Ordered, and the order only ever runs one way: every step asks strictly less than the one
     * before it, and **there is no step above [Open]**. That absence is the design — a rung above
     * "as the person set it" is precisely what would turn this into an engagement optimiser, and it
     * cannot be added here without failing the ceiling test.
     */
    enum class Reception {
        /** Nothing recorded yet, or offers are being taken up. The declared setting, untouched. */
        Open,

        /** Some offers were put off or waved away. One rung quieter. */
        Easing,

        /** Offers are consistently not being taken up. Two rungs quieter. */
        Quiet,

        /** They said stop. Nothing of this kind interrupts until they lift it themselves. */
        Closed,
    }

    private const val HOUR_MILLIS = 3_600_000L
    private const val DAY_MILLIS = 24 * HOUR_MILLIS
    private const val WEEK_MILLIS = 7 * DAY_MILLIS

    /** What [minimumGapMillis] returns for [SupportOfferFrequency.Never]: a gap that never elapses. */
    const val NEVER_GAP_MILLIS = Long.MAX_VALUE

    /** The frequency ladder, quietest first. An index into this is a rung. */
    private val LADDER = listOf(
        SupportOfferFrequency.Never,
        SupportOfferFrequency.OncePerWeek,
        SupportOfferFrequency.OncePerDay,
        SupportOfferFrequency.EveryTime,
    )

    /**
     * The quietest rung reception on its own can reach — [SupportOfferFrequency.OncePerWeek].
     *
     * Being ignored makes the app *quiet*, not absent. Silence is a thing the person chooses, by
     * their setting or by saying stop, because an engine that can switch a feature off on its own
     * reading of a few dismissals has made a decision the person cannot see and did not ask for.
     */
    private const val QUIETEST_INFERRED_RUNG = 1

    /** How many of a kind's most recent offers reception is read from. Fixed, not tuned. */
    const val RECENT_WINDOW = 5

    /** Restraint at or above this reads as [Reception.Quiet]; anything above zero is [Reception.Easing]. */
    private const val QUIET_AT = 3

    /**
     * The starting frequency for a kind, before the person has said otherwise.
     *
     * These are **judgement calls, not evidence** — the same honesty [SupportOfferFrequency]'s own
     * default is recorded with. [OfferKind.REMINDER] starts at [SupportOfferFrequency.EveryTime]
     * because the person already chose those times themselves and the engine has no business
     * second-guessing a schedule; reception can still quiet it, which is the only direction
     * anything here moves.
     */
    fun defaultFrequency(kind: OfferKind): SupportOfferFrequency = when (kind) {
        OfferKind.COMPANION -> SupportOfferFrequency.OncePerDay
        OfferKind.REMINDER -> SupportOfferFrequency.EveryTime
        OfferKind.ASSIGNMENT -> SupportOfferFrequency.OncePerDay
        OfferKind.SUPPORT -> SupportOfferFrequency.DEFAULT
    }

    /** When this kind last asked, or 0 if it never has. Rows of other kinds are ignored. */
    fun lastOfferedAt(kind: OfferKind, recent: List<OfferRecord>): Long =
        recent.filter { it.kind == kind.key }.maxOfOrNull { it.offeredAt } ?: 0L

    /**
     * How this kind's offers are being received, from its own rows of the ledger.
     *
     * [recent] may be in any order and may hold other kinds' rows; the [RECENT_WINDOW] newest of
     * this kind are what count. An empty list is [Reception.Open] — a new install has no history,
     * which is ordinary rather than exceptional.
     *
     * [saidStop] is the standing "stop asking" preference, which outlives the window: unlike the
     * other outcomes it is a preference rather than a fact about one moment, so callers read it
     * separately (`OfferRecordDao.hasOutcome`) and it is not left to chance that the row is still
     * inside [RECENT_WINDOW]. It has no default on purpose — forgetting it means carrying on
     * asking someone who asked you not to.
     */
    fun receptionOf(
        kind: OfferKind,
        recent: List<OfferRecord>,
        saidStop: Boolean,
    ): Reception {
        if (saidStop) return Reception.Closed
        val mine = recent.filter { it.kind == kind.key }
        if (mine.any { OfferOutcome.fromKey(it.outcome) == OfferOutcome.STOP }) return Reception.Closed
        val restraint = mine
            .sortedByDescending { it.offeredAt }
            .take(RECENT_WINDOW)
            .sumOf { restraintOf(OfferOutcome.fromKey(it.outcome)) }
        return when {
            restraint <= 0 -> Reception.Open
            restraint < QUIET_AT -> Reception.Easing
            else -> Reception.Quiet
        }
    }

    /**
     * How much quiet each outcome buys.
     *
     * **Not a ranking of outcomes, and nothing here is a failure to be corrected** — [OfferRecord]'s
     * own note on that is the rule, and this obeys it in the only way that matters: every weight is
     * zero or greater, so an outcome can lengthen the gap and has no way of shortening one.
     * [OfferOutcome.ACCEPTED] weighing nothing is not a reward for good behaviour; it leaves the
     * person's setting exactly as they set it, which is also the most it could ever do.
     *
     * An outcome this version does not recognise — an older backup, a hand-edited file, a key a
     * later version added — reads as the quieter interpretation. Version drift must fail towards
     * asking less, never towards asking more.
     */
    private fun restraintOf(outcome: OfferOutcome?): Int = when (outcome) {
        OfferOutcome.ACCEPTED -> 0
        OfferOutcome.SNOOZED -> 1
        OfferOutcome.DISMISSED -> 2
        // Already handled as Reception.Closed above; weighted anyway so that if that ever stops
        // being true, the fallthrough is towards quiet rather than towards asking more.
        OfferOutcome.STOP -> QUIET_AT
        null -> 2
    }

    /**
     * The frequency actually in force: the one the person declared, stepped down by [reception].
     *
     * Two guards make the one-directionality structural rather than a matter of getting the
     * arithmetic right. The result is floored at [QUIETEST_INFERRED_RUNG], so an inference can
     * quiet but not silence; and it is capped at the declared rung, so a floor can never lift a
     * quieter setting — [SupportOfferFrequency.Never] declared stays [SupportOfferFrequency.Never]
     * at every reception.
     */
    fun effectiveFrequency(
        declared: SupportOfferFrequency,
        reception: Reception,
    ): SupportOfferFrequency {
        val stepsDown = when (reception) {
            Reception.Closed -> return SupportOfferFrequency.Never
            Reception.Open -> 0
            Reception.Easing -> 1
            Reception.Quiet -> 2
        }
        // LADDER is hand-maintained, so unlike the `when`s above nothing forces it to stay in
        // sync with SupportOfferFrequency. A fifth member would compile clean and then index -1
        // here at runtime. Fail loudly at the seam instead, and never quieter than declared.
        val declaredRung = LADDER.indexOf(declared)
        if (declaredRung < 0) return declared
        val stepped = (declaredRung - stepsDown).coerceAtLeast(QUIETEST_INFERRED_RUNG)
        return LADDER[minOf(declaredRung, stepped)]
    }

    /**
     * The shortest permitted spacing between interruptions, so "asks less" has a number the tests
     * can compare. Permitted rate is 1/gap, so a gap that never shrinks is a rate that never rises.
     */
    fun minimumGapMillis(frequency: SupportOfferFrequency): Long = when (frequency) {
        SupportOfferFrequency.Never -> NEVER_GAP_MILLIS
        SupportOfferFrequency.OncePerWeek -> WEEK_MILLIS
        SupportOfferFrequency.OncePerDay -> DAY_MILLIS
        SupportOfferFrequency.EveryTime -> 0L
    }

    /**
     * Whether this kind may interrupt right now — the whole interface, and the same question
     * [SupportOffer.shouldInterrupt] answers.
     *
     * [lastOfferedAt] is when this kind last interrupted (0 = never). A clock that jumps backwards
     * — timezone change, manual clock edit — must not lock someone out forever, so a
     * [lastOfferedAt] in the future is treated as "no record", exactly as in the prototype.
     */
    fun shouldInterrupt(
        declared: SupportOfferFrequency,
        reception: Reception,
        lastOfferedAt: Long,
        nowMillis: Long,
    ): Boolean {
        val gap = minimumGapMillis(effectiveFrequency(declared, reception))
        if (gap == NEVER_GAP_MILLIS) return false
        if (gap == 0L) return true
        if (lastOfferedAt <= 0L || lastOfferedAt > nowMillis) return true
        return nowMillis - lastOfferedAt >= gap
    }

    /**
     * The same question, answered straight from this kind's ledger rows — what most callers want,
     * since it leaves no way to pair one kind's timestamp with another kind's reception.
     */
    fun shouldInterrupt(
        kind: OfferKind,
        declared: SupportOfferFrequency,
        recent: List<OfferRecord>,
        saidStop: Boolean,
        nowMillis: Long,
    ): Boolean = shouldInterrupt(
        declared = declared,
        reception = receptionOf(kind, recent, saidStop),
        lastOfferedAt = lastOfferedAt(kind, recent),
        nowMillis = nowMillis,
    )
}
