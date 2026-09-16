package com.daymark.app.stats

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug readout: a description of the rules, and the assertions that keep it from becoming a
 * reading of the person.
 *
 * Two things can go wrong with a screen like this and they are different failures, so they get
 * different tests:
 *
 *  1. **It could drift.** A readout that restates the rule in a string rather than calling it will
 *     keep saying the old thing after the rule changes, which is worse than no screen: someone
 *     debugging trusts it. So the sweep below checks the readout's own verdict against the engines
 *     it claims to be describing, over the whole input space, rather than against examples.
 *  2. **It could start describing the person.** A debug screen is exactly where an inference first
 *     gets written down as a sentence, because it is the one place where saying more feels helpful.
 *     [Hold] is therefore a closed list, and the source scan here — with a planted positive control
 *     — asserts that nothing in this file's code is named for a state of a person.
 */
class RuleReadoutTest {

    private val hourMillis = 3_600_000L
    private val day = 24 * hourMillis
    private val now = 1_700_000_000_000L
    private val kinds = InterruptionBudget.Kind.entries
    private val frequencies = SupportOfferFrequency.entries

    private fun offer(
        kind: InterruptionBudget.Kind,
        outcome: InterruptionBudget.Outcome,
        agoMillis: Long,
    ): InterruptionBudget.Offer = InterruptionBudget.Offer(
        kind = kind.key,
        offeredAt = now - agoMillis,
        outcome = outcome.key,
    )

    private fun ask(
        kind: InterruptionBudget.Kind,
        hour: Int,
        answered: Boolean,
    ): TimingGrid.Ask = TimingGrid.Ask(
        kind = kind.key,
        hour = hour,
        weekday = 1,
        outcome = if (answered) InterruptionBudget.Outcome.DISMISSED.key else null,
    )

    private fun readout(
        kind: InterruptionBudget.Kind = InterruptionBudget.Kind.COMPANION,
        declared: SupportOfferFrequency = SupportOfferFrequency.OncePerDay,
        recent: List<InterruptionBudget.Offer> = emptyList(),
        saidStop: Boolean = false,
        timed: List<TimingGrid.Ask> = emptyList(),
        hour: Int = 10,
    ): RuleReadout.Feature = RuleReadout.feature(
        kind = kind,
        declared = declared,
        recent = recent,
        saidStop = saidStop,
        timed = timed,
        hour = hour,
        nowMillis = now,
    )

    // ---- the readout describes the engine, and cannot drift from it -------------------------

    @Test
    fun `the readout's verdict is the engine's verdict, over the whole input space`() {
        var checked = 0
        val ledgers = listOf(
            emptyList(),
            listOf(InterruptionBudget.Outcome.ACCEPTED),
            listOf(InterruptionBudget.Outcome.DISMISSED, InterruptionBudget.Outcome.DISMISSED),
            listOf(InterruptionBudget.Outcome.SNOOZED),
            listOf(InterruptionBudget.Outcome.STOP),
        )
        val timedLedgers = listOf(
            emptyList(),
            List(TimingGrid.MIN_ASKS_FOR_EVIDENCE) { ask(InterruptionBudget.Kind.COMPANION, 9, true) },
            TimingGrid.DEFAULT_CANDIDATE_HOURS.flatMap { hour ->
                List(TimingGrid.MIN_ASKS_FOR_EVIDENCE) { ask(InterruptionBudget.Kind.COMPANION, hour, false) }
            },
        )
        for (kind in kinds) {
            for (declared in frequencies) {
                for (outcomes in ledgers) {
                    val recent = outcomes.mapIndexed { index, outcome ->
                        offer(kind, outcome, (outcomes.size - index) * day)
                    }
                    for (saidStop in listOf(false, true)) {
                        for (timed in timedLedgers) {
                            val mine = timed.map { it.copy(kind = kind.key) }
                            for (hour in 0 until TimingGrid.HOURS_IN_DAY) {
                                val feature = RuleReadout.feature(
                                    kind = kind,
                                    declared = declared,
                                    recent = recent,
                                    saidStop = saidStop,
                                    timed = mine,
                                    hour = hour,
                                    nowMillis = now,
                                )
                                val reception =
                                    InterruptionBudget.receptionOf(kind, recent, saidStop)
                                val lastAt = InterruptionBudget.lastOfferedAt(kind, recent)
                                val budgetAllows = InterruptionBudget.shouldInterrupt(
                                    declared,
                                    reception,
                                    lastAt,
                                    now,
                                )
                                assertEquals(reception, feature.reception)
                                assertEquals(lastAt, feature.lastOfferedAt)
                                assertEquals(
                                    InterruptionBudget.effectiveFrequency(declared, reception),
                                    feature.effective,
                                )
                                assertEquals(
                                    TimingGrid.mayAskNow(budgetAllows, feature.placement, hour),
                                    feature.wouldAskNow,
                                )
                                // The ceiling, once more through the surface that will be read by a
                                // person deciding whether the engine is behaving.
                                if (feature.wouldAskNow) {
                                    assertTrue(
                                        "kind=$kind declared=$declared hour=$hour",
                                        SupportOffer.shouldInterrupt(declared, lastAt, now),
                                    )
                                }
                                assertEquals(
                                    "kind=$kind declared=$declared hour=$hour hold=${feature.hold}",
                                    feature.wouldAskNow,
                                    feature.hold == RuleReadout.Hold.None,
                                )
                                assertEquals(
                                    TimingGrid.grid(kind.key, mine),
                                    feature.grid,
                                )
                                checked++
                            }
                        }
                    }
                }
            }
        }
        assertTrue("the sweep checked $checked cases", checked > 5_000)
    }

