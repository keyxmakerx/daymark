package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opening (`DECISIONS.md` §D11): one continuous build from a trickle to a burst, every star at
 * its own fixed moment, and a camera that arrives exactly where it was sent.
 */
class SkyOpeningTest {

    @Test
    fun `the opening starts empty and ends with every star`() {
        assertEquals(0f, SkyOpening.revealAt(0f), 0f)
        assertEquals(1f, SkyOpening.revealAt(SkyOpening.SECONDS), 0f)
        assertEquals(1f, SkyOpening.revealAt(SkyOpening.SECONDS + 5f), 0f)
        assertEquals(0f, SkyOpening.revealAt(-1f), 0f)
    }

    @Test
    fun `stars only ever arrive, never leave`() {
        var last = 0f
        var t = 0f
        while (t <= SkyOpening.SECONDS) {
            val r = SkyOpening.revealAt(t)
            assertTrue("reveal fell at $t", r >= last)
            last = r
            t += 0.01f
        }
    }

    @Test
    fun `it trickles first and bursts later, with no jump between`() {
        // A twentieth of the stars at most in the first two and a half seconds; most of them in the
        // burst after it.
        assertTrue(SkyOpening.revealAt(2.5f) < 0.06f)
        assertTrue(SkyOpening.revealAt(7f) > 0.6f)
        // No step anywhere: the most that arrives in any tenth of a second is a small share.
        var t = 0f
        var largest = 0f
        while (t < SkyOpening.SECONDS) {
            largest = maxOf(largest, SkyOpening.revealAt(t + 0.1f) - SkyOpening.revealAt(t))
            t += 0.05f
        }
        assertTrue("$largest of the stars arrived in a tenth of a second", largest < 0.05f)
        // The detector: a switch from slow to fast would have shown here.
        assertTrue(largest > 0f)
    }

    @Test
    fun `a star's moment is its own and the same every time`() {
        val a = Sky.identityOf(SkyKind.JOURNAL, 41L)
        val b = Sky.identityOf(SkyKind.JOURNAL, 42L)
        assertEquals(SkyOpening.moment(a), SkyOpening.moment(a), 0f)
        assertTrue(SkyOpening.moment(a) != SkyOpening.moment(b))
        for (id in 0L until 500L) {
            val m = SkyOpening.moment(Sky.identityOf(SkyKind.CHECK_IN, id))
            assertTrue(m >= 0f && m < 1f)
        }
    }

    @Test
    fun `a star is dark before its moment and itself once the opening is over`() {
        assertEquals(0f, SkyOpening.brightness(0.3f, 0.5f), 0f)
        assertTrue(SkyOpening.brightness(0.52f, 0.5f) > 0f)
        assertEquals(1f, SkyOpening.brightness(1f, 0.5f), 0f)
        // The flare is soft and brief: never more than two and a half times the star, and gone well
        // before the end.
        var r = 0.5f
        while (r < 1f) {
            assertTrue(SkyOpening.brightness(r, 0.5f) <= 2.5f)
            r += 0.001f
        }
        assertEquals(1f, SkyOpening.brightness(0.8f, 0.5f), 0.01f)
    }

    @Test
    fun `a flight ends exactly where it was sent`() {
        val near = SkyOpening.Flight(0.5f, 0.5f, 1f, 0.6f, 0.7f, 8f, viewsApart = 0.2f)
        val far = SkyOpening.Flight(0.1f, 0.2f, 12f, 0.9f, 3.5f, 12f, viewsApart = 40f)
        for (f in listOf(near, far)) {
            assertTrue(!f.at(0L))
            assertTrue(f.at(f.millis))
            assertEquals(f.x, if (f === near) 0.6f else 0.9f, 0f)
            assertEquals(f.y, if (f === near) 0.7f else 3.5f, 0f)
            assertEquals(f.zoom, if (f === near) 8f else 12f, 0f)
        }
    }

    @Test
    fun `a long way at close zoom draws back first, and a short way does not`() {
        val far = SkyOpening.Flight(0.1f, 0.2f, 12f, 0.9f, 3.5f, 12f, viewsApart = 40f)
        far.at(far.millis / 2)
        assertTrue("the far flight stayed at ${far.zoom}", far.zoom < 6f)
        // Never further back than the whole sky.
        assertTrue(far.zoom >= 0.99f)

        val near = SkyOpening.Flight(0.5f, 0.5f, 4f, 0.52f, 0.5f, 4f, viewsApart = 0.1f)
        near.at(near.millis / 2)
        assertEquals(4f, near.zoom, 0.001f)
    }
}
