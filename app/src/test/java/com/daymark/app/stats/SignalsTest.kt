package com.daymark.app.stats

import com.daymark.app.data.entity.OfferOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class SignalsTest {

    /** A neutral baseline: enough data to "speak", but no rule tripped. Tests tweak one field. */
    private fun base() = Signals.Inputs(
        totalEntries = 20,
        avgMood = 3.4,
        moodTodayLevel = 3,
        loggedToday = true,
        currentStreak = 1,
        longestStreak = 5,
        topLift = null,
        topDrag = null,
        monthDeltaPct = null,
        newlyUnlockedAchievement = null,
        dueCheckin = null,
        onThisDayNote = null,
    )

    private fun List<Signals.Signal>.kinds() = map { it.kind }.toSet()

    @Test
    fun emptyHistory_producesNoSignals() {
        val signals = Signals.build(base().copy(totalEntries = 0, avgMood = null))
        assertTrue(signals.isEmpty())
    }

    @Test
    fun baseline_stillOffersSupportMenu_butNoFeedCards() {
        // With nothing tripped, the only signals are the always-available Support-screen options.
        val signals = Signals.build(base())
        assertTrue(signals.all { Signals.Surface.Support in it.surfaces })
        assertTrue("support_breathe" in signals.kinds())
        assertTrue(Signals.forSurface(signals, Signals.Surface.Feed).isEmpty())
    }

    @Test
    fun lowMoodToday_offersSupport_atTopOfFeed() {
        val signals = Signals.build(base().copy(moodTodayLevel = 1))
        val offer = signals.first { it.kind == "support_offer" }
        assertEquals(Signals.Category.Support, offer.category)
        assertTrue(Signals.Surface.Feed in offer.surfaces)
        // It should be the single highest-scoring signal.
        assertEquals("support_offer", signals.maxByOrNull { it.score }!!.kind)
        val feed = Signals.forSurface(signals, Signals.Surface.Feed)
        assertEquals("support_offer", feed.first().kind)
    }

    @Test
    fun notLoggedToday_promptsCheckIn() {
        val signals = Signals.build(base().copy(loggedToday = false, moodTodayLevel = null))
        assertTrue("prompt_log_today" in signals.kinds())
        val prompt = signals.first { it.kind == "prompt_log_today" }
        assertFalse(prompt.dismissible)
        assertEquals(Signals.Action.LogToday, prompt.action)
    }

    @Test
    fun liftFactor_gatedByMinimumDelta() {
        val weak = Signals.build(base().copy(topLift = Signals.FactorLift("Tea", 0.2, 6)))
        assertFalse("lift_factor" in weak.kinds())

        val strong = Signals.build(base().copy(topLift = Signals.FactorLift("Exercise", 0.7, 9)))
        val lift = strong.first { it.kind == "lift_factor" }
        assertEquals(Signals.Action.CreateGoalFromFactor("Exercise"), lift.action)
        assertTrue("Exercise" in lift.title)
    }

    @Test
    fun strongerLift_scoresHigher() {
        val mild = Signals.build(base().copy(topLift = Signals.FactorLift("Walk", 0.5, 8)))
            .first { it.kind == "lift_factor" }.score
        val big = Signals.build(base().copy(topLift = Signals.FactorLift("Walk", 0.9, 8)))
            .first { it.kind == "lift_factor" }.score
        assertTrue(big > mild)
    }

    @Test
    fun dragFactor_isInsightsOnly_neverOnFeed() {
        val signals = Signals.build(base().copy(topDrag = Signals.FactorLift("Poor sleep", -0.6, 7)))
        val drag = signals.first { it.kind == "drag_factor" }
        assertEquals(setOf(Signals.Surface.Insights), drag.surfaces)
        assertFalse("drag_factor" in Signals.forSurface(signals, Signals.Surface.Feed).kinds())
        assertTrue("drag_factor" in Signals.forSurface(signals, Signals.Surface.Insights).kinds())
    }

    @Test
    fun monthUp_celebrates_monthDown_isGentleAndInsightsOnly() {
        val up = Signals.build(base().copy(monthDeltaPct = 22.0))
        assertTrue("month_up" in up.kinds())
        assertTrue(Signals.Surface.Feed in up.first { it.kind == "month_up" }.surfaces)

        val down = Signals.build(base().copy(monthDeltaPct = -30.0))
        val d = down.first { it.kind == "month_down" }
        assertEquals(setOf(Signals.Surface.Insights), d.surfaces)
        // A mild dip trips neither rule.
        assertFalse("month_down" in Signals.build(base().copy(monthDeltaPct = -5.0)).kinds())
    }

    @Test
    fun streakMilestone_firesOnNamedMilestoneAndAllTimeBest() {
        assertTrue("streak_milestone" in Signals.build(base().copy(currentStreak = 7)).kinds())
        // Matching the all-time best (>=3) counts even if not a named milestone.
        assertTrue("streak_milestone" in
            Signals.build(base().copy(currentStreak = 5, longestStreak = 5)).kinds())
        // A non-milestone, non-best streak does not.
        assertFalse("streak_milestone" in
            Signals.build(base().copy(currentStreak = 4, longestStreak = 9)).kinds())
    }

    @Test
    fun dueCheckin_carriesItsName() {
        val signals = Signals.build(base().copy(dueCheckin = "WHO-5"))
        val c = signals.first { it.kind == "checkin_due" }
        assertEquals(Signals.Action.TakeCheckin("WHO-5"), c.action)
        assertTrue("WHO-5" in c.title)
    }

    @Test
    fun forSurface_supportMenu_isContextual_andCrisisAlwaysLast() {
        val withLift = Signals.build(base().copy(topLift = Signals.FactorLift("Running", 0.6, 8)))
        val support = Signals.forSurface(withLift, Signals.Surface.Support)
        assertTrue(support.all { Signals.Surface.Support in it.surfaces })
        // Crisis resource is present and is the lowest-scoring (listed last).
        assertEquals("support_crisis", support.last().kind)
        // The movement option mentions the contextual lift.
        assertTrue("Running" in support.first { it.kind == "support_move" }.body)
    }

    @Test
    fun forSurface_respectsLimit_andOrdering() {
        val signals = Signals.build(
            base().copy(
                loggedToday = false,
                moodTodayLevel = null,
                newlyUnlockedAchievement = "First week",
                topLift = Signals.FactorLift("Friends", 0.6, 10),
            ),
        )
        val feedTop2 = Signals.forSurface(signals, Signals.Surface.Feed, limit = 2)
        assertEquals(2, feedTop2.size)
        // Returned best-first: scores are non-increasing.
        assertTrue(feedTop2[0].score >= feedTop2[1].score)
    }

    @Test
    fun copy_isDeterministic_forSameInputs() {
        val inputs = base().copy(monthDeltaPct = 12.0)
        val a = Signals.build(inputs, Locale.US)
        val b = Signals.build(inputs, Locale.US)
        assertEquals(a.map { it.kind to it.body }, b.map { it.kind to it.body })
    }

    @Test
    fun supportMenu_alwaysAvailable_evenWithNoLift() {
        val menu = Signals.supportMenu(null)
        assertTrue(menu.isNotEmpty())
        assertTrue(menu.all { Signals.Surface.Support in it.surfaces })
        // Crisis resources are present and listed last (lowest score).
        assertEquals("support_crisis", menu.minByOrNull { it.score }!!.kind)
        // With no known lift, the move option uses generic (un-quoted) copy.
        assertFalse("\"" in menu.first { it.kind == "support_move" }.body)
    }

    @Test
    fun supportMenu_personalisesAndPrioritisesMove_whenMovementIsALift() {
        val menu = Signals.supportMenu(Signals.FactorLift("Running", 0.6, 8))
        val move = menu.first { it.kind == "support_move" }
        assertTrue("Running" in move.body)
        // Move now ranks above breathing.
        assertTrue(move.score > menu.first { it.kind == "support_breathe" }.score)
    }
}

