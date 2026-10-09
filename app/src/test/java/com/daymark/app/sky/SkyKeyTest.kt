package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Key (`DECISIONS.md` §D11): red only means old, first; and only what is in this sky. */
class SkyKeyTest {

    private val start = SkyCalendar.epochDayOf(2022, 1, 1)

    private fun everyOtherDay(n: Int) = (0 until n).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it * 2) }

    private fun seedOf(form: SkyForm.Form): Long {
        var s = 1L
        while (SkyForm.formOf(s) != form) s++
        return s
    }

    @Test
    fun `the Key leads with colour, and says red only means old`() {
        val key = SkyKey.entries(Sky.layout(everyOtherDay(10), 7L), constellations = 0)
        assertEquals("Colour", key.first().title)
        assertTrue(key.first().text.contains("Red only means old, never bad."))
    }

    @Test
    fun `an empty sky has an empty Key`() {
        assertEquals(emptyList<SkyKey.Entry>(), SkyKey.entries(SkyLayout.EMPTY, constellations = 3))
    }

    @Test
    fun `the Key names this sky's own form`() {
        for (form in SkyForm.Form.entries) {
            val key = SkyKey.entries(Sky.layout(everyOtherDay(10), seedOf(form)), 0)
            val titles = key.map { it.title }
            val expected = when (form) {
                SkyForm.Form.RIVER -> "River"
                SkyForm.Form.GALAXIES -> "Galaxies"
                SkyForm.Form.OPEN -> "Open sky"
            }
            assertTrue("$form: $titles", expected in titles)
            // Exactly one form is named.
            assertEquals(1, titles.count { it in setOf("River", "Galaxies", "Open sky") })
        }
    }

    @Test
    fun `the Key lists only what is in the sky`() {
        // Every other day: singles only. No band, cluster, stream, life event or constellation.
        val sparse = SkyKey.entries(Sky.layout(everyOtherDay(40), 7L), constellations = 0).map { it.title }
        for (absent in listOf("Bands", "Clusters", "Streams", "Life events", "Constellations")) {
            assertFalse("$absent is listed in a sky without one", absent in sparse)
        }
        // The detector: a sky that has each of them lists each of them.
        val full = (0 until 120).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it) } +
            (0 until 3).map { SkyRecord(SkyKind.CHECK_IN, 500L + it, start + 200 + it) } +
            (0 until 9).map { SkyRecord(SkyKind.CHECK_IN, 600L + it, start + 300 + it) } +
            SkyRecord(SkyKind.LIFE_EVENT, 900L, start + 400)
        val titles = SkyKey.entries(Sky.layout(full, 7L), constellations = 1).map { it.title }
        for (present in listOf("Bands", "Clusters", "Streams", "Life events", "Constellations")) {
            assertTrue("$present is missing from a sky that has one: $titles", present in titles)
        }
    }

    @Test
    fun `no line rewards, scores or counts`() {
        val full = (0 until 120).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it) } +
            (0 until 9).map { SkyRecord(SkyKind.CHECK_IN, 600L + it, start + 300 + it) }
        val words = listOf("streak", "in a row", "score", "congrat", "well done", "keep it up", "earn", "unlock",
            "missed", "gap", "broken", "great", "good job", "achievement", "reward")
        var lines = 0
        for (form in SkyForm.Form.entries) {
            for (entry in SkyKey.entries(Sky.layout(full, seedOf(form)), constellations = 2)) {
                lines++
                val text = (entry.title + " " + entry.text).lowercase()
                for (w in words) assertFalse("\"${entry.title}\" says \"$w\"", text.contains(w))
            }
        }
        // The detector: the lines were read, and a planted line would be caught.
        assertTrue(lines > 10)
        assertTrue(words.any { "Keep it up: a seven-day streak!".lowercase().contains(it) })
    }
}
