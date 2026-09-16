package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redshift, and the two things it is not allowed to do: jump, and reach zero.
 *
 * Every absence assertion in this file is run through a helper, and every helper is pointed at a
 * planted counter-example first. A sweep that cannot see a stepped ramp certifies nothing about a
 * continuous one, and this repository's most common bug is exactly that — a check written against
 * an assumption that goes green when the assumption stops holding.
 */
class SkyAgeTest {

    /** Ages worth naming: the stops, either side of each stop, and the far end. */
    private val stops = floatArrayOf(0f, 0.6f, 1.6f, 3.2f, 5.5f)

    private fun red(rgb: Int): Int = (rgb shr 16) and 0xFF

    private fun green(rgb: Int): Int = (rgb shr 8) and 0xFF

    private fun blue(rgb: Int): Int = rgb and 0xFF

    /** How far from blue-white toward red a colour sits. Needs no colour space to be useful. */
    private fun warmth(rgb: Int): Int = red(rgb) - blue(rgb)

    /**
     * Fails if [ramp] moves any channel by more than [maxStep] between two ages 0.005 years apart.
     *
     * 0.005 years is under two days, and the steepest stretch of the real ramp moves a channel by
     * about 98 per year, so one step is worth half a unit. A jump at a stop — the thing a ramp
     * written as a table gets wrong — moves a channel by tens of units in one step and is caught
     * however coarse the sampling is.
     */
    private fun assertContinuous(name: String, maxStep: Int, ramp: (Float) -> Int) {
        var age = 0f
        var previous = ramp(0f)
        while (age <= 8f) {
            age += 0.005f
            val here = ramp(age)
            val step = maxOf(
                Math.abs(red(here) - red(previous)),
                Math.abs(green(here) - green(previous)),
                Math.abs(blue(here) - blue(previous)),
            )
            assertTrue(
                "$name jumped $step at age $age (#${"%06X".format(previous)} -> #${"%06X".format(here)})",
                step <= maxStep,
            )
            previous = here
        }
    }

