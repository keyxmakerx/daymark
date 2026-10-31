package com.daymark.app.sky

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A background from the sky (#534). Space only must be the seed and nothing else, so a lock screen
 * says nothing about the person; with stars frames a star in sight and never a put-away one.
 */
class SkyWallpaperTest {

    private val start = SkyCalendar.epochDayOf(2024, 3, 1)

    @Test
    fun space_only_is_the_same_for_the_same_seed_and_view_and_differs_by_view() {
        val a = FloatArray(SkyWallpaper.SPACE_LIGHTS * 4)
        val b = FloatArray(SkyWallpaper.SPACE_LIGHTS * 4)
        val ta = IntArray(SkyWallpaper.SPACE_LIGHTS)
        val tb = IntArray(SkyWallpaper.SPACE_LIGHTS)
        assertEquals(SkyWallpaper.SPACE_LIGHTS, SkyWallpaper.spaceLights(5L, 0, a, ta))
        SkyWallpaper.spaceLights(5L, 0, b, tb)
        assertTrue(a.contentEquals(b) && ta.contentEquals(tb))
        for (v in a) assertTrue(v in 0f..1f)
        SkyWallpaper.spaceLights(5L, 1, b, tb)
        assertFalse("another view changed nothing", a.contentEquals(b))
        val view = SkyWallpaper.spaceView(5L, 0)
        assertTrue(view.across in SkyWallpaper.SPACE_LEAST_ZOOM..SkyWallpaper.SPACE_MOST_ZOOM)
        assertNotEquals(view.centreX, SkyWallpaper.spaceView(5L, 1).centreX)
    }

    /** Nothing about a memory can reach space only: its functions take the seed, a view and arrays. */
    @Test
    fun nothing_about_a_memory_reaches_space_only() {
        val source = repoFile("app/src/main/java/com/daymark/app/sky/SkyWallpaper.kt").readText()
        fun params(name: String) = source.substringAfter("fun $name(").substringBefore(")")
        val memory = Regex("""SkyLayout|SkyRecord|epochDay|mood|count\b""", RegexOption.IGNORE_CASE)
        assertTrue(params("spaceView"), params("spaceView").contains("seed: Long"))
        assertFalse(memory.containsMatchIn(params("spaceView")))
        assertFalse(memory.containsMatchIn(params("spaceLights")))
        // The control: the frame with stars, which does take the layout, is seen.
        assertTrue(memory.containsMatchIn(params("starView")))
    }

    @Test
    fun a_view_with_stars_frames_a_star_in_sight() {
        val records = (0 until 6).map { SkyRecord(SkyKind.CHECK_IN, 1L + it, start + it) }
        val awayDay = start + 30
        val layout = Sky.layout(records.mapIndexed { i, r -> if (i < 5) r.copy(putAwayEpochDay = awayDay) else r }, 3L)
        assertEquals(1, layout.shownCount)
        for (n in 0 until 20) {
            val i = SkyWallpaper.starView(layout, 3L, n)
            assertTrue("view $n framed a put-away star", layout.isShown(i))
        }
        val gone = Sky.layout(records.map { it.copy(putAwayEpochDay = awayDay) }, 3L)
        assertEquals(-1, SkyWallpaper.starView(gone, 3L, 0))
    }

    @Test
    fun the_note_says_what_a_screen_shows_and_that_space_only_shows_no_star() {
        assertTrue(SkyWallpaper.STARS_NOTE.contains("Anyone who sees your screen sees its shape"))
        assertTrue(SkyWallpaper.STARS_NOTE.contains("Space only shows your sky's colours and none of your stars"))
    }
}