    @Test
    fun `each reason a feature is holding back is reported as itself`() {
        // The window comes out of the engine rather than being written down here, so this test
        // keeps working if the spread changes.
        val spread = readout().placement.hours
        assertTrue("the engine placed nothing to test against", spread.isNotEmpty())
        val allowedHour = spread.first()
        val blockedHour = (0 until TimingGrid.HOURS_IN_DAY).first { !spread.contains(it) }
        assertNotEquals(allowedHour, blockedHour)

        assertEquals(RuleReadout.Hold.None, readout(hour = allowedHour).hold)
        assertTrue(readout(hour = allowedHour).wouldAskNow)

        assertEquals(
            RuleReadout.Hold.NotOneOfItsHours,
            readout(hour = blockedHour).hold,
        )
        assertEquals(
            RuleReadout.Hold.SetToNever,
            readout(declared = SupportOfferFrequency.Never, hour = allowedHour).hold,
        )
        assertEquals(
            RuleReadout.Hold.SaidStop,
            readout(saidStop = true, hour = allowedHour).hold,
        )
        assertEquals(
            RuleReadout.Hold.NotLongEnoughYet,
            readout(
                recent = listOf(
                    offer(
                        InterruptionBudget.Kind.COMPANION,
                        InterruptionBudget.Outcome.ACCEPTED,
                        hourMillis,
                    ),
                ),
                hour = allowedHour,
            ).hold,
        )
        // Every reason is reachable and every reason is distinct, so the list is a list and not a
        // decoration.
        assertEquals(
            RuleReadout.Hold.entries.size,
            RuleReadout.Hold.entries.map { it.label }.distinct().size,
        )
        assertTrue(RuleReadout.Hold.entries.all { it.label.isNotBlank() })
    }

    @Test
    fun `saying stop is never reported as merely being too soon`() {
        // Most-specific-first ordering, which is the thing a `when` gets wrong quietly: a person who
        // asked the app to stop must not be told the app is just waiting for the clock.
        val recent = listOf(
            offer(
                InterruptionBudget.Kind.COMPANION,
                InterruptionBudget.Outcome.ACCEPTED,
                hourMillis,
            ),
        )
        assertEquals(
            RuleReadout.Hold.SaidStop,
            readout(recent = recent, saidStop = true).hold,
        )
        // Positive control, derived: the same rows without the standing stop report the other
        // reason, so the assertion above is about the stop and not about a constant.
        assertNotEquals(
            RuleReadout.Hold.SaidStop,
            readout(recent = recent, saidStop = false).hold,
        )
        assertEquals(
            RuleReadout.Hold.NotLongEnoughYet,
            readout(recent = recent, saidStop = false).hold,
        )
    }

