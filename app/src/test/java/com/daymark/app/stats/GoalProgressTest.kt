package com.daymark.app.stats

import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.data.entity.MoodEntry
import com.daymark.app.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class GoalProgressTest {

    private val gym = ActivityEntity(id = 1, name = "Gym")
    private val read = ActivityEntity(id = 2, name = "Read")

    private fun entry(date: LocalDate, hour: Int, activities: List<ActivityEntity>) =
        EntryWithActivities(
            entry = MoodEntry(
                id = 0,
                dateTime = DateUtils.toEpochMillis(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0))),
                moodLevel = 4,
                note = "",
            ),
            activities = activities,
        )

    @Test
    fun countsDistinctDaysWithActivity() {
        val weekStart = LocalDate.of(2026, 6, 22) // Monday
        val weekStartMillis = DateUtils.startOfDay(weekStart)
        val entries = listOf(
            entry(weekStart, 9, listOf(gym)),
            entry(weekStart, 18, listOf(gym)), // same day → still 1
            entry(weekStart.plusDays(2), 7, listOf(gym, read)),
            entry(weekStart.plusDays(3), 8, listOf(read)), // no gym → ignored
        )
        assertEquals(2, GoalProgress.completedDays(entries, gym.id, weekStartMillis))
        assertEquals(2, GoalProgress.completedDays(entries, read.id, weekStartMillis))
    }

    @Test
    fun ignoresEntriesBeforeWeekStart() {
        val weekStart = LocalDate.of(2026, 6, 22)
        val weekStartMillis = DateUtils.startOfDay(weekStart)
        val entries = listOf(
            entry(weekStart.minusDays(1), 9, listOf(gym)), // last week → ignored
            entry(weekStart, 9, listOf(gym)),
        )
        assertEquals(1, GoalProgress.completedDays(entries, gym.id, weekStartMillis))
    }

    @Test
    fun zeroWhenNoMatches() {
        val weekStartMillis = DateUtils.startOfDay(LocalDate.of(2026, 6, 22))
        assertEquals(0, GoalProgress.completedDays(emptyList(), gym.id, weekStartMillis))
    }
}