/**
 * The companion signal vocabulary — two jobs, kept apart deliberately.
 *
 * **The twin.** `companion/web/src/lib/companion/signals.ts` is the same eight definitions on the
 * other side of the product, and the two are one vocabulary rather than two that resemble each
 * other. The first block below is that file, transcribed as literals: the eight keys in order, the
 * type of each, and Finding 3's author partition. Literals and not a loop over
 * [CompanionSignals.Name], because a test that derives its expectation from the code under test
 * agrees with any rename and catches none.
 *
 * **The boundaries that will actually occur.** A new install with no history at all; one entry
 * logged today; the exact edge of the seven-day window; and a stored timestamp in the future,
 * which happens whenever a clock is corrected, a time zone changes or a backup is restored. None
 * of these is exotic — the first is every person's first day.
 */
class CompanionSignalsTest {

    /* --- the twin ------------------------------------------------------------------------- */

    /** Transcribed from `signals.ts`. Declaration order, which is the order the twin exports. */
    private val vocabulary = listOf(
        "daysSinceLastOpen",
        "daysSinceLastCheckIn",
        "checkInsLast7",
        "hardDaysLast7",
        "hasSafetyPlan",
        "prescribedModules",
        "lastOfferOutcome",
        "timeOfDay",
    )

    @Test
    fun theVocabularyIsClosed_andIsExactlyTheEightTheTwinDeclares() {
        assertEquals(vocabulary, CompanionSignals.Name.entries.map { it.key })
        // Spelled out as well as compared: a ninth signal is a design decision, not a patch, and
        // this line is where someone has to come and argue for it.
        assertEquals(8, CompanionSignals.Name.entries.size)
        assertEquals(1, CompanionSignals.VOCABULARY_VERSION)
    }

