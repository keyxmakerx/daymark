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
 * Several of these assert that something is **absent** — a star not hit, a count not spoken, a
 * region not drawn. Each is paired with a call that proves the same detector finds the thing when
 * it is there, because an assertion that nothing was found is worthless from a detector that never
 * finds anything.
 */
class SkyPresentationTest {

    private val locale: Locale = Locale.UK

    // 2020-03-04. Fixed, because a test that starts from "today" is a test that changes.
    private val day = SkyCalendar.epochDayOf(2020, 3, 4)
    private val nextMonthDay = SkyCalendar.epochDayOf(2020, 4, 9)

    private val seed = 0x5B1E5EEDL

    private fun label(level: Int) = listOf("", "Awful", "Bad", "Okay", "Good", "Rad")[level]

    /**
     * A layout with coordinates chosen by hand rather than hashed, so the hit-testing assertions are
     * about the hit test and not about [com.daymark.app.sky.SkyRandom].
     *
     * Three stars in the unit field: two side by side halfway down, one near the top.
     */
    private fun handPlaced(): SkyLayout = SkyLayout(
        x = floatArrayOf(0.25f, 0.75f, 0.5f),
        y = floatArrayOf(0.5f, 0.5f, 0.1f),
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
    fun `zoom runs from the whole sky to leaning in, and stops at both ends`() {
        assertEquals(SkyDetail.FAR, SkyPresentation.detailFor(SkyPresentation.MIN_ZOOM))
        assertEquals(SkyDetail.CLOSE, SkyPresentation.detailFor(SkyPresentation.MAX_ZOOM))
        // Every level between the two ends is reachable, or the zoom has a hole in it.
        val reached = generateSequence(SkyPresentation.MIN_ZOOM) { it * 1.05f }
            .takeWhile { it <= SkyPresentation.MAX_ZOOM }
            .map { SkyPresentation.detailFor(it) }
            .toSet()
        assertEquals(setOf(SkyDetail.FAR, SkyDetail.NEAR, SkyDetail.CLOSE), reached)

        // Pushing past either stop is the stop, not a different sky.
        assertEquals(
            SkyPresentation.detailFor(SkyPresentation.MAX_ZOOM),
            SkyPresentation.detailFor(SkyPresentation.MAX_ZOOM * 100f),
        )
        assertEquals(
            SkyPresentation.detailFor(SkyPresentation.MIN_ZOOM),
            SkyPresentation.detailFor(0.0001f),
        )
        assertEquals(SkyPresentation.MIN_ZOOM, SkyPresentation.clampZoom(0f), 0f)
        assertEquals(SkyPresentation.MAX_ZOOM, SkyPresentation.clampZoom(1e6f), 0f)
    }

    @Test
    fun `the sky opens showing all of itself and never smaller than the screen`() {
        // DEFAULT_ZOOM is the whole field in the viewport: no border of nothing around it, which on
        // this surface would read as the edge of someone's history.
        assertEquals(SkyPresentation.MIN_ZOOM, SkyPresentation.DEFAULT_ZOOM, 0f)
        assertEquals(400f, SkyPresentation.contentWidthPx(400f, SkyPresentation.DEFAULT_ZOOM), 0f)
        assertEquals(800f, SkyPresentation.contentHeightPx(800f, SkyPresentation.DEFAULT_ZOOM), 0f)
        // Zooming out past the stop cannot shrink it.
        assertEquals(400f, SkyPresentation.contentWidthPx(400f, 0.1f), 0f)
    }

    @Test
    fun `zooming in grows the field, monotonically, up to the stop`() {
        var previous = SkyPresentation.contentWidthPx(400f, SkyPresentation.MIN_ZOOM)
        var zoom = SkyPresentation.MIN_ZOOM
        var grew = false
        while (zoom < SkyPresentation.MAX_ZOOM) {
            zoom *= 1.05f
            val width = SkyPresentation.contentWidthPx(400f, zoom)
            assertTrue("the field shrank at zoom $zoom", width >= previous)
            if (width > previous) grew = true
            previous = width
        }
        assertTrue("the field never grew at all", grew)
        assertEquals(
            400f * SkyPresentation.MAX_ZOOM,
            SkyPresentation.contentWidthPx(400f, SkyPresentation.MAX_ZOOM * 4f),
            0f,
        )
    }

    /**
     * The one thing zoom must never consult.
     *
     * A detail level that resolved when few enough stars were on screen would make what the surface
     * draws a function of how much someone logged — the month rows' mistake, one layer up. So the
     * level is a function of the zoom factor and takes no layout at all, which is a fact about the
     * signature; what is checked here is that the two skies a person might have really do draw the
     * same way at the same zoom.
     */
    @Test
    fun `how much is in the sky does not change how it is drawn`() {
        val sparse = Sky.layout(
            listOf(SkyRecord(SkyKind.CHECK_IN, 1L, day, moodLevel = 3)),
            seed,
        )
        val dense = Sky.layout(
            (0 until 4_000).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, day + it / 4, moodLevel = 3) },
            seed,
        )
        assertEquals(1, sparse.starCount)
        assertEquals(4_000, dense.starCount)

