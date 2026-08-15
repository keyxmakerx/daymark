package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a hard day looks like, asserted rather than intended.
 *
 * The failure this guards against is not hypothetical and it is not hidden — it ships today.
 * `ui/components/YearInStarsGrid.kt` sizes a star from 1.7 dp at mood level 1 to 3.9 dp at level 5,
 * adds a glow disc and cross-rays for levels 4 and 5 only, and says so in its own docstring: *"the
 * amount of twinkle itself reads as how a stretch of life went."* A person opening that in a bad
 * week is shown their worst months as their faintest ones.
 *
 * So the property is stated as arithmetic. Every quantity a person could read as *more* or *less* is
 * held constant across the mood scale, and the two that vary are shown to vary in opposite
 * directions with their product fixed — a redistribution of light, not an amount of it.
 *
 * The constancy checks are run through one helper, and the helper is pointed at a function that
 * *does* vary before it is trusted, because a checker that cannot fail certifies nothing.
 */
class SkyGlyphTest {

    private val moods = (SkyGlyph.MOOD_MIN..SkyGlyph.MOOD_MAX).toList()

    /** Fails if [quantity] is not the same at every mood level. */
    private fun assertConstantAcrossMoods(name: String, quantity: (Int) -> Float) {
        val first = quantity(moods.first())
        for (level in moods) {
            assertEquals("$name varies with mood: level $level", first, quantity(level), 0f)
        }
    }

    @Test
    fun `the constancy check can fail`() {
        // Run first. Everything below is an absence assertion and is worth nothing without this.
        var caught = false
        try {
            assertConstantAcrossMoods("a deliberately mood-ranked size") { 1.7f + it * 0.55f }
        } catch (expected: AssertionError) {
            caught = true
        }
        assertTrue("the checker accepted a size that ranks moods", caught)
    }

    @Test
    fun `the core is identical at every mood and every kind`() {
        // M4. This is the one that matters: presence is the core, and the core does not know what
        // mood it is.
        for (kind in SkyKind.entries) {
            assertConstantAcrossMoods("core radius for $kind") { SkyGlyph.coreRadiusDp(kind, it) }
            assertConstantAcrossMoods("core alpha for $kind") { SkyGlyph.coreAlpha(kind, it) }
        }
        // And across kinds too, so no kind of act outranks another.
        val radii = SkyKind.entries.map { SkyGlyph.coreRadiusDp(it, SkyGlyph.MOOD_NONE) }.toSet()
        assertEquals("some kinds draw a bigger core than others", 1, radii.size)
        assertEquals(SkyGlyph.CORE_RADIUS_DP, radii.first(), 0f)
    }

    @Test
    fun `total light is the same at every mood`() {
        // The definition of "quieter" that this file commits to: a hard day's light is spread
        // wider and softer, a good day's is concentrated. Neither is more.
        assertConstantAcrossMoods("total halo light") {
            SkyGlyph.haloRadiusDp(it) * SkyGlyph.haloRadiusDp(it) * SkyGlyph.haloPeakAlpha(it)
        }
        assertEquals(SkyGlyph.HALO_LIGHT, SkyGlyph.haloRadiusDp(3) * SkyGlyph.haloRadiusDp(3) * SkyGlyph.haloPeakAlpha(3), 1e-5f)
    }

    @Test
    fun `the two things that vary move in opposite directions`() {
        // If they ever moved together, one of them would be an amount of light and the ramp would
        // be a brightness ranking again.
        for (level in SkyGlyph.MOOD_MIN until SkyGlyph.MOOD_MAX) {
            assertTrue(
                "halo radius did not narrow from level $level to ${level + 1}",
                SkyGlyph.haloRadiusDp(level) > SkyGlyph.haloRadiusDp(level + 1),
            )
            assertTrue(
                "halo peak did not rise from level $level to ${level + 1}",
                SkyGlyph.haloPeakAlpha(level) < SkyGlyph.haloPeakAlpha(level + 1),
            )
        }
    }

    @Test
    fun `the variation is texture, not a chart`() {
        // Mood should be legible up close and invisible at a glance. A wide spread would let anyone
        // read a bad month off the overview — including whoever is looking over their shoulder.
        val widest = SkyGlyph.haloRadiusDp(SkyGlyph.MOOD_MIN)
        val tightest = SkyGlyph.haloRadiusDp(SkyGlyph.MOOD_MAX)
        assertTrue("mood is doing nothing at all", widest > tightest)
        assertTrue("mood is shouting: $widest vs $tightest dp", widest / tightest < 1.5f)
    }

    @Test
    fun `a star with no mood sits in the middle, not at the bad end`() {
        // Four of the six kinds have no mood. An uncoloured star must not be drawn as if it were
        // the worst one — the sky's ink is its brightest value, not its dimmest.
        assertEquals(SkyGlyph.HALO_RADIUS_NEUTRAL_DP, SkyGlyph.haloRadiusDp(SkyGlyph.MOOD_NONE), 0f)
        assertTrue(SkyGlyph.haloRadiusDp(SkyGlyph.MOOD_NONE) < SkyGlyph.haloRadiusDp(SkyGlyph.MOOD_MIN))
        assertTrue(SkyGlyph.haloRadiusDp(SkyGlyph.MOOD_NONE) > SkyGlyph.haloRadiusDp(SkyGlyph.MOOD_MAX))
        // A level from a future version, or a corrupt row, also lands in the middle rather than
        // being guessed at.
        for (nonsense in intArrayOf(-3, 0, 6, 99)) {
            assertEquals(SkyGlyph.HALO_RADIUS_NEUTRAL_DP, SkyGlyph.haloRadiusDp(nonsense), 0f)
        }
    }

