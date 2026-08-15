package com.daymark.app.stats

import java.util.Locale
import kotlin.math.abs

/**
 * Side 4's discussion prompts — observations about a person's own data, phrased as questions for
 * the two humans in the room (`docs/PLAN_2026-08-NEXT.md` §1, "Side 4").
 *
 * **The bright line this file exists to hold.** A report that tells a clinician what to *do* is
 * clinical decision support, a regulated category with a different compliance life than anything
 * Daymark is. "Based on study X, consider increasing session frequency" crosses it; that is not a
 * style preference, it changes what the product legally is. So every rule here surfaces a pattern
 * in the person's own data and hands the judgement over:
 *
 * > *Wellbeing entries were lower on the 3 weeks with no logged activity. Worth asking about?*
 *
 * It cites nothing, prescribes nothing, and is useful precisely because it does not conclude. The
 * evidence base is citable in psychoeducation *for the person*, about a practice they are being
 * offered — never as justification for a clinical action in a clinician's report.
 *
 * Concretely, no prompt may carry a recommendation, a dose or frequency change, a citation, a
 * severity or risk word, or anything resembling a diagnosis. [FORBIDDEN_PHRASING] is that rule
 * written down, and `DiscussionPromptsTest` runs it over everything these rules can emit. The guard
 * is not applied inside [build] on purpose: silently dropping a bad prompt would hide the bug that
 * wrote it, and the guard is worth more as a test that fails loudly.
 *
 * **The most honest rule on the page is [KIND_THIN_DATA]** — the one that fires when there is too
 * little to say anything. Four entries across five weeks is thin, and a page that says so is worth
 * more than a page of patterns read out of noise. When it fires, the rules that would claim a
 * pattern across the range stand down.
 *
 * Nothing here labels the person or infers a clinical state (D1a): each prompt reflects what was
 * logged, counts what is there, and names where the data stops.
 *
 * Pure and Android-free like the rest of `stats/`: no Room types, no report types, no clock. The
 * caller maps `ReportData` onto [Inputs] — the mapping stays on the export side so this package
 * keeps its one-way dependency (export reads stats, never the reverse).
 */
object DiscussionPrompts {

    /**
     * One prompt. [kind] is a stable id for tests and for the renderer; [text] is the whole of what
     * gets printed, already assembled, because a prompt split into fragments is a prompt that can
     * be reassembled into something nobody reviewed.
     */
    data class Prompt(val kind: String, val text: String)

    /** One dated result on an instrument. [bandLabel] is the instrument's own word, copied as-is. */
    data class ScorePoint(val score: Int, val bandLabel: String = "")

    /**
     * One instrument's run of results over the range.
     *
     * [points] are oldest-first, the same order `ReportInstrumentSeries` carries them in. Dates are
     * deliberately absent: no rule here needs them, and taking them would mean taking a time zone
     * too. [rangeMin]/[rangeMax] are the raw scale, so a movement can be measured against the size
     * of the scale it happened on rather than treated as an absolute number of points.
     *
     * Note what is *not* here: any notion of which direction is "better". The bundled instruments
     * do not all agree on that, the definitions do not always carry the sentence that says so, and
     * a rule that guessed would be interpreting someone's scale. So the movement rule says "moved",
     * never "improved" or "worsened".
     */
    data class Instrument(
        val title: String,
        val rangeMin: Int,
        val rangeMax: Int,
        val points: List<ScorePoint>,
    )

    /**
     * One week of the range.
     *
     * [activityCount] counts logged activities, not entries — a week can hold check-ins and no
     * activity at all, which is exactly the co-occurrence the plan's worked example is about.
     */
    data class Week(
        val entryCount: Int,
        val activityCount: Int,
        val averageMood: Double?,
    )

    /**
     * Something a clinician published to this person, and what became of it.
     *
     * Declines are in scope here because this is the person's *own* export and they chose to
     * include it. D5's rule that a decline stays invisible governs the live clinician portal, where
     * visibility would turn accepting into a performance; it does not govern a document someone
     * decided to hand over. The fields are mechanical counts — nothing here scores engagement.
     */
    data class Offer(
        val item: String,
        val opened: Boolean = false,
        val completedCount: Int = 0,
        val declined: Boolean = false,
    )

