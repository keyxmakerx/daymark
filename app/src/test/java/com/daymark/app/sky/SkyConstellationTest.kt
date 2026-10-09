package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Constellations (`DECISIONS.md` §D11): the person's own, kept as they were drawn, fading from the
 * live sky as their stars drift, and leaving nothing behind for a deleted memory.
 */
class SkyConstellationTest {

    private val start = SkyCalendar.epochDayOf(2021, 3, 1)
    private val seed = 0x5B1E5EEDL

    private fun history(days: Int) = (0 until days).map {
        SkyRecord(SkyKind.CHECK_IN, id = 1L + it, epochDay = start + it * 3, moodLevel = 3)
    }

    private fun drawn(layout: SkyLayout, onDay: Long, vararg recordIds: Long) = recordIds.map { id ->
        val i = layout.indexOf(SkyKind.CHECK_IN, id)
        SkyConstellation.Point(SkyKind.CHECK_IN, id, layout.xOn(i, onDay), layout.yOn(i, onDay))
    }

    @Test
    fun `a name is kept on one line, within the limit, and never blank`() {
        assertEquals("Summer", SkyConstellation.cleanName("  Summer \n"))
        assertEquals("Long walks home", SkyConstellation.cleanName("Long\twalks\n\nhome"))
        assertEquals(SkyConstellation.UNTITLED, SkyConstellation.cleanName("   "))
        assertEquals(SkyConstellation.UNTITLED, SkyConstellation.cleanName(""))
        val long = SkyConstellation.cleanName("a".repeat(60))
        assertEquals(SkyConstellation.NAME_MAX, long.length)
        // A character outside the basic plane is never cut in half.
        val star = "🌟"
        val edge = SkyConstellation.cleanName("b".repeat(SkyConstellation.NAME_MAX - 1) + star)
        assertFalse(edge.last().isHighSurrogate())
    }

    @Test
    fun `points survive the round trip, and a broken one is skipped`() {
        val points = listOf(
            SkyConstellation.Point(SkyKind.JOURNAL, 12L, 0.25f, 1.5f),
            SkyConstellation.Point(SkyKind.LIFE_EVENT, 7L, 0.123456f, 0.000001f),
        )
        assertEquals(points, SkyConstellation.decode(SkyConstellation.encode(points)))
        val text = SkyConstellation.encode(points) + ";nonsense;unknown_kind,3,0.1,0.1;journal,x,0.1,0.1;journal,4,NaN,1"
        assertEquals(points, SkyConstellation.decode(text))
        assertEquals(emptyList<SkyConstellation.Point>(), SkyConstellation.decode(""))
    }

    @Test
    fun `a line stays while its stars stay close and is gone once they drift apart`() {
        assertEquals(1f, SkyConstellation.lineAlpha(1f, 1f), 0f)
        assertEquals(1f, SkyConstellation.lineAlpha(1f, 1.25f), 0f)
        assertEquals(0f, SkyConstellation.lineAlpha(1f, 1.6f), 0f)
        assertEquals(0f, SkyConstellation.lineAlpha(1f, 3f), 0f)
        val mid = SkyConstellation.lineAlpha(1f, 1.4f)
        assertTrue(mid > 0f && mid < 1f)
    }

    @Test
    fun `over the years every constellation falls out of the sky`() {
        val layout = Sky.layout(history(60), seed)
        val made = start + 200
        val points = drawn(layout, made, 10L, 11L, 12L, 13L)
        val resolved = SkyConstellation.resolve(points, layout)
        // On the day it was drawn, every line is whole.
        for (a in SkyConstellation.lineAlphas(points, resolved, layout, made)) assertEquals(1f, a, 1e-4f)
        // Decades on, its stars have drifted apart and nothing of it is left in the live sky.
        val later = made + 365L * 60
        for (a in SkyConstellation.lineAlphas(points, resolved, layout, later)) assertEquals(0f, a, 0f)
    }

    @Test
    fun `a deleted memory leaves no point and no line`() {
        val full = Sky.layout(history(30), seed)
        val made = start + 100
        val points = drawn(full, made, 5L, 6L, 7L)
        val without = Sky.layout(history(30).filterNot { it.id == 6L }, seed)
        val resolved = SkyConstellation.resolve(points, without)
        assertEquals(-1, resolved[1])
        val alphas = SkyConstellation.lineAlphas(points, resolved, without, made)
        assertEquals(0f, alphas[0], 0f)
        assertEquals(0f, alphas[1], 0f)
        // With only two points left it still shows; with one it does not.
        assertTrue(SkyConstellation.isShown(resolved))
        val alone = Sky.layout(history(30).filterNot { it.id == 6L || it.id == 7L }, seed)
        assertFalse(SkyConstellation.isShown(SkyConstellation.resolve(points, alone)))
        // The detector: in the full sky every point resolves.
        assertTrue(SkyConstellation.resolve(points, full).all { it >= 0 })
    }

    @Test
    fun `the photo holds only stars that already existed that day`() {
        val layout = Sky.layout(history(60), seed)
        val made = start + 60
        val points = drawn(layout, made, 3L, 4L)
        val resolved = SkyConstellation.resolve(points, layout)
        val around = SkyConstellation.neighbours(points, resolved, layout, made, reach = 10f)
        assertTrue(around.isNotEmpty())
        for (i in around) {
            assertTrue(layout.epochDay[i] <= made)
            assertFalse(i in resolved)
        }
        // The detector: the sky holds stars from after that day, and the photo left them out.
        assertTrue((0 until layout.starCount).any { layout.epochDay[it] > made })
    }

    @Test
    fun `a star that holds several records is found by any of them`() {
        // More records in a day than the day draws, so one star holds several.
        val day = (0 until 40).map { SkyRecord(SkyKind.JOURNAL, id = 100L + it, epochDay = start) }
        val layout = Sky.layout(day, seed)
        val folded = (0 until layout.starCount).first { layout.recordCountAt(it) > 1 }
        for (id in layout.recordIdsAt(folded)) assertEquals(folded, layout.indexOf(SkyKind.JOURNAL, id))
        assertEquals(-1, layout.indexOf(SkyKind.CHECK_IN, 100L))
    }
}
