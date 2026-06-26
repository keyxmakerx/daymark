package com.daymark.app.ui.navigation

import androidx.annotation.DrawableRes
import com.daymark.app.R

object Routes {
    const val HOME = "home"
    const val CALENDAR = "calendar"
    const val STATS = "stats"
    const val JOURNAL = "journal"
    const val SETTINGS = "settings"
    const val ACTIVITIES = "activities"
    const val ACTIVITY_LIBRARY = "activity_library"
    const val MORE = "more"
    const val ENTRY = "entry"
    const val JOURNAL_ENTRY = "journal_entry"
    const val YEAR_PIXELS = "year_pixels"
    const val GOALS = "goals"
    const val GOAL = "goal"

    /** Editor route; pass 0 to create a new entry, and an optional 1..5 mood to preselect. */
    fun entry(id: Long = 0L, mood: Int = -1) = "$ENTRY/$id?mood=$mood"
    const val ENTRY_PATTERN = "$ENTRY/{entryId}?mood={mood}"

    /** Goal editor route; pass 0 to create a new goal. */
    fun goal(id: Long = 0L) = "$GOAL/$id"
    const val GOAL_PATTERN = "$GOAL/{goalId}"

    /** Journal editor route; pass 0 to create a new journal entry. */
    fun journalEntry(id: Long = 0L) = "$JOURNAL_ENTRY/$id"
    const val JOURNAL_ENTRY_PATTERN = "$JOURNAL_ENTRY/{journalId}"
}

enum class TopLevelDestination(
    val route: String,
    val label: String,
    @param:DrawableRes val icon: Int,
) {
    HOME(Routes.HOME, "Home", R.drawable.ic_ui_home),
    CALENDAR(Routes.CALENDAR, "Calendar", R.drawable.ic_ui_calendar),
    JOURNAL(Routes.JOURNAL, "Journal", R.drawable.ic_ui_journal),
    STATS(Routes.STATS, "Stats", R.drawable.ic_ui_chart),
    MORE(Routes.MORE, "More", R.drawable.ic_ui_more),
}
