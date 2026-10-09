package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Put away, and marked as hard: what the layout, the list, the Key and the opening do with each. */
class SkyPutAwayTest {

    private val start = SkyCalendar.epochDayOf(2024, 1, 1)
    private fun checkIns(n: Int) = (0 until n).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it) }

    @Test
    fun `putting a memory away moves nothing, and hides only it`() {
        val plain = Sky.layout(checkIns(20), 5L)
        val away = Sky.layout(checkIns(20).map { if (it.id == 7L) it.copy(putAwayEpochDay = start + 40) else it }, 5L)
        assertEquals(plain.x.toList(), away.x.toList())
        assertEquals(plain.y.toList(), away.y.toList())
        val index = away.indexOf(SkyKind.CHECK_IN, 7L)
        assertTrue(away.isPutAway(index))
        assertEquals(1, (0 until away.starCount).count { away.isPutAway(it) })
        assertEquals(19, away.shownCount)
    }

    @Test
    fun `the list keeps put-away memories under their own heading, and the months say nothing`() {
        // Every memory of January but one is in February's month… the one in March is put away.
        val records = checkIns(3) + SkyRecord(SkyKind.CHECK_IN, 99L, start + 70, putAwayEpochDay = start + 80)
        val list = Sky.list(Sky.layout(records, 5L))
        val headings = list.filterIsInstance<SkyListItem.MonthHeading>()
        assertEquals("a month holding only a put-away memory has a heading", 1, headings.size)
        assertEquals(3, headings.single().itemCount)
        assertEquals(SkyListItem.PutAwayHeading, list[list.size - 2])
        val row = list.last() as SkyListItem.PutAway
        assertEquals(start + 80, row.putAwayEpochDay)

        // The control: the same memory in sight gets its month.
        val shown = Sky.list(Sky.layout(checkIns(3) + SkyRecord(SkyKind.CHECK_IN, 99L, start + 70), 5L))
        assertEquals(2, shown.filterIsInstance<SkyListItem.MonthHeading>().size)
        assertFalse(SkyListItem.PutAwayHeading in shown)
    }

    @Test
    fun `only a life event can be hard, and a hard one is a supernova the opening never flies to`() {
        val records = checkIns(5) +
            SkyRecord(SkyKind.CHECK_IN, 50L, start + 10, hard = true) +
            SkyRecord(SkyKind.LIFE_EVENT, 60L, start + 11, hard = true)
        val layout = Sky.layout(records, 5L)
        assertFalse(layout.isSupernova(layout.indexOf(SkyKind.CHECK_IN, 50L)))
        val event = layout.indexOf(SkyKind.LIFE_EVENT, 60L)
        assertTrue(layout.isSupernova(event))
        assertEquals(event, layout.newest)
        assertEquals(layout.indexOf(SkyKind.CHECK_IN, 50L), layout.openingStar)

        // The control: unmarked, the newest is where the opening goes.
        val plain = Sky.layout(records.map { it.copy(hard = false) }, 5L)
        assertEquals(plain.newest, plain.openingStar)
    }

    @Test
    fun `the opening skips put-away memories too`() {
        val layout = Sky.layout(checkIns(4).mapIndexed { i, r -> if (i == 3) r.copy(putAwayEpochDay = start) else r }, 5L)
        assertEquals(2, layout.openingStar)
    }

    @Test
    fun `the Key names a supernova only while one is in sight`() {
        val hard = SkyRecord(SkyKind.LIFE_EVENT, 60L, start + 11, hard = true)
        val withOne = SkyKey.entries(Sky.layout(checkIns(5) + hard, 5L), 0).map { it.title }
        assertTrue("Supernova" in withOne)
        assertFalse("an only life event marked hard is still listed as a plain one", "Life events" in withOne)
        val putAway = SkyKey.entries(Sky.layout(checkIns(5) + hard.copy(putAwayEpochDay = start), 5L), 0)
        assertFalse("Supernova" in putAway.map { it.title })
    }

    @Test
    fun `a sky whose every memory is put away has none in sight, and never says it has none`() {
        val layout = Sky.layout(checkIns(2).map { it.copy(putAwayEpochDay = start) }, 5L)
        assertEquals(SkyLayout.Emptiness.ALL_PUT_AWAY, layout.emptiness)
        // The control: the same sky with one brought back is a sky with one in sight.
        val one = Sky.layout(checkIns(2).mapIndexed { i, r -> if (i == 0) r else r.copy(putAwayEpochDay = start) }, 5L)
        assertEquals(SkyLayout.Emptiness.FIRST_LIGHT, one.emptiness)
        assertEquals(SkyLayout.Emptiness.NO_RECORDS, SkyLayout.EMPTY.emptiness)
        assertEquals(-1, layout.openingStar)
        assertEquals(emptyList<SkyKey.Entry>(), SkyKey.entries(layout, 0))
    }

    @Test
    fun `a constellation keeps a put-away point for its photo, and draws no line to it live`() {
        val away = Sky.layout(checkIns(6).map { if (it.id == 3L) it.copy(putAwayEpochDay = start + 9) else it }, 5L)
        val resolved = SkyConstellation.resolve(
            listOf(2L, 3L, 4L).map { SkyConstellation.Point(SkyKind.CHECK_IN, it, 0f, 0f) },
            away,
        )
        assertTrue("the photo lost the put-away point", resolved.all { it >= 0 })
        val live = SkyConstellation.inSight(resolved, away)
        assertEquals(listOf(resolved[0], -1, resolved[2]), live.toList())
        // The control: brought back, it is in sight again.
        val back = Sky.layout(checkIns(6), 5L)
        assertTrue(SkyConstellation.inSight(SkyConstellation.resolve(
            listOf(2L, 3L, 4L).map { SkyConstellation.Point(SkyKind.CHECK_IN, it, 0f, 0f) },
            back,
        ), back).all { it >= 0 })
    }
}
