package com.daymark.app.sky

/**
 * The Sky's layout: which acts become stars, where each one sits, and what the surface does when
 * there is nothing in it yet.
 *
 * Import-free, the discipline `stats/InterruptionBudget.kt`, `export/ReportLayout.kt`,
 * `goals/GoalBoard.kt` and `data/ImageStrip.kt` already keep. No Room types, no `LocalDate`, no
 * clock, no `Context`. [SkyRecord] is a plain mirror of whatever row produced it — exactly as
 * `InterruptionBudget.Offer` mirrors an `offer_records` row — so the data layer maps onto this and
 * the dependency runs one way. `docs/SKY.md` is the design; this file is the part of it that has
 * been built, and it is built here rather than in the Compose layer because every rule below is one
 * a unit test has to be able to execute.
 *
 * ## Determinism, which is the feature and not a property of it
 *
 * The same history draws the same sky, byte for byte, on every device and every open. Three things
 * make that true and each one is testable:
 *
 *  1. **Nothing here reads a clock or a random source.** There is no `now`, no `Math.random()`, no
 *     `java.util.Random`. [SkyRandom] is a hash, written out.
 *  2. **A star's position is a hash of that star's own identity** — its kind and its row id — and of
 *     nothing else. Not of its index, not of how many stars exist, not of the viewport. So
 *     inserting a record in 2019 moves nothing, and adding today's check-in does not reflow the
 *     past. This is the property the whole "a place, not a chart" framing rests on (§3.1), and it
 *     is why the placement is a hash rather than a seeded stream.
 *  3. **The order records arrive in does not matter.** The layout sorts, so a `Flow` that emits
 *     rows in a different order produces the same arrays.
 *
 * A one-record difference produces a different sky, because that record is a star that the other
 * sky does not have. Both halves are asserted in `SkyTest`.
 *
 * ## The rule the layout is shaped around
 *
 * > Every period has stars. Nothing is empty, and a hard stretch is never a void.
 *
 * Logging drops when things are hard — energy, self-monitoring capacity, and shame about what the
 * record would say all push the same way — so a surface that draws data quantity draws an inverted
 * map of suffering and hands it to the person as a portrait. §1 of the design sets out the argument
 * in full. Four mechanisms make it structural rather than advisory, and three of the four live in
 * this file:
 *
 *  - **A day with nothing logged draws nothing.** No faint speck, no dimmed cell, no placeholder.
 *    A record produces a star and the absence of a record produces the absence of a star, with no
 *    branch anywhere that emits a marker for an empty date. This is why the layout iterates
 *    *records* and never iterates *dates*: there is no loop here that could visit an empty day, so
 *    there is nowhere for a placeholder to be added later by someone being helpful.
 *  - **No ruler.** No per-day cell geometry, no 31-slot rows, no gridlines, no tick marks. The day
 *    is a *unit of the map* from date to position (see [Sky.layout]) and never a cell that is drawn
 *    or that anything snaps to — stars are scattered continuously inside it, so there is no column
 *    structure to count the gaps between.
 *  - **Equal presence.** [SkyGlyph] holds core radius and core alpha constant across every mood and
 *    every kind, and [SkyPalette] equalises the ramp's contrast so that constancy means what it
 *    says. The layout carries a mood level and never a rank.
 *
 * The fourth, the uniform decorative field, is [SkyField] — separate so its generator cannot see
 * data even by accident.
 *
 * ## What it never says
 *
 * Nothing here is derived, detected, scored or inferred. Every star is an act the person performed,
 * which is the single sentence that makes the surface defensible. There is no aggregate, no total,
 * no percentage, no coverage figure, no streak, no superlative and no ranking of any period against
 * any other — `docs/DECISIONS_2026-08.md` §D6 rules streaks out product-wide, and on a permanent
 * artefact a broken streak is a scar with a date on it. The one count that exists is
 * [SkyListItem.MonthHeading.itemCount], and it exists because a screen-reader user cannot navigate a
 * list without list semantics; see [list].
 *
 * The Sky makes no clinical claim. It never says a period was good or bad, better or worse,
 * improving or declining, and it has no vocabulary for any of those.
 */
object Sky {

    // -------------------------------------------------------------------------------------------
    // Bounds.
    // -------------------------------------------------------------------------------------------

