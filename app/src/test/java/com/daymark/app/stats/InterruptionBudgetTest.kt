package com.daymark.app.stats

import com.daymark.app.data.entity.OfferKind
import com.daymark.app.data.entity.OfferOutcome
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
 * The sweeps below are that sentence, executed. They are property sweeps rather than examples: the
 * whole input space of the engine is kind × declared frequency × ledger × standing stop × clock,
 * and each sweep walks all five dimensions rather than a chosen point in them. That matters more
 * than the feature does. A component that cannot escalate cannot become an engagement optimiser by
 * accident, cannot nag, and cannot be quietly retuned into one later — but only a test that has
 * actually tried every combination is entitled to say so.
 *
 * They are deliberately separate because they fail independently — each catches a different way of
 * breaking the rule, and no one of them catches the others:
 *
 *  1. *"no input path asks more than the person's own setting"* catches the engagement-optimiser
 *     shape: rewarding acceptance with more offers. **The pairwise sweeps are blind to this** —
 *     making the good case louder leaves "worse is never louder" perfectly true.
 *  2. *"worse reception never shortens the permitted gap"* catches a signal wired backwards, and
 *     catches version drift that fails open. The ceiling sweep is blind to both.
 *  3. *"worse reception never turns a no into a yes"* is the same rule at the level callers see,
 *     across elapsed time. A gap comparison alone would miss a threshold applied the wrong way
 *     round at the boundary.
 *  4. *"an inference may quiet the app, but only the person may silence it"* catches the floor
 *     being removed, which the others are blind to because silence is the quiet direction.
 *
 * So all four stay, and a fifth should be added rather than one of these relaxed.
 *
 * The sweeps state "worse" independently of the engine — [worstFirst] and [receptionWorstFirst] are
 * the decision record written out by hand — so they test the engine against the decision rather
 * than against its own weights, and a completeness check guards each list against a new enum member
 * silently dropping out of the sweep.
 */
class InterruptionBudgetTest {

    private val day = 24 * 3_600_000L
    private val week = 7 * day
    private val now = 1_700_000_000_000L

    /** A key no version of the app knows — a later outcome, or a hand-edited row. */
    private val unknownKey = "vanished_in_a_later_version"

    private val kinds = InterruptionBudget.Kind.entries
    private val frequencies = SupportOfferFrequency.entries

    /**
     * Outcomes worst first — an *independent* statement of what "worse" means. The unrecognised key
     * sits with [InterruptionBudget.Outcome.DISMISSED]: version drift may only ever be read as at
     * least as quiet as the outcome it replaced.
     */
    private val worstFirst: List<String> = listOf(
        InterruptionBudget.Outcome.STOP.key,
        InterruptionBudget.Outcome.DISMISSED.key,
        unknownKey,
        InterruptionBudget.Outcome.SNOOZED.key,
        InterruptionBudget.Outcome.ACCEPTED.key,
    )

    /** Reception states worst first, again stated by hand rather than read off the enum's order. */
    private val receptionWorstFirst: List<InterruptionBudget.Reception> = listOf(
        InterruptionBudget.Reception.Closed,
        InterruptionBudget.Reception.Quiet,
        InterruptionBudget.Reception.Easing,
        InterruptionBudget.Reception.Open,
    )

    /**
     * Every ledger of one, two and three outcomes over that vocabulary — 155 of them.
     *
     * The short ones are not redundant. At a single fixed length the restraint totals saturate at
     * the quietest step, and a sweep where every ledger lands on the same answer proves nothing:
     * an inverted weight was caught only by the boolean sweep until the shorter ledgers were added.
     */
    private val sequences: List<List<String>> = (1..3).flatMap { sequencesOf(worstFirst, it) }

    /** The same, one and two long — the end-to-end sweeps multiply by clocks and kinds as well. */
    private val shortSequences: List<List<String>> = (1..2).flatMap { sequencesOf(worstFirst, it) }

    /**
     * The elapsed-time dimension. [ledger] always puts its newest row at `now - day`, whatever its
     * length, so these clocks put the last interruption anywhere from the future to 41 days ago and
     * cross both the daily and the weekly threshold from each side.
     */
    private val clocks: List<Long> = listOf(
        now - 2 * day,
        now - day,
        now,
        now + day / 2,
        now + day - 1,
        now + day,
        now + 6 * day,
        now + 8 * day,
        now + 40 * day,
    )

