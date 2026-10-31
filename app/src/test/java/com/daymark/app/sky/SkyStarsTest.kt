package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A star's name, its shower and its place, all the person's own (#516, #517, #518). */
class SkyStarsTest {

    private fun day(y: Int, m: Int, d: Int) = SkyCalendar.epochDayOf(y, m, d)

    private val records = listOf(
        SkyRecord(SkyKind.CHECK_IN, 1, day(2024, 3, 9), moodLevel = 3),
        SkyRecord(SkyKind.JOURNAL, 7, day(2024, 6, 1)),
        SkyRecord(SkyKind.LIFE_EVENT, 4, day(2024, 2, 29)),
        SkyRecord(SkyKind.LIFE_EVENT, 5, day(2025, 1, 15), hard = true),
    )
    private val layout = Sky.layout(records, 99L)

    private fun indexOf(kind: SkyKind, id: Long): Int =
        (0 until layout.starCount).single { layout.kindAt(it) == kind && layout.recordIds[layout.idStart[it]] == id }

    @Test
    fun a_choice_lands_on_its_own_star_and_nowhere_else() {
        val chosen = SkyStars.resolve(layout, listOf(SkyStars.Choice("journal", 7, name = "The flat", placeId = 3)))
        val j = indexOf(SkyKind.JOURNAL, 7)
        assertEquals("The flat", chosen.nameAt(j))
        assertEquals(3L, chosen.placeAt(j))
        for (i in 0 until layout.starCount) if (i != j) {
            assertNull(chosen.nameAt(i))
            assertEquals(SkyStars.NO_PLACE, chosen.placeAt(i))
        }
        // A row for a memory that is gone matches nothing.
        val gone = SkyStars.resolve(layout, listOf(SkyStars.Choice("journal", 8, name = "Gone")))
        assertTrue((0 until layout.starCount).all { gone.nameAt(it) == null })
    }

    @Test
    fun an_anniversary_is_the_same_day_in_a_later_year() {
        val d = day(2024, 3, 9)
        assertTrue(SkyStars.isAnniversary(d, day(2025, 3, 9)))
        assertTrue(SkyStars.isAnniversary(d, day(2030, 3, 9)))
        assertFalse(SkyStars.isAnniversary(d, d))
        assertFalse(SkyStars.isAnniversary(d, day(2025, 3, 10)))
        assertFalse(SkyStars.isAnniversary(d, day(2023, 3, 9)))
        // 29 February keeps its shower on the 28th in a year without one, and only then.
        val leap = day(2024, 2, 29)
        assertTrue(SkyStars.isAnniversary(leap, day(2025, 2, 28)))
        assertFalse(SkyStars.isAnniversary(leap, day(2028, 2, 28)))
        assertTrue(SkyStars.isAnniversary(leap, day(2028, 2, 29)))
    }

    @Test
    fun a_shower_falls_only_on_a_day_the_person_chose() {
        val c = indexOf(SkyKind.CHECK_IN, 1)
        val none = SkyStars.resolve(layout, emptyList())
        assertEquals(-1, SkyStars.showerToday(layout, none, day(2025, 3, 9)))
        val chosen = SkyStars.resolve(layout, listOf(SkyStars.Choice("check_in", 1, shower = true)))
        assertEquals(c, SkyStars.showerToday(layout, chosen, day(2025, 3, 9)))
        assertEquals(-1, SkyStars.showerToday(layout, chosen, day(2025, 3, 8)))
    }

    /** Never on a day marked hard, even if a row says so. */
    @Test
    fun never_on_a_day_marked_hard() {
        val hard = indexOf(SkyKind.LIFE_EVENT, 5)
        assertTrue(layout.hard[hard])
        assertFalse(SkyStars.showerAllowed(layout, hard))
        val planted = SkyStars.resolve(layout, listOf(SkyStars.Choice("life_event", 5, shower = true)))
        assertTrue(planted.showerAt(hard))
        assertEquals(-1, SkyStars.showerToday(layout, planted, day(2026, 1, 15)))
        // The control: the same choice on a day not marked hard does fall.
        val fine = SkyStars.resolve(layout, listOf(SkyStars.Choice("life_event", 4, shower = true)))
        assertEquals(indexOf(SkyKind.LIFE_EVENT, 4), SkyStars.showerToday(layout, fine, day(2025, 2, 28)))
    }

    @Test
    fun a_name_is_the_persons_words_made_safe() {
        assertEquals("Grandma's", SkyStars.cleanName("  Grandma's \n"))
        assertNull(SkyStars.cleanName("   "))
        assertEquals(SkyStars.NAME_MAX, SkyStars.cleanName("x".repeat(80))!!.length)
    }
}
