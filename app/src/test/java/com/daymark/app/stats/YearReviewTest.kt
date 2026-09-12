package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class YearReviewTest {

    private val label: (Int) -> String =
        { level -> listOf("Awful", "Bad", "Meh", "Good", "Rad")[level - 1] }

    @Test
    fun emptyYear_hasNoStarsOrChapters() {
        val r = YearReview.build(2026, emptyMap(), label, Locale.US)
        assertEquals(0, r.totalStars)
        assertTrue(r.chapters.isEmpty())
        assertNull(r.mostOftenMoodLabel)
        assertNull(r.firstEntryLabel)
    }

    @Test
    fun countsStars() {
        val moods = mapOf(
            LocalDate.of(2026, 1, 5) to 4.0,
            LocalDate.of(2026, 1, 6) to 4.0,
            LocalDate.of(2026, 7, 10) to 5.0,
        )
        assertEquals(3, YearReview.build(2026, moods, label, Locale.US).totalStars)
    }

    @Test
    fun mostOften_isTheLabelChosenOnTheMostDays() {
        val moods = mapOf(
            LocalDate.of(2026, 1, 5) to 4.0,
            LocalDate.of(2026, 1, 6) to 4.0,
            LocalDate.of(2026, 7, 10) to 5.0,
        )
        assertEquals("Good", YearReview.build(2026, moods, label, Locale.US).mostOftenMoodLabel)
    }

    @Test
    fun mostOften_printsEveryJointMostLabel_inAFixedOrder() {
        // Two days each at level 4 and level 3. Neither is "the" answer, so both are printed, and
        // the same string comes back on every run over the same data rather than one of the two
        // chosen by map order.
        val moods = mapOf(
            LocalDate.of(2026, 1, 5) to 4.0,
            LocalDate.of(2026, 2, 5) to 4.0,
            LocalDate.of(2026, 3, 5) to 3.0,
            LocalDate.of(2026, 4, 5) to 3.0,
        )
        val forwards = YearReview.build(2026, moods, label, Locale.US).mostOftenMoodLabel
        val backwards = YearReview.build(
            2026,
            moods.entries.reversed().associate { it.key to it.value },
            label,
            Locale.US,
        ).mostOftenMoodLabel
        assertEquals("Good · Meh", forwards)
        assertEquals(forwards, backwards)
    }

    @Test
    fun firstEntry_isTheEarliestDayOfTheYear_asADayAndAMonth() {
        val moods = mapOf(
            LocalDate.of(2026, 3, 3) to 4.0,
            LocalDate.of(2026, 1, 20) to 4.0,
            LocalDate.of(2026, 11, 2) to 4.0,
        )
        assertEquals("20 January", YearReview.build(2026, moods, label, Locale.UK).firstEntryLabel)
        // Single-digit days carry no leading zero, which is the whole reason the pattern is "d".
        val march = mapOf(LocalDate.of(2026, 3, 3) to 4.0)
        assertEquals("3 March", YearReview.build(2026, march, label, Locale.UK).firstEntryLabel)
    }

    /**
     * The three deleted finale numbers, asserted gone from the model rather than merely unread by
     * the screen — a field left on [YearReview.Review] is a field the next person renders.
     *
     * The control is the two that remain: if `Review` ever stopped carrying anything at all, the
     * absence half below would pass while proving nothing.
     */
    @Test
    fun theModelCarriesNoAverage_noSuperlative_andNoRun() {
        val moods = buildMap {
            put(LocalDate.of(2026, 1, 1), 3.0)
            for (d in 10..13) put(LocalDate.of(2026, 4, d), 4.0)
            put(LocalDate.of(2026, 9, 1), 5.0)
        }
        val r = YearReview.build(2026, moods, label, Locale.US)

        assertEquals("Good", r.mostOftenMoodLabel)
        assertEquals("1 January", r.firstEntryLabel)

        val fields = YearReview.Review::class.java.declaredFields.map { it.name }
        assertTrue("no fields found — the check would pass on an empty list", fields.isNotEmpty())
        assertTrue(fields.toString(), fields.none { it.contains("avg", ignoreCase = true) })
        assertTrue(fields.toString(), fields.none { it.contains("brightest", ignoreCase = true) })
        assertTrue(fields.toString(), fields.none { it.contains("streak", ignoreCase = true) })
        // Control: the detector does match a name that IS there.
        assertTrue(fields.any { it.contains("mostOften", ignoreCase = true) })
    }

    /** A chapter states what was logged and stops — no highlight chip, no superlative, no run. */
    @Test
    fun chaptersOnlyForQuartersWithData_andSummarise() {
        val moods = mapOf(
            LocalDate.of(2026, 5, 1) to 4.0,
            LocalDate.of(2026, 5, 2) to 4.0,
        )
        val r = YearReview.build(2026, moods, label, Locale.US)
        assertEquals(1, r.chapters.size)
        val c = r.chapters.first()
        assertEquals(2, c.daysLogged)
        assertEquals("Mostly Good · 2 days", c.summary)
        assertEquals(listOf(4, 4), c.starLevels)

        val chapterFields = YearReview.Chapter::class.java.declaredFields.map { it.name }
        assertTrue(chapterFields.isNotEmpty())
        assertTrue(chapterFields.toString(), "highlight" !in chapterFields)
        // Control: the same check finds a field that is still there.
        assertTrue("summary" in chapterFields)
    }

    @Test
    fun ignoresEntriesOutsideTheYear() {
        val moods = mapOf(
            LocalDate.of(2025, 12, 31) to 5.0,
            LocalDate.of(2026, 1, 1) to 3.0,
        )
        val r = YearReview.build(2026, moods, label, Locale.US)
        assertEquals(1, r.totalStars)
        assertEquals("Meh", r.mostOftenMoodLabel)
        assertEquals("1 January", r.firstEntryLabel)
    }
}
