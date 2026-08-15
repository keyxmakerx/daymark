package com.daymark.app.stats

import com.daymark.app.data.entity.OfferKind
import com.daymark.app.data.entity.OfferOutcome
import com.daymark.app.data.entity.OfferRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision engine's budget, and the invariant that is the reason it is allowed to exist.
 *
 * `docs/DECISIONS_2026-08.md` §D1a:
 *
 * > The arbiter's response to falling reception is **monotonic and one-directional**: it may only
 * > ever ask *less*. No signal, in any combination, may cause it to ask more.
 *
 * The sweeps below are that sentence, executed. They are deliberately separate because they fail
 * independently — each catches a different way of breaking the rule, and no one of them catches the
 * others. Checked by running each assertion against four deliberately broken implementations:
 *
 *  1. *"no ledger asks more than the person's own setting"* catches the engagement-optimiser shape:
 *     rewarding acceptance with more offers. **The pairwise sweep is blind to this** — making the
 *     good case louder leaves "worse is never louder" perfectly true.
 *  2. *"worse reception never shortens the permitted gap"* catches a signal wired backwards, and
 *     catches version drift that fails open. The ceiling test is blind to both.
 *  3. *"an inference may quiet the app, but only the person may silence it"* catches the floor being
 *     removed, which the other two are blind to because silence is the quiet direction.
 *
 * So all three stay, and a fourth should be added rather than one of these relaxed.
 */
class InterruptionBudgetTest {

    private val day = 24 * 3_600_000L
    private val week = 7 * day
    private val now = 1_700_000_000_000L

    /** A key no version of the app knows — a later outcome, or a hand-edited row. */
    private val unknownKey = "vanished_in_a_later_version"

    /**
     * Reception worst first — an *independent* statement of what "worse" means, written here so
     * the sweeps test the engine against the decision record rather than against its own weights.
     * The unrecognised key sits with [OfferOutcome.DISMISSED]: version drift may only ever be read
     * as at least as quiet as the outcome it replaced.
     */
    private val worstFirst: List<String> = listOf(
        OfferOutcome.STOP.key,
        OfferOutcome.DISMISSED.key,
        unknownKey,
        OfferOutcome.SNOOZED.key,
        OfferOutcome.ACCEPTED.key,
    )

    private val frequencies = SupportOfferFrequency.entries

    /**
     * Every ledger of one, two and three outcomes over that vocabulary — 155 of them.
     *
     * The short ones are not redundant. At a single fixed length the restraint totals saturate at
     * the quietest step, and a sweep where every ledger lands on the same answer proves nothing:
     * an inverted weight was caught only by the boolean sweep until the shorter ledgers were added.
     */
    private val sequences: List<List<String>> = (1..3).flatMap { sequencesOf(worstFirst, it) }

    private val gapsBySequence: List<Map<SupportOfferFrequency, Long>> =
        sequences.map { outcomes ->
            frequencies.associateWith { permittedGap(it, outcomes, saidStop = false) }
        }

    private fun sequencesOf(values: List<String>, length: Int): List<List<String>> =
        if (length == 0) {
            listOf(emptyList())
        } else {
            sequencesOf(values, length - 1).flatMap { prefix -> values.map { prefix + it } }
        }

    /** Oldest first, a day apart, so the newest row is a day old. */
    private fun ledger(
        outcomes: List<String>,
        kind: OfferKind = OfferKind.COMPANION,
    ): List<OfferRecord> = outcomes.mapIndexed { index, outcome ->
        OfferRecord(
            kind = kind.key,
            offeredAt = now - (outcomes.size - index) * day,
            outcome = outcome,
        )
    }

    private fun permittedGap(
        declared: SupportOfferFrequency,
        outcomes: List<String>,
        saidStop: Boolean,
    ): Long = InterruptionBudget.minimumGapMillis(
        InterruptionBudget.effectiveFrequency(
            declared,
            InterruptionBudget.receptionOf(OfferKind.COMPANION, ledger(outcomes), saidStop),
        ),
    )

    /**
     * Pointwise: every outcome in [worse] is at least as badly received as the one in [better].
     * Ledgers of different lengths are not comparable this way, so they are not compared.
     */
    private fun isWorseOrEqual(worse: List<String>, better: List<String>): Boolean =
        worse.size == better.size &&
            worse.indices.all { worstFirst.indexOf(worse[it]) <= worstFirst.indexOf(better[it]) }

    // ---- the invariant -------------------------------------------------------------------

