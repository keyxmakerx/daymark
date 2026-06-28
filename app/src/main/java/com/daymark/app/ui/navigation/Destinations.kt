package com.daymark.app.ui.navigation

import androidx.annotation.DrawableRes
import com.daymark.app.R

object Routes {
    const val HOME = "home"
    const val CALENDAR = "calendar"
    const val STATS = "stats"
    const val INSIGHTS = "insights"
    const val JOURNAL = "journal"
    const val SETTINGS = "settings"
    const val ACTIVITIES = "activities"
    const val REMINDERS = "reminders"
    const val CUSTOMIZE_MOODS = "customize_moods"
    const val ACTIVITY_LIBRARY = "activity_library"
    const val SEARCH = "search"
    const val TRACKERS = "trackers"
    const val TRACKER = "tracker"

    /** Tracker detail/log screen, keyed by tracker id. */
    fun tracker(id: Long) = "$TRACKER/$id"
    const val TRACKER_PATTERN = "$TRACKER/{trackerId}"
    const val MORE = "more"
    const val GENTLE_SUPPORT = "gentle_support"
    const val SUPPORT = "support"
    const val SUPPORT_BREATHE = "support_breathe"
    const val CRISIS = "crisis"
    const val SLEEP = "sleep"
    const val SLEEP_LOG = "sleep_log"
    const val SLEEP_SETUP = "sleep_setup"
    const val BREATHING = "breathing"
    const val TREATMENTS = "treatments"
    const val TREATMENT = "treatment"
    const val SCREENER = "screener"

    /** Treatment before/after detail, keyed by treatment id. */
    fun treatment(id: Long) = "$TREATMENT/$id"
    const val TREATMENT_PATTERN = "$TREATMENT/{treatmentId}"

    /** Sleep self-check questionnaire route, keyed by screener id. */
    fun screener(key: String) = "$SCREENER/$key"
    const val SCREENER_PATTERN = "$SCREENER/{screenerKey}"
    const val ASSESSMENTS = "assessments"
    const val ACHIEVEMENTS = "achievements"
    const val ASSESSMENT = "assessment"
    fun assessment(key: String) = "$ASSESSMENT/$key"
    const val ASSESSMENT_PATTERN = "$ASSESSMENT/{assessmentKey}"
    const val ENTRY = "entry"
    const val JOURNAL_ENTRY = "journal_entry"
    const val YEAR_PIXELS = "year_pixels"
    const val GOALS = "goals"

    /** A single day's entries, keyed by epoch-day. */
    fun day(epochDay: Long) = "day/$epochDay"
    const val DAY_PATTERN = "day/{epochDay}"
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
    INSIGHTS(Routes.INSIGHTS, "Insights", R.drawable.ic_ui_chart),
    JOURNAL(Routes.JOURNAL, "Journal", R.drawable.ic_ui_journal),
    HOME(Routes.HOME, "Home", R.drawable.ic_ui_home),
    GOALS(Routes.GOALS, "Goals", R.drawable.ic_ui_target),
    MORE(Routes.MORE, "More", R.drawable.ic_ui_more),
}