    /** Reception per (kind, ledger), computed once — the pairwise sweeps read it tens of thousands of times. */
    private val receptionBySequence: Map<InterruptionBudget.Kind, List<InterruptionBudget.Reception>> =
        kinds.associateWith { kind ->
            sequences.map { InterruptionBudget.receptionOf(kind, ledger(it, kind), saidStop = false) }
        }

    private fun sequencesOf(values: List<String>, length: Int): List<List<String>> =
        if (length == 0) {
            listOf(emptyList())
        } else {
            sequencesOf(values, length - 1).flatMap { prefix -> values.map { prefix + it } }
        }

    /** Oldest first, a day apart, so the newest row is always a day old whatever the length. */
    private fun ledger(
        outcomes: List<String>,
        kind: InterruptionBudget.Kind = InterruptionBudget.Kind.COMPANION,
    ): List<InterruptionBudget.Offer> = outcomes.mapIndexed { index, outcome ->
        InterruptionBudget.Offer(
            kind = kind.key,
            offeredAt = now - (outcomes.size - index) * day,
            outcome = outcome,
        )
    }

    private fun gapFor(
        declared: SupportOfferFrequency,
        reception: InterruptionBudget.Reception,
    ): Long = InterruptionBudget.minimumGapMillis(
        InterruptionBudget.effectiveFrequency(declared, reception),
    )

    private fun permittedGap(
        declared: SupportOfferFrequency,
        outcomes: List<String>,
        saidStop: Boolean,
        kind: InterruptionBudget.Kind = InterruptionBudget.Kind.COMPANION,
    ): Long = gapFor(
        declared,
        InterruptionBudget.receptionOf(kind, ledger(outcomes, kind), saidStop),
    )

    /**
     * Pointwise: every outcome in [worse] is at least as badly received as the one in [better].
     * Ledgers of different lengths are not comparable this way, so they are not compared.
     */
    private fun isWorseOrEqual(worse: List<String>, better: List<String>): Boolean =
        worse.size == better.size &&
            worse.indices.all { worstFirst.indexOf(worse[it]) <= worstFirst.indexOf(better[it]) }

    /** Low is worse. Reception the sweeps do not know about would return -1 and fail loudly. */
    private fun quietnessRank(reception: InterruptionBudget.Reception): Int =
        receptionWorstFirst.indexOf(reception)

    // ---- the sweeps cover what they claim to -----------------------------------------------

    @Test
    fun `the sweeps cover every outcome, every reception state and every kind`() {
        // A sweep that quietly stopped covering a new enum member would keep passing while the
        // thing it guards went untested, so the vocabularies are checked for completeness first.
        assertEquals(
            InterruptionBudget.Outcome.entries.map { it.key }.toSet() + unknownKey,
            worstFirst.toSet(),
        )
        assertEquals(InterruptionBudget.Outcome.entries.size + 1, worstFirst.size)
        assertEquals(InterruptionBudget.Reception.entries.toSet(), receptionWorstFirst.toSet())
        assertEquals(InterruptionBudget.Reception.entries.size, receptionWorstFirst.size)
        assertEquals(InterruptionBudget.Kind.entries.size, kinds.size)
    }

    @Test
    fun `the engine's own kinds and outcomes are the keys the ledger table stores`() {
        // The engine takes InterruptionBudget.Offer, not the Room row, so stats/ stays Android-free
        // (the package rule DiscussionPrompts states, and the reason these tests need no Android).
        // The cost of that mapping is two vocabularies that could drift; this is the seam check.
        // It lives in the test because only a test may reach across the layer.
        assertEquals(
            OfferKind.entries.map { it.key }.toSet(),
            InterruptionBudget.Kind.entries.map { it.key }.toSet(),
        )
        assertEquals(
            OfferOutcome.entries.map { it.key }.toSet(),
            InterruptionBudget.Outcome.entries.map { it.key }.toSet(),
        )
    }

    // ---- the invariant -------------------------------------------------------------------

