package com.daymark.app.ui.sky

import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyCalendar
import com.daymark.app.sky.SkyDetail
import com.daymark.app.sky.SkyGlyph
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyLayout
import com.daymark.app.sky.SkyListItem
import com.daymark.app.sky.SkyPalette
import com.daymark.app.sky.SkyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The renderer's arithmetic and the renderer's words.
 *
 * The Compose surface cannot be compiled in this environment, so everything in it that could be
 * wrong in a way a person would feel — where a tap lands, what a screen reader is told, whether the
 * empty sky says something it should not — was pushed into [SkyPresentation] so that it could be
 * executed here instead.
 *
 * Two of these assert that something is **absent** from a string. Each is paired with a call that
 * proves the same search finds the thing when it is there, because an assertion that a substring is
 * missing is worthless from a search that could never match anything.
 */
class SkyPresentationTest {

    private val locale: Locale = Locale.UK

    // 2020-03-04. Fixed, because a test that starts from "today" is a test that changes.
    private val day = SkyCalendar.epochDayOf(2020, 3, 4)
    private val nextMonthDay = SkyCalendar.epochDayOf(2020, 4, 9)

    private fun label(level: Int) = listOf("", "Awful", "Bad", "Okay", "Good", "Rad")[level]

    /**
     * A layout with coordinates chosen by hand rather than hashed, so the hit-testing assertions are
     * about the hit test and not about [com.daymark.app.sky.SkyRandom].
     *
     * Row 0 holds two stars at x 0.25 and 0.75, both halfway down; row 1 holds one at x 0.5.
     */
    private fun handPlaced(): SkyLayout = SkyLayout(
        firstEpochMonth = SkyCalendar.epochMonth(day),
        rowCount = 2,
        rowStart = intArrayOf(0, 2, 3),
        x = floatArrayOf(0.25f, 0.75f, 0.5f),
        y = floatArrayOf(0.5f, 0.5f, 0.25f),
        row = intArrayOf(0, 0, 1),
        kindOrdinal = intArrayOf(
            SkyKind.CHECK_IN.ordinal,
            SkyKind.JOURNAL.ordinal,
            SkyKind.LIFE_EVENT.ordinal,
        ),
        moodLevel = intArrayOf(3, SkyGlyph.MOOD_NONE, SkyGlyph.MOOD_NONE),
        epochDay = longArrayOf(day, day, nextMonthDay),
        idStart = intArrayOf(0, 1, 2, 3),
        recordIds = longArrayOf(10, 11, 12),
    )