    /**
     * The most stars one day may draw.
     *
     * The brief was to pick a cap and justify it, and the justification has to survive the rule
     * that nothing is ever dropped to make a frame budget — a sky that thins out for the people
     * with the most history would break the uniform-field mechanism by way of a performance
     * decision (§8.3).
     *
     * So this is a cap on *drawn positions*, not on records. Above it, records of the same kind on
     * the same day fold together: the star keeps every id it covers ([recordIdsAt]), stays
     * individually openable, stays in the text list, and is drawn at exactly the same size as a
     * star holding one record. Nothing is discarded and nothing is hidden — some marks are
     * co-located.
     *
     * Sixteen, because it is far outside ordinary use and only bites degenerate input. The design's
     * heaviest profile is two check-ins a day plus other kinds; sixteen acts recorded against one
     * date is a bulk import or a day someone spent in the app, and drawing forty overlapping discs
     * inside one day's width is not more truthful than drawing sixteen, it is only less legible.
     *
     * The cost is stated rather than hidden: on a day that exceeds the cap, adding a record can
     * change the fold boundaries and move that day's stars. Position stability holds absolutely for
     * every day at or under the cap, which is every real day.
     */
    const val MAX_STARS_PER_DAY = 16

    /**
     * The structural bound the cap buys, and the reason no other bound is needed.
     *
     * A month row can hold at most 31 × [MAX_STARS_PER_DAY] stars whatever the person's history, so
     * the number of full glyphs on screen at [SkyDetail.MONTH] is bounded by the layout rather than
     * by how much anyone has logged. Ten years is 120 rows; forty years is 480; rows cost three
     * integers each, so the span is never the problem. Culling is a contiguous index range
     * ([rowRange]) because x is monotonic in time and the arrays are in time order — an array slice
     * rather than a scan or a search.
     */
    const val MAX_STARS_PER_ROW = 31 * MAX_STARS_PER_DAY

    // -------------------------------------------------------------------------------------------
    // Placement constants.
    // -------------------------------------------------------------------------------------------

    /**
     * How far into its own day a star may sit, as a fraction of a day's width.
     *
     * Wide enough that stars fill the day almost edge to edge and there is no visible column
     * rhythm, and stopping short of the edges so that a star on the 5th can never land to the right
     * of a star on the 6th. Time within the row stays monotonic; time *within* a day does not
     * appear at all, because encoding time-of-day spatially would draw the person's sleep pattern
     * across the whole surface (§2.3).
     */
    private const val DAY_JITTER_LO = 0.08f
    private const val DAY_JITTER_HI = 0.92f

    /** Vertical band a star may occupy inside its row, so nothing clips the row's edges. */
    private const val ROW_JITTER_LO = 0.10f
    private const val ROW_JITTER_HI = 0.90f

    /** Keeps a kind's stars from hashing onto the same coordinates as another kind's. */
    private const val KIND_SALT = 0x2F1B3C5DL

    private const val X_SALT = 0x51E1A9C7L
    private const val Y_SALT = -0x3D0A77B1L

    // -------------------------------------------------------------------------------------------

