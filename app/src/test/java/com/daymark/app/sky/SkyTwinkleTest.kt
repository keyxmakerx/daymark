package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four motion-safety rules from `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §1, as arithmetic.
 *
 * *"Everything stops under the motion switch; at any moment only a few stars are glinting; a glint
 * is under a third of a second; nothing is ever in step with anything else."* Each is a test below,
 * and each absence assertion has a planted counter-example run through the same checker first.
 *
 * The population is deliberately realistic — two thousand stars in the proportions the prototype's
 * fourteen months produced, life events included — because "only a few are glinting" is a claim
 * about a sky, not about a star, and a bound that holds for ten stars says nothing about a person
 * with five years of history.
 */
class SkyTwinkleTest {

    private val moving = SkyOptions()
    private val still = SkyOptions(motionEnabled = false)
    private val quiet = SkyOptions(highContrast = true)

    private val populationSize = 2000

    /**
     * Two thousand stars in roughly the proportions a real history produces: mostly check-ins, a
     * few of everything else, a life event about twice a year.
     */
    private val population: List<Pair<SkyKind, Long>> = buildPopulation()

    private fun buildPopulation(): List<Pair<SkyKind, Long>> {
        val mixture = listOf(
            SkyKind.CHECK_IN to 70,
            SkyKind.PRACTICE to 6,
            SkyKind.JOURNAL to 12,
            SkyKind.GOAL_REACHED to 3,
            SkyKind.PROJECT_STEP to 8,
            SkyKind.LIFE_EVENT to 1,
        )
        val out = ArrayList<Pair<SkyKind, Long>>(populationSize)
        var id = 1L
        while (out.size < populationSize) {
            for (entry in mixture) {
                var n = 0
                while (n < entry.second && out.size < populationSize) {
                    out.add(entry.first to id)
                    id++
                    n++
                }
            }
        }
        return out
    }

    /** Everything a star's rhythm is, as one comparable value. */
    private fun rhythmOf(kind: SkyKind, id: Long): String = listOf(
        SkyTwinkle.breathePeriodMs(kind, id),
        SkyTwinkle.breathePhase(kind, id),
        if (SkyTwinkle.shimmers(kind, id)) 1f else 0f,
        SkyTwinkle.shimmerPhase(kind, id),
        if (SkyTwinkle.glints(kind, id)) 1f else 0f,
        SkyTwinkle.glintPeriodMs(kind, id),
        SkyTwinkle.glintOffsetMs(kind, id),
    ).joinToString(":") { it.toRawBits().toString() }

    // -------------------------------------------------------------------------------------------
    // The same beat forever.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a star's rhythm is the same every time it is asked for`() {
        for (entry in population) {
            assertEquals(
                "${entry.first} ${entry.second} answered differently the second time",
                rhythmOf(entry.first, entry.second),
                rhythmOf(entry.first, entry.second),
            )
        }
        // And the same at a given instant, which is what the renderer actually asks for.
        for (entry in population.take(50)) {
            for (t in longArrayOf(0L, 1L, 997L, 60_000L, 86_400_000L)) {
                assertEquals(
                    SkyTwinkle.alphaAt(entry.first, entry.second, t, moving),
                    SkyTwinkle.alphaAt(entry.first, entry.second, t, moving),
                    0f,
                )
            }
        }
    }

    @Test
    fun `the duplicate-rhythm check can fail`() {
        // The planted control for the test below.
        val same = population.map { "one rhythm for everybody" }.toSet()
        assertTrue("the detector cannot see a sky beating in unison", same.size < population.size)
    }

    @Test
    fun `no two stars share a rhythm`() {
        // §1: "nothing is ever in step with anything else". Two stars with the same period and the
        // same phase pulse together forever, and a pair of synchronised lights is the one thing on
        // a night sky that reads as a machine.
        val rhythms = population.map { rhythmOf(it.first, it.second) }.toSet()
        assertEquals("some stars beat together", population.size, rhythms.size)

        // Periods are spread across the whole range rather than clustered, so even stars that are
        // briefly in phase drift apart within a cycle or two.
        val periods = population.map { SkyTwinkle.breathePeriodMs(it.first, it.second) }
        assertTrue(periods.min() < SkyTwinkle.BREATHE_PERIOD_MIN_MS + 200f)
        assertTrue(periods.max() > SkyTwinkle.BREATHE_PERIOD_MAX_MS - 200f)
    }

    @Test
    fun `the same star in two different skies keeps its beat`() {
        // The property at the level it matters: a rhythm follows the star's own identity, so
        // logging something today cannot change how last year's stars twinkle.
        val old = SkyRecord(SkyKind.CHECK_IN, id = 41L, epochDay = 19_000L, moodLevel = 2)
        val before = Sky.layout(listOf(old))
        val after = Sky.layout(
            listOf(
                old,
                SkyRecord(SkyKind.JOURNAL, id = 42L, epochDay = 19_400L),
                SkyRecord(SkyKind.CHECK_IN, id = 43L, epochDay = 19_450L, moodLevel = 5),
            ),
        )
        val idBefore = SkyTwinkle.identityIdAt(before, 0)
        val idAfter = SkyTwinkle.identityIdAt(after, 0)
        assertEquals("the anchor id is not the record's own id", 41L, idBefore)
        assertEquals(idBefore, idAfter)
        assertEquals(
            rhythmOf(before.kindAt(0), idBefore),
            rhythmOf(after.kindAt(0), idAfter),
        )
        // The planted control: the new stars are not carrying the old one's rhythm either.
        assertNotEquals(
            rhythmOf(after.kindAt(0), SkyTwinkle.identityIdAt(after, 0)),
            rhythmOf(after.kindAt(2), SkyTwinkle.identityIdAt(after, 2)),
        )
    }

    @Test
    fun `a folded star takes its beat from the record the layout anchored it on`() {
        // A day over `Sky.MAX_STARS_PER_DAY` folds records together, so a star can cover several.
        // Its position is hashed from the **first** id it covers, and its rhythm has to come from
        // the same one or the two drift apart — a star whose beat is not the beat of the star in
        // that place. Without this case the anchor could be the last id and every other test here
        // would still pass, because every other star in this file covers exactly one record.
        val many = ArrayList<SkyRecord>()
        for (i in 1..20) many.add(SkyRecord(SkyKind.CHECK_IN, id = i.toLong(), epochDay = 19_000L, moodLevel = 3))
        val layout = Sky.layout(many)

        var folded = 0
        for (index in 0 until layout.starCount) {
            val covered = layout.recordIdsAt(index)
            if (covered.size > 1) folded++
            assertEquals(
                "star $index is not anchored on the first record it covers",
                covered[0],
                SkyTwinkle.identityIdAt(layout, index),
            )
        }
        assertTrue("nothing folded, so the case was never exercised", folded > 0)
    }

    // -------------------------------------------------------------------------------------------
    // Seven independent draws, not one draw wearing seven hats.
    // -------------------------------------------------------------------------------------------

    private fun correlation(xs: List<Double>, ys: List<Double>): Double {
        val n = xs.size
        val mx = xs.sum() / n
        val my = ys.sum() / n
        var cov = 0.0
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until n) {
            cov += (xs[i] - mx) * (ys[i] - my)
            sx += (xs[i] - mx) * (xs[i] - mx)
            sy += (ys[i] - my) * (ys[i] - my)
        }
        return cov / (Math.sqrt(sx) * Math.sqrt(sy))
    }

    @Test
    fun `the correlation check can fail`() {
        // The planted control, and it is the exact shape of the mistake this guards against: a
        // second property derived by xor-ing a salt onto an already-mixed hash, where SkyRandom.unit
        // keeps only the top 24 bits. A salt with those bits set yields the mirror of the first
        // draw, which is perfectly correlated and looks perfectly random on its own.
        val xs = population.map { SkyTwinkle.breathePeriodMs(it.first, it.second).toDouble() }
        val mirrored = xs.map { 1.0 - it }
        assertTrue(
            "the checker cannot see a property that is another one mirrored",
            Math.abs(correlation(xs, mirrored)) > 0.2,
        )
    }

    @Test
    fun `every rhythm is its own draw`() {
        val draws = linkedMapOf(
            "breathe period" to population.map { SkyTwinkle.breathePeriodMs(it.first, it.second).toDouble() },
            "breathe phase" to population.map { SkyTwinkle.breathePhase(it.first, it.second).toDouble() },
            "shimmer phase" to population.map { SkyTwinkle.shimmerPhase(it.first, it.second).toDouble() },
            "glint period" to population.map { SkyTwinkle.glintPeriodMs(it.first, it.second).toDouble() },
            "glint offset" to population.map {
                (SkyTwinkle.glintOffsetMs(it.first, it.second) /
                    SkyTwinkle.glintPeriodMs(it.first, it.second)).toDouble()
            },
        )
        val names = draws.keys.toList()
        for (i in names.indices) {
            for (j in (i + 1) until names.size) {
                val r = correlation(draws[names[i]]!!, draws[names[j]]!!)
                println("  ${names[i]} vs ${names[j]}: r = ${"%.4f".format(r)}")
                assertTrue(
                    "${names[i]} and ${names[j]} are the same draw (r = $r)",
                    Math.abs(r) < 0.2,
                )
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Only a few stars are glinting.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `about one star in five ever glints, plus every landmark`() {
        val ever = population.count { SkyTwinkle.glints(it.first, it.second) }
        val share = ever.toDouble() / population.size
        println("  $ever of ${population.size} stars ever glint (${"%.1f".format(share * 100)}%)")
        assertTrue("a fifth of the sky does not glint: $share", share > 0.15)
        assertTrue("far more than a fifth of the sky glints: $share", share < 0.32)

        // Every landmark, and it is not because landmarks happen to be lucky: the same ids under
        // another kind are ordinary stars.
        val landmarks = population.filter { it.first == SkyKind.LIFE_EVENT }
        assertTrue("no landmarks in the population", landmarks.size > 10)
        for (entry in landmarks) {
            assertTrue("a landmark that never glints", SkyTwinkle.glints(SkyKind.LIFE_EVENT, entry.second))
        }
        val asCheckIns = landmarks.count { SkyTwinkle.glints(SkyKind.CHECK_IN, it.second) }
        assertTrue(
            "every id glints whatever its kind — the landmark rule is doing nothing",
            asCheckIns < landmarks.size,
        )

        // And **no other kind gets one for free**. This assertion was added because a mutation
        // survived without it: giving every reached goal a glint passed every other test in this
        // file, and a glint on the days the app decided went well is a reward, which is the
        // "amount of twinkle reads as how a stretch went" defect `SkyGlyph`'s header exists to
        // prevent. Two thousand ids under each kind, so a whole kind cannot hide in the mixture.
        for (kind in SkyKind.entries) {
            if (kind == SkyKind.LIFE_EVENT) continue
            var glinting = 0
            for (id in 1L..2000L) {
                if (SkyTwinkle.glints(kind, id)) glinting++
            }
            val ofKind = glinting / 2000.0
            println("  $kind: ${"%.1f".format(ofKind * 100)}% glint")
            assertTrue("$kind never glints at all: $ofKind", ofKind > 0.15)
            assertTrue("$kind is glinting like a landmark: $ofKind", ofKind < 0.32)
        }
    }

    /**
     * The worst instant in two minutes: the largest fraction of the whole sky that [lit] calls
     * alight at one time, with the mean printed alongside.
     */
    private fun worstInstant(lit: (SkyKind, Long, Long) -> Boolean): Double {
        var worst = 0.0
        var worstAt = 0L
        var total = 0.0
        var samples = 0
        var t = 0L
        while (t <= 120_000L) {
            var count = 0
            for (entry in population) {
                if (lit(entry.first, entry.second, t)) count++
            }
            val fraction = count.toDouble() / population.size
            if (fraction > worst) {
                worst = fraction
                worstAt = t
            }
            total += fraction
            samples++
            t += 97L
        }
        println(
            "  mean ${"%.3f".format(total / samples * 100)}%, " +
                "worst ${"%.3f".format(worst * 100)}% at ${worstAt}ms",
        )
        return worst
    }

    @Test
    fun `the glint census can fail`() {
        // Run first. The same counter the real test uses, pointed at a sky where every star is
        // alight at every instant — which is what a glint with no envelope, or an envelope that
        // never returns to zero, would produce.
        val worst = worstInstant { _, _, _ -> true }
        assertTrue("the census cannot see a sky that is glinting all over", worst >= 0.04)
    }

    @Test
    fun `at any one instant only a small fraction of the sky is glinting`() {
        val worst = worstInstant { kind, id, t ->
            SkyTwinkle.glintEnvelopeAt(kind, id, t, moving) > 0f
        }
        // The control: if the sweep never saw a glint at all, the bound below would be a bound on
        // nothing. This is the assertion that the test is looking at a moving sky.
        assertTrue("the sweep never saw a single glint", worst > 0.0)
        assertTrue("too much of the sky glints at once: $worst", worst < 0.04)
    }

    @Test
    fun `a glint is under a third of a second, and it fades in and out`() {
        val glinting = population.filter { SkyTwinkle.glints(it.first, it.second) }.take(40)
        for (entry in glinting) {
            val period = SkyTwinkle.glintPeriodMs(entry.first, entry.second)
            var lit = 0
            var peak = 0f
            var t = 0L
            while (t < period.toLong() * 2L) {
                val envelope = SkyTwinkle.glintEnvelopeAt(entry.first, entry.second, t, moving)
                if (envelope > 0f) lit++
                if (envelope > peak) peak = envelope
                assertTrue("an envelope outside 0..1: $envelope", envelope >= 0f && envelope <= 1f)
                t += 1L
            }
            // Two periods swept at a millisecond each, so the lit count is a duration in ms.
            assertTrue("a glint lasted ${lit / 2}ms", lit / 2 <= 300)
            assertTrue("a glint that never lit", lit > 0)
            assertTrue("a glint that never reaches full: $peak", peak > 0.98f)
        }
        assertTrue("under a third of a second", SkyTwinkle.GLINT_MS < 333f)
    }

    // -------------------------------------------------------------------------------------------
    // The motion switch, and the quiet sky.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `everything stops under the motion switch`() {
        var somethingMoved = false
        for (entry in population.take(200)) {
            var t = 0L
            while (t <= 30_000L) {
                assertEquals(
                    "alpha moved with motion off",
                    1f,
                    SkyTwinkle.alphaAt(entry.first, entry.second, t, still),
                    0f,
                )
                assertEquals(
                    "scale moved with motion off",
                    1f,
                    SkyTwinkle.scaleAt(entry.first, entry.second, t, still),
                    0f,
                )
                assertEquals(
                    "a glint fired with motion off",
                    0f,
                    SkyTwinkle.glintEnvelopeAt(entry.first, entry.second, t, still),
                    0f,
                )
                // The control: the same star, the same instants, with motion on — it must actually
                // be moving, or "everything stops" is a statement about a sky that never moved.
                if (SkyTwinkle.alphaAt(entry.first, entry.second, t, moving) != 1f) somethingMoved = true
                t += 311L
            }
        }
        assertTrue("nothing twinkles even with motion on", somethingMoved)
    }

    @Test
    fun `the quiet sky keeps its breathe and loses its glints`() {
        val glinting = population.first { SkyTwinkle.glints(it.first, it.second) }
        var lit = 0
        var breathed = false
        var t = 0L
        while (t <= 60_000L) {
            if (SkyTwinkle.glintEnvelopeAt(glinting.first, glinting.second, t, quiet) > 0f) lit++
            if (SkyTwinkle.alphaAt(glinting.first, glinting.second, t, quiet) != 1f) breathed = true
            t += 17L
        }
        assertEquals("a glint survived the high-contrast sky", 0, lit)
        assertTrue("the quiet sky stopped breathing as well", breathed)

        // The control: the same star, over the same instants, does glint in the ordinary sky — so
        // the zero above is the switch working and not a star that never glints anywhere.
        var litOrdinary = 0
        t = 0L
        while (t <= 60_000L) {
            if (SkyTwinkle.glintEnvelopeAt(glinting.first, glinting.second, t, moving) > 0f) litOrdinary++
            t += 17L
        }
        assertTrue("this star never glints anywhere, so the check proves nothing", litOrdinary > 0)
    }

    // -------------------------------------------------------------------------------------------
    // Subtle.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the twinkle is shallow, in brightness and in size`() {
        // "It should look like a night sky, not an instrument." A star may lose a fifth of its
        // brightness at the bottom of its breathe and gain none at the top: the twinkle only ever
        // takes light away from full, so no star can be twinkled up past another.
        //
        // The three depths are bounded here **as numbers**, not against themselves. A version of
        // this test that only checked the sweep against `1 - BREATHE_DEPTH - SHIMMER_DEPTH` passed
        // with the depth raised to 0.9 — the bound moved with the mutation, which is this repo's
        // commonest bug shape written into a test of my own.
        assertTrue("the breathe is a strobe: ${SkyTwinkle.BREATHE_DEPTH}", SkyTwinkle.BREATHE_DEPTH <= 0.25f)
        assertTrue("the shimmer is not faint: ${SkyTwinkle.SHIMMER_DEPTH}", SkyTwinkle.SHIMMER_DEPTH <= 0.08f)
        assertTrue("stars are pulsing in size: ${SkyTwinkle.BREATHE_SCALE}", SkyTwinkle.BREATHE_SCALE <= 0.06f)

        val floor = 1f - SkyTwinkle.BREATHE_DEPTH - SkyTwinkle.SHIMMER_DEPTH
        var lowest = 2f
        var highest = -1f
        var smallest = 2f
        var largest = -1f
        for (entry in population.take(300)) {
            var t = 0L
            while (t <= 20_000L) {
                val alpha = SkyTwinkle.alphaAt(entry.first, entry.second, t, moving)
                val scale = SkyTwinkle.scaleAt(entry.first, entry.second, t, moving)
                lowest = Math.min(lowest, alpha)
                highest = Math.max(highest, alpha)
                smallest = Math.min(smallest, scale)
                largest = Math.max(largest, scale)
                t += 53L
            }
        }
        println("  alpha $lowest..$highest, scale $smallest..$largest")
        assertTrue("a star fell below three quarters brightness: $lowest", lowest >= 0.7f)
        assertTrue("a star went darker than the twinkle allows: $lowest", lowest >= floor - 1e-5f)
        assertTrue("a star went brighter than full: $highest", highest <= 1f + 1e-5f)
        assertTrue("the breathe does nothing at all", lowest < 0.95f)
        val half = SkyTwinkle.BREATHE_SCALE / 2f
        assertTrue("a star grew more than the breathe allows: $largest", largest <= 1f + half + 1e-5f)
        assertTrue("a star shrank more than the breathe allows: $smallest", smallest >= 1f - half - 1e-5f)
        assertTrue("the breathe does not move the star at all", largest > smallest)
    }

    @Test
    fun `about a third of stars carry the quick shimmer`() {
        val shimmering = population.count { SkyTwinkle.shimmers(it.first, it.second) }
        val share = shimmering.toDouble() / population.size
        println("  $shimmering of ${population.size} shimmer (${"%.1f".format(share * 100)}%)")
        assertTrue("almost nothing shimmers: $share", share > 0.25)
        assertTrue("almost everything shimmers: $share", share < 0.42)
        // And it is not the breathe phase wearing a second hat, which is how the prototype picks
        // them: shimmering stars are spread across the whole breathe cycle, not bunched in a third
        // of it.
        val phases = population.filter { SkyTwinkle.shimmers(it.first, it.second) }
            .map { SkyTwinkle.breathePhase(it.first, it.second) }
        assertTrue("shimmering stars are bunched at the top of the breathe", phases.min() < 1f)
        assertTrue("shimmering stars are bunched at the bottom of the breathe", phases.max() > 5f)
    }

    // -------------------------------------------------------------------------------------------
    // What a rhythm may not know.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a rhythm is the star's identity and nothing else`() {
        // There is no mood parameter and no count parameter anywhere in SkyTwinkle — the sweep for
        // that lives in SkyGlyphTest, over the functions that do take a mood and ignore it. What
        // can be asserted here is the other half: two stars that differ only in kind get different
        // rhythms (kind is in the hash), and the same id under the same kind always gets the same
        // one, whatever else is going on.
        var differed = 0
        for (id in 1L..200L) {
            val a = rhythmOf(SkyKind.CHECK_IN, id)
            val b = rhythmOf(SkyKind.JOURNAL, id)
            if (a != b) differed++
        }
        assertEquals("kind is not part of the identity hash", 200, differed)
    }
}