    @Test
    fun `rays are a kind, never a reward`() {
        // The shipped component gives cross-rays to mood levels 4 and 5. Here they mark a goal the
        // person reached and a life event they placed — acts, not moods — and the signature takes a
        // kind, so a mood cannot get at them without a visible change.
        assertEquals(4, SkyGlyph.rayCount(SkyKind.GOAL_REACHED))
        assertEquals(4, SkyGlyph.rayCount(SkyKind.LIFE_EVENT))
        assertEquals(0, SkyGlyph.rayCount(SkyKind.CHECK_IN))
        assertEquals(0, SkyGlyph.rayCount(SkyKind.JOURNAL))
        assertEquals(0, SkyGlyph.rayCount(SkyKind.EXERCISE))
        assertEquals(0, SkyGlyph.rayCount(SkyKind.PROJECT_STEP))
    }

    @Test
    fun `the only star allowed to be louder is the one the person authored`() {
        // Prominence follows authorship, not value. Nothing the software creates outranks anything
        // else the software creates — and the loudness is in the rays, not the core, so equal
        // presence survives.
        val lengths = SkyKind.entries.associateWith { SkyGlyph.rayLengthDp(it) }
        val louder = lengths.filter { it.value > SkyGlyph.rayLengthDp(SkyKind.CHECK_IN) }.keys
        assertEquals(setOf(SkyKind.LIFE_EVENT), louder)
        assertEquals(SkyGlyph.CORE_RADIUS_DP, SkyGlyph.coreRadiusDp(SkyKind.LIFE_EVENT, 3), 0f)
    }

    @Test
    fun `every kind is distinguishable without colour`() {
        // Colour never carries kind, so the forms have to. Two kinds with the same rays, ring,
        // underline, stub and ray length would be the same silhouette in monochrome.
        //
        // This sweep has already earned its place once: without `threadStubDp` a project step and
        // a check-in were the same glyph, because a project step was distinguished only by the
        // thread back to the previous step — which the *first* step of every project does not have.
        val silhouettes = SkyKind.entries.map {
            listOf(
                SkyGlyph.rayCount(it).toFloat(),
                SkyGlyph.ringRadiusDp(it),
                SkyGlyph.underlineWidthDp(it),
                SkyGlyph.threadStubDp(it),
                SkyGlyph.rayLengthDp(it),
            )
        }
        for (i in silhouettes.indices) {
            for (j in (i + 1) until silhouettes.size) {
                assertNotEquals(
                    "${SkyKind.entries[i]} and ${SkyKind.entries[j]} share a silhouette",
                    silhouettes[i],
                    silhouettes[j],
                )
            }
        }
    }

    @Test
    fun `the touch target does not shrink with the drawn star`() {
        assertTrue(SkyGlyph.TOUCH_TARGET_DP >= 48f)
        assertTrue("the target is derived from the drawn size", SkyGlyph.TOUCH_TARGET_DP > SkyGlyph.CORE_RADIUS_DP * 10f)
    }

    // -------------------------------------------------------------------------------------------

    @Test
    fun `zoom levels are ordered and reachable`() {
        assertEquals(SkyDetail.DRIFT, SkyDetail.forVisibleMonths(120f))
        assertEquals(SkyDetail.DRIFT, SkyDetail.forVisibleMonths(12.5f))
        assertEquals(SkyDetail.SEASON, SkyDetail.forVisibleMonths(6f))
        assertEquals(SkyDetail.MONTH, SkyDetail.forVisibleMonths(2f))
        assertEquals(SkyDetail.NIGHT, SkyDetail.forVisibleMonths(1f))
        assertEquals(SkyDetail.NIGHT, SkyDetail.forVisibleMonths(0.03f))

        // Monotonic: zooming in never goes back out a level.
        var previous = SkyDetail.forVisibleMonths(400f).ordinal
        var v = 400f
        while (v > 0.01f) {
            val here = SkyDetail.forVisibleMonths(v).ordinal
            assertTrue("zooming in at $v months went backwards", here >= previous)
            previous = here
            v *= 0.97f
        }
    }

    @Test
    fun `the overview is points and the month is glyphs`() {
        assertTrue(!SkyDetail.drawsGlyphs(SkyDetail.DRIFT))
        assertTrue(SkyDetail.drawsGlyphs(SkyDetail.MONTH))
        // The project thread is the only line on the Sky, and it is gone at the overview.
        assertTrue(!SkyDetail.drawsThreads(SkyDetail.DRIFT))
        assertTrue(!SkyDetail.drawsThreads(SkyDetail.SEASON))
        assertTrue(SkyDetail.drawsThreads(SkyDetail.MONTH))
        // A canvas of thousands of points is not navigable, so stars only become focusable once
        // there are few enough of them on screen for that to mean something.
        assertTrue(!SkyDetail.starsAreFocusable(SkyDetail.DRIFT))
        assertTrue(!SkyDetail.starsAreFocusable(SkyDetail.SEASON))
        assertTrue(SkyDetail.starsAreFocusable(SkyDetail.MONTH))
    }

    @Test
    fun `the ordinary sky is the default and the quiet one is reachable`() {
        val ordinary = SkyOptions()
        assertTrue(ordinary.fieldEnabled)
        assertTrue(ordinary.motionEnabled)
        assertTrue(!ordinary.highContrast)
        val quiet = SkyOptions(fieldEnabled = false, motionEnabled = false, highContrast = true)
        assertNotEquals(ordinary, quiet)
    }
}
