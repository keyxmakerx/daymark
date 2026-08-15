package com.daymark.app.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.daymark.app.model.Mood
import com.daymark.app.ui.assessments.Assessments
import com.daymark.app.ui.components.ProvenanceTier
import com.daymark.app.util.DateUtils
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Renders a [ReportData] into a clinician-facing PDF using the platform [PdfDocument] + [Canvas]
 * (no third-party PDF dependency; text stays selectable/vector).
 *
 * **Four sides, one job each** (see `docs/PLAN_2026-08-NEXT.md` §1). The reconciliation of "fill the
 * page" with the alert-fatigue evidence is that the finding is about elements *competing in the same
 * glance*, not about page count. So the flag keeps its own zone at the top of side 1 and never shares
 * it; everything below and after may be dense, because a reader on side 2 has chosen to go looking.
 *
 *  1. **The glance** — header, what this is, the one flag, per-instrument plots, completion summary.
 *  2. **The detail** — every entry with its check-in note, what was suggested and what happened,
 *     goals and projects, and coverage stated without interpolation.
 *  3. **In their words** — journal entries, only when the person opted in, whole and never excerpted.
 *  4. **For the conversation** — discussion prompts, per-tool provenance, then verification at the
 *     bottom.
 *
 * This file is the renderer and nothing else. What a prompt may say lives in
 * [com.daymark.app.stats.DiscussionPrompts]; what a series *is* lives in [ReportInstrumentSeries].
 * The only thing derived here is layout — plus each series' own usual-range band, which is a property
 * of the drawing rather than of the data (it is the shape the points are plotted against).
 *
 * Deliberate omissions, each of which would be a product-invariant breach:
 *  - **No streaks.** [ReportData] no longer carries one, and [ReportData.periodReview] is not
 *    rendered either — it is second-person copy written for the in-app card, not for a clinician.
 *  - **No connected trend line.** Short self-report series are dominated by noise; a line between
 *    two check-ins draws an interpolation the data does not contain. Plots are scatter over a
 *    usual-range band, gaps are printed as gaps, and points sit at their real date.
 *  - **No inference.** Nothing here labels the person or names a clinical state. The flag is a
 *    change notice against this person's own earlier range, never a severity rating, and it fires
 *    the same way in both directions so it cannot become a bad-news detector.
 *  - **The mood ramp stays on mood.** Scatter points are coloured by the mood ramp only on the daily
 *    mood series, where the colour *is* the logged value. A questionnaire score is drawn in plain
 *    ink: colouring it would assert a good/bad direction for a scale whose direction the report
 *    does not claim to know.
 */
@Singleton
class PdfReportGenerator @Inject constructor() {

    fun generate(data: ReportData, options: PdfExportOptions, out: OutputStream) {
        val doc = PdfDocument()
        val w = options.paperSize.widthPt
        val h = options.paperSize.heightPt
        val ctx = PageCtx(doc, w.toFloat(), h.toFloat(), data)
        ctx.start()

        // Side 3 exists only if the person switched it on *and* ticked entries. Both gates are
        // theirs; an entry merely being in range is not inclusion.
        val journalSide = options.includeInTheirWords && data.journal.isNotEmpty()
        val sides = if (journalSide) 4 else 3

        ctx.sideOneGlance(data, options, sides)
        ctx.sideTwoDetail(data, sides)
        if (journalSide) ctx.sideThreeInTheirWords(data, sides)
        ctx.sideFourForTheConversation(data, options, sides)

        ctx.finish()
        doc.writeTo(out)
        doc.close()
    }
}

// ---------------------------------------------------------------------------------------------
// Palette. Paper ink, deliberately monochrome apart from the mood ramp.
// ---------------------------------------------------------------------------------------------

private const val INK = 0xFF2A2722.toInt()
private const val SOFT = 0xFF6B655B.toInt()
private const val FAINT = 0xFFA49C8E.toInt()
private const val HAIR = 0xFFE7DFD1.toInt()

/** Fill for the usual-range band. Neutral paper tone — a band is context, not a verdict. */
private const val BAND = 0xFFF1EADC.toInt()

/**
 * The mood ramp. It encodes a person's **mood data** and nothing else — never interface state,
 * never a judgement about a page, a section or a period, and never a score on some other scale.
 */
private val MOOD_ARGB = intArrayOf(
    0xFFAE5747.toInt(), 0xFFC27C46.toInt(), 0xFFC6A24E.toInt(), 0xFF8FA268.toInt(), 0xFF5E8A66.toInt(),
)

private fun moodColor(level: Int) = MOOD_ARGB[(level - 1).coerceIn(0, 4)]

private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

private fun fmt1(v: Double) = String.format("%.1f", v)

// ---------------------------------------------------------------------------------------------
// Fixed copy. These are constants on purpose: non-diagnostic framing, provenance disclaimers and
// audit caveats are not reworded, reflowed or "improved". Restyle the container, never the prose.
// ---------------------------------------------------------------------------------------------

private object Copy {

    const val FOOTER = "Daymark · self-reported data, not a clinical assessment"

    const val DECLARE_TITLE = "What this is."
    const val DECLARE_BODY =
        "Self-checks this person chose to share, with their own scores and descriptive bands. Bands " +
            "compare this person to their own history — never to anyone else. Nothing here is a " +
            "diagnosis, a screen, or a clinical threshold."

    const val FLAG_LABEL = "One thing to look at"
    const val FLAG_CAVEAT =
        "A change notice, not a severity rating. It says the pattern moved — not what it means."

    const val NO_FLAG_LABEL = "Nothing to flag"
    const val NO_FLAG_BODY =
        "Nothing has moved outside the usual range this person held over this period."
    const val NO_FLAG_THIN =
        "There are too few entries to establish a usual range, so no movement can be called."
    const val NO_FLAG_CAVEAT =
        "Stated so the absence of a flag reads as a finding rather than an oversight."

    const val DENSITY_LEFT = "entries per week"
    const val DENSITY_RIGHT = "lighter = fewer"
    const val USUAL_RANGE = "usual range"
    const val NO_ENTRIES = "No entries in this range."
    const val NO_RESULTS = "No results in this range."

    const val NOTE_FIELD =
        "“Their note” is the one-line note attached to a check-in — a field that has always existed, " +
            "that they fill in knowing it is part of the check-in. It is not an excerpt lifted out of " +
            "their journal; the journal is side 3 and is a separate decision."

