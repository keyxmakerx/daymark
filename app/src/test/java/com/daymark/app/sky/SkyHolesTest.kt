package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Black and white holes are passing events: they form, do their work, and are gone (§D11). */
class SkyHolesTest {

    @Test
    fun `a hole forms, stays while memories travel, and is gone at the end`() {
        for (count in listOf(1, 3, 20)) {
            val end = SkyHoles.duration(count)
            assertEquals(0f, SkyHoles.holeAt(0f, count), 0f)
            assertEquals(1f, SkyHoles.holeAt(SkyHoles.FORMS + 0.01f, count), 1e-3f)
            assertEquals("count $count: something is left behind", 0f, SkyHoles.holeAt(end, count), 0f)
            assertEquals(0f, SkyHoles.holeAt(end + 60f, count), 0f)
        }
    }

    @Test
    fun `every memory has arrived before the hole closes`() {
        for (count in listOf(1, 3, 20)) {
            val closing = SkyHoles.duration(count) - SkyHoles.CLOSES
            for (order in 0 until count) {
                assertEquals("count $count order $order", 1f, SkyHoles.travelAt(closing, order), 0f)
            }
        }
    }

    @Test
    fun `memories go one after another, not all at once`() {
        val t = SkyHoles.FORMS + 1f
        assertTrue(SkyHoles.travelAt(t, 0) > SkyHoles.travelAt(t, 1))
        assertTrue(SkyHoles.travelAt(t, 1) > SkyHoles.travelAt(t, 2))
    }

    @Test
    fun `a memory drawn in still shines on the way, and is out of sight once in`() {
        assertEquals(1f, SkyHoles.inwardLight(0.3f), 0f)
        assertEquals(0f, SkyHoles.inwardLight(1f), 0f)
        assertTrue(SkyHoles.inwardRadius(1f) < SkyHoles.inwardRadius(0.5f))
        assertEquals(1f, SkyHoles.inwardRadius(0f), 0f)
    }

    @Test
    fun `a memory sent home arrives exactly home, unturned`() {
        assertEquals(1f, SkyHoles.outwardShare(1f), 0f)
        assertEquals(0f, SkyHoles.outwardTurn(1f), 0f)
        assertEquals(1f, SkyHoles.outwardLight(1f), 0f)
        assertEquals(0f, SkyHoles.outwardShare(0f), 0f)
    }
}