    @Test
    fun `no input path asks more than the person's own setting`() {
        // The setting is a ceiling. Reception spends that budget down and has no way to widen it,
        // so there is no history a person can accumulate — however agreeable — that earns them more
        // interruptions than they asked for. This is the assertion that makes it structurally
        // impossible for this file to become an engagement optimiser, and it is swept over the
        // whole input space: every kind, every ledger, both stop states, every setting, every clock.
        var checked = 0
        val ledgers = listOf(emptyList<String>()) + sequences
        for (kind in kinds) {
            for (outcomes in ledgers) {
                val rows = ledger(outcomes, kind)
                val last = InterruptionBudget.lastOfferedAt(kind, rows)
                val openGap = permittedGap(frequencies.first(), outcomes, saidStop = false, kind = kind)
                val stoppedGap = permittedGap(frequencies.first(), outcomes, saidStop = true, kind = kind)
                assertTrue(
                    "kind=$kind ledger=$outcomes: saying stop must never widen the budget",
                    stoppedGap >= openGap,
                )
                for (declared in frequencies) {
                    val declaredGap = InterruptionBudget.minimumGapMillis(declared)
                    for (saidStop in listOf(false, true)) {
                        val gap = permittedGap(declared, outcomes, saidStop, kind)
                        assertTrue(
                            "kind=$kind declared=$declared saidStop=$saidStop " +
                                "ledger=$outcomes gap=$gap",
                            gap >= declaredGap,
                        )
                        for (at in clocks) {
                            val allowed = InterruptionBudget.shouldInterrupt(
                                kind = kind,
                                declared = declared,
                                recent = rows,
                                saidStop = saidStop,
                                nowMillis = at,
                            )
                            if (allowed) {
                                // The prototype at the declared setting is the independent oracle
                                // for "what the person actually asked for": if the engine speaks,
                                // the unadjusted setting must have permitted it too.
                                assertTrue(
                                    "kind=$kind declared=$declared saidStop=$saidStop " +
                                        "ledger=$outcomes at=${at - now}",
                                    SupportOffer.shouldInterrupt(declared, last, at),
                                )
                            }
                            checked++
                        }
                    }
                }
            }
        }
        assertTrue("the sweep checked $checked cases", checked > 10_000)
    }

    @Test
    fun `worse reception never shortens the permitted gap, for any kind or setting`() {
        var compared = 0
        for (kind in kinds) {
            val receptions = receptionBySequence.getValue(kind)
            for (better in sequences.indices) {
                for (worse in sequences.indices) {
                    if (!isWorseOrEqual(sequences[worse], sequences[better])) continue
                    // A worse ledger must first *read* as at least as quiet. Checked against the
                    // hand-written order, so a reception wired backwards fails here rather than
                    // being laundered through the gap arithmetic.
                    assertTrue(
                        "kind=$kind better=${sequences[better]} (${receptions[better]}) " +
                            "worse=${sequences[worse]} (${receptions[worse]})",
                        quietnessRank(receptions[worse]) <= quietnessRank(receptions[better]),
                    )
                    for (declared in frequencies) {
                        val betterGap = gapFor(declared, receptions[better])
                        val worseGap = gapFor(declared, receptions[worse])
                        assertTrue(
                            "kind=$kind declared=$declared better=${sequences[better]} " +
                                "($betterGap) worse=${sequences[worse]} ($worseGap)",
                            worseGap >= betterGap,
                        )
                        compared++
                    }
                }
            }
        }
        // A sweep that silently stopped comparing anything would pass forever.
        assertTrue("the sweep compared $compared pairs", compared > 10_000)
    }

