package com.daymark.app.stats

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comments removed from Kotlin source, so a property about *code* is not accidentally asserted
 * about prose. Shared with [PhrasePoolTest], which needs the same thing for a different reason.
 *
 * Naive about `//` inside a string literal, which is why every caller pairs it with a control that
 * the stripped text still contains the declarations it is about — a stripper that ate the file
 * would otherwise satisfy every absence assertion in it forever.
 */
internal fun stripKotlinComments(source: String): String {
    val out = StringBuilder()
    var index = 0
    var inBlock = false
    var inLine = false
    while (index < source.length) {
        val current = source[index]
        val next = if (index + 1 < source.length) source[index + 1] else ' '
        if (inBlock) {
            if (current == '*' && next == '/') {
                inBlock = false
                index++
            }
        } else if (inLine) {
            if (current == '\n') {
                inLine = false
                out.append(current)
            }
        } else if (current == '/' && next == '*') {
            inBlock = true
            index++
        } else if (current == '/' && next == '/') {
            inLine = true
            index++
        } else {
            out.append(current)
        }
        index++
    }
    return out.toString()
}

/**
 * Placement, and the invariant it is not allowed to break.
 *
 * `docs/DECISIONS_2026-08.md` §D1a:
 *
 * > The arbiter's response to falling reception is **monotonic and one-directional**: it may only
 * > ever ask *less*. No signal, in any combination, may cause it to ask more.
 *
 * [TimingGrid] is new machinery sitting downstream of the component that invariant was written
 * about, so the first thing these tests establish is that adding it did not open a door. Three
 * sweeps do that, and they are separate because each fails independently of the others:
 *
 *  1. **A placement can only withhold.** Swept over the whole input space: if the composed answer
 *     is yes, the gate's answer alone was yes too. This is the ceiling, and it is blind to the
 *     ordering rules entirely — which is the point. It would still pass if every weight in
 *     [TimingGrid.allocate] were inverted, and that is what makes it the right first assertion:
 *     the property must not depend on the arithmetic being right.
 *  2. **Worse reception still never turns a no into a yes**, now with placement in the loop.
 *     Catches the one thing the ceiling sweep cannot see: a quieter reception that somehow places
 *     an hour a louder one did not, which would be an escalation dressed as a placement.
 *  3. **A timeline with placement never makes more asks than the same timeline without it.** The
 *     same rule as (1) counted over time rather than decided at a point, because "never asks more"
 *     is a claim about a run of the app and not about one call. A deferral that reset the gap
 *     wrongly would pass (1) and fail here.
 *
 * The rest pin the design decisions: the evidence threshold, the refusal to silence the day, and
 * purity. Everything in this file is exact — nothing draws a random value, so none of the four
 * mutation-that-might-not-be-a-mutation bugs in `CLAUDE.md` §5 has anywhere to live here; the two
 * places a value *is* changed on purpose derive the change from the value and assert it really
 * changed first.
 */
class TimingGridTest {

    private val hourMillis = 3_600_000L
    private val day = 24 * hourMillis
    private val now = 1_700_000_000_000L

    private val kind = InterruptionBudget.Kind.COMPANION
    private val kindKey = kind.key
    private val frequencies = SupportOfferFrequency.entries
    private val everyHour = (0 until TimingGrid.HOURS_IN_DAY).toList()

    /** Reception states worst first, stated by hand rather than read off the enum's order. */
    private val receptionWorstFirst: List<InterruptionBudget.Reception> = listOf(
        InterruptionBudget.Reception.Closed,
        InterruptionBudget.Reception.Quiet,
        InterruptionBudget.Reception.Easing,
        InterruptionBudget.Reception.Open,
    )

    /**
     * An ask, answered or not.
     *
     * "Answered" is deliberately spelled [InterruptionBudget.Outcome.DISMISSED] rather than
     * `ACCEPTED`: a dismissal is an answer — someone was there and said no — and the tests would be
     * describing a different, worse product if they only ever used the agreeable outcome.
     */
    private fun ask(
        hour: Int,
        answered: Boolean,
        weekday: Int = 1,
        kindKey: String = this.kindKey,
    ): TimingGrid.Ask = TimingGrid.Ask(
        kind = kindKey,
        hour = hour,
        weekday = weekday,
        outcome = if (answered) InterruptionBudget.Outcome.DISMISSED.key else null,
    )

