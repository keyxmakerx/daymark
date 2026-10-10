package com.daymark.app.ui.sky

import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyCalendar
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyRecord
import com.daymark.app.sky.SkyTrackers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The words and picking for the sky's second batch: supernova, put away, nebulae, trackers, colours. */
class SkyObjectsPresentationTest {

    private val uk = Locale.UK
    private val may3 = SkyCalendar.epochDayOf(2026, 5, 3)

    @Test
    fun `a supernova's card names the day and the mark, and nothing about the day`() {
        val layout = Sky.layout(listOf(SkyRecord(SkyKind.LIFE_EVENT, 1L, may3, hard = true)), 3L)
        val text = SkyPresentation.starDescription(layout, 0, { "" }, uk)
        assertTrue(text, text.startsWith("Supernova, 3 May 2026."))
        assertTrue(text.contains("It marks that day and nothing more"))
        // The control: the same event unmarked is an ordinary life event.
        val plain = Sky.layout(listOf(SkyRecord(SkyKind.LIFE_EVENT, 1L, may3)), 3L)
        assertFalse(SkyPresentation.starDescription(plain, 0, { "" }, uk).contains("Supernova"))
    }

    @Test
    fun `a put-away row says only that it is put away, and when`() {
        assertEquals("Put away on 3 May 2026", SkyPresentation.putAwayRow(may3, uk))
    }

    @Test
    fun `the canvas description leaves out a year only a put-away memory is in`() {
        val records = listOf(
            SkyRecord(SkyKind.CHECK_IN, 1L, SkyCalendar.epochDayOf(2019, 2, 1), putAwayEpochDay = may3),
            SkyRecord(SkyKind.CHECK_IN, 2L, SkyCalendar.epochDayOf(2025, 2, 1)),
            SkyRecord(SkyKind.CHECK_IN, 3L, may3),
        )
        assertEquals("Your sky, 2025 to 2026.", SkyPresentation.canvasDescription(Sky.layout(records, 3L), uk))
    }

    @Test
    fun `a put-away star cannot be tapped`() {
        val xs = floatArrayOf(0.5f, 0.52f)
        val ys = floatArrayOf(0.5f, 0.5f)
        val bounds = SkyPresentation.Bounds(0f, 0f, 1f, 1f)
        fun tap(hidden: BooleanArray?) = SkyPresentation.nearestStar(
            xs, ys, bounds, scalePx = 1000f, panXPx = 0f, panYPx = 0f,
            tapXPx = 500f, tapYPx = 500f, maxDistancePx = 50f, hidden = hidden,
        )
        assertEquals(0, tap(null))
        assertEquals(1, tap(booleanArrayOf(true, false)))
    }

    @Test
    fun `a nebula's card counts entries as a description, with its days`() {
        assertEquals(
            "Weeks with a lot of writing: 9 journal entries, 3 to 19 May.",
            SkyPresentation.nebulaDescription(9, may3, may3 + 16, uk),
        )
        assertEquals("28 April to 9 May", SkyPresentation.dayRange(may3 - 5, may3 + 6, uk))
        // Years differ, so both dates carry theirs.
        assertEquals("29 Dec 2025 to 3 May 2026", SkyPresentation.dayRange(may3 - 125, may3, uk))
    }

    @Test
    fun `a tracker's card names its look and what a star in it is, never a count`() {
        val text = SkyPresentation.trackerDescription(SkyTrackers.Look.BELT)
        assertEquals("Asteroid belt. Each time you log it is one star here.", text)
        assertFalse(Regex("\\d").containsMatchIn(text))
    }

    @Test
    fun `the colours stay open through the window's last day, and not before it starts`() {
        assertTrue(SkyPresentation.coloursOpen(may3, may3))
        assertFalse(SkyPresentation.coloursOpen(may3, may3 + 1))
        assertFalse("a window not yet started is not open", SkyPresentation.coloursOpen(0L, may3))
        assertEquals(
            "You can change these colours until 3 May. After that they stay, as part of your sky.",
            SkyPresentation.colourWindow(may3, uk),
        )
    }

    @Test
    fun `a tap reaches the smallest object it is inside`() {
        val xs = floatArrayOf(0.5f, 0.51f)
        val ys = floatArrayOf(0.5f, 0.5f)
        val radii = floatArrayOf(0.1f, 0.02f)
        assertEquals(1, SkyPresentation.objectAt(xs, ys, radii, 0.51f, 0.5f))
        assertEquals(0, SkyPresentation.objectAt(xs, ys, radii, 0.45f, 0.5f))
        assertEquals(-1, SkyPresentation.objectAt(xs, ys, radii, 0.9f, 0.9f))
    }

    @Test
    fun `nebulae fade as the sky is followed in, and never go`() {
        assertEquals(1f, SkyPresentation.nebulaAlpha(1f), 1e-6f)
        val close = SkyPresentation.nebulaAlpha(SkyPresentation.MAX_ZOOM)
        assertEquals(1f - SkyPresentation.NEBULA_CLOSE_FADE, close, 1e-6f)
        assertTrue(close > 0f)
        assertTrue(SkyPresentation.nebulaAlpha(40f) in close..1f)
    }
}