    /**
     * Lay the person's history out.
     *
     * Order of [records] is irrelevant; duplicates of the same `(kind, id)` are the caller's bug and
     * are laid out as two stars rather than silently merged, because quietly dropping one of a
     * person's records is the worse failure.
     *
     * The map from date to position, stated once:
     *
     * ```
     * row = epochMonth(day) - epochMonth(earliest day)
     * x   = (day - first day of that month + jitter) / length of that month     in [0, 1)
     * y   = jitter                                                              in [0, 1)
     * ```
     *
     * Both jitters come from `hash(kind, first record id)`, so they are fixed for the life of the
     * record. `x` is a continuous function of the date: the day is the unit the map is written in,
     * not a cell — nothing snaps to it, nothing is drawn at its boundaries, and two stars on one day
     * do not share an `x`.
     */
    fun layout(records: List<SkyRecord>): SkyLayout {
        if (records.isEmpty()) return SkyLayout.EMPTY

        val sorted = records.sortedWith(
            compareBy<SkyRecord> { it.epochDay }.thenBy { it.kind.ordinal }.thenBy { it.id },
        )
        val firstMonth = SkyCalendar.epochMonth(sorted.first().epochDay)
        val lastMonth = SkyCalendar.epochMonth(sorted.last().epochDay)
        val rowCount = lastMonth - firstMonth + 1

        val starX = ArrayList<Float>(sorted.size)
        val starY = ArrayList<Float>(sorted.size)
        val starRow = ArrayList<Int>(sorted.size)
        val starKind = ArrayList<Int>(sorted.size)
        val starMood = ArrayList<Int>(sorted.size)
        val starDay = ArrayList<Long>(sorted.size)
        val idStart = ArrayList<Int>(sorted.size + 1)
        val ids = LongArray(sorted.size)
        var idCursor = 0

        var cursor = 0
        while (cursor < sorted.size) {
            var dayEnd = cursor
            val day = sorted[cursor].epochDay
            while (dayEnd < sorted.size && sorted[dayEnd].epochDay == day) dayEnd++

            val month = SkyCalendar.epochMonth(day)
            val row = month - firstMonth
            val monthStart = SkyCalendar.firstEpochDayOfMonth(month)
            val monthLength = SkyCalendar.lengthOfMonth(month)
            val dayIndex = (day - monthStart).toInt()

            // Fold the day down to at most MAX_STARS_PER_DAY drawn positions, within kinds.
            val drawn = drawnPerKind(sorted, cursor, dayEnd)

            var kindStart = cursor
            while (kindStart < dayEnd) {
                var kindEnd = kindStart
                val kind = sorted[kindStart].kind
                while (kindEnd < dayEnd && sorted[kindEnd].kind == kind) kindEnd++
                val recordCount = kindEnd - kindStart
                val starCount = drawn[kind.ordinal]

                for (s in 0 until starCount) {
                    // Even split, in id order. Integer arithmetic so the boundaries are exact and
                    // every record lands in exactly one star.
                    val from = kindStart + (recordCount * s) / starCount
                    val to = kindStart + (recordCount * (s + 1)) / starCount
                    val anchorId = sorted[from].id
                    val hash = SkyRandom.mix(kind.ordinal.toLong() * KIND_SALT, anchorId)

                    idStart.add(idCursor)
                    for (r in from until to) {
                        ids[idCursor] = sorted[r].id
                        idCursor++
                    }

                    starX.add(
                        (dayIndex + SkyRandom.between(hash xor X_SALT, DAY_JITTER_LO, DAY_JITTER_HI)) /
                            monthLength,
                    )
                    starY.add(SkyRandom.between(hash xor Y_SALT, ROW_JITTER_LO, ROW_JITTER_HI))
                    starRow.add(row)
                    starKind.add(kind.ordinal)
                    starMood.add(moodOf(sorted, from, to))
                    starDay.add(day)
                }
                kindStart = kindEnd
            }
            cursor = dayEnd
        }
        idStart.add(idCursor)

        val starCount = starX.size
        val rowStart = IntArray(rowCount + 1)
        for (i in 0 until starCount) rowStart[starRow[i] + 1]++
        for (r in 1..rowCount) rowStart[r] += rowStart[r - 1]

        return SkyLayout(
            firstEpochMonth = firstMonth,
            rowCount = rowCount,
            rowStart = rowStart,
            x = FloatArray(starCount) { starX[it] },
            y = FloatArray(starCount) { starY[it] },
            row = IntArray(starCount) { starRow[it] },
            kindOrdinal = IntArray(starCount) { starKind[it] },
            moodLevel = IntArray(starCount) { starMood[it] },
            epochDay = LongArray(starCount) { starDay[it] },
            idStart = IntArray(starCount + 1) { idStart[it] },
            recordIds = ids,
        )
    }

    /**
     * How many drawn positions each kind gets on one day.
     *
     * Starts at one per record — the ordinary case, reached without a single iteration of the loop
     * — and gives up positions from whichever kind currently has the most until the day fits under
     * [MAX_STARS_PER_DAY]. Ties go to the lowest ordinal so the result does not depend on iteration
     * order. Never reduces a kind below one, so a kind that happened at all always has a star.
     */
    private fun drawnPerKind(sorted: List<SkyRecord>, from: Int, to: Int): IntArray {
        val counts = IntArray(SkyKind.entries.size)
        for (i in from until to) counts[sorted[i].kind.ordinal]++
        var total = to - from
        while (total > MAX_STARS_PER_DAY) {
            var worst = -1
            for (k in counts.indices) if (counts[k] > 1 && (worst < 0 || counts[k] > counts[worst])) worst = k
            if (worst < 0) break
            counts[worst]--
            total--
        }
        return counts
    }