    @Test
    fun `worse reception never turns a no into a yes, at any elapsed time`() {
        // The same rule at the level callers actually see: permission, not spacing. If the app may
        // speak on the worse history, it must also have been allowed to on the better one — at
        // every point on the clock, for every kind and every setting.
        var compared = 0
        for (kind in kinds) {
            for (better in shortSequences) {
                val betterRows = ledger(better, kind)
                for (worse in shortSequences) {
                    if (!isWorseOrEqual(worse, better)) continue
                    val worseRows = ledger(worse, kind)
                    for (declared in frequencies) {
                        for (at in clocks) {
                            val allowedWorse = InterruptionBudget.shouldInterrupt(
                                kind = kind,
                                declared = declared,
                                recent = worseRows,
                                saidStop = false,
                                nowMillis = at,
                            )
                            if (allowedWorse) {
                                assertTrue(
                                    "kind=$kind declared=$declared at=${at - now} " +
                                        "better=$better worse=$worse",
                                    InterruptionBudget.shouldInterrupt(
                                        kind = kind,
                                        declared = declared,
                                        recent = betterRows,
                                        saidStop = false,
                                        nowMillis = at,
                                    ),
                                )
                            }
                            compared++
                        }
                    }
                }
            }
        }
        assertTrue("the sweep compared $compared cases", compared > 10_000)
    }

    @Test
    fun `every reception state, at every elapsed time, asks no more than the one above it`() {
        // The reception dimension swept directly rather than through ledgers, so a state that no
        // ledger currently produces is still held to the rule.
        var compared = 0
        val offsets = listOf(0L, 1L, day / 2, day - 1, day, 3 * day, week - 1, week, 8 * day, 400 * day)
        for (better in receptionWorstFirst.indices) {
            for (worse in 0..better) {
                for (declared in frequencies) {
                    val worseGap = gapFor(declared, receptionWorstFirst[worse])
                    val betterGap = gapFor(declared, receptionWorstFirst[better])
                    assertTrue(
                        "declared=$declared worse=${receptionWorstFirst[worse]} ($worseGap) " +
                            "better=${receptionWorstFirst[better]} ($betterGap)",
                        worseGap >= betterGap,
                    )
                    assertTrue(
                        "declared=$declared reception=${receptionWorstFirst[worse]}",
                        worseGap >= InterruptionBudget.minimumGapMillis(declared),
                    )
                    for (offset in offsets) {
                        val last = now - offset
                        val allowedWorse = InterruptionBudget.shouldInterrupt(
                            declared,
                            receptionWorstFirst[worse],
                            last,
                            now,
                        )
                        if (allowedWorse) {
                            assertTrue(
                                "declared=$declared offset=$offset " +
                                    "worse=${receptionWorstFirst[worse]} " +
                                    "better=${receptionWorstFirst[better]}",
                                InterruptionBudget.shouldInterrupt(
                                    declared,
                                    receptionWorstFirst[better],
                                    last,
                                    now,
                                ),
                            )
                        }
                        compared++
                    }
                }
            }
        }
        assertTrue("the sweep compared $compared cases", compared > 300)
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
        // And no ledger short of a stop reaches it either, for any kind.
        for (kind in kinds) {
            for (outcomes in sequences) {
                if (outcomes.contains(InterruptionBudget.Outcome.STOP.key)) continue
                for (declared in frequencies - SupportOfferFrequency.Never) {
                    assertTrue(
                        "kind=$kind declared=$declared ledger=$outcomes",
                        permittedGap(declared, outcomes, saidStop = false, kind = kind) <
                            InterruptionBudget.NEVER_GAP_MILLIS,
                    )
                }
            }
        }
    }

    @Test
    fun `every reception step asks less than the one before it`() {
        for (declared in frequencies) {
            var previous = 0L
            for (reception in InterruptionBudget.Reception.entries) {
                val gap = gapFor(declared, reception)
                assertTrue("declared=$declared reception=$reception", gap >= previous)
                previous = gap
            }
        }
    }

    // ---- the ladder ----------------------------------------------------------------------