    const val COVERAGE_FIXED =
        "The gaps are gaps — nothing is interpolated, and no figure on side 1 is adjusted for them."

    /**
     * Printed under the two side-2 sections when the model carries nothing for them. On a page whose
     * job is completeness a silently missing section is indistinguishable from a filtered one, so
     * the absence is stated rather than left blank.
     */
    const val NOT_RECORDED_YET =
        "These sections are part of the report's design; this build does not record the data behind " +
            "them yet. Nothing has been filtered out."

    const val DECLINES_SHOWN =
        "Declines are shown here because this is the person's own export — they chose to include it. " +
            "Inside the live clinician portal a decline stays invisible, so that accepting never " +
            "becomes a performance."

    const val JOURNAL_OPT_IN =
        "This side exists because they turned it on, entry by entry. The app defaults to not " +
            "including any of it. Nothing here was selected by the software, summarised, or " +
            "excerpted — each entry is whole, exactly as written, and they saw this page before " +
            "sending it."

    const val JOURNAL_ABSENT_TAIL =
        "There is no count of what was withheld beyond this line, no topic list, and no indication " +
            "of what they are about — because a shape is also a disclosure."

    const val PROMPTS_CAVEAT =
        "Observations from this person's own data, phrased as questions. They conclude nothing, " +
            "recommend nothing and cite nothing — the judgement is yours and theirs."

    const val PROMPTS_NONE =
        "Nothing in this range met the threshold for a prompt. Stated so the absence reads as a " +
            "finding rather than an oversight."

    const val PROMPTS_OFF =
        "Discussion prompts were switched off for this export."

    /** Verbatim from `docs/PROVENANCE.md`, "The disclaimer". */
    const val CUSTOM_DISCLAIMER =
        "Custom-made — a personal reflection tool, not a validated or clinical instrument. Not for " +
            "diagnosis."

    /** Printed when every tool in the report is self-authored, which is the common case. */
    const val PROVENANCE_ALL_CUSTOM =
        "Every tool here is Custom tier. None is a validated instrument, none has population norms, " +
            "and no score in this report has a clinical cut-off. Bands are splits chosen for this " +
            "app, and mean only what the person's own history makes them mean."

    /** Printed when the report mixes tiers, where the all-Custom sentence above would be false. */
    const val PROVENANCE_MIXED =
        "A tool's tier is immutable per version: it cannot promote itself, and editing a validated " +
            "tool's wording downgrades it. Custom tools have no population norms and no clinical " +
            "cut-off — their bands are splits chosen for this app, and mean only what this person's " +
            "own history makes them mean."

    const val NOT_IN_REPORT =
        "Not in this report, and not obtainable from it. Location — the app collects none. Anything " +
            "written inside an exercise — those fields are excluded from sharing by construction, " +
            "not by preference. Any journal entry not on side 3. Streak counts — this report " +
            "carries none, since a streak reports adherence to the app rather than anything about " +
            "the person. The app still shows them on the person's own screens."

    /** The authenticity wording. Unchanged from the previous report; only its position moved. */
    const val AUTHENTICITY =
        "This hash covers the report's underlying entries. It lets a recipient detect tampering; it " +
            "does not verify that the self-reported data is accurate."
}

// ---------------------------------------------------------------------------------------------
// Plot model. Layout only — every value comes off [ReportData] unchanged.
// ---------------------------------------------------------------------------------------------

/** One plotted observation. [moodLevel] is set only on the daily mood series, for its dot colour. */
private data class PlotPoint(val date: LocalDate, val value: Double, val moodLevel: Int? = null)

/** One instrument's plot: the series, the scale it sits on, and the band it is read against. */
private data class Instrument(
    val title: String,
    val tier: ProvenanceTier,
    /** The instrument's own sentence for which way is which. Blank when it does not carry one. */
    val direction: String,
    val scaleMin: Double,
    val scaleMax: Double,
    val scaleLabel: String,
    /** Value → the label drawn against that gridline. */
    val axisLabels: List<Pair<Double, String>>,
    val points: List<PlotPoint>,
    /** The middle 50% of this person's own values, or null when there is too little to say. */
    val band: Pair<Double, Double>?,
    val useMoodColours: Boolean,
    val ownBandWording: List<ReportBandWording>,
    val weeklyCounts: List<Int>,
)

private object Plot {

    /** Below this, a "usual range" would be an artefact of the sample rather than a fact about them. */
    const val MIN_POINTS_FOR_BAND = 5

    /** The flag looks at the last [FLAG_WINDOW] observations only. */
    const val FLAG_WINDOW = 4
    const val FLAG_MIN_OUTSIDE = 3

    /** A gap has to be this long before it is named individually on side 2. */
    const val GAP_MIN_DAYS = 5

    /**
     * This person's usual range: the middle 50% of their own values across the period. It is a
     * description of their history and nothing else — there is no population norm behind it.
     *
     * A degenerate range (someone who logged the same value every time) is returned as-is rather
     * than discarded. "They were at 3 every time" is a usual range, and a 1 really is outside it;
     * treating it as *no* range would tell the reader there was too little data when there was not.
     */
    fun usualRange(points: List<PlotPoint>): Pair<Double, Double>? {
        if (points.size < MIN_POINTS_FOR_BAND) return null
        val sorted = points.map { it.value }.sorted()
        return quantile(sorted, 0.25) to quantile(sorted, 0.75)
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val pos = q * (sorted.size - 1)
        val i = pos.toInt()
        val frac = pos - i
        return if (i + 1 < sorted.size) sorted[i] + (sorted[i + 1] - sorted[i]) * frac else sorted[i]
    }

    /**
     * The one flag. [below] is direction only, never severity: the same rule fires whether the
     * recent values sit under or over the band, so the flag cannot become a bad-news detector.
     *
     * The band it is measured against is the one drawn on the plot — the whole period's usual
     * range, recent values included. That is deliberate on two counts. It is checkable: a reader
     * can look at the plot and see the points the flag is talking about sitting outside the shaded
     * band, which a hidden baseline band would not allow. And quartiles are robust to a minority
     * excursion, so a handful of recent values well outside the range still fires it, while a
     * series where half the points sit "outside" does not — because at that point they are not an
     * excursion, they are the range.
     */
    data class Flag(val instrument: String, val outside: Int, val window: Int, val below: Boolean)

