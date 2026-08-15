package com.daymark.app.export

import com.daymark.app.data.dao.AssessmentDao
import com.daymark.app.data.dao.EntryDao
import com.daymark.app.data.dao.JournalDao
import com.daymark.app.stats.DiscussionPrompts
import com.daymark.app.stats.MoodStats
import com.daymark.app.ui.assessments.Assessments
import com.daymark.app.ui.components.ProvenanceTier
import com.daymark.app.util.DateUtils
import java.security.MessageDigest
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class ReportEntry(
    val dateTime: Long,
    val moodLevel: Int,
    val note: String,
    val activityNames: List<String>,
)

/**
 * A journal entry the person put on side 3. [id] is carried so the export screen can match a
 * printed entry back to the row the person ticked — the inclusion set in [PdfExportOptions] is by
 * id, and a report that could not name what it included would be impossible to review before
 * sending. Last in the parameter list, and defaulted, so existing positional callers still build.
 */
data class ReportJournalEntry(
    val dateTime: Long,
    val title: String,
    val body: String,
    val id: Long = 0,
)

data class ReportActivityStat(val name: String, val averageMood: Double, val count: Int)

data class TrendPoint(val date: LocalDate, val value: Double?)

/** One dated score on an instrument, with the band it fell in and whatever the person wrote with it. */
data class ReportScorePoint(
    val date: LocalDate,
    val score: Int,
    val bandLabel: String,
    /**
     * The note attached to this check-in — part of the check-in, filled in knowing it goes with
     * the score. It is not an excerpt lifted out of the journal; the journal is side 3 and is a
     * separate, per-entry decision. Empty when the instrument stores no note.
     */
    val note: String = "",
)

/**
 * The person's own wording for what a band on this instrument means to them.
 *
 * Only ever populated for self-authored tools, where the band label means exactly what they wrote
 * it to mean and printing their sentence is the only way a reader can know that. A bundled,
 * validated questionnaire's band text belongs to the instrument, not to the person, so it is never
 * copied in here and dressed up as theirs.
 */
data class ReportBandWording(val bandLabel: String, val inTheirWords: String)

/**
 * One instrument's run of scores over the report range — the unit side 1 plots.
 *
 * [directionInWords] is the sentence that says which way is which ("higher means more difficulty
 * holding a thread"). It comes from the instrument definition and is blank when the definition does
 * not carry one; the exporter never writes it, because guessing the direction of someone's scale is
 * exactly the kind of quiet interpretation the report is meant not to do.
 *
 * [rangeMin]/[rangeMax] are the raw scale, so a score reads as "7 of 20" rather than as a number
 * whose size is unknowable. Any presentation rescaling (WHO-5's ×4) stays with the renderer.
 */
data class ReportInstrumentSeries(
    val id: String,
    val title: String,
    val provenanceTier: ProvenanceTier,
    val directionInWords: String,
    val rangeMin: Int,
    val rangeMax: Int,
    val points: List<ReportScorePoint>,
    val ownBandWording: List<ReportBandWording> = emptyList(),
)

/**
 * A thing a clinician published to this person, and what became of it.
 *
 * Declines and silences are in here because this is the *person's own export* — they chose to
 * include it. The live clinician portal keeps a decline invisible on purpose (D5), so that
 * accepting never becomes a performance; that rule governs the portal, not a document someone
 * decided to hand over. [outcome] is mechanical ("Completed x5", "No response", "Declined"), never
 * a judgement of engagement.
 */
data class ReportAssignmentOutcome(
    val publishedAtMillis: Long,
    val item: String,
    val cadence: String,
    val outcome: String,
)

/** One concrete step inside a project. The step, not the project, is where an intention attaches. */
data class ReportProjectStep(val title: String, val done: Boolean)

/**
 * A goal or project and the steps under it.
 *
 * Steps are listed, not totalled: no percentage, no ring, no velocity. Completion does not predict
 * improvement (D6), and a project someone set down is not a failure — [stateLabel] carries a
 * neutral word for that state and nothing here ranks one project against another.
 */
data class ReportProjectProgress(
    val title: String,
    val cue: String = "",
    val routine: String = "",
    val steps: List<ReportProjectStep> = emptyList(),
    val stateLabel: String = "",
)