    /**
     * The mood a folded star carries.
     *
     * The **first** record's, not an average. Averaging moods would manufacture a level the person
     * never recorded and put a number they did not write into their own history; it is also the
     * shape that turns into "your average mood for the day", which is a summary of a person by
     * software. A folded star shows the mood of the record it is anchored to, and its detail lists
     * the rest.
     */
    private fun moodOf(sorted: List<SkyRecord>, from: Int, to: Int): Int {
        for (i in from until to) if (sorted[i].moodLevel in SkyGlyph.MOOD_MIN..SkyGlyph.MOOD_MAX) {
            return sorted[i].moodLevel
        }
        return SkyGlyph.MOOD_NONE
    }

    // -------------------------------------------------------------------------------------------
    // Navigation. Targets are dates and nothing else — there is no "jump to your best month",
    // because there is no ranking (§6.2).
    // -------------------------------------------------------------------------------------------

    /**
     * The contiguous index range covering rows `[firstRow, lastRow]`, clamped to the layout.
     *
     * Culling is a slice and not a search: stars are in time order and every star of a row is
     * adjacent to the rest of its row, so the visible set is `rowStart[a] until rowStart[b + 1]`.
     */
    fun rowRange(layout: SkyLayout, firstRow: Int, lastRow: Int): IntRange {
        if (layout.rowCount == 0) return IntRange.EMPTY
        val a = if (firstRow < 0) 0 else if (firstRow > layout.rowCount - 1) layout.rowCount - 1 else firstRow
        val b = if (lastRow < a) a else if (lastRow > layout.rowCount - 1) layout.rowCount - 1 else lastRow
        return layout.rowStart[a] until layout.rowStart[b + 1]
    }

    // -------------------------------------------------------------------------------------------
    // The text equivalent — a peer surface, not a fallback (§7.5).
    // -------------------------------------------------------------------------------------------

    /**
     * The same data as a list: month headings in time order, one row per star, same actions.
     *
     * **Only months that have stars get a heading.** A heading with nothing under it is the void
     * rendered in text, and the list must skip from March to July without comment, exactly as the
     * sky does. Absence is not remarked on in any modality — "no entries for March" is the rule most
     * likely to be broken by someone being helpful.
     *
     * The item count on a heading is the one number this surface produces. It is here because a
     * list without list semantics is unnavigable, and withholding that from a screen-reader user to
     * protect them from a figure they never asked to compare is the worse harm. It is structure at
     * the point of navigation: never aggregated into a total, never compared across periods, never
     * trended, and never shown to anyone who did not need it to move around.
     *
     * No summary, no overview sentence, no reading of the data. The list *is* the data.
     */
    fun list(layout: SkyLayout): List<SkyListItem> {
        val out = ArrayList<SkyListItem>()
        for (r in 0 until layout.rowCount) {
            val from = layout.rowStart[r]
            val to = layout.rowStart[r + 1]
            if (from == to) continue
            val month = layout.firstEpochMonth + r
            out.add(
                SkyListItem.MonthHeading(
                    year = SkyCalendar.yearOfMonth(month),
                    month = SkyCalendar.monthOfMonth(month),
                    itemCount = to - from,
                ),
            )
            for (i in from until to) out.add(SkyListItem.Star(i))
        }
        return out
    }
}

/**
 * The six things that become stars. The list is closed; a seventh is a design decision.
 *
 * **Every kind is an act the person performed.** Nothing on the Sky is derived, detected, scored,
 * inferred or synthesised — if a star is there, the person did the thing. That is what lets the
 * surface exist at all, and it is why there is no kind for a notification sent, an offer made, an
 * app open, a suggestion declined, a missed reminder or a practice skipped. There is no negative
 * star. The reception ledger `docs/DECISIONS_2026-08.md` §D1a describes is the decision engine's
 * private business and never appears here.
 *
 * [introduction] is the whole of the Sky's onboarding: the first time a kind appears, one line
 * names its form, once, ever. **Naming, not praise** — "A goal you reached.", never "Nice work!".
 * Congratulation is evaluation and evaluation is the thing this surface does not do, which is also
 * why there is no exclamation mark in any of them. This copy is *proposed copy for a new surface*;
 * any non-diagnostic, provenance or privacy sentence that ends up near the Sky must be reused
 * verbatim from the existing constants rather than re-authored to fit this tone.
 */
enum class SkyKind(val key: String, val introduction: String) {
    CHECK_IN("check_in", "A check-in you logged."),

