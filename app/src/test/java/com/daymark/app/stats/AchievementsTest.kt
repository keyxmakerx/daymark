package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {

    private fun inputs(
        entries: Int = 0,
        longest: Int = 0,
        activities: Int = 0,
        checkIns: Int = 0,
    ) = Achievements.Inputs(entries, longest, activities, checkIns)

    @Test
    fun nothingUnlockedAtZero() {
        assertTrue(Achievements.evaluate(inputs()).isEmpty())
    }

    @Test
    fun firstEntryUnlocksAtOne() {
        val u = Achievements.evaluate(inputs(entries = 1))
        assertTrue("first_entry" in u)
        assertFalse("entries_25" in u)
    }

    @Test
    fun streakThresholds() {
        assertTrue("streak_7" in Achievements.evaluate(inputs(longest = 7)))
        assertFalse("streak_30" in Achievements.evaluate(inputs(longest = 7)))
        val big = Achievements.evaluate(inputs(longest = 100))
        assertTrue("streak_7" in big && "streak_30" in big && "streak_100" in big)
    }

    @Test
    fun explorerAndCheckin() {
        assertTrue("explorer_10" in Achievements.evaluate(inputs(activities = 10)))
        assertTrue("checkin_first" in Achievements.evaluate(inputs(checkIns = 1)))
    }

    @Test
    fun everyRuleHasACatalogEntry() {
        val catalogIds = Achievements.CATALOG.map { it.id }.toSet()
        // Evaluate a maxed-out input: every satisfiable id must be a real catalog entry.
        val all = Achievements.evaluate(inputs(entries = 1000, longest = 1000, activities = 100, checkIns = 100))
        assertEquals(catalogIds, all)
    }
}
