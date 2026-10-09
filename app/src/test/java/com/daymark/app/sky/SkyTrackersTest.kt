package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Trackers in the sky: beside the memories, never among them, denser and never brighter. */
class SkyTrackersTest {

    private val start = SkyCalendar.epochDayOf(2023, 5, 1)
    private val records = (0 until 60).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it) }
    private val layout = Sky.layout(records, 11L)
    private val today = start + 70
    private val positions = layout.positionsOn(today)

    @Test
    fun `more logs make an object denser, never brighter`() {
        var before = SkyTrackers.TOTAL_LIGHT
        for (logs in listOf(6, 10, 50, 300, 1500)) {
            val total = SkyTrackers.grainAlpha(logs) * SkyTrackers.grains(logs)
            assertEquals("$logs logs", SkyTrackers.TOTAL_LIGHT, total, 1e-3f)
            assertTrue(total <= before + 1e-3f)
            before = total
        }
        // The control: a light that grew with the logs would fail the line above.
        assertTrue(1f * SkyTrackers.grains(300) > SkyTrackers.TOTAL_LIGHT)
    }

    @Test
    fun `an object sits beside the memories near the day its tracker started, not on them`() {
        val placed = SkyTrackers.place(
            listOf(SkyTrackers.Source(trackerId = 3L, logs = 40, firstEpochDay = start + 30)),
            layout, positions[0], positions[1], seed = 11L,
        ).single()
        val near = SkyTrackers.nearestByDay(layout, start + 30)
        assertEquals(start + 30, layout.epochDay[near])
        val apart = hypot(placed.x - positions[0][near], placed.y - positions[1][near])
        assertTrue("it sits on its day's star", apart > placed.radius)
        assertTrue("it is far from its day's star: $apart", apart < placed.radius * 2.2f + 0.02f)
    }

    @Test
    fun `a tracker with no logs is not drawn, and two trackers in one sky can differ`() {
        val none = SkyTrackers.place(listOf(SkyTrackers.Source(1L, 0, start)), layout, positions[0], positions[1], 11L)
        assertEquals(0, none.size)
        val looks = (1L..30L).map {
            SkyTrackers.place(listOf(SkyTrackers.Source(it, 20, start)), layout, positions[0], positions[1], 11L)
                .single().look
        }.toSet()
        assertEquals(SkyTrackers.Look.entries.toSet(), looks)
    }

    @Test
    fun `grains stay inside the object and stand still with motion off`() {
        for (look in SkyTrackers.Look.entries) {
            val placed = SkyTrackers.Placed(9L, look, 200, 0.5f, 0.5f, 0.006f, 0.4f, 0.4f, 0.3f)
            val at = FloatArray(2)
            val again = FloatArray(2)
            for (i in 0 until 200) {
                SkyTrackers.grain(placed, i, 0f, at)
                assertTrue("$look grain $i is outside", hypot(at[0], at[1]) <= placed.radius * 1.05f)
                SkyTrackers.grain(placed, i, 0f, again)
                assertEquals(at[0], again[0], 0f)
                assertEquals(at[1], again[1], 0f)
            }
        }
    }

    @Test
    fun `a belt or a ring is spread by order, so no stretch of it is empty`() {
        val placed = SkyTrackers.Placed(4L, SkyTrackers.Look.RING, 60, 0f, 0f, 0.006f, 0f, 1f, 0f)
        val at = FloatArray(2)
        val angles = (5 until 60).map {
            SkyTrackers.grain(placed, it, 0f, at)
            Math.toDegrees(kotlin.math.atan2(at[1].toDouble(), at[0].toDouble()))
        }.sorted()
        val gaps = angles.zipWithNext { a, b -> b - a } + (angles.first() + 360.0 - angles.last())
        assertTrue("a gap of ${gaps.max()} degrees", gaps.max() < 360.0 / 55 * 2)
    }
}