    /**
     * A therapeutic practice the person worked through — a thought record, a breathing exercise, a
     * self-compassion or values exercise.
     *
     * **This was `EXERCISE`, and the name was the whole defect.** A reader arrives at "exercise
     * star" with physical exercise in mind; the maintainer did, on his own design, which is the
     * strongest evidence available that users would too. Physical exercise needs no kind of its
     * own — it is a goal, either a habit goal or a project with steps, and it already reaches the
     * sky through [GOAL_REACHED] and [PROJECT_STEP] like any other goal.
     *
     * `SESSION` and `TOOL` were the other two candidates and both were dropped: a session is
     * something you attend, and a tool is something the app owns rather than something the person
     * did — which is the one claim every kind here has to be able to make.
     *
     * [introduction] says *used*, not *finished*. A practice worked halfway through is still a
     * practice used, and "finished" quietly grades the attempt.
     */
    PRACTICE("practice", "A practice you used."),
    JOURNAL("journal", "An entry you wrote."),
    GOAL_REACHED("goal_reached", "A goal you reached."),
    PROJECT_STEP("project_step", "A step you completed."),

    /**
     * The only authored kind, and the only one the app never creates.
     *
     * A short title, a date, nothing else. **Never inferred, never suggested, never prompted for**
     * — the app does not notice that someone's entries changed and ask whether something happened,
     * because detecting a discontinuity and asking a person to explain it is inference with a
     * question mark on the end, and the thing it would most reliably detect is a period of not
     * coping. No categories and no picker: a taxonomy is the software deciding what counts as a
     * life. No mood, no valence — a life event is not good or bad, and the UI never asks how it
     * felt.
     */
    LIFE_EVENT("life_event", "A mark you placed."),
    ;