    /**
     * The already-computed facts the rules read.
     *
     * [gapStretches] is a count of runs, not of days: the number of days with nothing logged is
     * `daysInRange - daysLogged` and is derived here rather than passed, so a prompt and the
     * coverage paragraph on side 2 can never disagree about the same range.
     */
    data class Inputs(
        val daysInRange: Int,
        val daysLogged: Int,
        val totalEntries: Int,
        val weeks: List<Week> = emptyList(),
        val gapStretches: Int = 0,
        val instruments: List<Instrument> = emptyList(),
        val offers: List<Offer> = emptyList(),
    )

    const val KIND_THIN_DATA = "thin_data"
    const val KIND_QUIET_WEEKS = "quiet_weeks"
    const val KIND_COVERAGE_GAPS = "coverage_gaps"
    const val KIND_NO_CHECKINS = "no_checkins"
    const val KIND_THIN_INSTRUMENT = "thin_instrument"
    const val KIND_BAND_HELD = "band_held"
    const val KIND_ONE_MOVED = "one_instrument_moved"
    const val KIND_OFFER_DECLINED = "offer_declined"
    const val KIND_OFFER_UNFINISHED = "offer_unfinished"
    const val KIND_OFFER_UNOPENED = "offer_unopened"

    // Gates. These constants *are* the rules, and they are deliberately conservative: the cost of a
    // prompt that should not have fired is a clinician spending a session on noise.
    /** Below this many entries, nothing in the range can carry a pattern. */
    private const val MIN_ENTRIES = 8
    /** Logging on fewer than one day in this many is thin however many entries it produced. */
    private const val LOGGED_DAY_DIVISOR = 4
    /** Both sides of the quiet-week comparison need at least this many weeks. */
    private const val MIN_WEEKS_EACH_SIDE = 2
    /** Mean-mood difference (on the 1..5 scale) below which the quiet-week rule says nothing. */
    private const val MIN_MOOD_DIFFERENCE = 0.5
    /** A gap this fraction of the range, in at least [MIN_GAP_STRETCHES] runs, is worth naming. */
    private const val GAP_FRACTION = 0.30
    private const val MIN_GAP_STRETCHES = 2
    /** At or below this many results, an instrument has a couple of dots rather than a shape. */
    private const val THIN_INSTRUMENT_MAX = 2
    /** Fewest results before "they all came back in the same band" means anything. */
    private const val MIN_POINTS_FOR_BAND = 3
    /** Fewest results before the halves of a run can be compared at all. */
    private const val MIN_POINTS_FOR_SHIFT = 4
    /** Shift, as a fraction of the instrument's own scale, that counts as having moved. */
    private const val SHIFT_MOVED = 0.15
    /** Shift, as a fraction of the scale, under which an instrument counts as having held. */
    private const val SHIFT_HELD = 0.075

    /**
     * Phrasing no generated prompt may contain — the bright line as a regex.
     *
     * Five families: advice and prescription; dose or frequency changes; inference and causal
     * claims; severity, risk and diagnosis; and citing an evidence base as justification.
     *
     * Applied by [violatesBrightLine], which strips double-quoted spans first. Everything inside
     * quotes is copy from somewhere else — an instrument's title, a band label the instrument
     * authors wrote, the name of a thing a clinician published — reproduced verbatim because
     * paraphrasing someone else's band would be its own kind of interpretation. PHQ-9's own band is
     * "Moderately severe"; printing it is not this file making a severity claim. The guard is about
     * the sentence we wrote around the quotes, which is the only part we author.
     */
    val FORBIDDEN_PHRASING = Regex(
        "\\b(?:" +
            // Advice, prescription, instruction.
            "consider\\w*|recommend\\w*|advis\\w*|suggest\\w*|encourag\\w*|propos\\w*|" +
            "should\\w*|must|ought|need\\w*|require\\w*|warrant\\w*|prescrib\\w*|try|tries|trying|" +
            // Changing a dose, a frequency, or an intensity.
            "increas\\w*|decreas\\w*|reduc\\w*|escalat\\w*|taper\\w*|titrat\\w*|adjust\\w*|" +
            // Inference and causal claims.
            "indicat\\w*|caus\\w*|correlat\\w*|predict\\w*|infer\\w*|" +
            // Severity, risk, trajectory.
            "severe\\w*|severity|risk\\w*|acute|critical|urgent|deteriorat\\w*|worsen\\w*|" +
            // Diagnosis and clinical labelling.
            "diagnos\\w*|disorder\\w*|symptom\\w*|remission|relapse|clinically|patholog\\w*|" +
            // Citing the literature as justification.
            "stud(?:y|ies)|research|evidence|literature|meta-analys\\w*|guideline\\w*|trial\\w*" +
            ")\\b",
        RegexOption.IGNORE_CASE,
    )

