package com.daymark.app.stats

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds the data + fixed copy for the "Review my year" walkthrough from a year's per-day mood
 * means. Pure and deterministic (no Android types, no AI) so it's unit-testable and the wording is
 * fully reviewable.
 *
 * Copy is deliberately **descriptive, not interpretive** — it states what was logged ("26 days ·
 * mostly Good", "Brightest month: July"), never an emotional narrative about how the person "must
 * have felt". For a wellbeing app, asserting feelings would be both presumptuous and unsafe.
 */
object YearReview {

    /** One quarter of the year, with its logged days and a factual highlight (if any). */
    data class Chapter(
        val label: String,
        val daysLogged: Int,
        /** e.g. "Mostly Good · 26 days", or null when nothing was logged this quarter. */
        val summary: String?,
        /** A factual note (brightest month / longest streak began here), or null. */
        val highlight: String?,
        /** Mood levels (1..5) of the logged days, for the star cluster. */
        val starLevels: List<Int>,
    )

    data class Review(
        val year: Int,
        val totalStars: Int,
        val avgMoodLabel: String?,
        val brightestMonthLabel: String?,
        val longestStreak: Int,
        val chapters: List<Chapter>,
    )

    private val QUARTERS = listOf(1..3, 4..6, 7..9, 10..12)

    fun build(
        year: Int,
        dayMoods: Map<LocalDate, Double>,
        moodLabel: (Int) -> String,
        locale: Locale = Locale.getDefault(),
    ): Review {
        val inYear = dayMoods.filterKeys { it.year == year }
        val total = inYear.size
        val overallAvg = if (inYear.isEmpty()) null else inYear.values.average()
        val avgLabel = overallAvg?.let { moodLabel(it.roundToInt().coerceIn(1, 5)) }

        // Brightest month by mean day-mood. Ties resolve to the earliest month (calendar-stable,
        // not dependent on map/entry order).
        val brightestMonth = inYear.entries
            .groupBy({ it.key.monthValue }, { it.value })
            .mapValues { it.value.average() }
            .entries
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
        val brightestLabel = brightestMonth?.let { Month.of(it).getDisplayName(TextStyle.FULL, locale) }

        // Longest run of consecutive logged days within the year, and where it began.
        val (longest, longestStart) = longestRun(inYear.keys.sorted())

        val chapters = QUARTERS.mapNotNull { months ->
            val days = inYear.filterKeys { it.monthValue in months }
            if (days.isEmpty()) return@mapNotNull null
            val levels = days.values.map { it.roundToInt().coerceIn(1, 5) }
            val qAvgLabel = moodLabel(days.values.average().roundToInt().coerceIn(1, 5))
            val label = "${Month.of(months.first).getDisplayName(TextStyle.FULL, locale)} – " +
                Month.of(months.last).getDisplayName(TextStyle.FULL, locale)
            val highlight = when {
                longest >= 3 && longestStart != null && longestStart.monthValue in months ->
                    "Longest streak began here · $longest days"
                brightestMonth != null && brightestMonth in months ->
                    "Brightest month · $brightestLabel"
                else -> null
            }
            Chapter(
                label = label,
                daysLogged = days.size,
                summary = "Mostly $qAvgLabel · ${days.size} ${if (days.size == 1) "day" else "days"}",
                highlight = highlight,
                starLevels = levels,
            )
        }

        return Review(
            year = year,
            totalStars = total,
            avgMoodLabel = avgLabel,
            brightestMonthLabel = brightestLabel,
            longestStreak = longest,
            chapters = chapters,
        )
    }

    /** Returns (longest consecutive-day run, the date it started) over a sorted day list. */
    private fun longestRun(sorted: List<LocalDate>): Pair<Int, LocalDate?> {
        if (sorted.isEmpty()) return 0 to null
        var best = 1
        var bestStart = sorted[0]
        var run = 1
        var runStart = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i - 1].plusDays(1) == sorted[i]) {
                run++
            } else {
                run = 1
                runStart = sorted[i]
            }
            if (run > best) {
                best = run
                bestStart = runStart
            }
        }
        return best to bestStart
    }
}
