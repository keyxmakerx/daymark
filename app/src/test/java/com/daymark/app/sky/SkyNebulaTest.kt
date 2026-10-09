package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nebulae: gas around weeks with a lot of writing, and nothing else (`DECISIONS.md` §D11). */
class SkyNebulaTest {

    // A Monday.
    private val monday = SkyCalendar.epochDayOf(2024, 3, 4)

    private fun journals(fromDay: Long, days: Int, firstId: Long) =
        (0 until days).map { SkyRecord(SkyKind.JOURNAL, firstId + it, fromDay + it) }

    @Test
    fun `weeks start on Monday`() {
        assertEquals(SkyNebula.weekOf(monday), SkyNebula.weekOf(monday + 6))
        assertEquals(SkyNebula.weekOf(monday) + 1, SkyNebula.weekOf(monday + 7))
        assertEquals(SkyNebula.weekOf(monday) - 1, SkyNebula.weekOf(monday - 1))
    }

    @Test
    fun `a week with enough writing makes a nebula, and one short of it does not`() {
        val enough = journals(monday, SkyNebula.ENTRIES_PER_WEEK, 1L)
        val nebulae = SkyNebula.find(Sky.layout(enough, 7L))
        assertEquals(1, nebulae.size)
        assertEquals(SkyNebula.ENTRIES_PER_WEEK, nebulae[0].entries)
        assertEquals(monday, nebulae[0].firstDay)

        val short = journals(monday, SkyNebula.ENTRIES_PER_WEEK - 1, 1L)
        assertEquals(0, SkyNebula.find(Sky.layout(short, 7L)).size)
    }

    @Test
    fun `only journal entries count, and check-ins in the same week are inside the gas`() {
        val records = journals(monday, SkyNebula.ENTRIES_PER_WEEK, 1L) +
            (0 until 7).map { SkyRecord(SkyKind.CHECK_IN, 100L + it, monday + it) }
        val nebula = SkyNebula.find(Sky.layout(records, 7L)).single()
        assertEquals(SkyNebula.ENTRIES_PER_WEEK, nebula.entries)
        assertEquals(records.size, nebula.members.size)
        // Check-ins alone make none, however many.
        val checkIns = (0 until 30).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, monday + it / 3) }
        assertEquals(0, SkyNebula.find(Sky.layout(checkIns, 7L)).size)
    }

    @Test
    fun `weeks one after another share a nebula, and a gap between them makes two`() {
        val two = journals(monday, 14, 1L)
        assertEquals(1, SkyNebula.find(Sky.layout(two, 7L)).size)
        val apart = journals(monday, 7, 1L) + journals(monday + 21, 7, 100L)
        assertEquals(2, SkyNebula.find(Sky.layout(apart, 7L)).size)
    }

    @Test
    fun `put-away entries never count, so no nebula gives away where they were`() {
        val records = journals(monday, SkyNebula.ENTRIES_PER_WEEK, 1L).mapIndexed { i, r ->
            if (i == 0) r.copy(putAwayEpochDay = monday + 30) else r
        }
        assertEquals(0, SkyNebula.find(Sky.layout(records, 7L)).size)
    }

    @Test
    fun `the gas has no edge and is faint at its thickest`() {
        val size = 64
        val texture = SkyNebula.texture(size, intArrayOf(0x7B4FA8, 0xC0568F, 0xE7A3C4), 99L)
        for (i in 0 until size) {
            for (edge in listOf(i, i * size, (size - 1) * size + i, i * size + size - 1)) {
                assertEquals("an edge pixel is not empty", 0, texture[edge] ushr 24)
            }
        }
        val peak = texture.maxOf { it ushr 24 }
        assertTrue("the gas is empty", peak > 0)
        assertTrue("the gas is $peak of 255 at its thickest", peak <= (SkyNebula.PEAK_ALPHA * 255f + 1f).toInt())
    }
}
