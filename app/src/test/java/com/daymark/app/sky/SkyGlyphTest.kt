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
        assertEquals(0, SkyGlyph.rayCount(SkyKind.PRACTICE))
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
    // Colour and brightness, which are age now. The sweeps below are what stops mood reaching them.
    // -------------------------------------------------------------------------------------------

    /** Every mood level a renderer could hand over, including ones this version does not have. */
    private val everyMoodLevel = listOf(-3, SkyGlyph.MOOD_NONE, 1, 2, 3, 4, 5, 6, 99)

    /** Ages either side of every stop on the redshift, and past the end of it. */
    private val sampleAges = floatArrayOf(0f, 0.1f, 0.59f, 0.6f, 1.6f, 2.4f, 3.2f, 5.5f, 9f)

    private val sampleIds = longArrayOf(1L, 2L, 17L, 4_242L, 900_001L)

    /**
     * Everything about a drawn star **except its halo**, which is the one thing mood is allowed to
     * move. Whatever else ends up on a star belongs in this list, because this list is what the
     * mood sweep compares.
     */
    private fun drawnStar(kind: SkyKind, id: Long, ageYears: Float, moodLevel: Int): List<Float> {
        val tint = SkyGlyph.starTint(kind, id, ageYears, moodLevel)
        return listOf(
            SkyGlyph.coreRadiusDp(kind, moodLevel) * SkyGlyph.coreScale(kind),
            SkyGlyph.coreAlpha(kind, moodLevel),
            ((tint shr 16) and 0xFF).toFloat(),
            ((tint shr 8) and 0xFF).toFloat(),
            (tint and 0xFF).toFloat(),
            SkyGlyph.starBrightness(kind, ageYears, moodLevel),
            SkyTwinkle.alphaAt(kind, id, 3_700L, SkyOptions()),
            SkyTwinkle.scaleAt(kind, id, 3_700L, SkyOptions()),
            SkyGlyph.rayCount(kind).toFloat(),
            SkyGlyph.rayLengthDp(kind),
            SkyGlyph.ringRadiusDp(kind),
            SkyGlyph.underlineWidthDp(kind),
            SkyGlyph.threadStubDp(kind),
        )
    }

    @Test
    fun `the drawn-star comparison can fail`() {
        // Run first. The sweep below is an absence assertion over a list of numbers, and a list
        // comparison that cannot see a difference certifies nothing. The halo is the difference:
        // it is the one thing mood still moves, and it is deliberately not in `drawnStar`.
        val hard = drawnStar(SkyKind.CHECK_IN, 7L, 1f, 1) + SkyGlyph.haloRadiusDp(1)
        val good = drawnStar(SkyKind.CHECK_IN, 7L, 1f, 5) + SkyGlyph.haloRadiusDp(5)
        assertNotEquals("the comparison cannot see a quantity that does vary with mood", hard, good)
    }

    @Test
    fun `no star's colour and no star's brightness is a function of mood`() {
        // The property the whole redesign turns on. Colour is age and brightness is age; mood keeps
        // the halo and nothing else. Somebody's worst month must not be their reddest or their
        // faintest, and that is now true by construction — `starTint` and `starBrightness` take a
        // mood level and ignore it, the way `coreRadiusDp` always has.
        for (kind in SkyKind.entries) {
            for (id in sampleIds) {
                for (age in sampleAges) {
                    val reference = drawnStar(kind, id, age, everyMoodLevel.first())
                    for (level in everyMoodLevel) {
                        assertEquals(
                            "$kind $id at $age years is drawn differently at mood $level",
                            reference,
                            drawnStar(kind, id, age, level),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `colour and brightness do move with age, so the sweep above is not over a constant`() {
        // The other half of the control: a tint and a fade that never changed at all would pass
        // every assertion in the test above.
        val young = SkyGlyph.starTint(SkyKind.CHECK_IN, 17L, 0f, 3)
        val old = SkyGlyph.starTint(SkyKind.CHECK_IN, 17L, 5.5f, 3)
        assertNotEquals("the redshift does nothing", young, old)
        assertTrue(
            "the redshift does not redden",
            ((old shr 16) and 0xFF) - (old and 0xFF) > ((young shr 16) and 0xFF) - (young and 0xFF),
        )
        assertTrue(
            "the fade does nothing",
            SkyGlyph.starBrightness(SkyKind.CHECK_IN, 5.5f, 3) <
                SkyGlyph.starBrightness(SkyKind.CHECK_IN, 0f, 3),
        )
    }

    // -------------------------------------------------------------------------------------------
    // Temperature: variety that says nothing.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a star's temperature is its own, and all four are used`() {
        val counts = IntArray(SkyGlyph.TEMPERATURE_COUNT)
        for (id in 1L..2000L) {
            val index = SkyGlyph.temperatureIndex(SkyKind.CHECK_IN, id)
            assertEquals("temperature is not stable", index, SkyGlyph.temperatureIndex(SkyKind.CHECK_IN, id))
            assertTrue("temperature $index out of range", index in 0 until SkyGlyph.TEMPERATURE_COUNT)
            counts[index]++
        }
        for (i in counts.indices) {
            val share = counts[i].toDouble() / 2000.0
            println("  temperature $i: ${"%.1f".format(share * 100)}%")
            assertTrue("temperature $i is all but unused: $share", share > 0.15)
            assertTrue("temperature $i has taken over the sky: $share", share < 0.35)
        }
        // Out of range clamps rather than throwing: a sprite cache keyed on an index from an older
        // version must not crash the sky.
        assertEquals(SkyGlyph.temperatureTint(0), SkyGlyph.temperatureTint(-1))
        assertEquals(
            SkyGlyph.temperatureTint(SkyGlyph.TEMPERATURE_COUNT - 1),
            SkyGlyph.temperatureTint(99),
        )
    }

    @Test
    fun `temperature varies the sky without hiding the redshift`() {
        // The mix is about a third, and a third is the number that has to be defended: too much and
        // an icy old star reads younger than a peach new one, which would make colour meaningless
        // rather than merely decorative.
        val idOfTemperature = LongArray(SkyGlyph.TEMPERATURE_COUNT) { -1L }
        var id = 1L
        while (id < 500L && idOfTemperature.any { it < 0L }) {
            val index = SkyGlyph.temperatureIndex(SkyKind.CHECK_IN, id)
            if (idOfTemperature[index] < 0L) idOfTemperature[index] = id
            id++
        }
        assertTrue("could not find one star of each temperature", idOfTemperature.all { it > 0L })

        fun warmth(temperature: Int, age: Float): Int {
            val tint = SkyGlyph.starTint(SkyKind.CHECK_IN, idOfTemperature[temperature], age, 3)
            return ((tint shr 16) and 0xFF) - (tint and 0xFF)
        }

        val ages = ArrayList<Float>()
        var age = 0f
        while (age <= 8f) {
            ages.add(age)
            age += 0.05f
        }

        // Within one temperature the ramp still only reddens.
        for (temperature in 0 until SkyGlyph.TEMPERATURE_COUNT) {
            var previous = warmth(temperature, 0f)
            for (a in ages) {
                val here = warmth(temperature, a)
                assertTrue("temperature $temperature cooled at age $a", here >= previous)
                previous = here
            }
        }

        val acrossTemperatures = ages.maxOf { a ->
            (0 until SkyGlyph.TEMPERATURE_COUNT).maxOf { warmth(it, a) } -
                (0 until SkyGlyph.TEMPERATURE_COUNT).minOf { warmth(it, a) }
        }
        val acrossAges = (0 until SkyGlyph.TEMPERATURE_COUNT).minOf { t ->
            ages.maxOf { warmth(t, it) } - ages.minOf { warmth(t, it) }
        }
        println("  warmth spread: $acrossTemperatures across temperature, $acrossAges across age")
        assertTrue("temperature is doing nothing", acrossTemperatures > 10)
        assertTrue(
            "temperature is louder than the redshift: $acrossTemperatures vs $acrossAges",
            acrossTemperatures * 2 < acrossAges,
        )
        // And the reading that matters at a glance: this year is colder than five years ago,
        // whichever temperatures the two stars happened to draw.
        val newest = (0 until SkyGlyph.TEMPERATURE_COUNT).maxOf { warmth(it, 0f) }
        val oldest = (0 until SkyGlyph.TEMPERATURE_COUNT).minOf { warmth(it, 5f) }
        assertTrue("an old star can read as new: $newest vs $oldest", newest < oldest)
    }

    // -------------------------------------------------------------------------------------------
    // The landmark, which is the one exception and follows authorship rather than measurement.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a landmark is bigger and brighter, and nothing else is`() {
        for (kind in SkyKind.entries) {
            if (kind == SkyKind.LIFE_EVENT) continue
            assertEquals(
                "$kind is drawn louder than the mark the person placed by hand",
                1f,
                SkyGlyph.coreScale(kind),
                0f,
            )
            for (level in everyMoodLevel) {
                assertEquals(SkyGlyph.haloRadiusDp(level), SkyGlyph.haloRadiusDp(kind, level), 0f)
                assertEquals(SkyGlyph.haloPeakAlpha(level), SkyGlyph.haloPeakAlpha(kind, level), 0f)
            }
        }

        val landmark = SkyKind.LIFE_EVENT
        assertTrue("a landmark is no bigger than anything else", SkyGlyph.coreScale(landmark) > 1f)
        // Bigger and brighter than any mood can make an ordinary star — so a landmark is found by
        // looking, and a hard day can never be mistaken for one.
        for (level in moods) {
            assertTrue(SkyGlyph.haloRadiusDp(landmark, level) > SkyGlyph.haloRadiusDp(level))
            assertTrue(SkyGlyph.haloPeakAlpha(landmark, level) > SkyGlyph.haloPeakAlpha(level))
        }
        // And none of that is mood: a landmark carries no mood at all, and is drawn identically
        // whatever one arrives attached to it.
        assertConstantAcrossMoods("a landmark's halo radius") { SkyGlyph.haloRadiusDp(landmark, it) }
        assertConstantAcrossMoods("a landmark's halo peak") { SkyGlyph.haloPeakAlpha(landmark, it) }
        // The core constant is untouched: the landmark's size is a scale applied to it, and M4 is
        // still one radius and one alpha for every star on the surface.
        assertEquals(SkyGlyph.CORE_RADIUS_DP, SkyGlyph.coreRadiusDp(landmark, 3), 0f)
        assertEquals(SkyGlyph.CORE_ALPHA, SkyGlyph.coreAlpha(landmark, 3), 0f)
    }

    // -------------------------------------------------------------------------------------------

    @Test
    fun `zoom levels are ordered and reachable`() {
        assertEquals(SkyDetail.FAR, SkyDetail.forZoom(1f))
        assertEquals(SkyDetail.FAR, SkyDetail.forZoom(SkyDetail.NEAR_ZOOM - 0.01f))
        assertEquals(SkyDetail.NEAR, SkyDetail.forZoom(SkyDetail.NEAR_ZOOM))
        assertEquals(SkyDetail.NEAR, SkyDetail.forZoom(SkyDetail.CLOSE_ZOOM - 0.01f))
        assertEquals(SkyDetail.CLOSE, SkyDetail.forZoom(SkyDetail.CLOSE_ZOOM))
        assertEquals(SkyDetail.CLOSE, SkyDetail.forZoom(400f))

        // Monotonic: zooming in never goes back out a level.
        var previous = SkyDetail.forZoom(0.01f).ordinal
        var zoom = 0.01f
        while (zoom < 400f) {
            val here = SkyDetail.forZoom(zoom).ordinal
            assertTrue("zooming in at $zoom went backwards", here >= previous)
            previous = here
            zoom *= 1.03f
        }
        // And every level is actually reached, or a threshold has swallowed one.
        assertEquals(
            SkyDetail.entries.toSet(),
            generateSequence(0.01f) { it * 1.03f }.takeWhile { it < 400f }
                .map { SkyDetail.forZoom(it) }.toSet(),
        )
    }

    @Test
    fun `everything is a point until you lean all the way in`() {
        // THIS TEST WAS INVERTED IN SEPTEMBER 2026. It used to assert that a star at the month
        // level DID draw its kind mark.
        //
        // `docs/SKY.md` §3.3, agreed with the maintainer: "Kind marks appear only at CLOSE (§4).
        // Further out, every kind is just a star, and the list always names it."
        //
        // It is inverted rather than deleted on purpose: the old assertion was the record that the
        // Sky used to sort a person's days into kinds of act at a zoom where a whole stretch of a
        // life is on screen, and the new one has to be just as hard to change back by accident.
        assertTrue("a kind mark at the whole sky", !SkyDetail.drawsGlyphs(SkyDetail.FAR))
        assertTrue("a kind mark part of the way in", !SkyDetail.drawsGlyphs(SkyDetail.NEAR))
        assertTrue("leaning all the way in resolves nothing", SkyDetail.drawsGlyphs(SkyDetail.CLOSE))
        // The positive control this needs. Every level above says "no", so without one level that
        // says "yes" the sweep would pass just as happily against `= false`, and the kind marks
        // would be gone from the app entirely with a green suite over it.
        assertTrue(
            "nothing draws a kind mark at any level, so the glyphs are unreachable",
            SkyDetail.entries.any { SkyDetail.drawsGlyphs(it) },
        )

        // The project thread is the only line on the Sky, and it is gone at the overview.
        assertTrue(!SkyDetail.drawsThreads(SkyDetail.FAR))
        assertTrue(!SkyDetail.drawsThreads(SkyDetail.NEAR))
        assertTrue(SkyDetail.drawsThreads(SkyDetail.CLOSE))
        // A canvas of thousands of points is not navigable, so stars only become focusable once
        // there are few enough of them on screen for that to mean something.
        assertTrue(!SkyDetail.starsAreFocusable(SkyDetail.FAR))
        assertTrue(SkyDetail.starsAreFocusable(SkyDetail.NEAR))
        assertTrue(SkyDetail.starsAreFocusable(SkyDetail.CLOSE))
    }

    // -------------------------------------------------------------------------------------------
    // The halo's radial fade — September 2026, and the same change as the near-black ground.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the halo fades to nothing instead of ending on an edge`() {
        // The flat translucent disc had a hard boundary at its radius. `docs/SKY.md` §3.5 replaces
        // it with a fade, and "to exactly nothing" is the part that is checkable: the outermost
        // stop must be exactly zero.
        assertEquals(1f, SkyGlyph.HALO_STOP_POSITION.last(), 0f)
        assertEquals(0f, SkyGlyph.HALO_STOP_WEIGHT.last(), 0f)
        assertEquals(0f, SkyGlyph.HALO_STOP_POSITION.first(), 0f)
        assertEquals(
            "the stops and their weights are different lengths",
            SkyGlyph.HALO_STOP_POSITION.size,
            SkyGlyph.HALO_STOP_WEIGHT.size,
        )
        // Monotonic in both, or it is not a fade: light that rises again on the way out is a ring.
        for (i in 1 until SkyGlyph.HALO_STOP_POSITION.size) {
            assertTrue(
                "stop $i does not move outward",
                SkyGlyph.HALO_STOP_POSITION[i] > SkyGlyph.HALO_STOP_POSITION[i - 1],
            )
            assertTrue(
                "stop $i is brighter than the one inside it",
                SkyGlyph.HALO_STOP_WEIGHT[i] < SkyGlyph.HALO_STOP_WEIGHT[i - 1],
            )
        }
        // And it ends past where the light is concentrated, which is what makes it a glow around a
        // point rather than a disc with a soft rim.
        for (level in SkyGlyph.MOOD_NONE..SkyGlyph.MOOD_MAX) {
            assertTrue(
                "the fade ends inside the halo radius at level $level",
                SkyGlyph.outerGlowRadiusDp(SkyKind.CHECK_IN, level) >
                    SkyGlyph.haloRadiusDp(SkyKind.CHECK_IN, level),
            )
        }
    }

    @Test
    fun `the fade emits the same total light at every mood`() {
        // The invariant `HALO_LIGHT` claims, restated against the shape that is now drawn. A flat
        // disc's total was `peak x radius²` by definition; a gradient's is the profile integrated
        // over the disc, and it has to come out flat for the same reason — mood is a quality of
        // light on this surface and never a quantity of it.
        val emitted = (SkyGlyph.MOOD_MIN..SkyGlyph.MOOD_MAX).map {
            SkyGlyph.haloEmittedLight(SkyKind.CHECK_IN, it)
        }
        for (i in emitted.indices) {
            println("  level ${i + 1} emits ${"%.4f".format(emitted[i])}")
        }
        assertEquals("the widest and the tightest halo emit different light",
            emitted.min().toDouble(), emitted.max().toDouble(), 1e-3)
        assertEquals("the profile area moved; SkyGlyph's header quotes 0.0831",
            0.0831, SkyGlyph.PROFILE_AREA.toDouble(), 1e-4)

        // Positive control. Every level emitting the same number would also be true of a function
        // that ignored its argument, so the radius and the peak really do move underneath it.
        assertTrue(
            "the halo radius no longer varies with mood, so flat total light proves nothing",
            SkyGlyph.haloRadiusDp(SkyKind.CHECK_IN, SkyGlyph.MOOD_MIN) !=
                SkyGlyph.haloRadiusDp(SkyKind.CHECK_IN, SkyGlyph.MOOD_MAX),
        )
        assertTrue(
            "the halo peak no longer varies with mood, so flat total light proves nothing",
            SkyGlyph.haloPeakAlpha(SkyKind.CHECK_IN, SkyGlyph.MOOD_MIN) !=
                SkyGlyph.haloPeakAlpha(SkyKind.CHECK_IN, SkyGlyph.MOOD_MAX),
        )

        // And the one star that is allowed to be louder, because a person placed it by hand.
        val landmark = SkyGlyph.haloEmittedLight(SkyKind.LIFE_EVENT, SkyGlyph.MOOD_NONE)
        println("  a landmark emits ${"%.4f".format(landmark)}")
        assertTrue("a landmark is not the one bright star", landmark > emitted.max())
    }

    @Test
    fun `a stop's alpha is the peak's, never mood's own idea`() {
        // Each stop is a fixed fraction of the star's own peak, so the SHAPE of the fall is
        // identical at every mood and only its scale moves. A profile that changed shape with mood
        // would be a second, hidden channel for mood to speak through.
        for (index in SkyGlyph.HALO_STOP_WEIGHT.indices) {
            for (level in SkyGlyph.MOOD_MIN..SkyGlyph.MOOD_MAX) {
                val peak = SkyGlyph.haloPeakAlpha(SkyKind.CHECK_IN, level)
                assertEquals(
                    "stop $index at level $level is not the peak's own fraction",
                    (SkyGlyph.HALO_STOP_WEIGHT[index] * peak).toDouble(),
                    SkyGlyph.haloStopAlpha(SkyKind.CHECK_IN, level, index).toDouble(),
                    1e-6,
                )
            }
        }
        // Out-of-range indices clamp rather than throw: the renderer walks these in a loop and a
        // crash on this surface is the worst possible failure mode.
        assertEquals(
            SkyGlyph.haloStopAlpha(SkyKind.CHECK_IN, 3, 0).toDouble(),
            SkyGlyph.haloStopAlpha(SkyKind.CHECK_IN, 3, -1).toDouble(),
            1e-6,
        )
        assertEquals(
            SkyGlyph.haloStopAlpha(SkyKind.CHECK_IN, 3, SkyGlyph.HALO_STOP_WEIGHT.size - 1).toDouble(),
            SkyGlyph.haloStopAlpha(SkyKind.CHECK_IN, 3, 99).toDouble(),
            1e-6,
        )
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