    // ---- what it says -----------------------------------------------------------------------

    @Test
    fun `every feature has a name, one rule, and the same list of what it reads`() {
        val names = kinds.map { RuleReadout.name(it) }
        assertEquals("two features share a name", names.size, names.distinct().size)
        assertTrue(names.all { it.isNotBlank() })
        for (kind in kinds) {
            val feature = readout(kind = kind)
            assertEquals(kind.key, feature.kindKey)
            assertEquals(RuleReadout.name(kind), feature.name)
            assertEquals(RuleReadout.RULE, feature.rule)
            assertEquals(RuleReadout.READS, feature.reads)
        }
        assertTrue("the rule should be one sentence, not none", RuleReadout.RULE.isNotBlank())
        assertTrue("what it reads should be listed", RuleReadout.READS.isNotEmpty())
    }

    @Test
    fun `the current values are labelled, filled in and free of duplicates`() {
        for (kind in kinds) {
            for (declared in frequencies) {
                val feature = readout(kind = kind, declared = declared)
                assertTrue(feature.values.isNotEmpty())
                assertEquals(
                    feature.values.size,
                    feature.values.map { it.label }.distinct().size,
                )
                for (value in feature.values) {
                    assertTrue("blank label on $kind", value.label.isNotBlank())
                    assertTrue("blank value for ${value.label} on $kind", value.value.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `the counts are of asks, and they add up`() {
        val asks = listOf(
            ask(InterruptionBudget.Kind.COMPANION, 9, true),
            ask(InterruptionBudget.Kind.COMPANION, 9, false),
            ask(InterruptionBudget.Kind.COMPANION, 14, true),
            ask(InterruptionBudget.Kind.REMINDER, 14, true),
        )
        val feature = readout(timed = asks)
        assertEquals(3, feature.asksOnRecord)
        assertEquals(2, feature.asksAnswered)
        assertTrue(feature.asksAnswered <= feature.asksOnRecord)
        // Another kind's rows are another kind's business.
        val reminders = readout(kind = InterruptionBudget.Kind.REMINDER, timed = asks)
        assertEquals(1, reminders.asksOnRecord)
    }

    @Test
    fun `how much is being held back is stated, and it is never negative`() {
        for (declared in frequencies) {
            for (reception in InterruptionBudget.Reception.entries) {
                val declaredGap = InterruptionBudget.minimumGapMillis(declared)
                val effectiveGap = InterruptionBudget.minimumGapMillis(
                    InterruptionBudget.effectiveFrequency(declared, reception),
                )
                assertTrue(
                    "declared=$declared reception=$reception",
                    effectiveGap >= declaredGap,
                )
                assertTrue(RuleReadout.heldBackLabel(declaredGap, effectiveGap).isNotBlank())
            }
        }
        assertEquals(
            "Nothing",
            RuleReadout.heldBackLabel(day, day),
        )
        assertEquals(
            "Everything — it is not asking at all",
            RuleReadout.heldBackLabel(day, InterruptionBudget.NEVER_GAP_MILLIS),
        )
        assertEquals(
            "Nothing — you set it to never",
            RuleReadout.heldBackLabel(
                InterruptionBudget.NEVER_GAP_MILLIS,
                InterruptionBudget.NEVER_GAP_MILLIS,
            ),
        )
    }

    @Test
    fun `spans and gaps read as plain english`() {
        assertEquals("Less than a minute", RuleReadout.describeSpan(0L))
        assertEquals("Less than a minute", RuleReadout.describeSpan(-1L))
        assertEquals("1 minute", RuleReadout.describeSpan(60_000L))
        assertEquals("59 minutes", RuleReadout.describeSpan(59 * 60_000L))
        assertEquals("1 hour", RuleReadout.describeSpan(hourMillis))
        assertEquals("23 hours", RuleReadout.describeSpan(23 * hourMillis))
        assertEquals("1 day", RuleReadout.describeSpan(day))
        assertEquals("7 days", RuleReadout.describeSpan(7 * day))
        assertEquals("Never", RuleReadout.describeGap(InterruptionBudget.NEVER_GAP_MILLIS))
        assertEquals("No minimum", RuleReadout.describeGap(0L))
        assertEquals("1 day", RuleReadout.describeGap(day))
    }

    @Test
    fun `a set of hours reads as clock times with runs collapsed`() {
        assertEquals("None", RuleReadout.hoursLabel(emptyList()))
        assertEquals("09:00", RuleReadout.hoursLabel(listOf(9)))
        assertEquals("09:00–11:00", RuleReadout.hoursLabel(listOf(9, 10, 11)))
        assertEquals("09:00, 18:00", RuleReadout.hoursLabel(listOf(9, 18)))
        assertEquals("00:00–01:00, 23:00", RuleReadout.hoursLabel(listOf(23, 0, 1)))
        assertEquals("09:00", RuleReadout.hoursLabel(listOf(9, 9, 9)))
    }

    @Test
    fun `the openers shown are the pool for the hour, and nothing narrower`() {
        for (hour in 0 until TimingGrid.HOURS_IN_DAY) {
            assertEquals(
                PhrasePool.pool(PhrasePool.bandForHour(hour)),
                readout(hour = hour).openers,
            )
        }
        // The readout shows the whole pool rather than a chosen line, because a debug screen that
        // showed "the line it would use" would have to draw one, and a draw needs a rotation the
        // screen does not own.
        assertTrue(readout(hour = 9).openers.size > 1)
    }

    // ---- it describes the rules, never the person -------------------------------------------

    @Test
    fun `nothing in the readout's code is named for a state of a person`() {
        val path = "app/src/main/java/com/daymark/app/stats/RuleReadout.kt"
        val raw = repoFile(path).readText()
        val code = stripKotlinComments(raw)

        // Control: the stripper left the code behind.
        assertTrue("the stripper ate the file", code.contains("object RuleReadout"))
        assertTrue("the stripper ate the file", code.contains("fun feature("))

        // Control: the matcher can find the vocabulary in the raw file, where the header says in so
        // many words what this screen must never show. So an empty result below is the comments
        // coming off, not the matcher failing.
        val vocabulary = listOf("mood", "Mood", "feel", "Feel", "emotion", "level", "Level", "score", "Score")
        assertTrue(
            "the header should say what this screen must not show",
            vocabulary.any { raw.contains(it) },
        )
        assertEquals(
            "RuleReadout's code names a state of a person",
            emptyList<String>(),
            vocabulary.filter { code.contains(it) },
        )

        // Positive control: the same matcher over a planted line must catch it.
        val planted = "Value(\"How you seemed\", moodLabel(level))"
        assertNotEquals(
            emptyList<String>(),
            vocabulary.filter { stripKotlinComments(planted).contains(it) },
        )
    }

    @Test
    fun `no sentence the readout can produce congratulates or scores`() {
        // CLAUDE.md §4 — no green, success, tick, score, streak or congratulation, anywhere. A debug
        // screen is still the product.
        val banned = listOf("well done", "great", "congrat", "streak", "score", "success", "nice work")
        val sentences = ArrayList<String>()
        sentences.add(RuleReadout.RULE)
        sentences.addAll(RuleReadout.READS)
        sentences.addAll(RuleReadout.Hold.entries.map { it.label })
        sentences.addAll(kinds.map { RuleReadout.name(it) })
        sentences.addAll(frequencies.map { RuleReadout.frequencyLabel(it) })
        sentences.addAll(InterruptionBudget.Reception.entries.map { RuleReadout.receptionLabel(it) })
        sentences.addAll(TimingGrid.Basis.entries.map { RuleReadout.basisLabel(it) })
        for (kind in kinds) {
            for (declared in frequencies) {
                val feature = readout(kind = kind, declared = declared)
                sentences.addAll(feature.values.map { it.label })
                sentences.addAll(feature.values.map { it.value })
            }
        }
        assertTrue("nothing was collected to check", sentences.size > 40)
        for (sentence in sentences) {
            val lower = sentence.lowercase()
            for (word in banned) {
                assertFalse("[$sentence] contains \"$word\"", lower.contains(word))
            }
        }
        // Positive control: the matcher can see one when it is there.
        assertTrue(banned.any { "Great work — a 5 day streak!".lowercase().contains(it) })
    }
}