/**
 * A stretch of days in the range with nothing logged, stated as itself.
 *
 * Gaps are printed rather than smoothed over: no figure on any side is interpolated across one,
 * and a reader who cannot see where the data stops cannot tell a flat line from an absent one.
 */
data class ReportCoverageGap(val from: LocalDate, val to: LocalDate, val days: Int)

/**
 * Everything the PDF generator needs, plus an authenticity hash over the source data.
 *
 * Deliberately absent: any streak. A streak measures adherence to the app rather than anything
 * about the person, and it is the one number here that can make a bad fortnight read as a failure
 * (D6). It used to be on this model; removing it is the point, not an oversight.
 */
data class ReportData(
    val patientName: String,
    val rangeLabel: String,
    val generatedAtMillis: Long,
    val daysInRange: Int,
    val daysLogged: Int,
    val totalEntries: Int,
    val averageMood: Double?,
    val moodCounts: Map<Int, Int>,
    val trend: List<TrendPoint>,
    val activityStats: List<ReportActivityStat>,
    val entries: List<ReportEntry>,
    val journal: List<ReportJournalEntry>,
    /** A short rules-based narrative summary (derived; not part of the authenticity hash). */
    val periodReview: String,
    val sha256Hex: String,
    // --- four-side layout (docs/PLAN_2026-08-NEXT.md §1) ---
    /** Side 1: one plot per instrument. */
    val instrumentSeries: List<ReportInstrumentSeries> = emptyList(),
    /** Side 2: what was suggested and what happened to it. */
    val assignmentOutcomes: List<ReportAssignmentOutcome> = emptyList(),
    /** Side 2: goals and projects, listed rather than scored. */
    val projects: List<ReportProjectProgress> = emptyList(),
    /**
     * Side 2: where the data stops. Counted against [daysInRange] and [daysLogged] rather than
     * carrying its own totals, so the coverage paragraph and the header can never disagree.
     */
    val coverageGaps: List<ReportCoverageGap> = emptyList(),
    /**
     * Side 4: observations from this person's own data, phrased as questions for the two humans.
     * Never a recommendation — see [PdfExportOptions.includeDiscussionPrompts].
     */
    val discussionPrompts: List<String> = emptyList(),
    /**
     * How many journal entries fall in the range, included or not — the denominator in "4 of 26".
     *
     * This is the whole of what side 3 may say about what was left out. No topic list, no dates, no
     * hint of subject: the shape of what someone withheld is itself a disclosure.
     */
    val journalEntriesAvailable: Int = 0,
)