    @Test
    fun everySignalCarriesTheTypeTheTwinDeclares() {
        assertEquals(
            mapOf(
                "daysSinceLastOpen" to "int",
                "daysSinceLastCheckIn" to "int",
                "checkInsLast7" to "int",
                "hardDaysLast7" to "int",
                "hasSafetyPlan" to "bool",
                "prescribedModules" to "stringArray",
                "lastOfferOutcome" to "enum",
                "timeOfDay" to "enum",
            ),
            CompanionSignals.Name.entries.associate { it.key to it.type.key },
        )
    }

    @Test
    fun aTherapistMayBranchOnPrescribedModulesAndTimeOfDayOnly() {
        assertEquals(
            listOf("prescribedModules", "timeOfDay"),
            CompanionSignals.namesFor(CompanionSignals.Author.THERAPIST).map { it.key },
        )
    }

    @Test
    fun theAppMayBranchOnAllEight() {
        assertEquals(vocabulary, CompanionSignals.namesFor(CompanionSignals.Author.APP).map { it.key })
    }

    @Test
    fun hasSafetyPlanIsTheOnlyNeverDisclosableSignal_andIsAppOnlyToo() {
        assertEquals(
            listOf(CompanionSignals.Name.HAS_SAFETY_PLAN),
            CompanionSignals.Name.entries.filter { it.neverDisclosable },
        )
        // Never-disclosable is a prohibition on top of the partition, not instead of one: if the
        // partition were ever widened by consent, this signal still may not be branched on.
        assertFalse(
            CompanionSignals.mayBranchOn(
                CompanionSignals.Name.HAS_SAFETY_PLAN,
                CompanionSignals.Author.THERAPIST,
            ),
        )
    }

    @Test
    fun everyAppOnlySignalIsRefusedToATherapist() {
        val appOnly = CompanionSignals.Name.entries
            .filterNot { CompanionSignals.Author.THERAPIST in it.authors }
        assertEquals(6, appOnly.size)
        appOnly.forEach {
            assertFalse(it.key, CompanionSignals.mayBranchOn(it, CompanionSignals.Author.THERAPIST))
        }
    }

    @Test
    fun anUnknownKeyOrRoleResolvesToNull_neverToAPermissiveDefault() {
        assertNull(CompanionSignals.Name.fromKey("hardDaysLast30"))
        assertNull(CompanionSignals.Name.fromKey("HardDaysLast7"))
        assertNull(CompanionSignals.Name.fromKey(null))
        assertNull(CompanionSignals.Author.fromKey("clinician"))
        assertNull(CompanionSignals.Author.fromKey(null))
        assertEquals(CompanionSignals.Name.HARD_DAYS_LAST_7, CompanionSignals.Name.fromKey("hardDaysLast7"))
    }

    @Test
    fun theOfferOutcomeKeysAreTheLedgersOwnColumn() {
        // Spelled out here rather than imported into stats/, so this is the check that they agree.
        assertEquals(OfferOutcome.entries.map { it.key }, CompanionSignals.OFFER_OUTCOME_KEYS)
    }

    /* --- nothing here names a state --------------------------------------------------------- */