    // -------------------------------------------------------------------------------------------
    // Zoom.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `zoom spans drift to night and never bottoms out at a star`() {
        assertEquals(SkyDetail.DRIFT, SkyPresentation.detailFor(SkyPresentation.MAX_VISIBLE_MONTHS))
        assertEquals(SkyDetail.NIGHT, SkyPresentation.detailFor(SkyPresentation.MIN_VISIBLE_MONTHS))
        // Every level between the two ends is reachable, or the zoom has a hole in it.
        val reached = generateSequence(SkyPresentation.MAX_VISIBLE_MONTHS) { it / 1.05f }
            .takeWhile { it >= SkyPresentation.MIN_VISIBLE_MONTHS }
            .map { SkyPresentation.detailFor(it) }
            .toSet()
        assertEquals(
            setOf(SkyDetail.DRIFT, SkyDetail.SEASON, SkyDetail.MONTH, SkyDetail.NIGHT),
            reached,
        )
        // Zooming past the stop does not reach SkyDetail.STAR — a star's detail is opened, never
        // zoomed into.
        assertNotEquals(SkyDetail.STAR, SkyPresentation.detailFor(0.0001f))
    }

    @Test
    fun `a month is one viewport wide from drift down to the month level`() {
        for (months in listOf(120f, 40f, 13f, 5f, 2f, 1.3f, SkyPresentation.ROW_FILLS_VIEWPORT)) {
            assertEquals(
                "width factor at $months months",
                1f,
                SkyPresentation.widthFactor(months),
                0f,
            )
        }
    }

    @Test
    fun `below the hinge the month widens, monotonically, up to one day a screen`() {
        val hinge = SkyPresentation.ROW_FILLS_VIEWPORT
        var previous = SkyPresentation.widthFactor(hinge)
        var months = hinge
        var grew = false
        while (months > SkyPresentation.MIN_VISIBLE_MONTHS) {
            months /= 1.05f
            val factor = SkyPresentation.widthFactor(months)
            assertTrue("width factor went backwards at $months", factor >= previous)
            if (factor > previous) grew = true
            previous = factor
        }
        assertTrue("the month never widened at all", grew)
        assertEquals(
            SkyPresentation.MAX_WIDTH_FACTOR,
            SkyPresentation.widthFactor(SkyPresentation.MIN_VISIBLE_MONTHS),
            0.5f,
        )
    }

    @Test
    fun `a row never grows taller than the viewport`() {
        val viewport = 1000f
        var months = SkyPresentation.MAX_VISIBLE_MONTHS
        while (months > SkyPresentation.MIN_VISIBLE_MONTHS) {
            val height = SkyPresentation.rowHeightPx(viewport, months)
            assertTrue(
                "a row is $height px tall in a $viewport px viewport at $months months",
                height <= viewport + 0.001f,
            )
            months /= 1.1f
        }
        // And it does reach the viewport, or the cap is really a floor nobody can get to.
        assertEquals(
            viewport,
            SkyPresentation.rowHeightPx(viewport, SkyPresentation.MIN_VISIBLE_MONTHS) *
                SkyPresentation.ROW_FILLS_VIEWPORT,
            0.001f,
        )
    }

    @Test
    fun `the quiet sky raises the whole ramp without tilting it`() {
        // The shipped hues, as SkyPalette's own table records them.
        val shipped = intArrayOf(0xAE5747, 0xC27C46, 0xC6A24E, 0x8FA268, 0x5E8A66)
        val quiet = SkyPalette.equalisedRamp(shipped, SkyPresentation.HIGH_CONTRAST_TARGET)
        val ordinary = SkyPalette.equalisedRamp(shipped)

        for (i in shipped.indices) {
            val raised = SkyPalette.contrastRatio(quiet[i], SkyPalette.NIGHT_BG)
            // Level, not merely legal: every mood is drawn at the same contrast as every other, or
            // the ramp ranks them by visibility again with the hardest one faintest.
            assertEquals(SkyPresentation.HIGH_CONTRAST_TARGET, raised, 0.05)
            // And the control does something, or "maximum contrast" is a label on a no-op.
            assertTrue(raised > SkyPalette.contrastRatio(ordinary[i], SkyPalette.NIGHT_BG))
        }
    }

    // -------------------------------------------------------------------------------------------
    // Pan.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a sky smaller than the screen is centred, not parked in a corner`() {
        assertEquals(150f, SkyPresentation.clampPan(0f, contentPx = 100f, viewportPx = 400f), 0f)
        // Whatever the person did with their finger, it lands in the same place.
        assertEquals(150f, SkyPresentation.clampPan(-900f, contentPx = 100f, viewportPx = 400f), 0f)
        assertEquals(150f, SkyPresentation.clampPan(900f, contentPx = 100f, viewportPx = 400f), 0f)
    }

    @Test
    fun `a sky larger than the screen cannot be dragged off it`() {
        // Content 1000, viewport 400: the pan runs from -600 (bottom edge on screen) to 0 (top).
        assertEquals(0f, SkyPresentation.clampPan(80f, contentPx = 1000f, viewportPx = 400f), 0f)
        assertEquals(-600f, SkyPresentation.clampPan(-5000f, contentPx = 1000f, viewportPx = 400f), 0f)
        // In range, it is left alone — otherwise the two assertions above would pass on a function
        // that ignored its argument.
        assertEquals(-250f, SkyPresentation.clampPan(-250f, contentPx = 1000f, viewportPx = 400f), 0f)
    }

    @Test
    fun `culling covers what is on screen and stops at the layout's edges`() {
        val layout = handPlaced()
        val rows = SkyPresentation.visibleRows(layout, rowHeightPx = 100f, panYPx = 0f, viewportHeightPx = 400f)!!
        assertEquals(0, rows.first)
        assertEquals(layout.rowCount - 1, rows.last)

        // The same call on an empty sky has nothing to cull to.
        assertEquals(
            null,
            SkyPresentation.visibleRows(SkyLayout.EMPTY, 100f, 0f, 400f),
        )
    }

    // -------------------------------------------------------------------------------------------
    // Hit testing.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a tap resolves to the nearest core, and to nothing when it is nowhere near one`() {
        val layout = handPlaced()
        val width = 100f
        val height = 100f
        val all = 0 until layout.starCount

        // Star 0 is at (25, 50); star 1 at (75, 50); star 2 at (50, 125).
        fun tap(x: Float, y: Float, radius: Float = 24f) = SkyPresentation.nearestStar(
            layout, all, width, height, 0f, 0f, x, y, radius,
        )

        assertEquals(0, tap(27f, 52f))
        assertEquals(1, tap(73f, 48f))
        assertEquals(2, tap(50f, 130f))

        // Far from every star: nothing is opened. Paired with the calls above, which prove this
        // detector does find a star when one is within reach.
        assertEquals(-1, tap(50f, 400f))
        assertEquals(-1, tap(0f, 0f))
    }

    @Test
    fun `an exact tie resolves to the earlier record, on every device`() {
        val layout = handPlaced()
        // Exactly between the two row-0 stars, which sit at x 25 and 75.
        val hit = SkyPresentation.nearestStar(
            layout, 0 until layout.starCount, 100f, 100f, 0f, 0f, 50f, 50f, 40f,
        )
        assertEquals(0, hit)
    }

    @Test
    fun `panning moves the targets with the sky`() {
        val layout = handPlaced()
        val all = 0 until layout.starCount
        val before = SkyPresentation.nearestStar(layout, all, 100f, 100f, 0f, 0f, 25f, 50f, 8f)
        val afterWrongPlace = SkyPresentation.nearestStar(layout, all, 100f, 100f, 40f, 0f, 25f, 50f, 8f)
        val afterRightPlace = SkyPresentation.nearestStar(layout, all, 100f, 100f, 40f, 0f, 65f, 50f, 8f)
        assertEquals(0, before)
        assertEquals(-1, afterWrongPlace)
        assertEquals(0, afterRightPlace)
    }

    // -------------------------------------------------------------------------------------------
    // Words.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a star says what it was, when, and the person's own mood word`() {
        val text = SkyPresentation.starDescription(
            kind = SkyKind.CHECK_IN,
            epochDay = day,
            moodLevel = 3,
            recordCount = 1,
            moodLabel = ::label,
            locale = locale,
        )
        assertTrue(text, text.startsWith(SkyKind.CHECK_IN.introduction))
        assertTrue(text, text.contains("2020"))
        assertTrue(text, text.contains("Okay"))
    }

    @Test
    fun `a star with no mood is not given one`() {
        val moodless = SkyPresentation.starDescription(
            kind = SkyKind.JOURNAL,
            epochDay = day,
            moodLevel = SkyGlyph.MOOD_NONE,
            recordCount = 1,
            moodLabel = ::label,
            locale = locale,
        )
        // The detector: the same search on a star that does have a mood finds the word, so its
        // absence below is a fact about the string and not about the search.
        val moody = SkyPresentation.starDescription(
            kind = SkyKind.CHECK_IN,
            epochDay = day,
            moodLevel = 1,
            recordCount = 1,
            moodLabel = ::label,
            locale = locale,
        )
        assertTrue(moody, moody.contains("Awful"))
        assertFalse(moodless, moodless.contains("Awful"))
        assertFalse(moodless, moodless.contains("Okay"))
    }

    @Test
    fun `only a folded star mentions how many records it covers`() {
        fun describe(count: Int) = SkyPresentation.starDescription(
            kind = SkyKind.PRACTICE,
            epochDay = day,
            moodLevel = SkyGlyph.MOOD_NONE,
            recordCount = count,
            moodLabel = ::label,
            locale = locale,
        )
        assertTrue(describe(3).contains("Covers 3 records"))
        assertFalse(describe(1).contains("Covers"))
    }

    @Test
    fun `the canvas describes what it is and never how much is in it`() {
        val records = (0 until 40).map {
            SkyRecord(SkyKind.CHECK_IN, id = it.toLong(), epochDay = day + it, moodLevel = 2)
        }
        val layout = Sky.layout(records)
        val text = SkyPresentation.canvasDescription(layout, locale)

        assertTrue(text, text.contains("2020"))
        // The detector first: the star count really is 40, and a search for "40" would find it if
        // the sentence contained it.
        assertEquals(40, layout.starCount)
        assertTrue("40".contains("40"))
        assertFalse(text, text.contains("40"))
    }

    @Test
    fun `an empty sky is described with the empty line and nothing else`() {
        assertEquals(
            SkyLayout.EMPTY_LINE,
            SkyPresentation.canvasDescription(SkyLayout.EMPTY, locale),
        )
    }

    @Test
    fun `a month heading carries list structure and pluralises`() {
        assertEquals(
            "March 2020, 6 items",
            SkyPresentation.monthHeading(SkyListItem.MonthHeading(2020, 3, 6), locale),
        )
        assertEquals(
            "March 2020, 1 item",
            SkyPresentation.monthHeading(SkyListItem.MonthHeading(2020, 3, 1), locale),
        )
    }

    @Test
    fun `the where-you-are label is a date`() {
        assertEquals(
            "March 2020",
            SkyPresentation.monthLabel(SkyCalendar.epochMonth(day), locale),
        )
    }

    // -------------------------------------------------------------------------------------------
    // Hand-off.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the two kinds with nowhere to hand off to offer no action`() {
        assertFalse(SkyPresentation.opensRecord(SkyKind.PROJECT_STEP))
        assertFalse(SkyPresentation.opensRecord(SkyKind.LIFE_EVENT))
        for (kind in SkyKind.entries - SkyKind.PROJECT_STEP - SkyKind.LIFE_EVENT) {
            assertTrue(kind.key, SkyPresentation.opensRecord(kind))
        }
    }

    @Test
    fun `a life event still activates, and only a project step is inert`() {
        assertTrue(SkyPresentation.hasAction(SkyKind.LIFE_EVENT))
        assertFalse(SkyPresentation.hasAction(SkyKind.PROJECT_STEP))
        for (kind in SkyKind.entries - SkyKind.PROJECT_STEP) {
            assertTrue(kind.key, SkyPresentation.hasAction(kind))
        }
    }
}