    @Test
    fun `every frequency is on the ladder, and each rung hands out its own gap`() {
        // The ladder is derived from minimumGapMillis rather than hand-maintained, so a new
        // SupportOfferFrequency takes its rung automatically once the compiler has forced it into
        // that `when`. Two of these assertions are what makes that derivation well-defined: every
        // frequency comes back unchanged when nothing is stepping it down, and no two frequencies
        // share a gap — a rung handing out its neighbour's number is not a rung.
        for (declared in frequencies) {
            assertEquals(
                declared,
                InterruptionBudget.effectiveFrequency(declared, InterruptionBudget.Reception.Open),
            )
        }
        assertEquals(
            frequencies.size,
            frequencies.map { InterruptionBudget.minimumGapMillis(it) }.distinct().size,
        )
    }

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
    fun `stop asking silences every frequency and every kind, including every time`() {
        for (kind in kinds) {
            for (declared in frequencies) {
                assertFalse(
                    "kind=$kind declared=$declared",
                    InterruptionBudget.shouldInterrupt(
                        kind = kind,
                        declared = declared,
                        recent = emptyList(),
                        saidStop = true,
                        nowMillis = now,
                    ),
                )
                assertFalse(
                    "kind=$kind declared=$declared",
                    InterruptionBudget.shouldInterrupt(
                        kind = kind,
                        declared = declared,
                        recent = ledger(listOf(InterruptionBudget.Outcome.STOP.key), kind),
                        saidStop = false,
                        nowMillis = now,
                    ),
                )
            }
        }
    }

    // ---- reading the ledger --------------------------------------------------------------

    @Test
    fun `a new install with no history is open`() {
        for (kind in kinds) {
            assertEquals(
                InterruptionBudget.Reception.Open,
                InterruptionBudget.receptionOf(kind, emptyList(), saidStop = false),
            )
            assertEquals(0L, InterruptionBudget.lastOfferedAt(kind, emptyList()))
        }
    }

    @Test
    fun `taking offers up leaves the setting exactly as the person set it`() {
        val accepted = ledger(
            List(InterruptionBudget.RECENT_WINDOW) { InterruptionBudget.Outcome.ACCEPTED.key },
        )
        val reception = InterruptionBudget.receptionOf(
            InterruptionBudget.Kind.COMPANION,
            accepted,
            saidStop = false,
        )
        assertEquals(InterruptionBudget.Reception.Open, reception)
        for (declared in frequencies) {
            assertEquals(declared, InterruptionBudget.effectiveFrequency(declared, reception))
        }
    }

    @Test
    fun `being waved away quiets it, one turn-down at a time`() {
        fun receptionAfter(vararg outcomes: String) = InterruptionBudget.receptionOf(
            InterruptionBudget.Kind.COMPANION,
            ledger(outcomes.toList()),
            saidStop = false,
        )
        assertEquals(
            InterruptionBudget.Reception.Easing,
            receptionAfter(InterruptionBudget.Outcome.SNOOZED.key),
        )
        assertEquals(
            InterruptionBudget.Reception.Easing,
            receptionAfter(InterruptionBudget.Outcome.DISMISSED.key),
        )
        assertEquals(
            InterruptionBudget.Reception.Quiet,
            receptionAfter(
                InterruptionBudget.Outcome.DISMISSED.key,
                InterruptionBudget.Outcome.DISMISSED.key,
            ),
        )
    }

    @Test
    fun `an outcome from a later version reads as quieter, never louder`() {
        // Finding 1 in docs/COMPANION_DIALOGUE.md is the same failure in the predicate evaluator:
        // drift must fail closed. Here "closed" means asking less.
        val unknown = ledger(List(3) { unknownKey })
        val accepted = ledger(List(3) { InterruptionBudget.Outcome.ACCEPTED.key })
        for (declared in frequencies) {
            val unknownGap = gapFor(
                declared,
                InterruptionBudget.receptionOf(
                    InterruptionBudget.Kind.COMPANION,
                    unknown,
                    saidStop = false,
                ),
            )
            val acceptedGap = gapFor(
                declared,
                InterruptionBudget.receptionOf(
                    InterruptionBudget.Kind.COMPANION,
                    accepted,
                    saidStop = false,
                ),
            )
            assertTrue("declared=$declared", unknownGap >= acceptedGap)
        }
    }

    @Test
    fun `an unknown kind key spends nobody's budget`() {
        // A row a later version wrote for a feature this one has never heard of must not quiet a
        // kind it was never about. Unknown keys resolve to null rather than to something.
        val alien = listOf(
            InterruptionBudget.Offer(
                kind = "a_feature_from_the_future",
                offeredAt = now - day,
                outcome = InterruptionBudget.Outcome.DISMISSED.key,
            ),
        )
        assertEquals(null, InterruptionBudget.Kind.fromKey("a_feature_from_the_future"))
        for (kind in kinds) {
            assertEquals(
                "$kind",
                InterruptionBudget.Reception.Open,
                InterruptionBudget.receptionOf(kind, alien, saidStop = false),
            )
            assertEquals(0L, InterruptionBudget.lastOfferedAt(kind, alien))
        }
    }

