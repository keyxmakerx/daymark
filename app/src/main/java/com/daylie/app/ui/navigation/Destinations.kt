package com.daylie.app.ui.navigation

import androidx.annotation.DrawableRes
import com.daylie.app.R

object Routes {
    const val HOME = "home"
    const val CALENDAR = "calendar"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val ACTIVITIES = "activities"
    const val ENTRY = "entry"

    /** Editor route; pass 0 to create a new entry. */
    fun entry(id: Long = 0L) = "$ENTRY/$id"
    const val ENTRY_PATTERN = "$ENTRY/{entryId}"
}

enum class TopLevelDestination(
    val route: String,
    val label: String,
    @param:DrawableRes val icon: Int,
) {
    HOME(Routes.HOME, "Home", R.drawable.ic_ui_home),
    CALENDAR(Routes.CALENDAR, "Calendar", R.drawable.ic_ui_calendar),
    STATS(Routes.STATS, "Stats", R.drawable.ic_ui_chart),
    SETTINGS(Routes.SETTINGS, "More", R.drawable.ic_ui_more),
}
