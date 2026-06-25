package com.daylie.app.stats

import com.daylie.app.data.entity.EntryWithActivities
import com.daylie.app.util.DateUtils

/** Pure weekly-goal progress logic, kept separate so it is unit-testable. */
object GoalProgress {

    /**
     * Number of distinct days at/after [weekStartMillis] whose entry includes [activityId].
     * That count is the goal's progress toward its weekly target.
     */
    fun completedDays(
        entries: List<EntryWithActivities>,
        activityId: Long,
        weekStartMillis: Long,
    ): Int = entries
        .filter { ew ->
            ew.entry.dateTime >= weekStartMillis && ew.activities.any { it.id == activityId }
        }
        .map { DateUtils.toLocalDate(it.entry.dateTime) }
        .distinct()
        .size
}