    /**
     * Ledgers the sweeps run over, including ones written to attack the invariant: every hour
     * hammered, every hour dead, rows for a kind this version has never heard of, and hours outside
     * the clock.
     */
    private val ledgers: List<List<TimingGrid.Ask>> = listOf(
        emptyList(),
        List(8) { ask(9, true) },
        List(8) { ask(9, false) },
        everyHour.flatMap { hour -> List(4) { ask(hour, hour % 2 == 0) } },
        everyHour.flatMap { hour -> List(9) { ask(hour, false) } },
        everyHour.flatMap { hour -> List(9) { ask(hour, true) } },
        listOf(
            ask(-1, true),
            ask(TimingGrid.HOURS_IN_DAY, true),
            ask(9, true, kindKey = "a_feature_from_the_future"),
            ask(11, false),
            ask(11, false),
            ask(11, false),
        ),
        listOf(ask(14, false), ask(14, false)),
    )

    private fun placementFor(
        asks: List<TimingGrid.Ask>,
        declared: SupportOfferFrequency,
        reception: InterruptionBudget.Reception,
    ): TimingGrid.Placement = TimingGrid.allocate(
        kind = kindKey,
        asks = asks,
        hoursWanted = TimingGrid.hoursFor(
            InterruptionBudget.effectiveFrequency(declared, reception),
        ),
    )

    // ---- the invariant, end to end ----------------------------------------------------------