    @Test
    fun `no ledger, in any combination, asks more than the person's own setting`() {
        // The setting is a ceiling. Reception spends that budget down and has no way to widen it,
        // so there is no history a person can accumulate — however agreeable — that earns them
        // more interruptions than they asked for. This is the assertion that makes it structurally
        // impossible for this file to become an engagement optimiser.
        for (outcomes in sequences) {
            for (saidStop in listOf(false, true)) {
                for (declared in frequencies) {
                    val gap = permittedGap(declared, outcomes, saidStop)
                    assertTrue(
                        "declared=$declared saidStop=$saidStop ledger=$outcomes gap=$gap",
                        gap >= InterruptionBudget.minimumGapMillis(declared),
                    )
                }
            }
        }
    }

    @Test
    fun `worse reception never shortens the permitted gap`() {
        var compared = 0
        for (better in sequences.indices) {
            for (worse in sequences.indices) {
                if (!isWorseOrEqual(sequences[worse], sequences[better])) continue
                for (declared in frequencies) {
                    val betterGap = gapsBySequence[better].getValue(declared)
                    val worseGap = gapsBySequence[worse].getValue(declared)
                    assertTrue(
                        "declared=$declared better=${sequences[better]} ($betterGap) " +
                            "worse=${sequences[worse]} ($worseGap)",
                        worseGap >= betterGap,
                    )
                    compared++
                }
            }
        }
        // A sweep that silently stopped comparing anything would pass forever.
        assertTrue("the sweep compared $compared pairs", compared > 1_000)
    }

    @Test
    fun `worse reception never turns a no into a yes`() {
        // The same rule at the level callers actually see: permission, not spacing. If the app may
        // speak on the worse history, it must also have been allowed to on the better one.
        val offsets = listOf(0L, day / 2, day, 3 * day, 8 * day, 30 * day)
        val pairs = (1..2).flatMap { sequencesOf(worstFirst, it) }
        var compared = 0
        for (better in pairs) {
            for (worse in pairs) {
                if (!isWorseOrEqual(worse, better)) continue
                for (declared in frequencies) {
                    for (offset in offsets) {
                        val last = now - offset
                        val allowedWorse = InterruptionBudget.shouldInterrupt(
                            declared,
                            InterruptionBudget.receptionOf(OfferKind.COMPANION, ledger(worse), false),
                            last,
                            now,
                        )
                        val allowedBetter = InterruptionBudget.shouldInterrupt(
                            declared,
                            InterruptionBudget.receptionOf(OfferKind.COMPANION, ledger(better), false),
                            last,
                            now,
                        )
                        if (allowedWorse) {
                            assertTrue(
                                "declared=$declared offset=$offset better=$better worse=$worse",
                                allowedBetter,
                            )
                        }
                        compared++
                    }
                }
            }
        }
        assertTrue("the sweep compared $compared cases", compared > 1_000)
    }

    @Test
    fun `an inference may quiet the app, but only the person may silence it`() {
        // Reception bottoms out at once a week. Reaching Never takes the person's own setting or
        // their own "stop asking" — an engine that could switch a feature off on its own reading of
        // a few dismissals would have made a decision nobody can see.
        for (declared in frequencies - SupportOfferFrequency.Never) {
            for (reception in listOf(
                InterruptionBudget.Reception.Open,
                InterruptionBudget.Reception.Easing,
                InterruptionBudget.Reception.Quiet,
            )) {
                assertFalse(
                    "declared=$declared reception=$reception",
                    InterruptionBudget.effectiveFrequency(declared, reception) ==
                        SupportOfferFrequency.Never,
                )
            }
        }
    }

    @Test
    fun `every reception step asks less than the one before it`() {
        for (declared in frequencies) {
            var previous = 0L
            for (reception in InterruptionBudget.Reception.entries) {
                val gap = InterruptionBudget.minimumGapMillis(
                    InterruptionBudget.effectiveFrequency(declared, reception),
                )
                assertTrue("declared=$declared reception=$reception", gap >= previous)
                previous = gap
            }
        }
    }

    // ---- the ladder ----------------------------------------------------------------------

    @Test
    fun `reception steps down the ladder one rung at a time`() {
        val f = SupportOfferFrequency.EveryTime
        assertEquals(f, InterruptionBudget.effectiveFrequency(f, InterruptionBudget.Reception.Open))
        assertEquals(
            SupportOfferFrequency.OncePerDay,
            InterruptionBudget.effectiveFrequency(f, InterruptionBudget.Reception.Easing),
        )
        assertEquals(
            SupportOfferFrequency.OncePerWeek,
            InterruptionBudget.effectiveFrequency(f, InterruptionBudget.Reception.Quiet),
        )
        assertEquals(
            SupportOfferFrequency.Never,
            InterruptionBudget.effectiveFrequency(f, InterruptionBudget.Reception.Closed),
        )
    }

