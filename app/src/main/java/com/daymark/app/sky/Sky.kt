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
 * ## One guide, measured in memories
 *
 * Every star lies along one guide, in time order, and distance along it is counted in memories,
 * never days ([SkyForm]; `DECISIONS.md` §D11, #449). The guide is a river in some skies, a run of
 * galaxies in others and an open sky in the rest. A month with forty memories takes forty places
 * along it and a month with two takes two, so a hard stretch takes almost no room and is never
 * drawn as an empty reach. That is how the rule below is kept now that there is no decorative
 * field: a quiet stretch has nowhere to show up as a gap.
 *
 * ## Determinism, which is the feature and not a property of it
 *
 * The same history draws the same sky, byte for byte, on every device and every open:
 *
 *  1. **Nothing here reads a clock or a random source.** There is no `now`, no `Math.random()`, no
 *     `java.util.Random`. [SkyRandom] is a hash, written out.
 *  2. **The sky's form is the seed's**, and a star's own scatter is a hash of its identity (its
 *     kind and anchor record id). Where it sits along its guide is its count, so
 *     appending newer records moves nothing already drawn; a record with an older date shifts the
 *     later stars one place along.
 *  3. **The order records arrive in does not matter.** The layout sorts, so a `Flow` that emits
 *     rows in a different order produces the same arrays.
 *
 * The `seed` [layout] takes is the sky's own seed ([SkySeed]): derived once, persisted, and never
 * re-derived. It decides the sky's form and size, its stream length and its clusters' shapes.
 *
 * ## The rule the layout is shaped around
 *
 * > Every period has stars. Nothing is empty, and a hard stretch is never a void.
 *
 * Logging drops when things are hard, so a surface that draws data quantity draws an inverted map
 * of suffering and hands it to the person as a portrait. `docs/SKY.md` §1 sets out the argument.
 *
 *  - **A day with nothing logged draws nothing.** No faint speck, no dimmed cell, no placeholder.
 *    The layout iterates *records* and never *dates*, so there is no loop here that could visit an
 *    empty day and nowhere for a placeholder to be added later.
 *  - **No ruler.** No per-day geometry, no axis and no gridlines. The guide is counted in memories,
 *    so no reach of it belongs to a stretch of time with nothing in it.
 *  - **Equal presence.** [SkyGlyph] holds core radius and core alpha constant across every mood and
 *    every kind, and [SkyPalette] equalises the ramp's contrast so that constancy means what it
 *    says. The layout carries a mood level and never a rank.
 *  - **No shape is the good one.** A band, a cluster, a stream and a single star are placed with
 *    the same care, and nothing here describes one as kept up or broken (§D11).
 *
 * ## What it never says
 *
 * Nothing here is derived, detected, scored or inferred. Every star is an act the person performed,
 * which is the single sentence that makes the surface defensible. There is no aggregate, no total,
 * no percentage, no coverage figure, no streak, no superlative and no ranking of any period against
 * any other — `docs/DECISIONS.md` §D6 rules streaks out product-wide, and on a permanent
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
     * the same day fold together: the star keeps every id it covers ([SkyLayout.recordIdsAt]),
     * stays individually openable, stays in the text list, and is drawn at exactly the same size as
     * a star holding one record. Nothing is discarded and nothing is hidden — some marks are
     * co-located.
     *
     * Sixteen, because it is far outside ordinary use and only bites degenerate input. The design's
     * heaviest profile is two check-ins a day plus other kinds; sixteen acts recorded against one
     * date is a bulk import or a day someone spent in the app, and drawing forty marks for one day
     * is not more truthful than drawing sixteen, it is only more work.
     *
     * The cost is stated rather than hidden: on a day that exceeds the cap, adding a record can
     * change the fold boundaries and move that day's stars. Position stability holds absolutely for
     * every day at or under the cap, which is every real day.
     */
    const val MAX_STARS_PER_DAY = 16

    // -------------------------------------------------------------------------------------------
    // Placement.
    // -------------------------------------------------------------------------------------------

    /** Kinds are spaced this far apart in the mixer's *input*, so no two kinds share an identity. */
    private const val KIND_SALT = 0x2F1B3C5DL

    /** Goes into the mixer's input, never onto its output; `SkyTwinkle`'s header says why. */
    private const val IDENTITY_SALT = 0x0A17C3E5L

    // -------------------------------------------------------------------------------------------

    /**
     * Lay the person's history out.
     *
     * Order of [records] is irrelevant; duplicates of the same `(kind, id)` are the caller's bug and
     * are laid out as two stars rather than silently merged, because quietly dropping one of a
     * person's records is the worse failure.
     *
     * [seed] is the sky's persisted seed ([SkySeed]). It decides the sky's form and how its
     * stretches are drawn, never which stars exist.
     *
     * Placement is [SkyForm.place]: star `i`, in time order, takes the `i`th place along the seed's guide,
     * scattered by a hash of its own identity and by the rhythm of the days around it. Mood never
     * reaches it.
     *
     * **Overlap is accepted and never resolved.** Two stars that land near each other stay near each
     * other: nudging one away would make its position depend on its neighbours. Zoom separates them,
     * a tap takes the nearest, and the list reaches anything.
     */
    fun layout(records: List<SkyRecord>, seed: Long): SkyLayout {
        if (records.isEmpty()) return SkyLayout.EMPTY

        val sorted = records.sortedWith(
            compareBy<SkyRecord> { it.epochDay }.thenBy { it.kind.ordinal }.thenBy { it.id },
        )

        val starIdentity = ArrayList<Long>(sorted.size)
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

                    idStart.add(idCursor)
                    for (r in from until to) {
                        ids[idCursor] = sorted[r].id
                        idCursor++
                    }

                    starIdentity.add(identityOf(kind, anchorId))
                    starKind.add(kind.ordinal)
                    starMood.add(moodOf(sorted, from, to))
                    starDay.add(day)
                }
                kindStart = kindEnd
            }
            cursor = dayEnd
        }
        idStart.add(idCursor)

        val starCount = starIdentity.size
        val days = LongArray(starCount) { starDay[it] }
        val placed = SkyForm.place(days, LongArray(starCount) { starIdentity[it] }, seed)
        return SkyLayout(
            x = placed.x,
            y = placed.y,
            kindOrdinal = IntArray(starCount) { starKind[it] },
            moodLevel = IntArray(starCount) { starMood[it] },
            epochDay = days,
            idStart = IntArray(starCount + 1) { idStart[it] },
            recordIds = ids,
            driftX = placed.driftX,
            driftY = placed.driftY,
            shape = placed.shape,
            height = placed.height,
            form = placed.form,
        )
    }

    /**
     * A star's identity for its own scatter: its kind and anchor record id, and nothing else, so
     * the same star is scattered the same way in every sky that holds it.
     */
    fun identityOf(kind: SkyKind, anchorId: Long): Long =
        SkyRandom.mix(kind.ordinal.toLong() * KIND_SALT + IDENTITY_SALT, anchorId)

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
    // The text equivalent — a peer surface, not a fallback (§7.5), and now the only way to reach a
    // particular date.
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
     * Months are read off the stars themselves rather than off any layout geometry — there is none
     * any more — which is why a month nobody logged in cannot produce a heading even by accident.
     * The stars are in time order, so this is one pass with no sort and no map.
     *
     * No summary, no overview sentence, no reading of the data. The list *is* the data.
     */
    fun list(layout: SkyLayout): List<SkyListItem> {
        val out = ArrayList<SkyListItem>()
        var index = 0
        while (index < layout.starCount) {
            val month = SkyCalendar.epochMonth(layout.epochDay[index])
            var end = index
            while (end < layout.starCount && SkyCalendar.epochMonth(layout.epochDay[end]) == month) {
                end++
            }
            out.add(
                SkyListItem.MonthHeading(
                    year = SkyCalendar.yearOfMonth(month),
                    month = SkyCalendar.monthOfMonth(month),
                    itemCount = end - index,
                ),
            )
            for (i in index until end) out.add(SkyListItem.Star(i))
            index = end
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
 * star. The reception ledger `docs/DECISIONS.md` §D1a describes is the decision engine's
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
 * written at 03:00 on the 4th about the 3rd is a star dated the 4th, because that is when the
 * person wrote it. The time is available in the record the star opens; it is absent here so that it
 * cannot find its way into a coordinate.
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
 * scroll smoothly, and the draw phase must allocate nothing.
 *
 * Coordinates are normalised to `[0, 1)` across **one open field** — there are no rows and no
 * regions. The renderer maps that square onto the canvas, so a five-star sky spreads across the
 * screen and a ten-year sky is dense, without any position ever changing and without anything
 * reflowing: **zoom is a transform and never a relayout**.
 *
 * Time order is kept because the text list ([Sky.list]) and the descriptions need it, not because
 * anything is drawn in it. A star's index says when it was logged relative to the others and says
 * nothing whatever about where it is.
 */
class SkyLayout(
    /** `0..1` across the sky, where the star was placed. Add [driftX] × age for where it is now. */
    val x: FloatArray,
    /** `0..`[height] down the sky, in the same units as [x]. */
    val y: FloatArray,
    /** [SkyKind.ordinal]. An ordinal and not a [SkyKind] so the array is primitive. */
    val kindOrdinal: IntArray,
    /** `1..5`, or [SkyGlyph.MOOD_NONE]. */
    val moodLevel: IntArray,
    /**
     * The local date each star was recorded on, ascending.
     *
     * Age is computed from it: [SkyAge.ageYears] turns this and the caller's idea of today into the
     * redshift, the fade and the drift. Placement reads it only through the rhythm of which days
     * have something on them ([SkyForm.Rhythm]).
     */
    val epochDay: LongArray,
    /** `starCount + 1` boundaries into [recordIds]. */
    val idStart: IntArray,
    /** Every record id, grouped by star. A star normally owns one; see [Sky.MAX_STARS_PER_DAY]. */
    val recordIds: LongArray,
    /** How far a star drifts per year of age, across. Decoration, never a relayout ([SkyForm]). */
    val driftX: FloatArray = FloatArray(x.size),
    /** How far a star drifts per year of age, down. */
    val driftY: FloatArray = FloatArray(x.size),
    /** [SkyForm.SHAPE_SINGLE], `SHAPE_BAND`, `SHAPE_CLUSTER` or `SHAPE_STREAM`, from dates alone. */
    val shape: IntArray = IntArray(x.size),
    /** How far down the sky reaches, in the units of [x]. At least [SkyForm.MIN_HEIGHT]. */
    val height: Float = SkyForm.MIN_HEIGHT,
    /** River, galaxies or open sky, from the seed; null in an empty sky. The Key names it. */
    val form: SkyForm.Form? = null,
) {

    /** Where star [index] is on [onEpochDay]: its place plus its drift for its age that day. */
    fun xOn(index: Int, onEpochDay: Long): Float = x[index] + driftX[index] * yearsOld(index, onEpochDay)

    fun yOn(index: Int, onEpochDay: Long): Float = y[index] + driftY[index] * yearsOld(index, onEpochDay)

    private fun yearsOld(index: Int, onEpochDay: Long): Float =
        ((onEpochDay - epochDay[index]).coerceAtLeast(0L)).toFloat() / SkyAge.DAYS_PER_YEAR

    /** Every star's position on [onEpochDay], as two arrays, for drawing a frame. */
    fun positionsOn(onEpochDay: Long): Array<FloatArray> = arrayOf(
        FloatArray(starCount) { xOn(it, onEpochDay) },
        FloatArray(starCount) { yOn(it, onEpochDay) },
    )

    /** The newest star: where the opening flies to. -1 in an empty sky. */
    val newest: Int get() = starCount - 1

    /** The index of the star whose anchor record is [recordId] of [kind], or -1. */
    fun indexOf(kind: SkyKind, recordId: Long): Int {
        for (i in 0 until starCount) {
            if (kindOrdinal[i] == kind.ordinal && recordIds[idStart[i]] == recordId) return i
        }
        return -1
    }


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
        /** Nothing logged. Field, one line, no stars. */
        NO_RECORDS,

        /**
         * Exactly one star.
         *
         * This is the whole of the onboarding: the person's first mark, and one short line naming
         * what it is. No walkthrough, no carousel, no coach marks, no "3 of 5". A
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
            x = FloatArray(0),
            y = FloatArray(0),
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
        const val EMPTY_LINE = "This is the sky. Nothing of yours is in it."
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