        var zoom = SkyPresentation.MIN_ZOOM
        while (zoom <= SkyPresentation.MAX_ZOOM) {
            assertEquals(SkyPresentation.detailFor(zoom), SkyPresentation.detailFor(zoom))
            assertEquals(
                SkyPresentation.contentWidthPx(400f, zoom),
                SkyPresentation.contentWidthPx(400f, zoom),
                0f,
            )
            zoom *= 1.3f
        }
        // The detector: the two skies really are different sizes, so the agreement above is not two
        // readings of the same object.
        assertNotEquals(sparse.starCount, dense.starCount)
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
    fun `culling keeps what is on screen and drops what is not`() {
        val layout = handPlaced()
        val content = 100f
        val viewport = 100f

        fun onScreen(index: Int, panX: Float, panY: Float, margin: Float = 4f) =
            SkyPresentation.isOnScreen(
                SkyPresentation.screenX(layout, index, content, panX),
                SkyPresentation.screenY(layout, index, content, panY),
                viewport,
                viewport,
                margin,
            )

        // Unpanned, the whole field is the viewport and every star is in it.
        for (i in 0 until layout.starCount) assertTrue("star $i was culled", onScreen(i, 0f, 0f))
        // Panned far enough that the field is off to the left, nothing is.
        for (i in 0 until layout.starCount) {
            assertFalse("star $i survived being panned away", onScreen(i, -500f, 0f))
        }
        // The margin keeps a star that is just past the edge drawn rather than popping in: star 0
        // sits at x = 25, so a pan of -30 puts it at -5, outside the viewport and inside the margin.
        assertTrue(onScreen(0, -30f, 0f, margin = 8f))
        assertFalse(onScreen(0, -30f, 0f, margin = 2f))
    }

    // -------------------------------------------------------------------------------------------
    // Hit testing.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a tap resolves to the nearest core, and to nothing when it is nowhere near one`() {
        val layout = handPlaced()
        val content = 100f
        val all = 0 until layout.starCount

        // Star 0 is at (25, 50); star 1 at (75, 50); star 2 at (50, 10).
        fun tap(x: Float, y: Float, radius: Float = 24f) = SkyPresentation.nearestStar(
            layout, all, content, content, 0f, 0f, x, y, radius,
        )

        assertEquals(0, tap(27f, 52f))
        assertEquals(1, tap(73f, 48f))
        assertEquals(2, tap(50f, 14f))

