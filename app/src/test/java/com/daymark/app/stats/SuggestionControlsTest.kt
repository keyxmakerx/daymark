package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionControlsTest {

    private val now = 1_700_000_000_000L

    private fun signal(kind: String, score: Double) = Signals.Signal(
        kind = kind,
        category = Signals.Category.Insight,
        score = score,
        title = kind,
        body = "",
        action = null,
        dismissible = true,
        surfaces = setOf(Signals.Surface.Feed),
    )

    @Test
    fun everyGroupedKindMapsBackToItsGroup() {
        SuggestionControls.GROUPS.forEach { group ->
            group.kinds.forEach { kind ->
                assertEquals(group.key, SuggestionControls.groupKeyOf(kind))
            }
        }
    }

    @Test
    fun groupKeysAndKindsAreUnique() {
        val keys = SuggestionControls.GROUPS.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        val kinds = SuggestionControls.GROUPS.flatMap { it.kinds }
        assertEquals(kinds.size, kinds.toSet().size)
    }

    @Test
    fun everyControllableSignalKindTheEngineCanEmitHasAGroup() {
        // Guards against adding a rule to Signals.build and silently leaving it uncontrollable.
        val emitted = Signals.build(
            Signals.Inputs(
                totalEntries = 40,
                avgMood = 3.4,
                moodTodayLevel = 1,
                loggedToday = false,
                currentStreak = 7,
                longestStreak = 7,
                topLift = Signals.FactorLift("Walk", 0.9, 12),
                topDrag = Signals.FactorLift("Late night", -0.8, 11),
                monthDeltaPct = 20.0,
                newlyUnlockedAchievement = "First week",
                dueCheckin = "WHO-5",
                onThisDayNote = "a note",
            ),
        )
        val ungrouped = emitted
            .filterNot { it.surfaces == setOf(Signals.Surface.Support) }
            .map { it.kind }
            .filter { SuggestionControls.groupKeyOf(it) == null }
        assertEquals(emptyList<String>(), ungrouped)
        // The month-down rule can't co-occur with month-up, so cover it separately.
        val down = Signals.build(
            Signals.Inputs(
                totalEntries = 40, avgMood = 3.0, moodTodayLevel = 3, loggedToday = true,
                currentStreak = 1, longestStreak = 5, topLift = null, topDrag = null,
                monthDeltaPct = -30.0, newlyUnlockedAchievement = null, dueCheckin = null,
                onThisDayNote = null,
            ),
        )
        down.filterNot { it.surfaces == setOf(Signals.Surface.Support) }.forEach {
            assertTrue(it.kind, SuggestionControls.groupKeyOf(it.kind) != null)
        }
    }

    @Test
    fun turnOffDropsEveryKindInTheGroup() {
        val controls = SuggestionControls.Controls(
            mapOf("milestones" to SuggestionControls.State(off = true)),
        )
        val out = SuggestionControls.filter(
            listOf(
                signal("streak_milestone", 65.0),
                signal("achievement_unlocked", 72.0),
                signal("checkin_due", 54.0),
            ),
            controls,
            now,
        )
        assertEquals(listOf("checkin_due"), out.map { it.kind })
    }

    @Test
    fun snoozeHidesUntilItExpiresThenTheCardComesBack() {
        val state = SuggestionControls.apply(
            SuggestionControls.State(), SuggestionControls.Action.RemindLater, now,
        )
        val controls = SuggestionControls.Controls(mapOf("self_checks" to state))
        val input = listOf(signal("checkin_due", 54.0))

        assertTrue(SuggestionControls.filter(input, controls, now).isEmpty())
        assertTrue(
            SuggestionControls.filter(input, controls, now + SuggestionControls.REMIND_LATER_MILLIS - 1).isEmpty(),
        )
        assertEquals(
            1,
            SuggestionControls.filter(input, controls, now + SuggestionControls.REMIND_LATER_MILLIS).size,
        )
    }

    @Test
    fun showLessDemotesRatherThanSilences() {
        val state = SuggestionControls.apply(
            SuggestionControls.State(), SuggestionControls.Action.ShowLess, now,
        )
        assertEquals(1, state.damping)
        // It also snoozes, so look past that window to see the demotion itself.
        val later = now + SuggestionControls.SHOW_LESS_MILLIS
        val out = SuggestionControls.filter(
            listOf(signal("checkin_due", 54.0), signal("on_this_day", 44.0)),
            SuggestionControls.Controls(mapOf("self_checks" to state)),
            later,
        )
        assertEquals(2, out.size)
        assertEquals(54.0 - SuggestionControls.DAMPING_STEP, out.first { it.kind == "checkin_due" }.score, 0.0001)
    }

    @Test
    fun repeatedShowLessReordersTheFeedAndThenStopsCounting() {
        var state = SuggestionControls.State()
        repeat(5) { state = SuggestionControls.apply(state, SuggestionControls.Action.ShowLess, now) }
        assertEquals(SuggestionControls.MAX_DAMPING, state.damping)

        val later = now + SuggestionControls.SHOW_LESS_MILLIS
        val out = SuggestionControls.filter(
            listOf(signal("checkin_due", 54.0), signal("on_this_day", 44.0)),
            SuggestionControls.Controls(mapOf("self_checks" to state)),
            later,
        )
        // 54 - 3*12 = 18, so the demoted card now ranks below the untouched one.
        assertEquals(listOf("on_this_day", "checkin_due"), out.map { it.kind })
    }

    @Test
    fun snoozesNeverShortenAnExistingOne() {
        val hidden = SuggestionControls.apply(
            SuggestionControls.State(), SuggestionControls.Action.Hide, now,
        )
        val thenRemindLater = SuggestionControls.apply(
            hidden, SuggestionControls.Action.RemindLater, now,
        )
        assertEquals(hidden.snoozedUntil, thenRemindLater.snoozedUntil)
    }

    @Test
    fun turningBackOnClearsEverythingHoldingItBack() {
        var state = SuggestionControls.State()
        state = SuggestionControls.apply(state, SuggestionControls.Action.ShowLess, now)
        state = SuggestionControls.apply(state, SuggestionControls.Action.Hide, now)
        state = SuggestionControls.apply(state, SuggestionControls.Action.TurnOff, now)
        assertFalse(state.isDefault)

        val revived = SuggestionControls.turnOn()
        assertTrue(revived.isDefault)
        assertEquals(
            1,
            SuggestionControls.filter(
                listOf(signal("checkin_due", 54.0)),
                SuggestionControls.Controls(mapOf("self_checks" to revived)),
                now,
            ).size,
        )
    }

    @Test
    fun theSupportMenuIsNotControllable() {
        // You open "what might help" on purpose — nothing there is pushed at you, so nothing there
        // can be turned off, even if every group is off.
        val allOff = SuggestionControls.Controls(
            SuggestionControls.GROUPS.associate { it.key to SuggestionControls.State(off = true) },
        )
        val menu = Signals.supportMenu(topLift = null)
        assertEquals(menu.size, SuggestionControls.filter(menu, allOff, now).size)
    }

    @Test
    fun snoozeSummaryIsNullWhenNotSnoozed() {
        assertNull(SuggestionControls.snoozeSummary(SuggestionControls.State(), now))
        assertNull(
            SuggestionControls.snoozeSummary(SuggestionControls.State(snoozedUntil = now), now),
        )
    }

    @Test
    fun snoozeSummaryRoundsUpSoItNeverPromisesTooSoon() {
        fun summaryIn(millis: Long) =
            SuggestionControls.snoozeSummary(SuggestionControls.State(snoozedUntil = now + millis), now)

        val hour = 3_600_000L
        val day = 24 * hour
        assertEquals("back within the hour", summaryIn(1))
        assertEquals("back within the hour", summaryIn(hour))
        assertEquals("back in 2h", summaryIn(hour + 1))
        assertEquals("back in 4h", summaryIn(4 * hour))
        assertEquals("back in 24h", summaryIn(day - 1))
        assertEquals("back tomorrow", summaryIn(day))
        assertEquals("back tomorrow", summaryIn(2 * day))
        assertEquals("back in 3 days", summaryIn(2 * day + 1))
        assertEquals("back in 30 days", summaryIn(SuggestionControls.HIDE_MILLIS))
    }
}