    @Test
    fun `only the newest offers count, and older ones age out of the window`() {
        val outcomes =
            List(InterruptionBudget.RECENT_WINDOW) { InterruptionBudget.Outcome.ACCEPTED.key } +
                List(6) { InterruptionBudget.Outcome.DISMISSED.key }
        // ledger() puts the last element newest, so the dismissals are the recent ones here.
        assertEquals(
            InterruptionBudget.Reception.Quiet,
            InterruptionBudget.receptionOf(
                InterruptionBudget.Kind.COMPANION,
                ledger(outcomes),
                saidStop = false,
            ),
        )
        assertEquals(
            InterruptionBudget.Reception.Open,
            InterruptionBudget.receptionOf(
                InterruptionBudget.Kind.COMPANION,
                ledger(outcomes.reversed()),
                saidStop = false,
            ),
        )
    }

    @Test
    fun `the order rows arrive in does not change the answer`() {
        val rows = ledger(
            listOf(
                InterruptionBudget.Outcome.DISMISSED.key,
                InterruptionBudget.Outcome.ACCEPTED.key,
                InterruptionBudget.Outcome.SNOOZED.key,
            ),
        )
        assertEquals(
            InterruptionBudget.receptionOf(
                InterruptionBudget.Kind.COMPANION,
                rows,
                saidStop = false,
            ),
            InterruptionBudget.receptionOf(
                InterruptionBudget.Kind.COMPANION,
                rows.reversed(),
                saidStop = false,
            ),
        )
        assertEquals(
            InterruptionBudget.lastOfferedAt(InterruptionBudget.Kind.COMPANION, rows),
            InterruptionBudget.lastOfferedAt(InterruptionBudget.Kind.COMPANION, rows.reversed()),
        )
    }

    // ---- one budget per kind -------------------------------------------------------------

    @Test
    fun `each kind spends its own budget and cannot quiet another`() {
        val dismissedCompanion = ledger(
            List(4) { InterruptionBudget.Outcome.DISMISSED.key },
            InterruptionBudget.Kind.COMPANION,
        )
        assertEquals(
            InterruptionBudget.Reception.Quiet,
            InterruptionBudget.receptionOf(
                InterruptionBudget.Kind.COMPANION,
                dismissedCompanion,
                saidStop = false,
            ),
        )
        kinds.filter { it != InterruptionBudget.Kind.COMPANION }.forEach { other ->
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
        val stopped = ledger(
            listOf(InterruptionBudget.Outcome.STOP.key),
            InterruptionBudget.Kind.REMINDER,
        )
        assertEquals(
            InterruptionBudget.Reception.Closed,
            InterruptionBudget.receptionOf(
                InterruptionBudget.Kind.REMINDER,
                stopped,
                saidStop = false,
            ),
        )
        assertEquals(
            InterruptionBudget.Reception.Open,
            InterruptionBudget.receptionOf(
                InterruptionBudget.Kind.SUPPORT,
                stopped,
                saidStop = false,
            ),
        )
    }

    @Test
    fun `last offered at is this kind's newest row`() {
        val rows = ledger(
            List(3) { InterruptionBudget.Outcome.ACCEPTED.key },
            InterruptionBudget.Kind.ASSIGNMENT,
        )
        assertEquals(
            now - day,
            InterruptionBudget.lastOfferedAt(InterruptionBudget.Kind.ASSIGNMENT, rows),
        )
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
                    kind = InterruptionBudget.Kind.SUPPORT,
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
            InterruptionBudget.defaultFrequency(InterruptionBudget.Kind.SUPPORT),
        )
        // No kind starts silent — a gate that defaults to off is a feature nobody can find.
        kinds.forEach { kind ->
            assertFalse(
                "$kind",
                InterruptionBudget.defaultFrequency(kind) == SupportOfferFrequency.Never,
            )
        }
    }
}