    companion object {
        /** Never throws. An unrecognised key is a kind this version does not have; it draws nothing. */
        fun fromKey(key: String?): SkyKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One act, as the layout needs it.
 *
 * A plain mirror of a row, with the mapping living on the data side — the same arrangement
 * `InterruptionBudget.Offer` has. Four fields, and the two that are missing matter:
 *
 * **No text of any kind.** Not a title, not a note, not an excerpt. The Sky never renders journal
 * prose: it shows *that* you wrote, not *what* you wrote. The reason is the one behind the ban on
 * note excerpts in the companion (§D6) — what is protected is a place to write without an audience
 * — and it is stronger here, because this is a surface people show to other people and screenshot.
 * A shoulder-surfer must learn nothing but that something was written. Having no text field at all
 * means the query that feeds this cannot select one, which is the property enforced rather than
 * intended.
 *
 * **No time of day.** [epochDay] is the *local date the act was recorded*, from the record's own
 * timestamp — never a derived date, never a backfilled one, never "the day it was about". An entry
 * written at 03:00 on the 4th about the 3rd is a star on the 4th, because that is when the person
 * wrote it. The time is available in the record the star opens; it is absent here so that it cannot
 * find its way into a coordinate, which would draw the person's sleep pattern across the surface.
 */
data class SkyRecord(
    val kind: SkyKind,
    /** The row id, unique within [kind]. Half of the star's position hash. */
    val id: Long,
    /** `LocalDate.toEpochDay()` of the local date the act was recorded. */
    val epochDay: Long,
    /**
     * `1..5` as `model/Mood.level`, or [SkyGlyph.MOOD_NONE] when the record has no mood — which is
     * four of the six kinds. **An uncoloured star is not a lesser star**: the sky's ink is its
     * brightest value, not its dimmest, and mood is drawn as a quality of light and never as a rank
     * (see [SkyGlyph]).
     */
    val moodLevel: Int = SkyGlyph.MOOD_NONE,
)

/**
 * The laid-out sky: packed parallel arrays, one entry per drawn star, in time order.
 *
 * Structure of arrays and not `List<Star>`, for the reason `docs/SKY.md` §8.2 gives: fifteen
 * thousand short-lived objects is avoidable collector pressure on a surface whose entire job is to
 * scroll smoothly, and the draw phase must allocate nothing. Coordinates are normalised to `[0, 1)`
 * inside their row, so **zoom is a transform and never a relayout** — every level is the same
 * numbers at a different scale, nothing reflows, and a star can be followed from the overview to
 * its own detail by eye.
 */
class SkyLayout(
    /** The month of the earliest record. Row `r` is the month `firstEpochMonth + r`. */
    val firstEpochMonth: Int,
    /**
     * One row per calendar month from the earliest record to the latest, with no gaps.
     *
     * A month with nothing in it still has a row, and that row draws **nothing** — it is sky, the
     * same sky as every other row, at the same field density. Allocating rows only to months that
     * have stars was rejected twice over: it would make the vertical axis a function of how much
     * was logged, so a quiet year would be compressed into a line and a busy one stretched, and it
     * would mean adding one record to an empty month shifted every row below it, which breaks the
     * one property this surface cannot lose.
     */
    val rowCount: Int,
    /** `rowCount + 1` boundaries. Row `r` owns `rowStart[r] until rowStart[r + 1]`. */
    val rowStart: IntArray,
    /** `[0, 1)` across the row's month. */
    val x: FloatArray,
    /** `[0, 1)` down the row. */
    val y: FloatArray,
    val row: IntArray,
    /** [SkyKind.ordinal]. An ordinal and not a [SkyKind] so the array is primitive. */
    val kindOrdinal: IntArray,
    /** `1..5`, or [SkyGlyph.MOOD_NONE]. */
    val moodLevel: IntArray,
    val epochDay: LongArray,
    /** `starCount + 1` boundaries into [recordIds]. */
    val idStart: IntArray,
    /** Every record id, grouped by star. A star normally owns one; see [Sky.MAX_STARS_PER_DAY]. */
    val recordIds: LongArray,
) {

    val starCount: Int get() = x.size

    fun kindAt(index: Int): SkyKind = SkyKind.entries[kindOrdinal[index]]

    /** How many records this star covers. Never affects how it is drawn — see [SkyGlyph]. */
    fun recordCountAt(index: Int): Int = idStart[index + 1] - idStart[index]

    /** Every record this star covers, in id order. Length is [recordCountAt]. */
    fun recordIdsAt(index: Int): LongArray =
        LongArray(recordCountAt(index)) { recordIds[idStart[index] + it] }

    /**
     * Which of three honest states the surface is in.
     *
     * A brand-new install renders a calm sky and one line — not an error, not a placeholder, and
     * not a fabricated sky. The field is there because the field is not data (it is declared
     * decorative and excluded from every text equivalent), so the screen is never blank, and it is
     * truthful because there is nothing of the person's in it yet.
     */
    val emptiness: Emptiness
        get() = when {
            starCount == 0 -> Emptiness.NO_RECORDS
            starCount == 1 -> Emptiness.FIRST_LIGHT
            else -> Emptiness.POPULATED
        }

    enum class Emptiness {
        /** Nothing logged. Field, one line, no stars, no gutter, no month rows. */
        NO_RECORDS,

        /**
         * Exactly one star.
         *
         * This is the whole of the onboarding: the person's first mark, with a hairline leader to a
         * short line naming what it is. No walkthrough, no carousel, no coach marks, no "3 of 5". A
         * sky with one star says what the app is for better than any onboarding copy could.
         */
        FIRST_LIGHT,

        POPULATED,
    }

    companion object {
        /**
         * A sky with no history in it. Not an error state and not a special case the renderer has
         * to detect — the same object with nothing in the arrays, so every path through the
         * renderer works on it unchanged.
         */
        val EMPTY = SkyLayout(
            firstEpochMonth = 0,
            rowCount = 0,
            rowStart = IntArray(1),
            x = FloatArray(0),
            y = FloatArray(0),
            row = IntArray(0),
            kindOrdinal = IntArray(0),
            moodLevel = IntArray(0),
            epochDay = LongArray(0),
            idStart = IntArray(1),
            recordIds = LongArray(0),
        )

        /**
         * The line under an empty sky.
         *
         * Proposed copy, not fixed copy. It asks for nothing, promises nothing and does not
         * congratulate anyone for arriving — the empty state is an ordinary state of this surface
         * and not a gap to be filled.
         */
        const val EMPTY_LINE = "This is the sky. Nothing of yours is in it yet."
    }
}

/** A row of the text equivalent. Months in time order, stars under them, nothing else. */
sealed class SkyListItem {

    /**
     * [itemCount] is list structure at the point of navigation and is the only count the Sky
     * produces. It is never summed, never compared, and never rendered on the sky itself.
     */
    data class MonthHeading(val year: Int, val month: Int, val itemCount: Int) : SkyListItem()

    /** An index into the [SkyLayout] arrays — the same star, addressed the same way. */
    data class Star(val index: Int) : SkyListItem()
}
