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
    fun `the Key says brightness means nothing, second, and only where stars differ`() {
        val layout = Sky.layout(everyOtherDay(10), 7L)
        val key = SkyKey.entries(layout, constellations = 0)
        assertEquals("Brightness", key[1].title)
        assertTrue(key[1].text.startsWith("It means nothing."))
        // The quiet sky draws every star at one light, and so does a sky of life events alone,
        // which have no magnitude: there the line would describe what is not there.
        assertFalse("Brightness" in SkyKey.entries(layout, constellations = 0, quiet = true).map { it.title })
        val events = Sky.layout((0 until 4).map { SkyRecord(SkyKind.LIFE_EVENT, 1L + it, start + it * 30) }, 7L)
        val eventTitles = SkyKey.entries(events, constellations = 0).map { it.title }
        assertFalse("Brightness" in eventTitles)
        // The control: the life events' sky does have a Key, so the line is left out, not the Key.
        assertTrue("Life events" in eventTitles)
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
    fun `colour is the one time key, and every form is chance`() {
        val times = listOf("oldest", "newest", "at the top", "then the next", "earliest", "latest")
        for (form in SkyForm.Form.entries) {
            val key = SkyKey.entries(Sky.layout(everyOtherDay(10), seedOf(form)), 0)
            val line = key.first { it.title in setOf("River", "Galaxies", "Open sky") }.text
            for (t in times) assertFalse("$form's line tells time: \"$t\"", line.lowercase().contains(t))
            assertTrue("$form's line does not say the shape is chance: $line", line.contains("The shape is chance"))
        }
        // The colour line still tells it.
        assertTrue(SkyKey.COLOUR.startsWith("Colour is age."))
        // The control: the line that told time twice is caught.
        val old = "Your memories fill one galaxy, then the next, oldest at the top."
        assertTrue(times.any { old.lowercase().contains(it) })
    }

    @Test
    fun `the river is the course the stars follow, because no river is drawn`() {
        val river = SkyKey.entries(Sky.layout(everyOtherDay(10), seedOf(SkyForm.Form.RIVER)), 0)
            .first { it.title == "River" }.text
        assertTrue(river, river.contains("like a river"))
        val promises = listOf("along a river", "the river", "a river of")
        for (p in promises) assertFalse("the river line promises a drawn river: \"$p\"", river.contains(p))
        // The control: the line that promised one is caught.
        val old = "Your memories run along a river, oldest at the top."
        assertTrue(promises.any { old.contains(it) })
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