@Singleton
class ReportDataBuilder @Inject constructor(
    private val entryDao: EntryDao,
    private val journalDao: JournalDao,
    private val assessmentDao: AssessmentDao,
) {
    suspend fun build(options: PdfExportOptions, nowMillis: Long): ReportData {
        val ewas = entryDao.getBetween(options.fromMillis, options.toMillis)
        val reportEntries = ewas.map { e ->
            ReportEntry(
                dateTime = e.entry.dateTime,
                moodLevel = e.entry.moodLevel,
                note = if (options.includeNotes) e.entry.note else "",
                activityNames = e.activities.map { it.name },
            )
        }

        // Side 3. Two gates, both the person's: the side has to be switched on, and each entry has
        // to have been ticked. An entry that is merely in range is not included by that fact.
        // Only read the bodies when side 3 might actually print. journalEntriesAvailable needs a
        // COUNT, not the rows, and pulling every journal body into process memory on an export that
        // is not carrying any of them is the kind of quiet over-collection this product exists not
        // to do.
        val journalInRange = if (options.includeInTheirWords) {
            journalDao.getAll().filter { it.dateTime in options.fromMillis..options.toMillis }
        } else {
            emptyList()
        }
        val journal = if (options.includeInTheirWords) {
            journalInRange
                .filter { options.includeAllJournalInRange || it.id in options.includedJournalEntryIds }
                .sortedBy { it.dateTime }
                .map { ReportJournalEntry(it.dateTime, it.title, it.body, it.id) }
        } else {
            emptyList()
        }

        val levels = ewas.map { it.entry.moodLevel }
        val days = ewas.map { DateUtils.toLocalDate(it.entry.dateTime) }.toSet()

        // Per-activity average mood with names + counts.
        val pairs = ewas.map { it.entry.moodLevel to it.activities.map { a -> a.id } }
        val averages = MoodStats.activityAverages(pairs)
        val nameById = ewas.flatMap { it.activities }.associate { it.id to it.name }
        val counts = mutableMapOf<Long, Int>()
        pairs.forEach { (_, ids) -> ids.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val activityStats = averages.entries
            .mapNotNull { (id, avg) -> nameById[id]?.let { ReportActivityStat(it, avg, counts[id] ?: 0) } }
            .sortedByDescending { it.averageMood }

        // Daily trend across the range.
        val from = DateUtils.toLocalDate(options.fromMillis)
        val to = DateUtils.toLocalDate(options.toMillis)
        val byDay = ewas.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
        val daysInRange = (java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt() + 1).coerceAtLeast(1)
        val trend = (0 until daysInRange).map { offset ->
            val day = from.plusDays(offset.toLong())
            TrendPoint(day, byDay[day]?.map { it.entry.moodLevel }?.average())
        }

        val instrumentSeries = buildInstrumentSeries(options.fromMillis, options.toMillis)
        val gaps = coverageGaps(from, daysInRange, byDay.keys)

        // Rules-based narrative summary (reuses the same pure helpers as the Insights tab).
        val dow = com.daymark.app.stats.MoodPatterns.byDayOfWeek(
            ewas.map { DateUtils.toLocalDate(it.entry.dateTime).dayOfWeek to it.entry.moodLevel },
        )
        val topUp = com.daymark.app.stats.MoodCorrelations
            .rankLifts(com.daymark.app.stats.MoodCorrelations.factorDeltas(pairs, 3), 1).first
            .firstOrNull()?.let { nameById[it.id] }
        val periodReview = com.daymark.app.stats.PeriodReview.build(
            com.daymark.app.stats.PeriodReview.Inputs(
                totalEntries = ewas.size,
                avgMood = MoodStats.averageMood(levels),
                bestDay = dow.maxByOrNull { it.value }?.key,
                worstDay = dow.minByOrNull { it.value }?.key,
                topFactorUp = topUp,
                // Zero, always: PeriodReview only adds its streak sentence at two or more, so this
                // suppresses it for the report without touching the shared copy the in-app card
                // still uses. The report carries no streak (D6).
                currentStreak = 0,
            ),
        )

        // Side 4's prompts. Computed here rather than left to default, because the generator's
        // "nothing met the threshold" line asserts to a clinician that a threshold WAS evaluated —
        // and printing that while never calling the rules would be a false assurance, which is worse
        // than printing nothing. DiscussionPrompts carries its own bright-line guard against
        // recommendation phrasing; this call site only supplies data.
        val prompts = if (options.includeDiscussionPrompts) {
            DiscussionPrompts.texts(
                DiscussionPrompts.Inputs(
                    daysInRange = daysInRange,
                    daysLogged = days.size,
                    totalEntries = ewas.size,
                    gapStretches = gaps.size,
                    instruments = instrumentSeries.map { series ->
                        DiscussionPrompts.Instrument(
                            title = series.title,
                            rangeMin = series.rangeMin,
                            rangeMax = series.rangeMax,
                            points = series.points.map {
                                DiscussionPrompts.ScorePoint(it.score, it.bandLabel)
                            },
                        )
                    },
                ),
            )
        } else {
            emptyList()
        }

        return ReportData(
            patientName = options.patientName,
            rangeLabel = options.rangeLabel,
            generatedAtMillis = nowMillis,
            daysInRange = daysInRange,
            daysLogged = days.size,
            totalEntries = ewas.size,
            averageMood = MoodStats.averageMood(levels),
            moodCounts = MoodStats.moodCounts(levels),
            trend = trend,
            activityStats = activityStats,
            entries = reportEntries,
            journal = journal,
            periodReview = periodReview,
            sha256Hex = sha256Hex(canonicalPayload(options.fromMillis, options.toMillis, reportEntries, journal)),
            instrumentSeries = instrumentSeries,
            coverageGaps = gaps,
            journalEntriesAvailable = journalInRange.size,
            discussionPrompts = prompts,
        )
    }

    /**
     * One series per bundled self-check that has results in the range.
     *
     * The tier is read off the catalog rather than asserted here: a bundled questionnaire carries a
     * citation precisely when it reproduces a published instrument faithfully, which is what
     * [ProvenanceTier.VALIDATED] means. Anything without one is our own and says so.
     *
     * `directionInWords` and `ownBandWording` stay empty for these. Both are the *instrument's*
     * copy, and the bundled three do not carry either; inventing a direction sentence for someone
     * else's scale, or reprinting a catalog band as though the person had written it, would be the
     * exporter making a claim it has no standing to make.
     */
    private suspend fun buildInstrumentSeries(fromMillis: Long, toMillis: Long): List<ReportInstrumentSeries> =
        assessmentDao.getAll()
            .filter { it.dateTime in fromMillis..toMillis }
            .groupBy { it.key }
            .entries
            .mapNotNull { (key, results) ->
                val screener = Assessments.byKey(key) ?: return@mapNotNull null
                ReportInstrumentSeries(
                    id = key,
                    title = screener.title,
                    provenanceTier =
                        if (screener.citation.isNotBlank()) ProvenanceTier.VALIDATED else ProvenanceTier.ORIGINAL,
                    directionInWords = "",
                    rangeMin = 0,
                    // Same max-score idiom as the assessment runner, but null-safe: an export must
                    // not be the thing that crashes on a malformed screener.
                    rangeMax = screener.questions.sumOf { q -> q.options.maxOfOrNull { it.points } ?: 0 },
                    points = results.sortedBy { it.dateTime }.map {
                        ReportScorePoint(
                            date = DateUtils.toLocalDate(it.dateTime),
                            score = it.score,
                            bandLabel = it.bandLabel,
                        )
                    },
                )
            }
            .sortedBy { it.title }

    /**
     * Every run of consecutive days in the range with no entry at all.
     *
     * All of them, not just the long ones — the renderer decides what is worth printing, but the
     * model does not quietly drop the short gaps and leave a reader with a tidier period than the
     * one that happened.
     */
    private fun coverageGaps(
        from: LocalDate,
        daysInRange: Int,
        loggedDays: Set<LocalDate>,
    ): List<ReportCoverageGap> {
        val gaps = mutableListOf<ReportCoverageGap>()
        var runStart: LocalDate? = null
        var runEnd: LocalDate? = null
        for (offset in 0 until daysInRange) {
            val day = from.plusDays(offset.toLong())
            if (day in loggedDays) {
                val s = runStart
                val e = runEnd
                if (s != null && e != null) gaps.add(gap(s, e))
                runStart = null
                runEnd = null
            } else {
                if (runStart == null) runStart = day
                runEnd = day
            }
        }
        // A range that ends mid-gap still ends mid-gap; the trailing run is a gap like any other.
        val s = runStart
        val e = runEnd
        if (s != null && e != null) gaps.add(gap(s, e))
        return gaps
    }

    private fun gap(from: LocalDate, to: LocalDate) = ReportCoverageGap(
        from = from,
        to = to,
        days = java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt() + 1,
    )

    companion object {
        /**
         * A deterministic, order-stable string over the report's source data, used for the
         * authenticity hash. Excludes the volatile "generated at" timestamp on purpose so the
         * same data always hashes the same.
         *
         * Still v1, and still entries + journal only. The instrument series, coverage gaps and
         * prompts are all derived from entries that are already covered, so nothing verifiable was
         * lost by leaving the payload alone — and changing what v1 hashes would silently break
         * every report already in someone's filing cabinet.
         */
        fun canonicalPayload(
            fromMillis: Long,
            toMillis: Long,
            entries: List<ReportEntry>,
            journal: List<ReportJournalEntry>,
        ): String {
            val sb = StringBuilder()
            sb.append("daymark-report-v1\n")
            sb.append("range:").append(fromMillis).append('-').append(toMillis).append('\n')
            entries.sortedWith(compareBy({ it.dateTime }, { it.moodLevel })).forEach { e ->
                sb.append("E|").append(e.dateTime).append('|').append(e.moodLevel)
                    .append('|').append(e.note.replace("\n", " "))
                    .append('|').append(e.activityNames.sorted().joinToString(","))
                    .append('\n')
            }
            journal.sortedBy { it.dateTime }.forEach { j ->
                sb.append("J|").append(j.dateTime).append('|').append(j.title.replace("\n", " "))
                    .append('|').append(j.body.replace("\n", " ")).append('\n')
            }
            return sb.toString()
        }

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