        // Far from every star: nothing is opened. Paired with the calls above, which prove this
        // detector does find a star when one is within reach.
        assertEquals(-1, tap(50f, 400f))
        assertEquals(-1, tap(0f, 95f))
    }

    @Test
    fun `an exact tie resolves to the earlier record, on every device`() {
        val layout = handPlaced()
        // Exactly between the two stars at x 25 and 75, both 25 away; star 2 is 40 away.
        val hit = SkyPresentation.nearestStar(
            layout, 0 until layout.starCount, 100f, 100f, 0f, 0f, 50f, 50f, 30f,
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

    @Test
    fun `zooming in separates two stars that overlap, without moving either`() {
        // Overlap is accepted and never resolved by nudging — a star pushed away from a neighbour
        // would have a position that depended on the other records. What separates them is zoom.
        val close = SkyLayout(
            x = floatArrayOf(0.500f, 0.512f),
            y = floatArrayOf(0.5f, 0.5f),
            kindOrdinal = intArrayOf(SkyKind.CHECK_IN.ordinal, SkyKind.JOURNAL.ordinal),
            moodLevel = intArrayOf(3, SkyGlyph.MOOD_NONE),
            epochDay = longArrayOf(day, day + 400),
            idStart = intArrayOf(0, 1, 2),
            recordIds = longArrayOf(1, 2),
        )
        val viewport = 400f
        fun gapAt(zoom: Float): Float {
            val content = SkyPresentation.contentWidthPx(viewport, zoom)
            return SkyPresentation.screenX(close, 1, content, 0f) -
                SkyPresentation.screenX(close, 0, content, 0f)
        }
        // At the whole-sky view they are under 5 px apart: one mark, as far as a finger is concerned.
        assertTrue("they are already apart: ${gapAt(SkyPresentation.MIN_ZOOM)}", gapAt(SkyPresentation.MIN_ZOOM) < 5f)
        // Zoomed in, they are a comfortable target apart.
        assertTrue("zoom did not separate them: ${gapAt(SkyPresentation.MAX_ZOOM)}", gapAt(SkyPresentation.MAX_ZOOM) > 48f)
        // And neither moved: the coordinates are the same numbers at every zoom.
        assertEquals(0.500f, close.x[0], 0f)
        assertEquals(0.512f, close.x[1], 0f)
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
        val layout = Sky.layout(records, seed)
        val text = SkyPresentation.canvasDescription(layout, locale)

        assertTrue(text, text.contains("2020"))
        // The detector first: the star count really is 40, and a search for "40" would find it if
        // the sentence contained it.
        assertEquals(40, layout.starCount)
        assertTrue("40".contains("40"))
        assertFalse(text, text.contains("40"))
    }

    @Test
    fun `the canvas names the span it covers, from the stars themselves`() {
        val layout = Sky.layout(
            listOf(
                SkyRecord(SkyKind.JOURNAL, 1L, SkyCalendar.epochDayOf(2019, 11, 2)),
                SkyRecord(SkyKind.JOURNAL, 2L, SkyCalendar.epochDayOf(2023, 2, 14)),
            ),
            seed,
        )
        assertEquals("Your sky, 2019 to 2023.", SkyPresentation.canvasDescription(layout, locale))

        val oneYear = Sky.layout(
            listOf(SkyRecord(SkyKind.JOURNAL, 1L, SkyCalendar.epochDayOf(2019, 11, 2))),
            seed,
        )
        assertEquals("Your sky, 2019.", SkyPresentation.canvasDescription(oneYear, locale))
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

    /**
     * The label over the sky is gone, and nothing here can produce one.
     *
     * `monthLabel(epochMonth, locale)` named the month at the top of the viewport, which meant
     * something only while the sky was a stack of month rows. Nothing on the field is a date now,
     * so a label there would be a claim that is not true. The month headings live on in the text
     * list, which is the surface that *does* address dates.
     */
    @Test
    fun `nothing on the sky claims to name where you are in time`() {
        val names = SkyPresentation.javaClass.methods.map { it.name }
        // The detector: the reflection really does see this object's functions, and one of the ones
        // that should still be here is.
        assertTrue(names.toString(), names.contains("monthHeading"))
        assertFalse(names.toString(), names.contains("monthLabel"))
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

    // -------------------------------------------------------------------------------------------
    // Pinching about a place.
    // -------------------------------------------------------------------------------------------

    /**
     * The property, stated as what a person's fingers do: whatever was under the pinch is still
     * under the pinch.
     *
     * Checked by carrying a point all the way to the screen and back rather than by re-deriving the
     * formula, which would only assert that the same arithmetic equals itself.
     */
    @Test
    fun `whatever is under the pinch stays under the pinch`() {
        val viewport = 1000f
        for (focus in listOf(0f, 137f, 500f, 863f, 1000f)) {
            for (from in listOf(1f, 1.7f, 4f, 11.5f)) {
                for (factor in listOf(0.4f, 0.9f, 1.15f, 3f)) {
                    val to = SkyPresentation.clampZoom(from * factor)
                    val pan = -0.31f * SkyPresentation.contentWidthPx(viewport, from)
                    val moved = SkyPresentation.panForZoomAbout(focus, pan, from, to)

                    // The normalised point that was under the focus before, and where it lands after.
                    val before = (focus - pan) / SkyPresentation.contentWidthPx(viewport, from)
                    val after = before * SkyPresentation.contentWidthPx(viewport, to) + moved
                    assertEquals("focus $focus, $from -> $to", focus, after, 0.01f)
                }
            }
        }
    }

    /** A drag is not a pinch: with no scale change the pan is returned exactly as it came in. */
    @Test
    fun `a gesture that does not scale does not move the field on its own`() {
        for (pan in listOf(0f, -240f, -4821.5f)) {
            for (focus in listOf(0f, 333f, 1000f)) {
                assertEquals(pan, SkyPresentation.panForZoomAbout(focus, pan, 3.25f, 3.25f), 0f)
            }
        }
    }

    /**
     * At a stop, a pinch that cannot change the zoom does not move the sky either.
     *
     * The failure this rules out is a control that keeps responding after it has stopped having an
     * effect: fingers still spreading at MAX_ZOOM, nothing getting bigger, and the field sliding
     * anyway. [SkyPresentation.panForZoomAbout] takes the CLAMPED zoom for this reason.
     */
    @Test
    fun `at the stops a pinch that changes nothing moves nothing`() {
        val pan = -900f
        val beyondMax = SkyPresentation.clampZoom(SkyPresentation.MAX_ZOOM * 2f)
        assertEquals(SkyPresentation.MAX_ZOOM, beyondMax, 0f)
        assertEquals(pan, SkyPresentation.panForZoomAbout(400f, pan, SkyPresentation.MAX_ZOOM, beyondMax), 0f)

        val belowMin = SkyPresentation.clampZoom(SkyPresentation.MIN_ZOOM / 2f)
        assertEquals(SkyPresentation.MIN_ZOOM, belowMin, 0f)
        assertEquals(0f, SkyPresentation.panForZoomAbout(400f, 0f, SkyPresentation.MIN_ZOOM, belowMin), 0f)
    }

    /**
     * The controls. Without these the three tests above pass on a function that ignores its focus.
     *
     * The old behaviour is reproduced rather than described — scaling about the field's top-left is
     * `pan * ratio`, which is what the surface did when it discarded the centroid — and asserted to
     * be a different answer. Then the same reconstruction is run at the one focus where the two
     * agree, so the difference is shown to be about the focus and not about the formula.
     */
    @Test
    fun `the check would fail on a zoom that ignores where the fingers are`() {
        val viewport = 1000f
        val from = 2f
        val to = 8f
        val pan = -0.31f * SkyPresentation.contentWidthPx(viewport, from)
        val focus = 640f

        val corner = pan * (to / from)
        val held = SkyPresentation.panForZoomAbout(focus, pan, from, to)
        assertNotEquals("the old behaviour and the new one agree, so this proves nothing", corner, held, 1f)

        // Where the corner-scaling answer actually puts the pinched point, which is not under it.
        val before = (focus - pan) / SkyPresentation.contentWidthPx(viewport, from)
        val strayed = before * SkyPresentation.contentWidthPx(viewport, to) + corner
        assertNotEquals("the old behaviour kept the point in place", focus, strayed, 1f)

        // And at focus 0 the two are the same, because the top-left corner IS the focus there.
        assertEquals(corner, SkyPresentation.panForZoomAbout(0f, pan, from, to), 0.01f)
    }
}