    /**
     * Every name this module exposes, at every nesting depth — object, enums, data classes, their
     * members and their generated accessors.
     */
    private fun exposedNames(): List<String> {
        val names = mutableListOf<String>()
        val seen = mutableSetOf<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.add(CompanionSignals::class.java)
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            if (!seen.add(type)) continue
            names.add(type.simpleName)
            type.declaredMethods.forEach { names.add(it.name) }
            type.declaredFields.forEach { names.add(it.name) }
            type.declaredClasses.forEach { queue.add(it) }
        }
        return names
    }

    /**
     * §D1b, reflect-never-label: this module hands someone their own counts back, and the drift
     * that would break that has a shape — a member named for a state rather than for a tally.
     * `hardDaysLast7` is a count of days the person logged in the lower bands of the scale they
     * picked from; the moment something here is called `isDepressed`, `riskLevel` or `severity`,
     * the count has become a claim and this file has made a clinical judgement with no instrument,
     * no validation and no consent.
     */
    @Test
    fun noNameInThisModuleImpliesAClinicalLabel() {
        val forbidden = listOf(
            "depress", "anxi", "diagnos", "symptom", "disorder", "suicid", "harm", "relapse",
            "severity", "severe", "clinical", "distress", "psycho", "bipolar", "mania", "trauma",
            "unwell", "crisis", "episode", "risk", "deteriorat", "unstable", "impair",
        )
        val offenders = exposedNames()
            .filter { name -> forbidden.any { it in name.lowercase() } }
            .distinct()
            .sorted()
        assertEquals(emptyList<String>(), offenders)
        // The scan is worthless if it looks at nothing; these are names it must be seeing.
        assertTrue("hardDaysLast7" in exposedNames().map { it })
        assertTrue("LOWER_BAND_MAX_LEVEL" in exposedNames())
    }

    /* --- computing the values --------------------------------------------------------------- */

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val today: LocalDate = LocalDate.parse("2026-08-15")

    /** Epoch millis at [hour]:00 on [date], in the person's zone. */
    private fun millisOn(date: LocalDate, hour: Int = 12): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun clockOn(date: LocalDate, hour: Int = 12): Clock =
        Clock.fixed(Instant.ofEpochMilli(millisOn(date, hour)), zone)

    private fun checkInOn(date: LocalDate, hour: Int = 12, level: Int = 3) =
        CompanionSignals.CheckIn(millisOn(date, hour), level)

    private fun compute(
        inputs: CompanionSignals.Inputs,
        clock: Clock = clockOn(today),
    ): CompanionSignals.Values = CompanionSignals.compute(inputs, clock)

    @Test
    fun emptyHistory_everySignalHasASensibleValue_andNothingThrows() {
        val values = compute(CompanionSignals.Inputs(), clockOn(today, hour = 9))

        // Null, not zero. "0 days since" is *today*; telling someone on their first morning that
        // it has been a while would be both wrong and unkind.
        assertNull(values.daysSinceLastOpen)
        assertNull(values.daysSinceLastCheckIn)
        assertEquals(0, values.checkInsLast7)
        assertEquals(0, values.hardDaysLast7)
        assertFalse(values.hasSafetyPlan)
        assertEquals(emptyList<String>(), values.prescribedModules)
        assertNull(values.lastOfferOutcome)
        assertEquals(CompanionSignals.TimeOfDay.MORNING, values.timeOfDay)
    }

    @Test
    fun emptyHistory_omitsTheFactsThatDoNotExistYet_soAPredicateOnThemFailsClosed() {
        val answers = compute(CompanionSignals.Inputs()).asAnswers()
        assertEquals(
            listOf("checkInsLast7", "hardDaysLast7", "hasSafetyPlan", "prescribedModules", "timeOfDay"),
            answers.keys.toList(),
        )
        // The evaluator fails an absent ref closed, so the dialogue drifts to its fallback line —
        // the most ordinary thing to say — rather than branching on a stand-in number.
        assertFalse("daysSinceLastOpen" in answers)
        assertFalse("daysSinceLastCheckIn" in answers)
        assertFalse("lastOfferOutcome" in answers)
    }

    @Test
    fun oneCheckInToday() {
        val values = compute(
            CompanionSignals.Inputs(
                lastOpenedAtMillis = millisOn(today, 19),
                checkIns = listOf(checkInOn(today, hour = 19, level = 2)),
            ),
            clockOn(today, hour = 20),
        )
        assertEquals(0, values.daysSinceLastOpen)
        assertEquals(0, values.daysSinceLastCheckIn)
        assertEquals(1, values.checkInsLast7)
        assertEquals(1, values.hardDaysLast7)
        assertEquals(CompanionSignals.TimeOfDay.EVENING, values.timeOfDay)
    }

    @Test
    fun theWindowIsTodayAndTheSixDaysBefore() {
        val tenDays = (0..9).map { checkInOn(today.minusDays(it.toLong()), level = 1) }
        val values = compute(CompanionSignals.Inputs(checkIns = tenDays))
        assertEquals(7, values.checkInsLast7)
        assertEquals(7, values.hardDaysLast7)
    }

    @Test
    fun sixDaysAgoIsInsideTheWindow_sevenDaysAgoIsOutsideIt() {
        val inside = compute(
            CompanionSignals.Inputs(checkIns = listOf(checkInOn(today.minusDays(6), hour = 0, level = 1))),
        )
        assertEquals(1, inside.checkInsLast7)
        assertEquals(1, inside.hardDaysLast7)
        assertEquals(6, inside.daysSinceLastCheckIn)

        val outside = compute(
            CompanionSignals.Inputs(checkIns = listOf(checkInOn(today.minusDays(7), hour = 23, level = 1))),
        )
        assertEquals(0, outside.checkInsLast7)
        assertEquals(0, outside.hardDaysLast7)
        // Out of the window is not out of the history: it is still the last one there was.
        assertEquals(7, outside.daysSinceLastCheckIn)
    }

    @Test
    fun hardDaysLast7_countsDays_notEntries() {
        val threeOnOneDay = listOf(
            checkInOn(today, hour = 8, level = 1),
            checkInOn(today, hour = 13, level = 2),
            checkInOn(today, hour = 21, level = 1),
        )
        val values = compute(CompanionSignals.Inputs(checkIns = threeOnOneDay))
        assertEquals(3, values.checkInsLast7)
        assertEquals(1, values.hardDaysLast7)
    }

    @Test
    fun hardDaysLast7_neverExceedsTheWindow_evenWithTheClockWrong() {
        val twoADayForAFortnight = (0..13).flatMap {
            listOf(
                checkInOn(today.minusDays(it.toLong()), hour = 9, level = 1),
                checkInOn(today.minusDays(it.toLong()), hour = 18, level = 2),
            )
        }
        // Plus one dated tomorrow, which is what a corrected clock leaves behind. It must fold onto
        // today rather than becoming an eighth day in a seven-day count.
        val withSkew = twoADayForAFortnight + checkInOn(today.plusDays(1), hour = 9, level = 1)
        val values = compute(CompanionSignals.Inputs(checkIns = withSkew))
        assertEquals(CompanionSignals.WINDOW_DAYS, values.hardDaysLast7)
    }

    @Test
    fun hardDaysLast7_countsTheLowerBandsOnly_andNothingOffTheScale() {
        val upperBands = (3..5).map { checkInOn(today, hour = it, level = it) }
        assertEquals(0, compute(CompanionSignals.Inputs(checkIns = upperBands)).hardDaysLast7)

        // A level outside 1..5 is not data about a mood band, so it counts as neither.
        val offScale = listOf(checkInOn(today, hour = 8, level = 0), checkInOn(today, hour = 9, level = 9))
        val values = compute(CompanionSignals.Inputs(checkIns = offScale))
        assertEquals(2, values.checkInsLast7)
        assertEquals(0, values.hardDaysLast7)

        assertEquals(2, CompanionSignals.LOWER_BAND_MAX_LEVEL)
        val lowerBands = listOf(checkInOn(today.minusDays(1), level = 1), checkInOn(today, level = 2))
        assertEquals(2, compute(CompanionSignals.Inputs(checkIns = lowerBands)).hardDaysLast7)
    }

    @Test
    fun checkInsLast7_countsEntries_andIsNotAStreak() {
        // Five entries with a two-day gap in the middle. A streak would say one; this says five,
        // because there is no run here to break (§D6).
        val entries = listOf(0L, 1L, 4L, 5L, 6L).map { checkInOn(today.minusDays(it)) }
        assertEquals(5, compute(CompanionSignals.Inputs(checkIns = entries)).checkInsLast7)
    }

    @Test
    fun aTimestampInTheFutureReadsAsNow() {
        val tomorrow = millisOn(today.plusDays(1), hour = 12)
        val values = compute(
            CompanionSignals.Inputs(
                lastOpenedAtMillis = tomorrow,
                checkIns = listOf(
                    CompanionSignals.CheckIn(tomorrow, moodLevel = 1),
                    checkInOn(today, hour = 9, level = 1),
                ),
            ),
        )
        assertEquals(0, values.daysSinceLastOpen)
        assertEquals(0, values.daysSinceLastCheckIn)
        // Both still counted: a corrected clock must not quietly discount what the person logged.
        assertEquals(2, values.checkInsLast7)
        // ...and the one dated tomorrow falls on today, rather than being a second hard day. This
        // is the assertion that pins the clamp: without it the future entry is its own day.
        assertEquals(1, values.hardDaysLast7)
    }

    @Test
    fun noSkew_forwardOrBackward_producesANegativeOrNonsenseDayCount() {
        val skews = listOf(
            millisOn(today.plusDays(1)),
            millisOn(today.plusDays(400)),
            millisOn(today.plusYears(50)),
            Long.MAX_VALUE,
            Long.MIN_VALUE,
            0L,
            -1L,
        )
        for (skew in skews) {
            val values = compute(
                CompanionSignals.Inputs(
                    lastOpenedAtMillis = skew,
                    checkIns = listOf(CompanionSignals.CheckIn(skew, moodLevel = 1)),
                ),
            )
            values.daysSinceLastOpen?.let { assertTrue("open at $skew: $it", it >= 0) }
            values.daysSinceLastCheckIn?.let { assertTrue("check-in at $skew: $it", it >= 0) }
            assertTrue(values.checkInsLast7 >= 0)
            assertTrue(values.hardDaysLast7 in 0..CompanionSignals.WINDOW_DAYS)
        }
    }

    @Test
    fun zeroOrNegativeLastOpened_readsAsNever_notAsNineteenSeventy() {
        assertNull(compute(CompanionSignals.Inputs(lastOpenedAtMillis = 0L)).daysSinceLastOpen)
        assertNull(compute(CompanionSignals.Inputs(lastOpenedAtMillis = -1L)).daysSinceLastOpen)
        assertNull(compute(CompanionSignals.Inputs(lastOpenedAtMillis = null)).daysSinceLastOpen)
    }

    @Test
    fun daysAreCalendarDaysInThePersonsOwnZone_notFixedTwentyFourHourBlocks() {
        // 23:30 last night to 00:30 this morning is one hour, and it is also one day ago.
        val justAfterMidnight = Clock.fixed(
            Instant.ofEpochMilli(today.atTime(0, 30).atZone(zone).toInstant().toEpochMilli()),
            zone,
        )
        val lastNight = today.minusDays(1).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val values = CompanionSignals.compute(
            CompanionSignals.Inputs(lastOpenedAtMillis = lastNight),
            justAfterMidnight,
        )
        assertEquals(1, values.daysSinceLastOpen)
    }

    @Test
    fun anUnrecognisedOutcomeKeyIsOmitted_soAPredicateOnItFailsClosed() {
        for (unknown in listOf("STOP", "accepted ", "", "ignored", "Accepted")) {
            val values = compute(CompanionSignals.Inputs(lastOfferOutcomeKey = unknown))
            assertNull(unknown, values.lastOfferOutcome)
            assertFalse(unknown, "lastOfferOutcome" in values.asAnswers())
        }
        for (known in CompanionSignals.OFFER_OUTCOME_KEYS) {
            assertEquals(known, compute(CompanionSignals.Inputs(lastOfferOutcomeKey = known)).lastOfferOutcome)
        }
    }

    @Test
    fun prescribedModules_dropBlanksAndDuplicates_keepingTheOrderTheyCameIn() {
        val values = compute(
            CompanionSignals.Inputs(
                prescribedModuleIds = listOf(
                    "compassion-hard-moment", "", "   ", "compassion-hard-moment", "values-what-matters",
                ),
            ),
        )
        assertEquals(listOf("compassion-hard-moment", "values-what-matters"), values.prescribedModules)
    }

    @Test
    fun timeOfDay_readsTheHourInThePersonsOwnZone() {
        val bands = listOf(
            0 to CompanionSignals.TimeOfDay.NIGHT,
            4 to CompanionSignals.TimeOfDay.NIGHT,
            5 to CompanionSignals.TimeOfDay.MORNING,
            11 to CompanionSignals.TimeOfDay.MORNING,
            12 to CompanionSignals.TimeOfDay.DAY,
            16 to CompanionSignals.TimeOfDay.DAY,
            17 to CompanionSignals.TimeOfDay.EVENING,
            21 to CompanionSignals.TimeOfDay.EVENING,
            22 to CompanionSignals.TimeOfDay.NIGHT,
            23 to CompanionSignals.TimeOfDay.NIGHT,
        )
        for ((hour, band) in bands) {
            assertEquals("hour $hour", band, compute(CompanionSignals.Inputs(), clockOn(today, hour)).timeOfDay)
        }
    }

    @Test
    fun timeOfDayNeverDisagreesWithTheGreetingTheHeaderIsShowing() {
        for (hour in 0..23) {
            val band = CompanionSignals.TimeOfDay.forHour(hour)
            val greeting = Greeting.forHour(hour)
            val expected = when (band) {
                CompanionSignals.TimeOfDay.MORNING -> "Good morning"
                CompanionSignals.TimeOfDay.DAY -> "Good afternoon"
                CompanionSignals.TimeOfDay.EVENING -> "Good evening"
                CompanionSignals.TimeOfDay.NIGHT -> "Hello"
            }
            assertEquals("hour $hour", expected, greeting)
        }
    }

    @Test
    fun asAnswers_isKeyedByTheVocabulary_inTheShapesItDeclares() {
        val values = compute(
            CompanionSignals.Inputs(
                lastOpenedAtMillis = millisOn(today.minusDays(2)),
                checkIns = listOf(checkInOn(today.minusDays(1), level = 1)),
                hasSafetyPlan = true,
                prescribedModuleIds = listOf("compassion-hard-moment"),
                lastOfferOutcomeKey = "snoozed",
            ),
            clockOn(today, hour = 18),
        )
        val answers = values.asAnswers()

        assertEquals(vocabulary, answers.keys.toList())
        assertTrue(answers["daysSinceLastOpen"] is Int)
        assertTrue(answers["daysSinceLastCheckIn"] is Int)
        assertTrue(answers["checkInsLast7"] is Int)
        assertTrue(answers["hardDaysLast7"] is Int)
        // A real Boolean: the authored predicate asks `gte 1` and the evaluator coerces, so both a
        // boolean and a 0/1 host read correctly.
        assertEquals(true, answers["hasSafetyPlan"])
        assertEquals(listOf("compassion-hard-moment"), answers["prescribedModules"])
        assertEquals("snoozed", answers["lastOfferOutcome"])
        // The enums cross the boundary as their authored keys, never as Kotlin enum names.
        assertEquals("evening", answers["timeOfDay"])
    }

    @Test
    fun asAnswers_neverCarriesAKeyOutsideTheVocabulary() {
        val everything = CompanionSignals.Inputs(
            lastOpenedAtMillis = millisOn(today),
            checkIns = listOf(checkInOn(today, level = 1)),
            hasSafetyPlan = true,
            prescribedModuleIds = listOf("values-what-matters"),
            lastOfferOutcomeKey = "stop",
        )
        assertTrue(compute(everything).asAnswers().keys.all { CompanionSignals.Name.fromKey(it) != null })
    }

    @Test
    fun computeIsPure_theSameInputsGiveTheSameValues() {
        val inputs = CompanionSignals.Inputs(
            lastOpenedAtMillis = millisOn(today.minusDays(3)),
            checkIns = (0..4).map { checkInOn(today.minusDays(it.toLong()), level = it % 5 + 1) },
            hasSafetyPlan = true,
            prescribedModuleIds = listOf("compassion-hard-moment"),
            lastOfferOutcomeKey = "dismissed",
        )
        val clock = clockOn(today, hour = 14)
        assertEquals(CompanionSignals.compute(inputs, clock), CompanionSignals.compute(inputs, clock))
    }

    @Test
    fun theOrderCheckInsArriveInDoesNotChangeAnything() {
        val checkIns = (0..8).map { checkInOn(today.minusDays(it.toLong()), level = if (it % 2 == 0) 1 else 4) }
        val forwards = compute(CompanionSignals.Inputs(checkIns = checkIns))
        val backwards = compute(CompanionSignals.Inputs(checkIns = checkIns.reversed()))
        assertEquals(forwards, backwards)
    }
}