    // -------------------------------------------------------------------------------------------
    // The ramp.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the continuity check can fail`() {
        // Run first. Everything below it is worth nothing without this.
        var caught = false
        try {
            // A ramp that holds a colour for a year and then changes it — the shape someone writes
            // when they reach for a `when` block instead of an interpolation.
            assertContinuous("a deliberately stepped ramp", 2) { age ->
                if (age < 1f) 0xC4DAFF else 0xFF6E58
            }
        } catch (expected: AssertionError) {
            caught = true
        }
        assertTrue("the checker accepted a ramp with a cliff in it", caught)
    }

    @Test
    fun `the age ramp is continuous, with no jump at a boundary`() {
        assertContinuous("the redshift", 2) { SkyAge.tintFor(it) }

        // And at the stops specifically, from both sides, where a table-driven ramp breaks.
        for (stop in stops) {
            if (stop <= 0f) continue
            val before = SkyAge.tintFor(stop - 0.001f)
            val at = SkyAge.tintFor(stop)
            val after = SkyAge.tintFor(stop + 0.001f)
            for (pair in listOf(before to at, at to after)) {
                val step = maxOf(
                    Math.abs(red(pair.first) - red(pair.second)),
                    Math.abs(green(pair.first) - green(pair.second)),
                    Math.abs(blue(pair.first) - blue(pair.second)),
                )
                assertTrue("the ramp jumps at the $stop-year stop: $step", step <= 1)
            }
        }
    }

    @Test
    fun `the age ramp only ever reddens`() {
        // Monotonic in the two directions that carry the redshift: blue drains and warmth rises.
        // Green is not monotonic and is not asserted to be — it rises into the white stop and falls
        // away through gold, which is what makes the middle of the ramp look like a star and not
        // like a colour wheel.
        var age = 0f
        var previous = SkyAge.tintFor(0f)
        while (age <= 8f) {
            age += 0.005f
            val here = SkyAge.tintFor(age)
            assertTrue("blue rose again at age $age", blue(here) <= blue(previous))
            assertTrue("the ramp cooled at age $age", warmth(here) >= warmth(previous))
            previous = here
        }
        // And it moves at all, over the range that matters — a constant ramp would pass every
        // monotonicity assertion above.
        assertTrue(
            "the ramp does not redden",
            warmth(SkyAge.tintFor(5.5f)) - warmth(SkyAge.tintFor(0f)) > 150,
        )
    }

    @Test
    fun `both ends are clamped and a broken age never invents a colour`() {
        assertEquals(SkyAge.NEWEST_TINT, SkyAge.tintFor(0f))
        assertEquals(SkyAge.NEWEST_TINT, SkyAge.tintFor(-1f))
        assertEquals(SkyAge.NEWEST_TINT, SkyAge.tintFor(Float.NaN))
        assertEquals(SkyAge.NEWEST_TINT, SkyAge.tintFor(Float.NEGATIVE_INFINITY))
        assertEquals(SkyAge.OLDEST_TINT, SkyAge.tintFor(5.5f))
        assertEquals(SkyAge.OLDEST_TINT, SkyAge.tintFor(40f))
        assertEquals(SkyAge.OLDEST_TINT, SkyAge.tintFor(Float.MAX_VALUE))
        assertEquals(SkyAge.OLDEST_TINT, SkyAge.tintFor(Float.POSITIVE_INFINITY))
    }

    // -------------------------------------------------------------------------------------------
    // The fade.
    // -------------------------------------------------------------------------------------------

    /** Every age a `Float` can hold that anyone could ever be handed, and then some. */
    private val extremeAges = floatArrayOf(
        0f, 0.5f, 1f, 2.1f, 5.5f, 10f, 40f, 120f, 1e6f, 1e30f,
        Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.MIN_VALUE, Float.NaN, -1f,
    )

    @Test
    fun `the floor check can fail`() {
        // The planted control for the sweep below: a fade that decays to nothing must be caught.
        var caught = false
        try {
            for (age in extremeAges) {
                val planted = if (!(age > 0f)) 1f else Math.exp((-age / 2.1f).toDouble()).toFloat()
                assertTrue("fade reached zero at age $age", planted > 0f)
            }
        } catch (expected: AssertionError) {
            caught = true
        }
        assertTrue("the sweep cannot see a fade that reaches zero", caught)
    }

    @Test
    fun `the fade never reaches zero, at any age that can be represented`() {
        // The rule this is: old stars recede but never vanish. A star that faded to nothing would
        // be a deletion on a timer, and the person would have no way to know it had happened.
        for (age in extremeAges) {
            val fade = SkyAge.fadeFor(age)
            assertTrue("fade at age $age is $fade", fade > 0f)
            assertTrue("fade at age $age is under the floor: $fade", fade >= SkyAge.FADE_FLOOR)
            assertTrue("fade at age $age is over full: $fade", fade <= SkyAge.FADE_NEW)
        }
        // A sweep across the ordinary range too, not only the extremes.
        var age = 0f
        while (age <= 60f) {
            assertTrue("fade at age $age", SkyAge.fadeFor(age) >= SkyAge.FADE_FLOOR)
            age += 0.01f
        }
    }

    @Test
    fun `the fade falls with age and settles on the floor`() {
        assertEquals(SkyAge.FADE_NEW, SkyAge.fadeFor(0f), 0f)
        var age = 0f
        var previous = SkyAge.fadeFor(0f)
        while (age <= 40f) {
            age += 0.01f
            val here = SkyAge.fadeFor(age)
            assertTrue("the fade brightened at age $age", here <= previous)
            previous = here
        }
        assertEquals("the far sky is not on the floor", SkyAge.FADE_FLOOR, SkyAge.fadeFor(40f), 1e-6f)
        // And it does something on the way: an old star is plainly fainter than a new one.
        assertTrue(SkyAge.fadeFor(3f) < 0.5f)
        assertTrue(SkyAge.fadeFor(0.25f) > 0.8f)
    }

    // -------------------------------------------------------------------------------------------
    // The landmark exemption.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a life event is never redshifted and never faded, at any age`() {
        var age = 0f
        var everyOtherKindMoved = false
        while (age <= 8f) {
            assertEquals(
                "a landmark redshifted at age $age",
                SkyAge.LANDMARK_TINT,
                SkyAge.tintFor(SkyKind.LIFE_EVENT, age),
            )
            assertEquals(
                "a landmark faded at age $age",
                SkyAge.FADE_NEW,
                SkyAge.fadeFor(SkyKind.LIFE_EVENT, age),
                0f,
            )
            // The planted control, which is the rest of the sky: the same sweep, the same ages, and
            // every other kind DOES redshift and DOES fade. Without this, an exemption that had
            // swallowed every kind would pass the two assertions above.
            for (kind in SkyKind.entries) {
                if (kind == SkyKind.LIFE_EVENT) continue
                assertEquals(SkyAge.tintFor(age), SkyAge.tintFor(kind, age))
                assertEquals(SkyAge.fadeFor(age), SkyAge.fadeFor(kind, age), 0f)
                if (age > 2f) {
                    assertTrue(SkyAge.tintFor(kind, age) != SkyAge.LANDMARK_TINT)
                    assertTrue(SkyAge.fadeFor(kind, age) < SkyAge.FADE_NEW)
                    everyOtherKindMoved = true
                }
            }
            age += 0.05f
        }
        assertTrue("the control never ran", everyOtherKindMoved)
    }

    // -------------------------------------------------------------------------------------------
    // Age itself, and the sprite buckets.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `age is the distance between two dates, and the future is not blue-shifted`() {
        assertEquals(0f, SkyAge.ageYears(100L, 100L), 0f)
        assertEquals(365f / SkyAge.DAYS_PER_YEAR, SkyAge.ageYears(0L, 365L), 1e-6f)
        assertEquals(2f, SkyAge.ageYears(0L, 731L), 0.01f)
        // A record dated tomorrow — a clock that moved, a timezone boundary — is brand new, not
        // negative. Nothing downstream has to defend itself against an age below zero.
        assertEquals(0f, SkyAge.ageYears(200L, 100L), 0f)
        assertEquals(SkyAge.NEWEST_TINT, SkyAge.tintFor(SkyAge.ageYears(200L, 100L)))
        assertEquals(SkyAge.FADE_NEW, SkyAge.fadeFor(SkyAge.ageYears(200L, 100L)), 0f)
    }

    @Test
    fun `sprite buckets round age without ever leaving the range`() {
        assertEquals(0, SkyAge.ageBucket(0f))
        assertEquals(0, SkyAge.ageBucket(-5f))
        assertEquals(0, SkyAge.ageBucket(Float.NaN))
        assertEquals(4, SkyAge.ageBucket(1f))
        assertEquals(SkyAge.MAX_AGE_BUCKET, SkyAge.ageBucket(100f))
        assertEquals(SkyAge.MAX_AGE_BUCKET, SkyAge.ageBucket(Float.POSITIVE_INFINITY))

        var previous = -1
        var age = 0f
        while (age <= 12f) {
            val bucket = SkyAge.ageBucket(age)
            assertTrue("bucket $bucket out of range at age $age", bucket in 0..SkyAge.MAX_AGE_BUCKET)
            assertTrue("buckets went backwards at age $age", bucket >= previous)
            previous = bucket
            age += 0.01f
        }

        // A bucket stands for a real age, so a sprite's colour is a colour on the ramp and not an
        // approximation of one.
        for (bucket in 0..SkyAge.MAX_AGE_BUCKET) {
            val age = SkyAge.bucketAgeYears(bucket)
            assertEquals("bucket $bucket does not round-trip", bucket, SkyAge.ageBucket(age))
        }
        // The rounding is invisible: a whole bucket is a small step on the ramp.
        for (bucket in 1..SkyAge.MAX_AGE_BUCKET) {
            val before = SkyAge.tintFor(SkyAge.bucketAgeYears(bucket - 1))
            val here = SkyAge.tintFor(SkyAge.bucketAgeYears(bucket))
            val step = maxOf(
                Math.abs(red(here) - red(before)),
                Math.abs(green(here) - green(before)),
                Math.abs(blue(here) - blue(before)),
            )
            assertTrue("bucket $bucket is a visible band: $step", step <= 30)
        }
    }
}
