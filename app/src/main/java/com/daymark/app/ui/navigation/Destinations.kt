package com.daymark.app.ui.navigation

import androidx.annotation.DrawableRes
import com.daymark.app.R

object Routes {
    const val HOME = "home"
    const val STATS = "stats"
    const val INSIGHTS = "insights"
    const val JOURNAL = "journal"
    const val SETTINGS = "settings"
    const val ACTIVITIES = "activities"
    const val REMINDERS = "reminders"
    const val CUSTOMIZE_MOODS = "customize_moods"
    const val ACTIVITY_LIBRARY = "activity_library"
    const val SEARCH = "search"

    /** The full day-grouped archive of entries, linked from Home. */
    const val HISTORY = "history"

    /** The ranked "For you" suggestions Home doesn't have room for. */
    const val FOR_YOU = "for_you"

    /** Settings → Suggestions: the on/off/snoozed dial for every suggestion family. */
    const val SUGGESTIONS = "suggestions"
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

    /**
     * "My safety plan" — written while steady, read in a hard moment. Reachable from More and from
     * a quiet row on the support screen; deliberately never surfaced by a Signals card.
     */
    const val SAFETY_PLAN = "safety_plan"
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
    const val ACTIVATION = "behavioral_activation"
    const val THOUGHT_RECORDS = "thought_records"
    const val THOUGHT_RECORD = "thought_record"
    fun thoughtRecord(id: Long = 0L) = "$THOUGHT_RECORD/$id"
    const val THOUGHT_RECORD_PATTERN = "$THOUGHT_RECORD/{recordId}"
    const val MOVEMENT = "movement"
    const val MOVEMENT_SESSION = "movement_session"
    fun movementSession(id: String) = "$MOVEMENT_SESSION/$id"
    const val MOVEMENT_SESSION_PATTERN = "$MOVEMENT_SESSION/{routineId}"
    const val ASSESSMENT = "assessment"
    fun assessment(key: String) = "$ASSESSMENT/$key"
    const val ASSESSMENT_PATTERN = "$ASSESSMENT/{assessmentKey}"
    const val ENTRY = "entry"
    const val JOURNAL_ENTRY = "journal_entry"
    const val YEAR_PIXELS = "year_pixels"

    /** Full-screen "Review my year" walkthrough, keyed by year. */
    const val REVIEW_YEAR = "review_year"
    fun reviewYear(year: Int) = "$REVIEW_YEAR/$year"
    const val REVIEW_YEAR_PATTERN = "$REVIEW_YEAR/{year}"
    const val GOALS = "goals"

    /**
     * "Your sky" — the whole history as a night sky.
     *
     * A drill-down reached from More, **not** a tab, as `docs/SKY.md` §11 item 1 settles it. The
     * argument on both sides: the Sky is a place, which argues for a top-level destination, and it
     * is also the most identifying surface in the product (§6.5), which argues for keeping it
     * behind whatever lock the app has rather than one tap from a cold screen. The cautious half
     * won — a route can be promoted to a tab later, and a surface that has been on the tab bar
     * cannot be quietly demoted.
     */
    const val SKY = "sky"

    /**
     * The life-events list, where a person places their own marks.
     *
     * The screen was built before anything could reach it. `docs/SKY.md` §2.2 gives life events two
     * doors: the Sky, whose Life events control and whose life-event stars ("Open it") are the only
     * ways here, and "Mark this day" on an entry's page, which adds a mark for that entry's date
     * without coming here. There is deliberately no entry point in the More hub: a door there would
     * make this feel like a thing the app collects rather than a thing the person marks.
     */
    const val LIFE_EVENTS = "life_events"

    /**
     * "People and communities" — every name the person has written down, grouped for sorting.
     *
     * A drill-down from the More hub. It is not a tab and it is not on Home: it is a place to keep
     * things, not a thing to be reminded of, and nothing in this app nudges anybody about the
     * people in their life (`docs/FEATURES.md` §11.3 forbids the one prompt everybody reaches for
     * first).
     */
    const val PEOPLE = "people"

    /**
     * The sharing screen: one list, a default per group, an override per item, all off to begin
     * with. Reached from the people list and from the quiet line at the foot of a person's page.
     */
    const val PEOPLE_SHARING = "people_sharing"

    /** One person's or community's page, keyed by their row id. */
    const val PERSON = "person"
    fun person(id: Long) = "$PERSON/$id"
    const val PERSON_PATTERN = "$PERSON/{personId}"

    /**
     * A past entry, read rather than edited — the mood word the person chose, their activities,
     * who they were with, their note and their photo.
     *
     * Distinct from [ENTRY], which is the editor. Tapping an entry anywhere in the app now lands
     * here, and the editor is one deliberate tap further on, because opening a record of a hard
     * day straight into a form with a delete button in the corner is the wrong first thing to
     * happen.
     */
    const val ENTRY_VIEW = "entry_view"
    fun entryView(id: Long) = "$ENTRY_VIEW/$id"
    const val ENTRY_VIEW_PATTERN = "$ENTRY_VIEW/{entryId}"

    /**
     * "Why it asks" — the timing layer described to itself. **Debug builds only.**
     *
     * Registered in `DaymarkAppScaffold` inside `if (BuildConfig.DEBUG)`, and reached from a
     * Settings row inside the same check, so removing either leaves it unreachable in a release
     * build. The constant itself is unconditional because a route string that does not exist is a
     * compile error at both gates rather than one fewer door.
     */
    const val DEBUG_TIMING = "debug_timing"

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