    fun flag(inst: Instrument): Flag? {
        val band = inst.band ?: return null
        if (inst.points.size < MIN_POINTS_FOR_BAND) return null
        val recent = inst.points.takeLast(FLAG_WINDOW)
        if (recent.size < FLAG_WINDOW) return null
        val under = recent.count { it.value < band.first }
        val over = recent.count { it.value > band.second }
        return when {
            under >= FLAG_MIN_OUTSIDE -> Flag(inst.title, under, recent.size, true)
            over >= FLAG_MIN_OUTSIDE -> Flag(inst.title, over, recent.size, false)
            else -> null
        }
    }

    /**
     * Picks the single flag that gets the zone at the top of side 1: the one with the most
     * observations outside its band, ties going to the first instrument. Exactly one, or none —
     * the zone is never shared, and it never becomes a list.
     */
    fun oneFlag(instruments: List<Instrument>): Flag? =
        instruments.mapNotNull { flag(it) }.maxByOrNull { it.outside }

    /** Observations per week from the start of the range — the sampling density strip. */
    fun weeklyCounts(start: LocalDate, days: Int, dates: List<LocalDate>): List<Int> {
        if (days <= 0) return emptyList()
        val weeks = (days + 6) / 7
        val counts = IntArray(weeks)
        dates.forEach { d ->
            val off = ChronoUnit.DAYS.between(start, d).toInt()
            val w = off / 7
            if (off >= 0 && w < weeks) counts[w]++
        }
        return counts.toList()
    }

    /**
     * The daily mood check-in as an instrument. It is the app's own tool, so the axis carries the
     * person's own words for their scale rather than a direction sentence written here.
     */
    fun moodInstrument(data: ReportData, start: LocalDate, days: Int): Instrument {
        val points = data.trend.mapNotNull { p ->
            p.value?.let { PlotPoint(p.date, it, it.roundToInt()) }
        }
        return Instrument(
            title = "Daily mood check-in",
            tier = ProvenanceTier.ORIGINAL,
            direction = "",
            scaleMin = 1.0,
            scaleMax = 5.0,
            scaleLabel = "1–5",
            axisLabels = listOf(
                5.0 to Mood.fromLevel(5).label,
                3.0 to Mood.fromLevel(3).label,
                1.0 to Mood.fromLevel(1).label,
            ),
            points = points,
            band = usualRange(points),
            useMoodColours = true,
            ownBandWording = emptyList(),
            weeklyCounts = weeklyCounts(start, days, data.entries.map { DateUtils.toLocalDate(it.dateTime) }),
        )
    }

    /**
     * A scored self-check as an instrument. The axis is numeric because the series carries per-point
     * band labels rather than band thresholds — inventing gridline names for someone else's bands
     * would be the renderer making a claim the model does not.
     */
    fun scoreInstrument(series: ReportInstrumentSeries, start: LocalDate, days: Int): Instrument {
        val points = series.points.map { PlotPoint(it.date, it.score.toDouble()) }
        val lo = series.rangeMin.toDouble()
        val hi = series.rangeMax.toDouble().takeIf { it > lo } ?: (lo + 1.0)
        val mid = ((series.rangeMin + series.rangeMax) / 2)
        return Instrument(
            title = series.title,
            tier = series.provenanceTier,
            direction = series.directionInWords,
            scaleMin = lo,
            scaleMax = hi,
            scaleLabel = "${series.rangeMin}–${series.rangeMax}",
            axisLabels = listOf(
                hi to series.rangeMax.toString(),
                mid.toDouble() to mid.toString(),
                lo to series.rangeMin.toString(),
            ),
            points = points,
            band = usualRange(points),
            useMoodColours = false,
            ownBandWording = series.ownBandWording,
            weeklyCounts = weeklyCounts(start, days, series.points.map { it.date }),
        )
    }
}

/**
 * Raw score against the instrument's own scale.
 *
 * Deliberately raw, including for WHO-5, which [Assessments.displayScore] otherwise reports out of
 * 100. The denominator is printed alongside every score, so "14 / 25" is unambiguous, and keeping
 * one unit means the number in the table is the same number as the dot on the plot.
 */
private fun scoreText(series: ReportInstrumentSeries, score: Int) = "$score / ${series.rangeMax}"

// ---------------------------------------------------------------------------------------------
// Drawing.
// ---------------------------------------------------------------------------------------------