    @Test
    fun `a setting of never stays never at every reception`() {
        InterruptionBudget.Reception.entries.forEach { reception ->
            assertEquals(
                SupportOfferFrequency.Never,
                InterruptionBudget.effectiveFrequency(SupportOfferFrequency.Never, reception),
            )
            assertFalse(
                InterruptionBudget.shouldInterrupt(
                    SupportOfferFrequency.Never,
                    reception,
                    0L,
                    now,
                ),
            )
        }
    }

    @Test
    fun `stop asking silences every frequency, including every time`() {
        for (declared in frequencies) {
            assertFalse(
                "declared=$declared",
                InterruptionBudget.shouldInterrupt(
                    kind = OfferKind.COMPANION,
                    declared = declared,
                    recent = emptyList(),
                    saidStop = true,
                    nowMillis = now,
                ),
            )
            assertFalse(
                "declared=$declared",
                InterruptionBudget.shouldInterrupt(
                    kind = OfferKind.COMPANION,
                    declared = declared,
                    recent = ledger(listOf(OfferOutcome.STOP.key)),
                    saidStop = false,
                    nowMillis = now,
                ),
            )
        }
    }

    // ---- reading the ledger --------------------------------------------------------------

    @Test
    fun `a new install with no history is open`() {
        assertEquals(
            InterruptionBudget.Reception.Open,
            InterruptionBudget.receptionOf(OfferKind.COMPANION, emptyList(), saidStop = false),
        )
        assertEquals(0L, InterruptionBudget.lastOfferedAt(OfferKind.COMPANION, emptyList()))
    }

    @Test
    fun `taking offers up leaves the setting exactly as the person set it`() {
        val accepted = ledger(List(InterruptionBudget.RECENT_WINDOW) { OfferOutcome.ACCEPTED.key })
        val reception = InterruptionBudget.receptionOf(OfferKind.COMPANION, accepted, saidStop = false)
        assertEquals(InterruptionBudget.Reception.Open, reception)
        for (declared in frequencies) {
            assertEquals(declared, InterruptionBudget.effectiveFrequency(declared, reception))
        }
    }

    @Test
    fun `being waved away quiets it, one turn-down at a time`() {
        fun receptionAfter(vararg outcomes: String) = InterruptionBudget.receptionOf(
            OfferKind.COMPANION,
            ledger(outcomes.toList()),
            saidStop = false,
        )
        assertEquals(InterruptionBudget.Reception.Easing, receptionAfter(OfferOutcome.SNOOZED.key))
        assertEquals(InterruptionBudget.Reception.Easing, receptionAfter(OfferOutcome.DISMISSED.key))
        assertEquals(
            InterruptionBudget.Reception.Quiet,
            receptionAfter(OfferOutcome.DISMISSED.key, OfferOutcome.DISMISSED.key),
        )
    }

    @Test
    fun `an outcome from a later version reads as quieter, never louder`() {
        // Finding 1 in docs/COMPANION_DIALOGUE.md is the same failure in the predicate evaluator:
        // drift must fail closed. Here "closed" means asking less.
        val unknown = ledger(List(3) { unknownKey })
        val accepted = ledger(List(3) { OfferOutcome.ACCEPTED.key })
        for (declared in frequencies) {
            val unknownGap = InterruptionBudget.minimumGapMillis(
                InterruptionBudget.effectiveFrequency(
                    declared,
                    InterruptionBudget.receptionOf(OfferKind.COMPANION, unknown, saidStop = false),
                ),
            )
            val acceptedGap = InterruptionBudget.minimumGapMillis(
                InterruptionBudget.effectiveFrequency(
                    declared,
                    InterruptionBudget.receptionOf(OfferKind.COMPANION, accepted, saidStop = false),
                ),
            )
            assertTrue("declared=$declared", unknownGap >= acceptedGap)
        }
    }

    @Test
    fun `only the newest offers count, and older ones age out of the window`() {
        val outcomes = List(InterruptionBudget.RECENT_WINDOW) { OfferOutcome.ACCEPTED.key } +
            List(6) { OfferOutcome.DISMISSED.key }
        // ledger() puts the last element newest, so the dismissals are the recent ones here.
        assertEquals(
            InterruptionBudget.Reception.Quiet,
            InterruptionBudget.receptionOf(OfferKind.COMPANION, ledger(outcomes), saidStop = false),
        )
        assertEquals(
            InterruptionBudget.Reception.Open,
            InterruptionBudget.receptionOf(
                OfferKind.COMPANION,
                ledger(outcomes.reversed()),
                saidStop = false,
            ),
        )
    }

    @Test
    fun `the order rows arrive in does not change the answer`() {
        val rows = ledger(
            listOf(OfferOutcome.DISMISSED.key, OfferOutcome.ACCEPTED.key, OfferOutcome.SNOOZED.key),
        )
        assertEquals(
            InterruptionBudget.receptionOf(OfferKind.COMPANION, rows, saidStop = false),
            InterruptionBudget.receptionOf(OfferKind.COMPANION, rows.reversed(), saidStop = false),
        )
        assertEquals(
            InterruptionBudget.lastOfferedAt(OfferKind.COMPANION, rows),
            InterruptionBudget.lastOfferedAt(OfferKind.COMPANION, rows.reversed()),
        )
    }