    private val QUOTED_SPAN = Regex("\"[^\"]*\"")

    /** True when [text] carries phrasing side 4 may not print. See [FORBIDDEN_PHRASING]. */
    fun violatesBrightLine(text: String): Boolean =
        FORBIDDEN_PHRASING.containsMatchIn(QUOTED_SPAN.replace(text, "\"\""))

    /**
     * Every prompt this range supports, in a fixed order (thin-data first, then the range-wide
     * observations, then per-instrument, then what was offered). The order is the order of the
     * rules below and does not depend on map iteration, so the same inputs always print the same
     * page.
     *
     * Returns empty when there is nothing at all to describe — an empty range needs no prompt to
     * say it is empty, and manufacturing one would be padding.
     */
    fun build(inputs: Inputs, locale: Locale = Locale.getDefault()): List<Prompt> {
        if (inputs.totalEntries == 0 && inputs.instruments.isEmpty() && inputs.offers.isEmpty()) {
            return emptyList()
        }
        val out = ArrayList<Prompt>()

        // RULE 1 — too little data to say anything. Fires when the range holds fewer than
        // MIN_ENTRIES entries, or when something was logged on under a quarter of its days. The
        // most honest thing on the page, and it silences the two rules below it that would
        // otherwise read a pattern out of four points.
        val thin = inputs.totalEntries < MIN_ENTRIES ||
            inputs.daysLogged * LOGGED_DAY_DIVISOR < inputs.daysInRange
        if (thin) {
            val text = if (inputs.totalEntries == 0) {
                "Nothing was logged in this range. Nothing here supports a pattern. Worth asking about?"
            } else {
                "${inputs.totalEntries} ${entryWord(inputs.totalEntries)} across " +
                    "${span(inputs.daysInRange)} is thin — something was logged on " +
                    "${inputs.daysLogged} of ${inputs.daysInRange} days. Nothing here supports a " +
                    "pattern. Worth asking about?"
            }
            out.add(Prompt(KIND_THIN_DATA, text))
        }

        // RULE 2 — the plan's worked example. Fires when at least MIN_WEEKS_EACH_SIDE weeks logged
        // entries but no activity at all, at least that many logged both, and the quiet weeks
        // averaged at least MIN_MOOD_DIFFERENCE lower. Co-occurrence, stated as co-occurrence: it
        // reports two counts and two means and asks a question, and says nothing about which way
        // round any of it runs.
        if (!thin) {
            val logged = inputs.weeks.filter { it.entryCount > 0 && it.averageMood != null }
            val quietWeeks = logged.filter { it.activityCount == 0 }
            val activeWeeks = logged.filter { it.activityCount > 0 }
            if (quietWeeks.size >= MIN_WEEKS_EACH_SIDE && activeWeeks.size >= MIN_WEEKS_EACH_SIDE) {
                val quietMean = quietWeeks.mapNotNull { it.averageMood }.average()
                val activeMean = activeWeeks.mapNotNull { it.averageMood }.average()
                if (activeMean - quietMean >= MIN_MOOD_DIFFERENCE) {
                    out.add(
                        Prompt(
                            KIND_QUIET_WEEKS,
                            "Wellbeing entries were lower on the ${quietWeeks.size} weeks with no " +
                                "logged activity — averaging ${oneDp(quietMean, locale)} against " +
                                "${oneDp(activeMean, locale)} on the other ${activeWeeks.size}. " +
                                "Worth asking about?",
                        ),
                    )
                }
            }
        }

        // RULE 3 — where the data stops. Fires when at least GAP_FRACTION of the range has nothing
        // logged, spread over MIN_GAP_STRETCHES or more separate runs. Restates the report's own
        // invariant rather than an observation about the person: nothing on any side is drawn
        // across a gap, and a reader who cannot see where the data stops cannot tell a flat line
        // from an absent one.
        val gapDays = (inputs.daysInRange - inputs.daysLogged).coerceAtLeast(0)
        if (!thin && gapDays > 0 && inputs.gapStretches >= MIN_GAP_STRETCHES &&
            gapDays >= inputs.daysInRange * GAP_FRACTION
        ) {
            out.add(
                Prompt(
                    KIND_COVERAGE_GAPS,
                    "$gapDays of the ${inputs.daysInRange} days in this range have nothing logged, " +
                        "in ${inputs.gapStretches} separate stretches. Nothing on these pages is " +
                        "drawn across them. Worth asking about?",
                ),
            )
        }

        // RULE 4 — entries but no self-checks. Fires when the range holds enough daily entries to
        // be worth reading and no instrument results at all. Side 1's plots are empty in that case,
        // and an empty plot reads as a flat line unless something says otherwise.
        if (inputs.totalEntries >= MIN_ENTRIES && inputs.instruments.isEmpty()) {
            out.add(
                Prompt(
                    KIND_NO_CHECKINS,
                    "There are ${inputs.totalEntries} daily entries in this range and no self-check " +
                        "results. The plots on side 1 are empty because nothing was taken, not " +
                        "because scores held flat. Worth asking about?",
                ),
            )
        }

        // RULE 5 — a tool with too few results to say anything. Fires per instrument holding
        // between 1 and THIN_INSTRUMENT_MAX results. The per-tool twin of rule 1: a two-point plot
        // is two dots, and printing it next to a twelve-point one invites reading a line into it.
        inputs.instruments
            .filter { it.points.size in 1..THIN_INSTRUMENT_MAX }
            .forEach { instrument ->
                out.add(
                    Prompt(
                        KIND_THIN_INSTRUMENT,
                        "\"${instrument.title}\" has ${instrument.points.size} " +
                            "${resultWord(instrument.points.size)} in this range — too few for the " +
                            "plot on side 1 to be more than dots. Worth asking about?",
                    ),
                )
            }

        // RULE 6 — every result in the same band. Fires per instrument with at least
        // MIN_POINTS_FOR_BAND results that all carry the same non-blank band label. The band is the
        // instrument's own word, quoted and unaltered; the question is whether the number matched
        // the weeks, which only the two of them can answer.
        inputs.instruments
            .filter { it.points.size >= MIN_POINTS_FOR_BAND }
            .forEach { instrument ->
                val band = instrument.points.first().bandLabel
                if (band.isNotBlank() && instrument.points.all { it.bandLabel == band }) {
                    out.add(
                        Prompt(
                            KIND_BAND_HELD,
                            "All ${instrument.points.size} \"${instrument.title}\" results in this " +
                                "range came back in the same band (\"$band\"). Does that match how " +
                                "the weeks felt?",
                        ),
                    )
                }
            }

        // RULE 7 — a change confined to one instrument. Fires when two or more instruments each
        // hold at least MIN_POINTS_FOR_SHIFT results, exactly one moved by SHIFT_MOVED of its own
        // scale between the first and last halves of its run, and every other one moved by less
        // than SHIFT_HELD. Measured against each instrument's own range so a 3-point move on a
        // 0..15 scale is not compared with a 3-point move on a 0..27 one. The word is "moved":
        // which direction is better is the instrument's business, and often it does not say.
        val comparable = inputs.instruments.filter {
            it.points.size >= MIN_POINTS_FOR_SHIFT && it.rangeMax > it.rangeMin
        }
        if (comparable.size >= 2) {
            val moved = comparable.filter { relativeShift(it) >= SHIFT_MOVED }
            val held = comparable.filter { relativeShift(it) < SHIFT_HELD }
            if (moved.size == 1 && held.size == comparable.size - 1) {
                val mover = moved.first()
                val (early, late) = halves(mover.points)
                val theOthers = if (held.size == 1) "it" else "they"
                out.add(
                    Prompt(
                        KIND_ONE_MOVED,
                        "\"${mover.title}\" moved across this range — a mean of " +
                            "${oneDp(early, locale)} early against " +
                            "${oneDp(late, locale)} late — while " +
                            andList(held.map { "\"${it.title}\"" }) +
                            " stayed near where $theOthers started. Worth asking about?",
                    ),
                )
            }
        }

        // RULE 8 — a declined offer. Fires for anything published to this person and declined. It
        // is on the page because they put it there; the live portal keeps declines invisible so
        // that accepting never becomes a performance (D5). Mechanical, and it does not ask why —
        // the point is that only they know.
        val declined = inputs.offers.filter { it.declined }
        if (declined.isNotEmpty()) {
            val names = andList(declined.map { "\"${it.item}\"" })
            out.add(
                Prompt(
                    KIND_OFFER_DECLINED,
                    if (declined.size == 1) {
                        "$names was offered in this range and declined. Worth asking about?"
                    } else {
                        "${declined.size} things offered in this range were declined — $names. " +
                            "Worth asking about?"
                    },
                ),
            )
        }

        // RULE 9 — opened, never finished. Fires for an offer that was opened and completed zero
        // times, and was not declined. "Nothing here says why" is doing real work: an unfinished
        // module is a fact about a log, not about a person.
        val unfinished = inputs.offers.filter { it.opened && it.completedCount == 0 && !it.declined }
        if (unfinished.isNotEmpty()) {
            val wasWere = if (unfinished.size == 1) "was" else "were"
            out.add(
                Prompt(
                    KIND_OFFER_UNFINISHED,
                    andList(unfinished.map { "\"${it.item}\"" }) +
                        " $wasWere opened in this range and not finished. " +
                        "Nothing here says why. Worth asking about?",
                ),
            )
        }

        // RULE 10 — offered, never opened. Fires for an offer never opened, never completed and
        // never declined. Kept apart from rule 9 because "did not open it" and "opened it and
        // stopped" are different facts, and collapsing them into one engagement number is exactly
        // the throughput metric this product does not print.
        val unopened = inputs.offers.filter { !it.opened && it.completedCount == 0 && !it.declined }
        if (unopened.isNotEmpty()) {
            val wasWere = if (unopened.size == 1) "was" else "were"
            out.add(
                Prompt(
                    KIND_OFFER_UNOPENED,
                    andList(unopened.map { "\"${it.item}\"" }) +
                        " $wasWere offered in this range and not opened. " +
                        "Nothing here says why. Worth asking about?",
                ),
            )
        }

        return out
    }