private class PageCtx(
    private val doc: PdfDocument,
    private val pageW: Float,
    private val pageH: Float,
    private val data: ReportData,
) {
    private val margin = 42f
    private val contentW = pageW - 2 * margin
    private val footerSpace = 46f

    private var pageNum = 0
    private lateinit var page: PdfDocument.Page
    private lateinit var canvas: Canvas
    private var y = 0f

    private val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    /** The report range, shared by every plot so the x-axes line up. */
    private val rangeStart: LocalDate =
        data.trend.firstOrNull()?.date ?: DateUtils.toLocalDate(data.generatedAtMillis)
    private val rangeEnd: LocalDate =
        data.trend.lastOrNull()?.date ?: DateUtils.toLocalDate(data.generatedAtMillis)
    private val rangeDays: Int = data.trend.size.coerceAtLeast(1)

    private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) sansBold else sans
    }

    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HAIR; strokeWidth = 0.8f; style = Paint.Style.STROKE
    }

    fun start() {
        pageNum++
        val info = PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), pageNum).create()
        page = doc.startPage(info)
        canvas = page.canvas
        y = margin
    }

    fun finish() {
        drawFooter()
        doc.finishPage(page)
    }

    private fun newPage() {
        drawFooter()
        doc.finishPage(page)
        start()
    }

    /** Breaks to a new page when [space] will not fit. Returns true if it did. */
    private fun ensure(space: Float): Boolean {
        if (y + space > pageH - footerSpace) {
            newPage()
            return true
        }
        return false
    }

    /** Places the next block flush against the bottom of the page, breaking first if it will not fit. */
    private fun anchorBottom(blockH: Float) {
        if (y + blockH > pageH - footerSpace) newPage()
        y = (pageH - footerSpace - blockH).coerceAtLeast(margin)
    }

    private fun drawFooter() {
        val p = paint(7.5f, FAINT)
        canvas.drawLine(margin, pageH - footerSpace + 6, pageW - margin, pageH - footerSpace + 6, hairline)
        canvas.drawText(Copy.FOOTER, margin, pageH - 22f, p)
        val right = "page $pageNum · SHA-256 ${data.sha256Hex.take(12)}…"
        canvas.drawText(right, pageW - margin - p.measureText(right), pageH - 22f, p)
    }

    // ---- shared primitives ----

    private fun sectionLabel(text: String) {
        ensure(20f)
        canvas.drawText(text.uppercase(), margin, y + 8f, paint(8f, FAINT, bold = true).apply { letterSpacing = 0.08f })
        y += 18f
    }

    private fun lineHeight(p: Paint) = p.textSize * 1.35f

    /** Flowing body copy. Breaks across pages a line at a time. */
    private fun paragraph(text: String, p: Paint, x: Float = margin, width: Float = contentW) {
        if (text.isBlank()) return
        val lh = lineHeight(p)
        wrap(text, p, width).forEach { ln ->
            ensure(lh)
            canvas.drawText(ln, x, y + p.textSize * 0.85f, p)
            y += lh
        }
    }

    /**
     * A hairline-bordered block of fixed copy. Sized up front so the border can never be split
     * across a page break.
     */
    private fun noteBox(blocks: List<Pair<Paint, String>>) {
        val innerW = contentW - 20f
        val wrapped = blocks.map { (p, t) -> p to wrap(t, p, innerW) }
        var h = 16f
        wrapped.forEach { (p, ls) -> h += ls.size * lineHeight(p) + 3f }
        ensure(h + 12f)
        val top = y
        y += 8f
        wrapped.forEach { (p, ls) ->
            ls.forEach { ln ->
                canvas.drawText(ln, margin + 10f, y + p.textSize * 0.85f, p)
                y += lineHeight(p)
            }
            y += 3f
        }
        y += 8f
        canvas.drawRoundRect(margin, top, pageW - margin, y, 3f, 3f, hairline)
        y += 12f
    }

    /**
     * Starts a side. Each side gets its own sheet face; side 1 reuses the page [start] already
     * opened rather than wasting a blank one.
     */
    private fun sideHeader(index: Int, of: Int, job: String, title: String, subtitle: String, meta: List<String>) {
        if (y > margin) newPage()
        canvas.drawText(
            "SIDE $index OF $of · ${job.uppercase()}",
            margin, y + 8f,
            paint(7.5f, FAINT, bold = true).apply { letterSpacing = 0.1f },
        )
        y += 16f
        canvas.drawText(title, margin, y + 14f, paint(17f, INK, bold = true))
        val mp = paint(8f, FAINT)
        meta.forEachIndexed { i, line ->
            canvas.drawText(line, pageW - margin - mp.measureText(line), y + 4f + i * 11f, mp)
        }
        y += 22f
        if (subtitle.isNotBlank()) {
            canvas.drawText(subtitle, margin, y + 8f, paint(9f, SOFT))
            y += 14f
        }
        y += 2f
        canvas.drawLine(margin, y, pageW - margin, y, hairline)
        y += 16f
    }

    /** Column header row for a table, redrawn whenever a table spills onto a new page. */
    private fun tableHead(vararg cols: Pair<Float, String>) {
        val hp = paint(7f, FAINT, bold = true).apply { letterSpacing = 0.06f }
        cols.forEach { (x, label) -> canvas.drawText(label, x, y + 7f, hp) }
        y += 11f
        canvas.drawLine(margin, y, pageW - margin, y, hairline)
        y += 6f
    }

    /** Every instrument in this report, daily mood first, then each scored self-check. */
    private fun instruments(): List<Instrument> =
        listOf(Plot.moodInstrument(data, rangeStart, rangeDays)) +
            data.instrumentSeries.map { Plot.scoreInstrument(it, rangeStart, rangeDays) }

    // ---- side 1 · the glance ----

    fun sideOneGlance(data: ReportData, options: PdfExportOptions, of: Int) {
        val checkIns = "${data.totalEntries} check-in${if (data.totalEntries == 1) "" else "s"}"
        sideHeader(
            index = 1,
            of = of,
            job = "the glance",
            title = "Between-session summary",
            subtitle = data.patientName,
            meta = listOf(
                data.rangeLabel,
                "$checkIns · ${data.daysLogged} of ${data.daysInRange} days logged",
                "Prepared ${DateUtils.formatDate(data.generatedAtMillis)}",
            ),
        )

        // What this is — before any number, so nobody reads a score without knowing what made it.
        noteBox(
            listOf(
                paint(8.5f, INK, bold = true) to Copy.DECLARE_TITLE,
                paint(8.5f, SOFT) to Copy.DECLARE_BODY,
            ),
        )

        val instruments = instruments()

        // ---- the flag zone. One flag, alone, and it never shares this band of the page. ----
        flagZone(Plot.oneFlag(instruments), instruments)
        y += 6f
        // ---- end of the flag zone. Nothing above this line competes with it. ----

        if (options.includeCharts) {
            sectionLabel("Shared self-checks")
            instruments.forEach { instrumentPlot(it) }
        }

        completionSummary(data)
    }

    /**
     * The one flag, or a stated absence. Both are findings; an empty zone would read as an oversight,
     * which is exactly the failure mode this page is arranged to avoid.
     */
    private fun flagZone(flag: Plot.Flag?, instruments: List<Instrument>) {
        if (flag != null) {
            val dir = if (flag.below) "below" else "above"
            noteBox(
                listOf(
                    paint(8f, FAINT, bold = true) to Copy.FLAG_LABEL.uppercase(),
                    paint(10f, INK, bold = true) to
                        "${flag.instrument} has moved $dir this person's own usual range.",
                    paint(8.5f, SOFT) to
                        "${flag.outside} of the last ${flag.window} results sit $dir the usual " +
                        "range for this period — the band drawn on the plot below.",
                    paint(8f, FAINT) to Copy.FLAG_CAVEAT,
                ),
            )
            return
        }
        val thin = instruments.all { it.band == null }
        noteBox(
            listOf(
                paint(8f, FAINT, bold = true) to Copy.NO_FLAG_LABEL.uppercase(),
                paint(9.5f, INK) to (if (thin) Copy.NO_FLAG_THIN else Copy.NO_FLAG_BODY),
                paint(8f, FAINT) to Copy.NO_FLAG_CAVEAT,
            ),
        )
    }

    /**
     * One instrument: scatter over a usual-range band, plus a sampling density strip.
     *
     * Deliberately **not** a connected line. Short self-report series are dominated by noise, and a
     * segment drawn between two check-ins asserts values for the days in between that nobody logged.
     * Irregular sampling therefore shows as irregular spacing — points sit at their real date.
     */
    private fun instrumentPlot(inst: Instrument) {
        val plotH = 84f
        val densityH = 8f
        ensure(14f + 12f + plotH + 14f + densityH + 14f + 10f)

        val np = paint(10f, INK, bold = true)
        canvas.drawText(inst.title, margin, y + 8f, np)
        val tp = paint(7.5f, FAINT)
        canvas.drawText(inst.tier.label, margin + np.measureText(inst.title) + 8f, y + 8f, tp)
        val meta = "${inst.points.size} entries · ${inst.scaleLabel}"
        canvas.drawText(meta, pageW - margin - tp.measureText(meta), y + 8f, tp)
        y += 14f
        if (inst.direction.isNotBlank()) {
            canvas.drawText(inst.direction, margin, y + 7f, paint(8f, SOFT))
            y += 12f
        }

        val top = y
        val axisW = 52f
        val left = margin + axisW
        val right = pageW - margin
        val plotW = right - left
        val span = (inst.scaleMax - inst.scaleMin).takeIf { it > 0.0 } ?: 1.0
        fun yFor(v: Double): Float = top + plotH - (((v - inst.scaleMin) / span).toFloat() * plotH)

        // The usual-range band first, so the observations sit on top of it. A range with no spread
        // (every value the same) draws at a 2pt floor purely so it is visible — 2pt of an 84pt plot
        // is well under a tenth of a scale step, so it does not widen what the band claims.
        inst.band?.let { (lo, hi) ->
            val mid = (yFor(hi) + yFor(lo)) / 2f
            val half = maxOf((yFor(lo) - yFor(hi)) / 2f, 1f)
            val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAND }
            canvas.drawRect(left, mid - half, right, mid + half, bandPaint)
            val bl = paint(6.5f, FAINT)
            canvas.drawText(Copy.USUAL_RANGE, right - bl.measureText(Copy.USUAL_RANGE) - 2f, mid - half - 2.5f, bl)
        }

        // Gridlines, labelled with whatever the instrument calls those points on its scale.
        val al = paint(6.5f, FAINT)
        inst.axisLabels.forEach { (value, label) ->
            val gy = yFor(value)
            canvas.drawLine(left, gy, right, gy, hairline)
            canvas.drawText(label.uppercase(), margin, gy + 2.5f, al)
        }

        if (inst.points.isEmpty()) {
            canvas.drawText(Copy.NO_RESULTS, left + 6f, top + plotH / 2f, paint(8f, FAINT))
        } else {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
            val lastDay = (rangeDays - 1).coerceAtLeast(1)
            inst.points.forEach { pt ->
                val off = ChronoUnit.DAYS.between(rangeStart, pt.date).toFloat()
                val x = left + (off / lastDay).coerceIn(0f, 1f) * plotW
                // Mood ramp only where the colour is the logged value; plain ink everywhere else.
                dot.color = if (inst.useMoodColours && pt.moodLevel != null) moodColor(pt.moodLevel) else INK
                canvas.drawCircle(x, yFor(pt.value), 2.6f, dot)
            }
        }

        y = top + plotH + 4f
        val ax = paint(6.5f, FAINT)
        canvas.drawText(rangeStart.format(DAY_MONTH).uppercase(), left, y + 6f, ax)
        val endLabel = rangeEnd.format(DAY_MONTH).uppercase()
        canvas.drawText(endLabel, right - ax.measureText(endLabel), y + 6f, ax)
        y += 12f

        // Sampling density — how much of the period each point actually stands for.
        val weeks = inst.weeklyCounts
        if (weeks.isNotEmpty()) {
            val maxCount = (weeks.maxOrNull() ?: 0).coerceAtLeast(1)
            val cw = plotW / weeks.size
            val cell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
            weeks.forEachIndexed { i, count ->
                cell.alpha = if (count == 0) 16 else (40 + 175 * count / maxCount).coerceAtMost(215)
                canvas.drawRect(left + i * cw + 0.6f, y, left + (i + 1) * cw - 0.6f, y + densityH, cell)
            }
            y += densityH + 3f
            val dl = paint(6.5f, FAINT)
            canvas.drawText(Copy.DENSITY_LEFT, left, y + 5f, dl)
            canvas.drawText(Copy.DENSITY_RIGHT, right - dl.measureText(Copy.DENSITY_RIGHT), y + 5f, dl)
            y += 12f
        }

        // The person's own words for a band, printed because on a self-authored tool the label
        // means exactly what they wrote it to mean.
        if (inst.ownBandWording.isNotEmpty()) {
            val wp = paint(8f, SOFT)
            inst.ownBandWording.forEach { w ->
                paragraph("Their words for “${w.bandLabel}”: ${w.inTheirWords}", wp)
            }
            y += 4f
        }
        y += 6f
    }

    /** What ran and what came back. No streaks, and no percentage of anything completed. */
    private fun completionSummary(data: ReportData) {
        ensure(52f)
        sectionLabel("What ran, and what came back")
        val results = data.instrumentSeries.sumOf { it.points.size }
        val cells = listOf(
            "Check-ins" to data.totalEntries.toString(),
            "Days with an entry" to "${data.daysLogged}/${data.daysInRange}",
            "Self-check results" to results.toString(),
            "Average of what was logged" to (data.averageMood?.let { fmt1(it) } ?: "–"),
        )
        val cw = contentW / cells.size
        cells.forEachIndexed { i, (label, value) ->
            val x = margin + i * cw
            canvas.drawText(value, x, y + 16f, paint(18f, INK, bold = true))
            canvas.drawText(label.uppercase(), x, y + 30f, paint(6.5f, FAINT))
        }
        y += 42f
        distribution(data)
    }

    private fun distribution(data: ReportData) {
        ensure(20f + 5 * 16f)
        sectionLabel("What came back, by level")
        val max = (data.moodCounts.values.maxOrNull() ?: 1).coerceAtLeast(1)
        val barLeft = margin + 60f
        val barMax = pageW - margin - 40f - barLeft
        for (lvl in 5 downTo 1) {
            val count = data.moodCounts[lvl] ?: 0
            canvas.drawText(Mood.fromLevel(lvl).label, margin, y + 9f, paint(8.5f, SOFT))
            val bw = (barMax * count / max).coerceAtLeast(if (count > 0) 2f else 0f)
            val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = moodColor(lvl) }
            canvas.drawRoundRect(barLeft, y, barLeft + bw, y + 10f, 3f, 3f, bar)
            canvas.drawText(count.toString(), pageW - margin - 24f, y + 9f, paint(8.5f, SOFT))
            y += 16f
        }
        y += 8f
    }

    // ---- side 2 · the detail ----

    fun sideTwoDetail(data: ReportData, of: Int) {
        sideHeader(
            index = 2,
            of = of,
            job = "the detail",
            title = "Detail",
            subtitle = "Every entry behind the plots on side 1",
            meta = if (data.patientName.isBlank()) {
                listOf(data.rangeLabel)
            } else {
                listOf(data.patientName, data.rangeLabel)
            },
        )

        if (data.instrumentSeries.isNotEmpty()) resultsTable(data)
        entriesTable(data)
        noteBox(listOf(paint(8f, SOFT) to Copy.NOTE_FIELD))

        suggestions(data)
        projects(data)
        if (data.assignmentOutcomes.isEmpty() && data.projects.isEmpty()) {
            noteBox(listOf(paint(8f, FAINT) to Copy.NOT_RECORDED_YET))
        }

        if (data.activityStats.isNotEmpty()) activityTable(data)

        coverage(data)
    }

    /** Every self-check result behind the plots, newest first. */
    private fun resultsTable(data: ReportData) {
        val rows = data.instrumentSeries
            .flatMap { s -> s.points.map { s to it } }
            .sortedByDescending { it.second.date }
        sectionLabel("Self-check results (${rows.size})")

        val colDate = margin
        val colTool = margin + 66f
        val colScore = margin + 196f
        val colBand = margin + 248f
        val colNote = margin + 330f
        val noteW = pageW - margin - colNote
        val head = {
            tableHead(
                colDate to "DATE", colTool to "TOOL", colScore to "SCORE",
                colBand to "BAND", colNote to "THEIR NOTE",
            )
        }
        ensure(28f)
        head()

        val body = paint(8.5f, INK)
        val soft = paint(8f, SOFT)
        rows.forEach { (series, point) ->
            val noteLines = if (point.note.isNotBlank()) wrap(point.note, body, noteW) else listOf("—")
            val rowH = (noteLines.size * 11f + 7f).coerceAtLeast(17f)
            if (ensure(rowH)) head()
            canvas.drawText(point.date.format(DAY_MONTH), colDate, y + 8f, body)
            canvas.drawText(series.title, colTool, y + 8f, body)
            canvas.drawText(scoreText(series, point.score), colScore, y + 8f, body)
            canvas.drawText(point.bandLabel.ifBlank { "—" }, colBand, y + 8f, soft)
            var ny = y
            noteLines.forEach { ln ->
                canvas.drawText(ln, colNote, ny + 8f, body)
                ny += 11f
            }
            y += rowH
            canvas.drawLine(margin, y - 3f, pageW - margin, y - 3f, hairline)
        }
        y += 10f
    }

    /** Every daily check-in, newest first — a clinician scanning recent state reads that way. */
    private fun entriesTable(data: ReportData) {
        sectionLabel("Daily check-ins (${data.entries.size})")
        val colDate = margin
        val colTime = margin + 82f
        val colScore = margin + 130f
        val colBand = margin + 168f
        val colNote = margin + 236f
        val noteW = pageW - margin - colNote
        val head = {
            tableHead(
                colDate to "DATE", colTime to "TIME", colScore to "SCORE",
                colBand to "BAND", colNote to "THEIR NOTE",
            )
        }

        ensure(28f)
        head()

        if (data.entries.isEmpty()) {
            canvas.drawText(Copy.NO_ENTRIES, margin, y + 8f, paint(8.5f, FAINT))
            y += 18f
            return
        }

        val body = paint(8.5f, INK)
        val soft = paint(8f, SOFT)
        data.entries.sortedByDescending { it.dateTime }.forEach { e ->
            val noteLines = if (e.note.isNotBlank()) wrap(e.note, body, noteW) else listOf("—")
            val actLines: List<String> = if (e.activityNames.isNotEmpty()) {
                wrap(e.activityNames.joinToString(" · "), soft, noteW)
            } else {
                emptyList()
            }
            val rowH = (noteLines.size * 11f + actLines.size * 10f + 7f).coerceAtLeast(18f)
            if (ensure(rowH)) head()

            canvas.drawText(DateUtils.formatDate(e.dateTime), colDate, y + 8f, body)
            canvas.drawText(DateUtils.formatTime(e.dateTime), colTime, y + 8f, soft)
            canvas.drawText("${e.moodLevel} / 5", colScore, y + 8f, body)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = moodColor(e.moodLevel) }
            canvas.drawCircle(colBand + 3.5f, y + 5f, 3.5f, dot)
            canvas.drawText(Mood.fromLevel(e.moodLevel).label, colBand + 11f, y + 8f, soft)

            var ny = y
            noteLines.forEach { ln ->
                canvas.drawText(ln, colNote, ny + 8f, body)
                ny += 11f
            }
            actLines.forEach { ln ->
                canvas.drawText(ln, colNote, ny + 7f, soft)
                ny += 10f
            }

            y += rowH
            canvas.drawLine(margin, y - 3f, pageW - margin, y - 3f, hairline)
        }
        y += 8f
    }

    /**
     * What a clinician published, and what became of it.
     *
     * Declines and silences are printed because the person chose to include them in their own
     * export; D5's rule that a decline stays invisible governs the live portal, not a document
     * someone decided to hand over. The outcome column is mechanical and ranks nothing.
     */
    private fun suggestions(data: ReportData) {
        // Guard BEFORE the heading. On a side whose stated job is that an absence reads as a
        // finding rather than an oversight, a heading with nothing under it is the ambiguous case
        // it exists to avoid.
        if (data.assignmentOutcomes.isEmpty()) return
        sectionLabel("What was suggested, and what happened")

        val colWhen = margin
        val colItem = margin + 76f
        val colCadence = margin + 290f
        val colOutcome = margin + 386f
        val head = {
            tableHead(
                colWhen to "PUBLISHED", colItem to "ITEM",
                colCadence to "CADENCE", colOutcome to "OUTCOME",
            )
        }
        ensure(28f)
        head()

        val body = paint(8.5f, INK)
        val soft = paint(8f, SOFT)
        data.assignmentOutcomes.sortedByDescending { it.publishedAtMillis }.forEach { a ->
            val itemLines = wrap(a.item, body, colCadence - colItem - 8f)
            val rowH = (itemLines.size * 11f + 7f).coerceAtLeast(17f)
            if (ensure(rowH)) head()
            canvas.drawText(DateUtils.formatDate(a.publishedAtMillis), colWhen, y + 8f, soft)
            var iy = y
            itemLines.forEach { ln ->
                canvas.drawText(ln, colItem, iy + 8f, body)
                iy += 11f
            }
            canvas.drawText(a.cadence, colCadence, y + 8f, soft)
            canvas.drawText(a.outcome, colOutcome, y + 8f, body)
            y += rowH
            canvas.drawLine(margin, y - 3f, pageW - margin, y - 3f, hairline)
        }
        y += 8f
        noteBox(listOf(paint(8f, SOFT) to Copy.DECLINES_SHOWN))
    }

    /**
     * Goals and projects, listed rather than scored. No percentage, no ring, no velocity: completion
     * does not predict improvement, and a project someone set down is not a failure.
     */
    private fun projects(data: ReportData) {
        if (data.projects.isEmpty()) return
        sectionLabel("Goals and projects")

        val title = paint(9.5f, INK, bold = true)
        val soft = paint(8f, SOFT)
        val step = paint(8.5f, INK)
        data.projects.forEach { p ->
            ensure(26f)
            canvas.drawText(p.title, margin, y + 8f, title)
            if (p.stateLabel.isNotBlank()) {
                canvas.drawText(p.stateLabel, pageW - margin - soft.measureText(p.stateLabel), y + 8f, soft)
            }
            y += 13f
            // The intention attaches to the next concrete action, not to the project (D5).
            if (p.cue.isNotBlank() || p.routine.isNotBlank()) {
                paragraph(listOf(p.cue, p.routine).filter { it.isNotBlank() }.joinToString(" → "), soft)
            }
            p.steps.forEach { s ->
                ensure(12f)
                canvas.drawText(if (s.done) "✓" else "·", margin + 8f, y + 8f, soft)
                canvas.drawText(s.title, margin + 22f, y + 8f, step)
                y += 12f
            }
            y += 8f
            canvas.drawLine(margin, y - 4f, pageW - margin, y - 4f, hairline)
        }
        y += 6f
    }

    private fun activityTable(data: ReportData) {
        sectionLabel("Average mood by activity")
        val body = paint(8.5f, INK)
        val soft = paint(8.5f, SOFT)
        data.activityStats.take(12).forEach { s ->
            ensure(14f)
            canvas.drawText("${s.name}  (${s.count})", margin, y + 9f, body)
            val avg = fmt1(s.averageMood)
            canvas.drawText(avg, pageW - margin - soft.measureText(avg), y + 9f, soft)
            y += 14f
        }
        y += 8f
    }

    /** Coverage, stated as coverage. Nothing here is filled in, smoothed, or averaged over a gap. */
    private fun coverage(data: ReportData) {
        sectionLabel("Coverage")
        paragraph(
            "${data.daysLogged} of ${data.daysInRange} days have an entry. ${Copy.COVERAGE_FIXED}",
            paint(9f, SOFT),
        )
        y += 4f
        // The model carries every gap; the page names the ones long enough to be worth a line.
        val named = data.coverageGaps.filter { it.days >= Plot.GAP_MIN_DAYS }
        if (named.isEmpty()) {
            paragraph(
                "No stretch of ${Plot.GAP_MIN_DAYS} or more days is entirely without an entry.",
                paint(9f, SOFT),
            )
        } else {
            val word = if (named.size == 1) "stretch" else "stretches"
            paragraph(
                "${named.size} $word of ${Plot.GAP_MIN_DAYS} or more days with nothing:",
                paint(9f, SOFT),
            )
            val gp = paint(8.5f, INK)
            named.forEach { g ->
                ensure(12f)
                canvas.drawText(
                    "${g.from.format(DAY_MONTH)} – ${g.to.format(DAY_MONTH)}  (${g.days} days)",
                    margin + 10f, y + 8f, gp,
                )
                y += 12f
            }
        }
        y += 8f
    }

    // ---- side 3 · in their words ----

    /**
     * Only reached when the person opted in and ticked entries. They print whole, in full — never
     * excerpted, never summarised, and never selected by the software.
     */
    fun sideThreeInTheirWords(data: ReportData, of: Int) {
        val n = data.journal.size
        val available = maxOf(data.journalEntriesAvailable, n)
        sideHeader(
            index = 3,
            of = of,
            job = "in their words",
            title = "In their words",
            subtitle = "Included by them · $n of $available ${if (available == 1) "entry" else "entries"}",
            meta = listOf("Switched on for this export", "Off by default"),
        )

        noteBox(listOf(paint(8.5f, SOFT) to Copy.JOURNAL_OPT_IN))

        val titleP = paint(10f, INK, bold = true)
        val metaP = paint(7.5f, FAINT)
        val bodyP = paint(9f, INK)
        data.journal.sortedByDescending { it.dateTime }.forEach { j ->
            ensure(34f)
            canvas.drawText(j.title.ifBlank { "Untitled" }, margin, y + 9f, titleP)
            y += 14f
            canvas.drawText(
                "${DateUtils.formatDate(j.dateTime)} · ${DateUtils.formatTime(j.dateTime)}",
                margin, y + 6f, metaP,
            )
            y += 13f
            paragraph(j.body, bodyP)
            y += 10f
            canvas.drawLine(margin, y, pageW - margin, y, hairline)
            y += 10f
        }

        // The count of what was withheld is the whole of what this page may say about it.
        val withheld = available - n
        if (withheld > 0) {
            val lead = if (withheld == 1) {
                "The other entry is not here and is not summarised."
            } else {
                "The other $withheld entries are not here and are not summarised."
            }
            noteBox(listOf(paint(8f, FAINT) to "$lead ${Copy.JOURNAL_ABSENT_TAIL}"))
        }
    }

    // ---- side 4 · for the conversation ----

    fun sideFourForTheConversation(data: ReportData, options: PdfExportOptions, of: Int) {
        sideHeader(
            index = of,
            of = of,
            job = "for the conversation",
            title = "For the conversation",
            subtitle = "Prompts, provenance, and how to check this file",
            meta = listOf(
                "Generated ${DateUtils.formatDate(data.generatedAtMillis)} " +
                    DateUtils.formatTime(data.generatedAtMillis),
                "daymark-verify:v1",
            ),
        )

        discussionPrompts(data, options)
        provenance(data, options)
        noteBox(listOf(paint(8f, FAINT) to Copy.NOT_IN_REPORT))
        verification(data)
    }

    /**
     * Prompts exactly as [com.daymark.app.stats.DiscussionPrompts] wrote them. The renderer does not
     * assemble, shorten or reorder them: a prompt split into fragments is a prompt that can be
     * reassembled into something nobody reviewed.
     */
    private fun discussionPrompts(data: ReportData, options: PdfExportOptions) {
        sectionLabel("Worth asking about")
        val prompts = if (options.includeDiscussionPrompts) data.discussionPrompts else emptyList()

        if (prompts.isEmpty()) {
            paragraph(
                if (options.includeDiscussionPrompts) Copy.PROMPTS_NONE else Copy.PROMPTS_OFF,
                paint(9f, SOFT),
            )
            y += 6f
        } else {
            val p = paint(9.5f, INK)
            val bullet = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FAINT }
            prompts.forEach { text ->
                ensure(lineHeight(p) + 6f)
                canvas.drawCircle(margin + 2.5f, y + 5f, 1.6f, bullet)
                paragraph(text, p, x = margin + 12f, width = contentW - 12f)
                y += 5f
            }
        }
        noteBox(listOf(paint(8f, FAINT) to Copy.PROMPTS_CAVEAT))
    }

    /** What every tool in this report is, tool by tool, before anyone acts on a number from it. */
    private fun provenance(data: ReportData, options: PdfExportOptions) {
        sectionLabel("The tools in this report")

        val rows = ArrayList<Triple<String, ProvenanceTier, String>>()
        rows.add(
            Triple(
                "Daily mood check-in",
                ProvenanceTier.ORIGINAL,
                "Self-authored 5-level scale. Not derived from any published instrument; no " +
                    "population norms and no clinical cut-off.",
            ),
        )
        data.instrumentSeries.forEach { s ->
            // A validated tool's source is its own citation, not a sentence written here.
            val citation = Assessments.byKey(s.id)?.citation.orEmpty()
            rows.add(
                Triple(
                    s.title,
                    s.provenanceTier,
                    citation.ifBlank {
                        "Self-authored. Not a validated instrument; no population norms and no " +
                            "clinical cut-off."
                    },
                ),
            )
        }
        if (options.includeNotes) {
            rows.add(
                Triple(
                    "Check-in note",
                    ProvenanceTier.ORIGINAL,
                    "The one-line note attached to a check-in. Reproduced verbatim, never excerpted " +
                        "into a chart or a summary.",
                ),
            )
        }
        if (data.activityStats.isNotEmpty()) {
            rows.add(
                Triple(
                    "Activity tags",
                    ProvenanceTier.ORIGINAL,
                    "Labels the person chose for themselves. Counted and averaged; no figure here is " +
                        "a causal claim.",
                ),
            )
        }
        if (data.journal.isNotEmpty()) {
            rows.add(
                Triple(
                    "Journal",
                    ProvenanceTier.ORIGINAL,
                    "Long-form writing. Unscored, in no chart, and included only entry by entry by " +
                        "the person.",
                ),
            )
        }

        val colTier = margin + 150f
        val colSource = margin + 210f
        val sourceW = pageW - margin - colSource
        ensure(24f)
        tableHead(margin to "TOOL", colTier to "TIER", colSource to "SOURCE")

        val name = paint(8.5f, INK)
        val tier = paint(8.5f, SOFT)
        val src = paint(8f, SOFT)
        rows.forEach { (tool, tierValue, source) ->
            val lines = wrap(source, src, sourceW)
            val rowH = (lines.size * 10.5f + 7f).coerceAtLeast(17f)
            ensure(rowH)
            canvas.drawText(tool, margin, y + 8f, name)
            canvas.drawText(tierValue.label, colTier, y + 8f, tier)
            var sy = y
            lines.forEach { ln ->
                canvas.drawText(ln, colSource, sy + 8f, src)
                sy += 10.5f
            }
            y += rowH
            canvas.drawLine(margin, y - 3f, pageW - margin, y - 3f, hairline)
        }
        y += 8f

        // The all-Custom sentence is only printed when it is true of every row.
        val allCustom = rows.all { it.second == ProvenanceTier.ORIGINAL }
        noteBox(
            listOf(
                paint(8f, SOFT) to (if (allCustom) Copy.PROVENANCE_ALL_CUSTOM else Copy.PROVENANCE_MIXED),
                paint(8f, FAINT) to Copy.CUSTOM_DISCLAIMER,
            ),
        )
    }

    /**
     * Verification, anchored to the bottom of the last side. It is the least urgent thing on the
     * page and the last thing anyone needs, so it sits where a footer would — but it stays whole,
     * with the QR and the full hash together.
     */
    private fun verification(data: ReportData) {
        val qrSize = 90f
        val textP = paint(8f, FAINT)
        val hashP = paint(7.5f, SOFT)
        val textLeft = margin + qrSize + 14f
        val textW = pageW - margin - textLeft
        val noteLines = wrap(Copy.AUTHENTICITY, textP, textW)
        val textH = 16f + noteLines.size * 11f
        // 10 for the rule, 18 for the label, then the taller of the QR and the text, plus clearance.
        val blockH = 28f + maxOf(qrSize, textH) + 8f

        anchorBottom(blockH)
        canvas.drawLine(margin, y, pageW - margin, y, hairline)
        y += 10f
        sectionLabel("Verify this file")

        val top = y
        // The QR payload and encoder are unchanged; only where it sits on the page has moved.
        val payload = "daymark-verify:v1;range=${data.rangeLabel};sha256=${data.sha256Hex}"
        QrEncoder.draw(
            canvas, payload, margin, top, qrSize,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK },
        )

        var ty = top
        canvas.drawText("SHA-256: ${data.sha256Hex}", textLeft, ty + 8f, hashP)
        ty += 16f
        noteLines.forEach { ln ->
            canvas.drawText(ln, textLeft, ty + 8f, textP)
            ty += 11f
        }
        y = maxOf(top + qrSize, ty)
    }

    // ---- text ----

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        text.split("\n").forEach { para ->
            var line = StringBuilder()
            para.split(" ").forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                    out.add(line.toString()); line = StringBuilder(word)
                } else {
                    line = StringBuilder(candidate)
                }
            }
            out.add(line.toString())
        }
        return out
    }
}
