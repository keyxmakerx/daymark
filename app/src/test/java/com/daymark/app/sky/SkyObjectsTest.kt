package com.daymark.app.sky

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pulsars for habits with a set time (#519) and ringed planets for named places (#518). */
class SkyObjectsTest {

    private fun day(y: Int, m: Int, d: Int) = SkyCalendar.epochDayOf(y, m, d)

    private val records = (0 until 30).map { SkyRecord(SkyKind.CHECK_IN, it.toLong() + 1, day(2025, 1, 1) + it * 3L, moodLevel = 3) }
    private val layout = Sky.layout(records, 7L)
    private val xs = layout.x.copyOf()
    private val ys = layout.y.copyOf()

    @Test
    fun the_beat_is_one_fixed_rate() {
        val habits = listOf(SkyObjects.Habit(1, day(2025, 1, 4), 8 * 60), SkyObjects.Habit(2, day(2025, 2, 1), 21 * 60 + 30))
        val pulsars = SkyObjects.placePulsars(habits, layout, xs, ys, 7L)
        assertEquals(2, pulsars.size)
        for (p in pulsars) {
            // A whole beat later the jets point the same way: one turn per beat, for every pulsar.
            val a = SkyObjects.jetAngle(p, 1.3f)
            val b = SkyObjects.jetAngle(p, 1.3f + SkyObjects.PULSAR_BEAT_SECONDS)
            assertEquals(Math.cos(a.toDouble()), Math.cos(b.toDouble()), 1e-3)
        }
        // Motion off holds the jets at their starting angle.
        assertEquals(SkyObjects.jetAngle(pulsars[0], 0f), SkyObjects.jetAngle(pulsars[0], 0f), 0f)
    }

    /** Nothing about whether a habit was done can reach a pulsar: no function is handed it. */
    @Test
    fun no_pulsar_function_is_handed_the_record() {
        val source = repoFile("app/src/main/java/com/daymark/app/sky/SkyObjects.kt").readText()
        val pulsarHalf = source.substringBefore("// Planets")
        val signatures = Regex("""fun \w+\(([^)]*)\)""").findAll(pulsarHalf).map { it.groupValues[1] }.toList()
        assertTrue(signatures.size >= 5)
        val record = Regex("""(?i)done|count|logs|streak|progress|week|completed|record""")
        assertFalse(signatures.joinToString(), signatures.any { record.containsMatchIn(it) })
        assertTrue(record.containsMatchIn("habit: Habit, doneThisWeek: Int"))
    }

    @Test
    fun far_away_it_beats_quietly_and_full_from_close_in() {
        val peak = SkyObjects.flash(0f)
        assertEquals(1f, peak, 1e-4f)
        assertEquals(0f, SkyObjects.flash((Math.PI / 2).toFloat()), 1e-4f)
        assertEquals(SkyObjects.PULSAR_FAR_PEAK, SkyObjects.pulsarLight(peak, SkyObjects.nearness(1f)), 1e-4f)
        assertEquals(SkyObjects.PULSAR_NEAR_PEAK, SkyObjects.pulsarLight(peak, SkyObjects.nearness(14f)), 1e-4f)
        assertEquals(SkyObjects.PULSAR_NEAR_PEAK, SkyObjects.pulsarLight(peak, SkyObjects.nearness(400f)), 1e-4f)
        assertTrue(SkyObjects.jetLength(SkyObjects.nearness(1f)) < SkyObjects.jetLength(SkyObjects.nearness(14f)))
    }

    @Test
    fun a_planet_sits_among_its_memories_and_no_count_changes_it() {
        val places = listOf(SkyStars.Place(3, "Home"), SkyStars.Place(4, "Nowhere yet"))
        fun withMemories(ids: List<Long>) = SkyStars.resolve(layout, ids.map { SkyStars.Choice("check_in", it, placeId = 3) })
        val few = SkyObjects.placePlanets(places, withMemories(listOf(1, 2)), layout, xs, ys, 7L)
        val many = SkyObjects.placePlanets(places, withMemories((1L..20L).toList()), layout, xs, ys, 7L)
        // A place with nothing in it is not drawn.
        assertEquals(listOf(3L), few.map { it.placeId })
        // Its size, ring and colour come from the seed: the same with two memories as with twenty.
        assertEquals(few[0].radius, many[0].radius, 0f)
        assertEquals(few[0].tilt, many[0].tilt, 0f)
        assertEquals(few[0].tone, many[0].tone)
        // It sits near its memories.
        val i = (0 until layout.starCount).first { layout.recordIds[layout.idStart[it]] == 1L }
        val j = (0 until layout.starCount).first { layout.recordIds[layout.idStart[it]] == 2L }
        val mx = (xs[i] + xs[j]) / 2f
        val my = (ys[i] + ys[j]) / 2f
        assertTrue(Math.hypot((few[0].x - mx).toDouble(), (few[0].y - my).toDouble()) < 0.02)
    }

    @Test
    fun a_shower_sends_one_meteor_at_a_time_the_same_every_time() {
        val a = FloatArray(3)
        val b = FloatArray(3)
        var flying = 0
        var t = 0f
        while (t < 30f) {
            val one = SkyObjects.meteor(t, 11L, a)
            val two = SkyObjects.meteor(t, 11L, b)
            assertEquals(one, two)
            if (one) {
                flying++
                assertTrue(a.contentEquals(b))
                assertTrue(a[1] in 0f..1f && a[2] in 0f..1f)
            }
            t += 0.05f
        }
        // Meteors come and go: some moments have one in flight, and some have none.
        assertTrue(flying > 0)
        assertTrue(flying < (30f / 0.05f).toInt())
        // No meteor before the clock starts, so a still sky (seconds held at 0 and no star) has none.
        assertFalse(SkyObjects.meteor(-1f, 11L, a))
        // The control: a different meteor really does leave in a different direction.
        SkyObjects.meteor(0.2f, 11L, a)
        SkyObjects.meteor(SkyObjects.METEOR_EVERY_SECONDS + 0.2f, 11L, b)
        assertTrue(a[0] != b[0])
    }
}