    /** [build], flattened to the strings `ReportData.discussionPrompts` carries. */
    fun texts(inputs: Inputs, locale: Locale = Locale.getDefault()): List<String> =
        build(inputs, locale).map { it.text }

    /**
     * Mean of the first half of a run against the mean of the last half, skipping the middle
     * result on an odd count so the two halves are the same size.
     */
    private fun halves(points: List<ScorePoint>): Pair<Double, Double> {
        val half = points.size / 2
        val early = points.take(half).map { it.score.toDouble() }.average()
        val late = points.takeLast(half).map { it.score.toDouble() }.average()
        return early to late
    }

    /** How far an instrument moved, as a fraction of its own scale. */
    private fun relativeShift(instrument: Instrument): Double {
        val scale = (instrument.rangeMax - instrument.rangeMin).toDouble()
        if (scale <= 0.0) return 0.0
        val (early, late) = halves(instrument.points)
        return abs(late - early) / scale
    }

    private fun oneDp(value: Double, locale: Locale): String = String.format(locale, "%.1f", value)

    private fun entryWord(count: Int): String = if (count == 1) "entry" else "entries"

    private fun resultWord(count: Int): String = if (count == 1) "result" else "results"

    /** "a", "a and b", "a, b and c" — plain English for a short list of names. */
    private fun andList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items.first()
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** Weeks read better than days once there are a couple of them. */
    private fun span(daysInRange: Int): String = when {
        daysInRange >= 14 -> "${daysInRange / 7} weeks"
        daysInRange == 1 -> "1 day"
        else -> "$daysInRange days"
    }
}
