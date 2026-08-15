package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mixer, checked against something other than itself.
 *
 * A hand-written PRNG is the easiest thing in a codebase to get subtly wrong and the hardest to
 * notice: a typo in one of the three constants still produces plausible-looking scatter, and the
 * only symptom is that the sky is a slightly worse sky than it should be, forever. So the first
 * test compares against the **published SplitMix64 vectors** rather than against a value this
 * implementation produced — a self-consistency test would pass just as happily with a wrong
 * constant in it.
 *
 * The rest check the two properties the Sky actually depends on: that a hash is a function of its
 * inputs and nothing else, and that [SkyRandom.unit] covers its range without escaping it.
 */
class SkyRandomTest {

    /**
     * The first five outputs of SplitMix64 from seed 0.
     *
     * Produced by an **independent implementation of the published algorithm**, not by this file —
     * that is the whole value of the test. Written as negated hex where the top bit is set, because
     * Kotlin has no unsigned `Long` literal; the unsigned value is in the comment.
     */
    private val referenceFromZero = longArrayOf(
        -0x1DDF57C684E23251L, // 0xE220A8397B1DCDAF
        0x6E789E6AA1B965F4L,
        0x06C45D188009454FL,
        -0x077447578DB37E14L, // 0xF88BB8A8724C81EC
        0x1B39896A51A8749BL,
    )

    @Test
    fun `the stream is SplitMix64, not something that resembles it`() {
        val stream = SkyStream(0L)
        for (i in referenceFromZero.indices) {
            assertEquals(
                "SplitMix64 output $i for seed 0",
                referenceFromZero[i],
                stream.nextLong(),
            )
        }
    }

    @Test
    fun `a hash depends on its inputs and on nothing else`() {
        // Two independently constructed calls, interleaved with unrelated work, must agree. If the
        // mixer ever acquired state — a field, a counter, a clock — this is what would catch it.
        val first = SkyRandom.mix(3L, 91L)
        repeat(1000) { SkyRandom.mix(it.toLong(), it.toLong() * 7L) }
        val second = SkyRandom.mix(3L, 91L)
        assertEquals(first, second)
    }

    @Test
    fun `swapping the two inputs gives a different hash`() {
        // Star placement hashes (kind, id). Without the gamma multiply on the second argument, a
        // plain xor would make mix(a, b) == mix(b, a) and kind 3 record 5 would land exactly on
        // kind 5 record 3.
        assertNotEquals(SkyRandom.mix(3L, 5L), SkyRandom.mix(5L, 3L))
    }

    @Test
    fun `neighbouring ids do not produce neighbouring positions`() {
        // Row ids are consecutive. If the mixer passed them through with weak avalanche, a day's
        // stars would land in a row, and the sky would have a visible comb in it.
        val units = FloatArray(64) { SkyRandom.unit(SkyRandom.mix(0L, it.toLong())) }
        var adjacentPairs = 0
        for (i in 1 until units.size) if (Math.abs(units[i] - units[i - 1]) < 0.02f) adjacentPairs++
        assertTrue("consecutive ids clustered: $adjacentPairs of 63", adjacentPairs <= 4)
    }

    @Test
    fun `unit stays inside its half-open range and covers it`() {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        val buckets = IntArray(10)
        repeat(20_000) {
            val u = SkyRandom.unit(SkyRandom.mix(it.toLong()))
            assertTrue("unit escaped [0, 1): $u", u >= 0f && u < 1f)
            if (u < lo) lo = u
            if (u > hi) hi = u
            buckets[(u * 10).toInt()]++
        }
        assertTrue("never approached 0, lowest was $lo", lo < 0.001f)
        assertTrue("never approached 1, highest was $hi", hi > 0.999f)
        for (b in buckets.indices) {
            assertTrue("bucket $b held ${buckets[b]} of 20000", buckets[b] in 1400..2600)
        }
    }

    @Test
    fun `between maps onto its bounds`() {
        repeat(5_000) {
            val v = SkyRandom.between(SkyRandom.mix(it.toLong()), 0.08f, 0.92f)
            assertTrue("between escaped its bounds: $v", v >= 0.08f && v < 0.92f)
        }
    }

    @Test
    fun `the empty-sky seed and a derived seed are different fields`() {
        val derived = SkySeed.forFirstRecord(SkyRecord(SkyKind.CHECK_IN, id = 1L, epochDay = 20_000L))
        assertNotEquals(SkySeed.EMPTY_SKY, derived)
        // Two people whose first act was the same kind on the same day, with different row ids,
        // must not share a background.
        assertNotEquals(
            derived,
            SkySeed.forFirstRecord(SkyRecord(SkyKind.CHECK_IN, id = 2L, epochDay = 20_000L)),
        )
    }
}
