package com.daymark.app.stats

import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds the data + fixed copy for the "Review my year" walkthrough from a year's per-day mood
 * means. Pure and deterministic (no Android types, no AI) so it's unit-testable and the wording is
 * fully reviewable.
 *
 * Copy is deliberately **descriptive, not interpretive** — it states what was logged ("26 days ·
 * mostly Good"), never an emotional narrative about how the person "must have felt". For a
 * wellbeing app, asserting feelings would be both presumptuous and unsafe.
 *
 * ## What this deliberately no longer computes
 *
 * The finale used to end on three numbers, and each one was a different way of grading a year of
 * somebody's life:
 *
 *  - **Longest streak.** A run, and therefore a run that ended. `docs/DECISIONS_2026-08.md` §D6.
 *  - **Brightest month.** A superlative, and a rank of the person's own past against itself. Its
 *    mirror is the part nobody prints: naming the brightest month tells the reader that some other
 *    month was the darkest, and they can usually work out which.
 *  - **Average mood.** A mean of an ordinal scale. "3.2" is a grade, and it is a grade for a year
 *    someone lived through rather than for anything they did.
 *
 * Two things are left, and neither ranks anything: the mood the person picked most often, in the
 * word they picked it under, and the date they started. A count was considered and rejected as
 * well — "138 days" puts the denominator 365 in the room whether or not it is drawn.
 */
object YearReview {

    /** One quarter of the year, with its logged days. */
    data class Chapter(
        val label: String,
        val daysLogged: Int,
        /** e.g. "Mostly Good · 26 days", or null when nothing was logged this quarter. */
        val summary: String?,
        /** Mood levels (1..5) of the logged days, for the star cluster. */
        val starLevels: List<Int>,
    )

    data class Review(
        val year: Int,
        val totalStars: Int,
        /**
         * The mood label chosen on more days than any other, in the person's own word for it, or
         * null when no day that year carries a mood. A tie prints every joint-most label, joined
         * by " · " — "Good · Okay" — rather than one of them picked by map order, which would show
         * a different word on two runs over the same data.
         */
        val mostOftenMoodLabel: String?,
        /** The first logged date of the year, e.g. "3 March", or null when the year is empty. */
        val firstEntryLabel: String?,
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

        // The mood chosen most often, counted over days. Ties print every joint-most label, in a
        // fixed order so the string is the same on every run over the same year; the order is a
        // tie-break and nothing more, since a tie is by definition two moods with equal standing.
        val byLevel = inYear.values
            .map { it.roundToInt().coerceIn(1, 5) }
            .groupingBy { it }
            .eachCount()
        val mostOften = byLevel.values.maxOrNull()?.let { best ->
            byLevel.filterValues { it == best }.keys
                .sortedDescending()
                .joinToString(" · ") { moodLabel(it) }
        }

        // The day they started, not the day they stopped. "Last entry" was rejected: a year that
        // ends in August would draw the lapse and put a date on it.
        val firstEntry = inYear.keys.minOrNull()
            ?.format(DateTimeFormatter.ofPattern(FIRST_ENTRY_PATTERN, locale))

        val chapters = QUARTERS.mapNotNull { months ->
            val days = inYear.filterKeys { it.monthValue in months }
            if (days.isEmpty()) return@mapNotNull null
            val levels = days.values.map { it.roundToInt().coerceIn(1, 5) }
            val qAvgLabel = moodLabel(days.values.average().roundToInt().coerceIn(1, 5))
            val label = "${Month.of(months.first).getDisplayName(TextStyle.FULL, locale)} – " +
                Month.of(months.last).getDisplayName(TextStyle.FULL, locale)
            Chapter(
                label = label,
                daysLogged = days.size,
                summary = "Mostly $qAvgLabel · ${days.size} ${if (days.size == 1) "day" else "days"}",
                starLevels = levels,
            )
        }

        return Review(
            year = year,
            totalStars = total,
            mostOftenMoodLabel = mostOften,
            firstEntryLabel = firstEntry,
            chapters = chapters,
        )
    }

    /**
     * Day-of-month then month name — "3 March", never "03 March" and never a year, which the
     * walkthrough's own heading already carries.
     */
    private const val FIRST_ENTRY_PATTERN = "d MMMM"
}
