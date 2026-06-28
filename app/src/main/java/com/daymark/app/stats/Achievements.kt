package com.daymark.app.stats

/**
 * Pure achievement catalogue + evaluation. Gentle and encouraging by design — milestones for
 * showing up, never streak-shaming or punishing a missed day. Free of Android/Room types so it's
 * unit-testable; the unlock timestamps and persistence live in the data layer.
 */
object Achievements {

    data class Achievement(val id: String, val title: String, val description: String)

    /** The signals achievements are derived from — all already tracked locally. */
    data class Inputs(
        val totalEntries: Int,
        val longestStreak: Int,
        val distinctActivities: Int,
        val checkInsTaken: Int,
    )

    val CATALOG: List<Achievement> = listOf(
        Achievement("first_entry", "First step", "Logged your first entry"),
        Achievement("entries_25", "Getting the habit", "Logged 25 entries"),
        Achievement("entries_100", "Hundred moments", "Logged 100 entries"),
        Achievement("entries_365", "A year of moments", "Logged 365 entries"),
        Achievement("streak_7", "A steady week", "A 7-day logging streak"),
        Achievement("streak_30", "A month of showing up", "A 30-day logging streak"),
        Achievement("streak_100", "Hundred-day streak", "A 100-day logging streak"),
        Achievement("explorer_10", "Explorer", "Used 10 different activities"),
        Achievement("checkin_first", "Checking in", "Took a wellbeing check-in"),
    )

    private val RULES: Map<String, (Inputs) -> Boolean> = mapOf(
        "first_entry" to { it.totalEntries >= 1 },
        "entries_25" to { it.totalEntries >= 25 },
        "entries_100" to { it.totalEntries >= 100 },
        "entries_365" to { it.totalEntries >= 365 },
        "streak_7" to { it.longestStreak >= 7 },
        "streak_30" to { it.longestStreak >= 30 },
        "streak_100" to { it.longestStreak >= 100 },
        "explorer_10" to { it.distinctActivities >= 10 },
        "checkin_first" to { it.checkInsTaken >= 1 },
    )

    /** The set of achievement ids unlocked for the given [inputs]. */
    fun evaluate(inputs: Inputs): Set<String> =
        RULES.filterValues { it(inputs) }.keys
}