    @Test
    fun `a placement can only ever withhold an ask, never grant one`() {
        // The ceiling. Whatever the ledger says and however the hours are ranked, the composed
        // answer implies the gate's own answer — so no history a person can accumulate places an
        // ask the budget had refused. This is the assertion that makes it structurally impossible
        // for placement to become a second, louder decision maker, and it is swept over the whole
        // input space: every ledger, every reception, every setting, every clock, every hour.
        var checked = 0
        val offsets = listOf(0L, hourMillis, day - 1, day, 3 * day, 8 * day, 400 * day)
        for (asks in ledgers) {
            for (reception in receptionWorstFirst) {
                for (declared in frequencies) {
                    val placement = placementFor(asks, declared, reception)
                    for (offset in offsets) {
                        val lastAt = now - offset
                        val budgetAllows = InterruptionBudget.shouldInterrupt(
                            declared,
                            reception,
                            lastAt,
                            now,
                        )
                        for (hour in everyHour) {
                            val composed = TimingGrid.mayAskNow(budgetAllows, placement, hour)
                            if (composed) {
                                assertTrue(
                                    "declared=$declared reception=$reception hour=$hour " +
                                        "offset=$offset hours=${placement.hours}",
                                    budgetAllows,
                                )
                                // And the same claim against the independent oracle the existing
                                // budget sweep uses: the person's unadjusted setting.
                                assertTrue(
                                    "declared=$declared reception=$reception hour=$hour",
                                    SupportOffer.shouldInterrupt(declared, lastAt, now),
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
    fun `worse reception never turns a no into a yes, with placement in the loop`() {
        // The monotonic rule at the level a caller sees it, now that a second stage sits behind the
        // gate. A quieter reception must never place an hour a louder one did not: that would be an
        // escalation wearing a placement's clothes, and the ceiling sweep above cannot see it.
        var compared = 0
        val offsets = listOf(0L, hourMillis, day - 1, day, 3 * day, 8 * day, 400 * day)
        for (asks in ledgers) {
            for (better in receptionWorstFirst.indices) {
                for (worse in 0..better) {
                    for (declared in frequencies) {
                        val worseReception = receptionWorstFirst[worse]
                        val betterReception = receptionWorstFirst[better]
                        val worsePlacement = placementFor(asks, declared, worseReception)
                        val betterPlacement = placementFor(asks, declared, betterReception)
                        for (offset in offsets) {
                            val lastAt = now - offset
                            for (hour in everyHour) {
                                val allowedWorse = TimingGrid.mayAskNow(
                                    InterruptionBudget.shouldInterrupt(
                                        declared,
                                        worseReception,
                                        lastAt,
                                        now,
                                    ),
                                    worsePlacement,
                                    hour,
                                )
                                if (allowedWorse) {
                                    assertTrue(
                                        "declared=$declared worse=$worseReception " +
                                            "better=$betterReception hour=$hour offset=$offset",
                                        TimingGrid.mayAskNow(
                                            InterruptionBudget.shouldInterrupt(
                                                declared,
                                                betterReception,
                                                lastAt,
                                                now,
                                            ),
                                            betterPlacement,
                                            hour,
                                        ),
                                    )
                                }
                                compared++
                            }
                        }
                    }
                }
            }
        }
        assertTrue("the sweep compared $compared cases", compared > 10_000)
    }

    @Test
    fun `over a run of the app, placement never produces more asks than no placement at all`() {
        // "Never asks more" is a claim about a run, not about one call, and a deferral that reset
        // the minimum gap wrongly would satisfy every pointwise assertion above while quietly
        // making the app ask twice as often. So: walk ninety days an hour at a time, twice, with
        // the placement veto and without it, and count.
        var simulated = 0
        for (asks in ledgers) {
            for (reception in receptionWorstFirst) {
                for (declared in frequencies) {
                    val placement = placementFor(asks, declared, reception)
                    val withPlacement = runTimeline(declared, reception, placement)
                    val withoutPlacement = runTimeline(declared, reception, null)
                    assertTrue(
                        "declared=$declared reception=$reception " +
                            "with=$withPlacement without=$withoutPlacement hours=${placement.hours}",
                        withPlacement <= withoutPlacement,
                    )
                    // And the other side of it, which matters just as much: placement is supposed
                    // to MOVE asks, not lose them. Every permitted gap is a whole number of days,
                    // so a window that repeats daily lines up with it and costs at most the first
                    // ask's delay. A frequency whose gap was not a multiple of a day would drift
                    // out of its own window and quietly starve the feature, and this is where that
                    // would show up.
                    assertTrue(
                        "placement starved the feature: declared=$declared reception=$reception " +
                            "with=$withPlacement without=$withoutPlacement hours=${placement.hours}",
                        withPlacement >= withoutPlacement - 1,
                    )
                    simulated++
                }
            }
        }
        assertTrue("the sweep simulated $simulated timelines", simulated > 100)

        // A positive control, because the comparison above is satisfied by a veto that never fires
        // — and a placement that allowed everything would make this test blind while still passing.
        // Narrow the window to one hour and the count must actually fall.
        val oneHour = TimingGrid.allocate(
            kind = kindKey,
            asks = emptyList(),
            hoursWanted = 1,
            candidateHours = listOf(9),
        )
        val narrowed = runTimeline(
            SupportOfferFrequency.EveryTime,
            InterruptionBudget.Reception.Open,
            oneHour,
        )
        val open = runTimeline(
            SupportOfferFrequency.EveryTime,
            InterruptionBudget.Reception.Open,
            null,
        )
        assertTrue("a narrowed window must ask strictly less: $narrowed vs $open", narrowed < open)
    }

    /** Ninety days, one step an hour, counting the asks that actually happen. */
    private fun runTimeline(
        declared: SupportOfferFrequency,
        reception: InterruptionBudget.Reception,
        placement: TimingGrid.Placement?,
    ): Int {
        var lastAt = 0L
        var asked = 0
        for (step in 0 until 90 * TimingGrid.HOURS_IN_DAY) {
            val at = now + step * hourMillis
            val hourOfDay = step % TimingGrid.HOURS_IN_DAY
            val budgetAllows = InterruptionBudget.shouldInterrupt(declared, reception, lastAt, at)
            val allowed = if (placement == null) {
                budgetAllows
            } else {
                TimingGrid.mayAskNow(budgetAllows, placement, hourOfDay)
            }
            if (allowed) {
                asked++
                lastAt = at
            }
        }
        return asked
    }

    // ---- the evidence threshold -------------------------------------------------------------

    @Test
    fun `an hour needs more than a coin flip's worth of history before it counts`() {
        // The number is TimingGrid.MIN_ASKS_FOR_EVIDENCE and this reads it rather than restating
        // it, so raising or lowering the threshold moves this test with it instead of breaking it
        // for the wrong reason.
        val justUnder = TimingGrid.MIN_ASKS_FOR_EVIDENCE - 1
        assertTrue("the threshold must leave room for a case below it", justUnder >= 1)

        assertEquals(
            TimingGrid.Standing.TooLittleEvidence,
            TimingGrid.standingOf(asks = justUnder, answered = 0),
        )
        assertEquals(
            TimingGrid.Standing.TooLittleEvidence,
            TimingGrid.standingOf(asks = justUnder, answered = justUnder),
        )
        assertEquals(
            TimingGrid.Standing.Unanswered,
            TimingGrid.standingOf(asks = TimingGrid.MIN_ASKS_FOR_EVIDENCE, answered = 0),
        )
        // The asymmetry the constant's docstring argues for: one answer is enough to keep an hour,
        // a whole threshold's worth of silence is needed to give one up.
        assertEquals(
            TimingGrid.Standing.Answered,
            TimingGrid.standingOf(asks = TimingGrid.MIN_ASKS_FOR_EVIDENCE, answered = 1),
        )
    }

    @Test
    fun `two data points do not move the app's day`() {
        // The lopsided-guess case, end to end rather than through standingOf: an hour with one ask
        // answered and one not must not become a preference, and the placement must be the same one
        // a brand new install gets.
        val fresh = TimingGrid.allocate(kindKey, emptyList(), TimingGrid.MIN_PLACED_HOURS)
        val twoPoints = TimingGrid.allocate(
            kindKey,
            listOf(ask(20, true), ask(20, false)),
            TimingGrid.MIN_PLACED_HOURS,
        )
        assertEquals(TimingGrid.Basis.TooLittleEvidence, twoPoints.basis)
        assertEquals(fresh.hours, twoPoints.hours)

        // Positive control: the check above is worthless if nothing could ever change the hours.
        // One more ask at the same hour crosses the threshold, and the placement must move.
        val threePoints = TimingGrid.allocate(
            kindKey,
            listOf(ask(20, true), ask(20, false), ask(20, true)),
            TimingGrid.MIN_PLACED_HOURS,
        )
        assertEquals(TimingGrid.Basis.AnsweredHours, threePoints.basis)
        assertNotEquals(fresh.hours, threePoints.hours)
        assertTrue("the answered hour must be used", threePoints.hours.contains(20))
    }

    @Test
    fun `an empty ledger spreads across the day rather than guessing`() {
        val placement = TimingGrid.allocate(kindKey, emptyList(), TimingGrid.MIN_PLACED_HOURS)
        assertEquals(TimingGrid.Basis.TooLittleEvidence, placement.basis)
        assertEquals(TimingGrid.MIN_PLACED_HOURS, placement.hours.size)
        assertTrue(
            "every placed hour must be one the caller offered",
            TimingGrid.DEFAULT_CANDIDATE_HOURS.containsAll(placement.hours),
        )
        // Not the front of the window: taking the first three would have the app decide, on no
        // evidence at all, that this person is a morning person.
        assertNotEquals(
            TimingGrid.DEFAULT_CANDIDATE_HOURS.take(TimingGrid.MIN_PLACED_HOURS),
            placement.hours,
        )
        val spread = placement.hours.last() - placement.hours.first()
        assertTrue(
            "three hours out of ${TimingGrid.DEFAULT_CANDIDATE_HOURS.size} should span the day, not huddle: " +
                "${placement.hours}",
            spread >= TimingGrid.DEFAULT_CANDIDATE_HOURS.size / 2,
        )
        // Every hour reads as neutral, not as bad. "No information" is its own state.
        assertTrue(
            placement.readings.all { it.standing == TimingGrid.Standing.TooLittleEvidence },
        )
        assertEquals(TimingGrid.HOURS_IN_DAY, placement.readings.size)
    }

    // ---- placement does what it says --------------------------------------------------------

    @Test
    fun `asks move into the hours that were answered and out of the hours that were not`() {
        val answeredHour = 9
        val deadHour = 20
        val evidence = TimingGrid.MIN_ASKS_FOR_EVIDENCE
        val asks = List(evidence) { ask(answeredHour, true) } + List(evidence) { ask(deadHour, false) }

        val placement = TimingGrid.allocate(kindKey, asks, TimingGrid.MIN_PLACED_HOURS)
        assertEquals(TimingGrid.Basis.AnsweredHours, placement.basis)
        assertTrue("the answered hour must be used: ${placement.hours}", placement.hours.contains(answeredHour))
        assertFalse("the dead hour must not be: ${placement.hours}", placement.hours.contains(deadHour))

        // The mutation, derived from the value rather than written as a literal: flip each dead
        // hour's outcome to whatever it currently is not, assert the rows really did change, then
        // assert the answer changes with them. Without the first assertion a no-op "mutation" would
        // make the second one assert that nothing changed when nothing was changed.
        val mutated = asks.map { row ->
            if (row.hour != deadHour) {
                row
            } else {
                row.copy(
                    outcome = if (row.outcome == null) InterruptionBudget.Outcome.DISMISSED.key else null,
                )
            }
        }
        assertNotEquals("the mutation must actually mutate", asks, mutated)
        val afterMutation = TimingGrid.allocate(kindKey, mutated, TimingGrid.MIN_PLACED_HOURS)
        assertTrue(
            "once it is answered, the same hour must be used: ${afterMutation.hours}",
            afterMutation.hours.contains(deadHour),
        )
    }

    @Test
    fun `an unexplored hour is preferred to one known to go unanswered`() {
        // The window is wider than the answered hours, so something has to fill it. Neutral before
        // known-dead is what stops the app narrowing onto whatever it happened to try first.
        val candidates = listOf(9, 10, 11)
        val asks = List(TimingGrid.MIN_ASKS_FOR_EVIDENCE) { ask(10, false) }
        val placement = TimingGrid.allocate(
            kind = kindKey,
            asks = asks,
            hoursWanted = 2,
            candidateHours = candidates,
        )
        // Two are wanted but MIN_PLACED_HOURS is the floor, so all three are used here; the
        // ordering is checked at a width where it can actually bite.
        val narrow = TimingGrid.allocate(
            kind = kindKey,
            asks = asks,
            hoursWanted = TimingGrid.MIN_PLACED_HOURS,
            candidateHours = listOf(9, 10, 11, 12, 13, 14),
        )
        assertFalse(
            "the known-dead hour must be the last one reached for: ${narrow.hours}",
            narrow.hours.contains(10),
        )
        assertEquals(TimingGrid.MIN_PLACED_HOURS, placement.hours.size)
    }

    @Test
    fun `no ledger can close the day down to silence`() {
        // Reception bottoms out at quiet, never at absent (§D1a), and the hour window has the same
        // floor. An engine that could narrow its own way to nothing has made the decision only the
        // person may make.
        for (asks in ledgers) {
            for (frequency in frequencies - SupportOfferFrequency.Never) {
                val placement = TimingGrid.allocate(
                    kindKey,
                    asks,
                    TimingGrid.hoursFor(frequency),
                )
                assertTrue(
                    "frequency=$frequency asks=${asks.size}",
                    placement.hours.size >= TimingGrid.MIN_PLACED_HOURS,
                )
            }
        }
        // Even when every single candidate hour is known dead.
        val everythingDead = TimingGrid.DEFAULT_CANDIDATE_HOURS.flatMap { hour ->
            List(TimingGrid.MIN_ASKS_FOR_EVIDENCE) { ask(hour, false) }
        }
        val placement = TimingGrid.allocate(kindKey, everythingDead, TimingGrid.MIN_PLACED_HOURS)
        assertEquals(TimingGrid.Basis.NoHourAnswered, placement.basis)
        assertEquals(TimingGrid.MIN_PLACED_HOURS, placement.hours.size)
        // And the person's own setting still reaches silence, which is the other half of the rule.
        assertEquals(
            0,
            TimingGrid.allocate(
                kindKey,
                everythingDead,
                TimingGrid.hoursFor(SupportOfferFrequency.Never),
            ).hours.size,
        )
    }

    @Test
    fun `a moment the person chose is not the app's to move`() {
        // EveryTime means "every qualifying save": the person picked the moment by doing something,
        // so placement declines to act rather than refusing an hour they are demonstrably awake in.
        val placement = TimingGrid.allocate(
            kindKey,
            ledgers.last(),
            TimingGrid.hoursFor(SupportOfferFrequency.EveryTime),
        )
        assertEquals(TimingGrid.Basis.EveryHour, placement.basis)
        assertEquals(everyHour, placement.hours)
        assertTrue(everyHour.all { placement.allows(it) })
    }

    @Test
    fun `a window never widens as the setting gets quieter`() {
        // hoursFor is a second number derived from the frequency, so it gets the same treatment the
        // gap ladder gets: quieter must never mean more.
        val loudestFirst = SupportOfferFrequency.entries
            .sortedBy { InterruptionBudget.minimumGapMillis(it) }
        var previous = TimingGrid.HOURS_IN_DAY + 1
        for (frequency in loudestFirst) {
            val hours = TimingGrid.hoursFor(frequency)
            assertTrue("$frequency wants $hours, after $previous", hours <= previous)
            previous = hours
        }
        assertEquals(0, TimingGrid.hoursFor(SupportOfferFrequency.Never))
    }

    // ---- reading the ledger -----------------------------------------------------------------

    @Test
    fun `an unknown kind key spends nobody's window`() {
        val alien = List(9) { ask(9, true, kindKey = "a_feature_from_the_future") }
        assertNull(InterruptionBudget.Kind.fromKey("a_feature_from_the_future"))
        for (other in InterruptionBudget.Kind.entries) {
            val placement = TimingGrid.allocate(other.key, alien, TimingGrid.MIN_PLACED_HOURS)
            assertEquals("$other", TimingGrid.Basis.TooLittleEvidence, placement.basis)
            assertEquals("$other", 0, placement.readings.fold(0) { sum, r -> sum + r.asks })
        }
        // Positive control: the same rows under their own key are counted, so the assertion above
        // is about the key and not about allocate having quietly stopped counting anything.
        val own = TimingGrid.allocate(
            "a_feature_from_the_future",
            alien,
            TimingGrid.MIN_PLACED_HOURS,
        )
        assertEquals(TimingGrid.Basis.AnsweredHours, own.basis)
        assertEquals(alien.size, own.readings.fold(0) { sum, r -> sum + r.asks })
    }

    @Test
    fun `an outcome key from a later version is still an answer`() {
        // Only the absence of an outcome means nobody answered. A key this version does not know is
        // a response it cannot name, which is a different thing from no response at all — reading
        // it as silence would have the app abandon an hour on the strength of a schema change.
        val unknownKey = "vanished_in_a_later_version"
        assertNull(InterruptionBudget.Outcome.fromKey(unknownKey))
        val asks = List(TimingGrid.MIN_ASKS_FOR_EVIDENCE) {
            TimingGrid.Ask(kind = kindKey, hour = 9, weekday = 1, outcome = unknownKey)
        }
        assertEquals(
            TimingGrid.Standing.Answered,
            TimingGrid.allocate(kindKey, asks, TimingGrid.MIN_PLACED_HOURS).readings[9].standing,
        )
        // Positive control, derived from the value: strip the outcome off the same rows and the
        // same hour must read the other way.
        val silent = asks.map { it.copy(outcome = null) }
        assertNotEquals(asks, silent)
        assertEquals(
            TimingGrid.Standing.Unanswered,
            TimingGrid.allocate(kindKey, silent, TimingGrid.MIN_PLACED_HOURS).readings[9].standing,
        )
    }

    @Test
    fun `the order rows arrive in does not change the answer`() {
        for (asks in ledgers) {
            assertEquals(
                TimingGrid.allocate(kindKey, asks, TimingGrid.MIN_PLACED_HOURS),
                TimingGrid.allocate(kindKey, asks.reversed(), TimingGrid.MIN_PLACED_HOURS),
            )
            assertEquals(TimingGrid.grid(kindKey, asks), TimingGrid.grid(kindKey, asks.reversed()))
        }
    }

    @Test
    fun `the same inputs always give the same outputs`() {
        // Nothing here reads a clock or a random source, so two calls a moment apart must be equal.
        for (asks in ledgers) {
            for (wanted in listOf(0, 1, TimingGrid.MIN_PLACED_HOURS, 6, TimingGrid.HOURS_IN_DAY)) {
                assertEquals(
                    TimingGrid.allocate(kindKey, asks, wanted),
                    TimingGrid.allocate(kindKey, asks, wanted),
                )
            }
        }
    }

    @Test
    fun `an adversarial ledger produces a well formed placement and nothing more`() {
        val junk = listOf(
            ask(-5, true),
            ask(TimingGrid.HOURS_IN_DAY, true),
            ask(Int.MIN_VALUE, false),
            ask(Int.MAX_VALUE, false),
            ask(9, true, weekday = 0),
            ask(9, true, weekday = TimingGrid.DAYS_IN_WEEK + 1),
        ) + List(500) { ask(11, false) }
        val wanted = listOf(
            Int.MIN_VALUE,
            -1,
            0,
            1,
            TimingGrid.MIN_PLACED_HOURS,
            TimingGrid.HOURS_IN_DAY - 1,
            TimingGrid.HOURS_IN_DAY,
            Int.MAX_VALUE,
        )
        val candidateSets = listOf(
            emptyList(),
            listOf(-1, 99),
            listOf(9, 9, 9),
            TimingGrid.DEFAULT_CANDIDATE_HOURS,
            everyHour,
        )
        for (hoursWanted in wanted) {
            for (candidates in candidateSets) {
                val placement = TimingGrid.allocate(kindKey, junk, hoursWanted, candidates)
                assertEquals(placement.hours.distinct(), placement.hours)
                assertEquals(placement.hours.sorted(), placement.hours)
                assertTrue(everyHour.containsAll(placement.hours))
                assertEquals(TimingGrid.HOURS_IN_DAY, placement.readings.size)
                if (placement.basis != TimingGrid.Basis.EveryHour) {
                    assertTrue(
                        "hoursWanted=$hoursWanted candidates=$candidates hours=${placement.hours}",
                        candidates.containsAll(placement.hours),
                    )
                }
                // Out-of-clock rows are dropped rather than clamped onto hour 0 or hour 23.
                assertEquals(0, placement.readings[0].asks)
                assertEquals(0, placement.readings[TimingGrid.HOURS_IN_DAY - 1].asks)
            }
        }
        // Positive control for the drop: an in-range row at the same edge hour IS counted, so the
        // two assertions above are about the out-of-range values and not about a counter that has
        // stopped counting.
        val atEdges = TimingGrid.allocate(
            kindKey,
            listOf(ask(0, false), ask(TimingGrid.HOURS_IN_DAY - 1, false)),
            TimingGrid.MIN_PLACED_HOURS,
        )
        assertEquals(1, atEdges.readings[0].asks)
        assertEquals(1, atEdges.readings[TimingGrid.HOURS_IN_DAY - 1].asks)
    }

    // ---- the grid ---------------------------------------------------------------------------

    @Test
    fun `the grid is every slot of the week, filled or not`() {
        val grid = TimingGrid.grid(kindKey, listOf(ask(9, true, weekday = 3)))
        assertEquals(TimingGrid.DAYS_IN_WEEK * TimingGrid.HOURS_IN_DAY, grid.cells.size)
        assertEquals(
            grid.cells.size,
            grid.cells.map { it.weekday * 100 + it.hour }.distinct().size,
        )
        for (weekday in 1..TimingGrid.DAYS_IN_WEEK) {
            for (hour in 0 until TimingGrid.HOURS_IN_DAY) {
                val cell = grid.cell(weekday, hour)!!
                assertEquals(weekday, cell.weekday)
                assertEquals(hour, cell.hour)
            }
        }
        assertEquals(1, grid.cell(3, 9)!!.asks)
        assertEquals(1, grid.cell(3, 9)!!.answered)
        assertEquals(0, grid.cell(4, 9)!!.asks)
        assertNull(grid.cell(0, 9))
        assertNull(grid.cell(TimingGrid.DAYS_IN_WEEK + 1, 9))
        assertNull(grid.cell(3, -1))
        assertNull(grid.cell(3, TimingGrid.HOURS_IN_DAY))
    }

    @Test
    fun `a row the grid cannot place is dropped, not guessed at`() {
        val unplaceable = listOf(
            ask(9, true, weekday = 0),
            ask(9, true, weekday = TimingGrid.DAYS_IN_WEEK + 1),
            ask(-1, true, weekday = 1),
            ask(TimingGrid.HOURS_IN_DAY, true, weekday = 1),
        )
        val grid = TimingGrid.grid(kindKey, unplaceable)
        assertEquals(0, grid.cells.fold(0) { sum, cell -> sum + cell.asks })
        // Positive control: a row that CAN be placed lands, so the zero above is about the rows and
        // not about grid() having stopped counting.
        val placeable = TimingGrid.grid(kindKey, unplaceable + ask(9, true, weekday = 1))
        assertEquals(1, placeable.cells.fold(0) { sum, cell -> sum + cell.asks })
        assertEquals(1, placeable.cell(1, 9)!!.asks)
    }

    // ---- purity -----------------------------------------------------------------------------

    @Test
    fun `the timing layer reads no clock, no random source and nothing else at all`() {
        // stats/ is pure and Android-free, and these three files go further: like
        // InterruptionBudget they import nothing whatever, so there is no route by which they could
        // acquire a dependency — or a clock — without the diff saying so. An import line is the
        // cheapest possible thing to check and the exact shape the regression would arrive in.
        val paths = listOf(
            "app/src/main/java/com/daymark/app/stats/TimingGrid.kt",
            "app/src/main/java/com/daymark/app/stats/PhrasePool.kt",
            "app/src/main/java/com/daymark/app/stats/RuleReadout.kt",
        )
        for (path in paths) {
            val code = stripKotlinComments(repoFile(path).readText())
            // The control that keeps this from being a test of an empty string: the stripper must
            // leave the declarations behind.
            assertTrue("$path: the stripper ate the file", code.contains("object "))
            assertFalse("$path imports something", code.contains("import "))
            for (forbidden in FORBIDDEN_IN_A_PURE_FILE) {
                assertFalse("$path reaches for $forbidden", code.contains(forbidden))
            }
        }
        // Positive control: the same matcher, run over a planted file, must catch every one of
        // them. A check that cannot see an example proves only that it is blind.
        val planted = """
            package com.daymark.app.stats
            import java.time.Clock
            object Planted {
                fun now(): Long = System.currentTimeMillis()
                fun pick(): Double = Math.random()
                fun other(): Int = kotlin.random.Random.nextInt()
                fun stamp(): Long = java.time.Instant.now().toEpochMilli()
            }
        """.trimIndent()
        val plantedCode = stripKotlinComments(planted)
        assertTrue("the stripper must not eat the planted control", plantedCode.contains("import "))
        for (forbidden in FORBIDDEN_IN_A_PURE_FILE) {
            assertTrue(
                "the planted control must still contain $forbidden after stripping",
                plantedCode.contains(forbidden),
            )
        }
    }

    @Test
    fun `the comment stripper removes comments and keeps code`() {
        // The two source tests above and in PhrasePoolTest both stand on this, so it gets its own
        // assertion rather than being trusted.
        val stripped = stripKotlinComments(
            "/** doc mentions import */\nval a = 1 // trailing import\n/* block */ val b = 2\n",
        )
        assertFalse(stripped.contains("import"))
        assertTrue(stripped.contains("val a = 1"))
        assertTrue(stripped.contains("val b = 2"))
    }

    private companion object {
        /** Everything a file with no clock and no random source must never name. */
        val FORBIDDEN_IN_A_PURE_FILE: List<String> = listOf(
            "System.currentTimeMillis",
            "Math.random",
            "kotlin.random",
            "java.time",
            "Instant.now",
        )
    }
}
