package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