    // ---- one budget per kind -------------------------------------------------------------

    @Test
    fun `each kind spends its own budget and cannot quiet another`() {
        val dismissedCompanion = ledger(List(4) { OfferOutcome.DISMISSED.key }, OfferKind.COMPANION)
        assertEquals(
            InterruptionBudget.Reception.Quiet,
            InterruptionBudget.receptionOf(OfferKind.COMPANION, dismissedCompanion, saidStop = false),
        )
        OfferKind.entries.filter { it != OfferKind.COMPANION }.forEach { other ->
            assertEquals(
                "$other must not be quieted by the companion's ledger",
                InterruptionBudget.Reception.Open,
                InterruptionBudget.receptionOf(other, dismissedCompanion, saidStop = false),
            )
            assertEquals(0L, InterruptionBudget.lastOfferedAt(other, dismissedCompanion))
        }
    }

    @Test
    fun `a stop on one kind does not stop the others`() {
        // Saying "stop" to reminders is not saying it to everything: that would be the engine
        // generalising from one feature to the person, which is exactly what §D1a forbids.
        val stopped = ledger(listOf(OfferOutcome.STOP.key), OfferKind.REMINDER)
        assertEquals(
            InterruptionBudget.Reception.Closed,
            InterruptionBudget.receptionOf(OfferKind.REMINDER, stopped, saidStop = false),
        )
        assertEquals(
            InterruptionBudget.Reception.Open,
            InterruptionBudget.receptionOf(OfferKind.SUPPORT, stopped, saidStop = false),
        )
    }

    @Test
    fun `last offered at is this kind's newest row`() {
        val rows = ledger(List(3) { OfferOutcome.ACCEPTED.key }, OfferKind.ASSIGNMENT)
        assertEquals(now - day, InterruptionBudget.lastOfferedAt(OfferKind.ASSIGNMENT, rows))
    }

    // ---- the prototype's behaviour, unchanged ---------------------------------------------

    @Test
    fun `the support kind with no history behaves exactly as SupportOffer always did`() {
        val offsets = listOf(0L, 1L, day - 1, day, 3 * day, week - 1, week, 8 * day, 400 * day)
        for (declared in frequencies) {
            assertEquals(
                "declared=$declared, never offered",
                SupportOffer.shouldInterrupt(declared, 0L, now),
                InterruptionBudget.shouldInterrupt(
                    kind = OfferKind.SUPPORT,
                    declared = declared,
                    recent = emptyList(),
                    saidStop = false,
                    nowMillis = now,
                ),
            )
            for (offset in offsets) {
                val last = now - offset
                assertEquals(
                    "declared=$declared offset=$offset",
                    SupportOffer.shouldInterrupt(declared, last, now),
                    InterruptionBudget.shouldInterrupt(
                        declared,
                        InterruptionBudget.Reception.Open,
                        last,
                        now,
                    ),
                )
            }
        }
    }

    @Test
    fun `a clock that jumps backwards does not lock the offer out forever`() {
        for (declared in listOf(SupportOfferFrequency.OncePerDay, SupportOfferFrequency.OncePerWeek)) {
            assertTrue(
                InterruptionBudget.shouldInterrupt(
                    declared,
                    InterruptionBudget.Reception.Open,
                    now + 30 * day,
                    now,
                ),
            )
        }
    }

    @Test
    fun `logging several hard days in a row still only interrupts once`() {
        // The regression SupportOffer was written for, re-checked through the general path.
        var last = 0L
        var interruptions = 0
        for (minute in listOf(0L, 5L, 20L, 45L, 90L, 200L)) {
            val at = now + minute * 60_000L
            if (InterruptionBudget.shouldInterrupt(
                    SupportOfferFrequency.OncePerDay,
                    InterruptionBudget.Reception.Open,
                    last,
                    at,
                )
            ) {
                interruptions++
                last = at
            }
        }
        assertEquals(1, interruptions)
    }

    @Test
    fun `the support kind starts where SupportOffer's own default is`() {
        assertEquals(
            SupportOfferFrequency.DEFAULT,
            InterruptionBudget.defaultFrequency(OfferKind.SUPPORT),
        )
        // No kind starts silent — a gate that defaults to off is a feature nobody can find.
        OfferKind.entries.forEach { kind ->
            assertFalse(
                "$kind",
                InterruptionBudget.defaultFrequency(kind) == SupportOfferFrequency.Never,
            )
        }
    }
}
