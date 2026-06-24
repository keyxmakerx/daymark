package com.daylie.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.ui.graphics.vector.ImageVector

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
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "Home", Icons.Filled.Home),
    CALENDAR(Routes.CALENDAR, "Calendar", Icons.Filled.CalendarMonth),
    STATS(Routes.STATS, "Stats", Icons.Filled.BarChart),
    SETTINGS(Routes.SETTINGS, "More", Icons.Filled.MoreHoriz),
}
