package com.daymark.app.stats

import com.daymark.app.stats.TrackerRhythm.MINUTES_PER_DAY
import com.daymark.app.stats.TrackerRhythm.Rhythm
import com.daymark.app.stats.TrackerRhythm.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tracker's check-in times, held to the person's settings: never outside their hours, never more
 * than they asked for, never two bunched together, and the same day always the same. Swept over
 * every count the settings offer, windows that cross midnight, and many trackers and days; the
 * draw is seeded, so nothing here passes or fails by luck.
 */
class TrackerRhythmTest {

    private val windows = listOf(
        Window(9 * 60, 21 * 60),
        Window(7 * 60 + 30, 8 * 60),
        Window(22 * 60, 2 * 60),
        Window(0, 0),
        Window(23 * 60 + 59, 0),
    )
    private val seeds = listOf(1L, 2L, 42L, -7L, Long.MAX_VALUE)
    private val days = (19_000L until 19_060L)

    private fun inWindow(time: Int, window: Window): Boolean {
        val offset = time - window.start
        return offset >= 0 && offset < window.lengthMinutes
    }

    @Test
    fun `a new tracker asks nothing, and so does a rhythm this version does not know`() {
        assertEquals(Rhythm.WHEN_IT_HAPPENS, Rhythm.DEFAULT)
        assertEquals(Rhythm.WHEN_IT_HAPPENS, Rhythm.fromKey(null))
        assertEquals(Rhythm.WHEN_IT_HAPPENS, Rhythm.fromKey("hourly_from_a_later_version"))
        // Positive control: a known key still reads back as itself.
        for (rhythm in Rhythm.entries) assertEquals(rhythm, Rhythm.fromKey(rhythm.key))
        assertEquals(setOf(Rhythm.ONCE_A_DAY, Rhythm.FEW_A_DAY), Rhythm.entries.filter { it.asks }.toSet())
    }

    @Test
    fun `every check-in falls inside the hours the person set, as many as they asked for`() {
        for (window in windows) for (count in 1..8) for (seed in seeds) for (day in days) {
            val times = TrackerRhythm.timesForDay(seed, day, count, window)
            // A window one minute long holds one check-in: never more than the minutes it has.
            assertEquals("count $count in $window", minOf(count, window.lengthMinutes), times.size)
            for (t in times) assertTrue("$t outside $window", inWindow(t, window))
        }
    }

    @Test
    fun `no two check-ins are closer than half a slot`() {
        for (window in windows) for (count in 2..8) for (seed in seeds) for (day in days) {
            val times = TrackerRhythm.timesForDay(seed, day, count, window)
            val slot = window.lengthMinutes / count
            for ((a, b) in times.zipWithNext()) {
                assertTrue("$a then $b in $window, count $count", b - a >= slot / 2)
            }
        }
    }

    @Test
    fun `the same tracker on the same day always gets the same times`() {
        for (window in windows) for (seed in seeds) for (day in days) {
            assertEquals(
                TrackerRhythm.timesForDay(seed, day, 4, window),
                TrackerRhythm.timesForDay(seed, day, 4, window),
            )
        }
    }

    @Test
    fun `the times vary from day to day, and from tracker to tracker`() {
        val window = Window(9 * 60, 21 * 60)
        val firstTimes = days.map { TrackerRhythm.timesForDay(42L, it, 3, window).first() }.toSet()
        assertTrue("only ${firstTimes.size} different first times over ${days.count()} days", firstTimes.size > 20)
        val trackers = (1L..30L).map { TrackerRhythm.timesForDay(it, 19_000L, 3, window) }.toSet()
        assertTrue("only ${trackers.size} different days across 30 trackers", trackers.size > 20)
    }

    @Test
    fun `the next check-in is strictly after now and is one of the day's own times`() {
        val window = Window(22 * 60, 2 * 60)
        for (seed in seeds) for (day in days) for (minute in 0 until MINUTES_PER_DAY step 7) {
            val due = TrackerRhythm.nextDue(Rhythm.FEW_A_DAY, seed, day, minute, 0, 3, window)
            assertNotNull(due)
            requireNotNull(due)
            val dueAt = due.epochDay * MINUTES_PER_DAY + due.minuteOfDay
            assertTrue(dueAt > day * MINUTES_PER_DAY + minute)
            assertTrue(due.minuteOfDay in 0 until MINUTES_PER_DAY)
            // It is one of the times its own window produced, from the day the window opened on.
            val fromSameDay = TrackerRhythm.timesForDay(seed, due.epochDay, 3, window).map { due.epochDay * MINUTES_PER_DAY + it }
            val fromDayBefore = TrackerRhythm.timesForDay(seed, due.epochDay - 1, 3, window).map { (due.epochDay - 1) * MINUTES_PER_DAY + it }
            assertTrue(dueAt in fromSameDay || dueAt in fromDayBefore)
            // And nothing the person set falls between now and it.
            val skipped = (day - 1..day + 1).flatMap { d -> TrackerRhythm.timesForDay(seed, d, 3, window).map { d * MINUTES_PER_DAY + it } }
                .filter { it > day * MINUTES_PER_DAY + minute && it < dueAt }
            assertEquals(emptyList<Long>(), skipped)
        }
    }

    @Test
    fun `once a day asks at the picked time, and the quiet rhythms never ask`() {
        val window = Window(9 * 60, 21 * 60)
        val before = TrackerRhythm.nextDue(Rhythm.ONCE_A_DAY, 1L, 19_000L, 8 * 60, 20 * 60, 3, window)
        assertEquals(TrackerRhythm.Due(19_000L, 20 * 60), before)
        val after = TrackerRhythm.nextDue(Rhythm.ONCE_A_DAY, 1L, 19_000L, 20 * 60, 20 * 60, 3, window)
        assertEquals(TrackerRhythm.Due(19_001L, 20 * 60), after)
        for (quiet in listOf(Rhythm.WHEN_IT_HAPPENS, Rhythm.DONT_ASK)) {
            assertNull(TrackerRhythm.nextDue(quiet, 1L, 19_000L, 8 * 60, 20 * 60, 3, window))
            assertEquals(0L, TrackerRhythm.setSpacingMillis(quiet, 3, window))
        }
    }

    @Test
    fun `the spacing handed to the engine is the person's own`() {
        assertEquals(24 * 3_600_000L, TrackerRhythm.setSpacingMillis(Rhythm.ONCE_A_DAY, 3, Window(0, 0)))
        assertEquals(4 * 3_600_000L, TrackerRhythm.setSpacingMillis(Rhythm.FEW_A_DAY, 3, Window(9 * 60, 21 * 60)))
        // A single daily check-in's first quiet step is every two days, not a no-op "once a day".
        val daily = TrackerRhythm.setSpacingMillis(Rhythm.ONCE_A_DAY, 1, Window(0, 0))
        assertEquals(1, CheckInEngine.stepsAlreadyMet(daily))
        // A few times a day eases first to once a day.
        val few = TrackerRhythm.setSpacingMillis(Rhythm.FEW_A_DAY, 3, Window(9 * 60, 21 * 60))
        assertEquals(0, CheckInEngine.stepsAlreadyMet(few))
    }
}
