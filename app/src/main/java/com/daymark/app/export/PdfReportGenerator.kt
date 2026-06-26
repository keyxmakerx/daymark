package com.daymark.app.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.daymark.app.model.Mood
import com.daymark.app.util.DateUtils
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a [ReportData] into a clean, clinician-facing PDF using the platform
 * [PdfDocument] + [Canvas] (no third-party PDF dependency; text stays selectable/vector).
 */
@Singleton
class PdfReportGenerator @Inject constructor() {

    fun generate(data: ReportData, options: PdfExportOptions, out: OutputStream) {
        val doc = PdfDocument()
        val w = options.paperSize.widthPt
        val h = options.paperSize.heightPt
        val ctx = PageCtx(doc, w.toFloat(), h.toFloat(), data)
        ctx.start()

        ctx.coverHeader(data, options)
        ctx.summaryStrip(data)
        if (options.includeCharts) {
            ctx.trendChart(data)
            ctx.distribution(data)
        }
        if (data.activityStats.isNotEmpty()) ctx.activityTable(data)
        ctx.entriesSection(data, options)
        if (options.includeJournal && data.journal.isNotEmpty()) ctx.journalSection(data)
        ctx.authenticityBlock(data)

        ctx.finish()
        doc.writeTo(out)
        doc.close()
    }
}

private const val INK = 0xFF2A2722.toInt()
private const val SOFT = 0xFF6B655B.toInt()
private const val FAINT = 0xFFA49C8E.toInt()
private const val HAIR = 0xFFE7DFD1.toInt()

private val MOOD_ARGB = intArrayOf(
    0xFFAE5747.toInt(), 0xFFC27C46.toInt(), 0xFFC6A24E.toInt(), 0xFF8FA268.toInt(), 0xFF5E8A66.toInt(),
)

private fun moodColor(level: Int) = MOOD_ARGB[(level - 1).coerceIn(0, 4)]

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

    private fun ensure(space: Float) {
        if (y + space > pageH - footerSpace) newPage()
    }

    private fun drawFooter() {
        val p = paint(7.5f, FAINT)
        canvas.drawLine(margin, pageH - footerSpace + 6, pageW - margin, pageH - footerSpace + 6, hairline)
        canvas.drawText(
            "Daymark · self-reported data, not a clinical assessment",
            margin, pageH - 22f, p,
        )
        val right = "page $pageNum · SHA-256 ${data.sha256Hex.take(12)}…"
        canvas.drawText(right, pageW - margin - p.measureText(right), pageH - 22f, p)
    }

    // ---- sections ----

    fun coverHeader(data: ReportData, options: PdfExportOptions) {
        canvas.drawText("Mood & Journal Report", margin, y + 22f, paint(22f, INK, bold = true))
        y += 34f
        if (data.patientName.isNotBlank()) {
            canvas.drawText(data.patientName, margin, y, paint(12f, INK)); y += 16f
        }
        canvas.drawText(data.rangeLabel, margin, y, paint(11f, SOFT)); y += 14f
        canvas.drawText(
            "Generated ${DateUtils.formatDate(data.generatedAtMillis)} ${DateUtils.formatTime(data.generatedAtMillis)} · Daymark v0.1.0 · offline mood tracker",
            margin, y, paint(8.5f, FAINT),
        )
        y += 12f
        canvas.drawLine(margin, y, pageW - margin, y, hairline)
        y += 18f
    }

    fun summaryStrip(data: ReportData) {
        ensure(54f)
        val cells = listOf(
            "Entries" to data.totalEntries.toString(),
            "Days logged" to "${data.daysLogged}/${data.daysInRange}",
            "Avg mood" to (data.averageMood?.let { String.format("%.1f", it) } ?: "–"),
            "Current streak" to "${data.currentStreak}d",
            "Longest streak" to "${data.longestStreak}d",
        )
        val cw = contentW / cells.size
        cells.forEachIndexed { i, (label, value) ->
            val x = margin + i * cw
            canvas.drawText(value, x, y + 16f, paint(18f, INK, bold = true))
            canvas.drawText(label.uppercase(), x, y + 30f, paint(7.5f, FAINT))
        }
        y += 48f
    }

    fun trendChart(data: ReportData) {
        val chartH = 110f
        ensure(chartH + 30f)
        sectionLabel("Mood over time")
        val top = y
        val left = margin + 14f
        val right = pageW - margin
        val chartW = right - left
        fun yFor(level: Double) = top + chartH - ((level - 1.0) / 4.0).toFloat() * chartH
        // gridlines + level labels
        for (lvl in 1..5) {
            val gy = yFor(lvl.toDouble())
            canvas.drawLine(left, gy, right, gy, hairline)
            canvas.drawText(lvl.toString(), margin, gy + 3f, paint(7f, FAINT))
        }
        val n = data.trend.size
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8FA268.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5E8A66.toInt() }
        var prevX = 0f; var prevY = 0f; var havePrev = false
        data.trend.forEachIndexed { i, pt ->
            val v = pt.value ?: run { havePrev = false; return@forEachIndexed }
            val x = left + if (n <= 1) 0f else i.toFloat() / (n - 1) * chartW
            val py = yFor(v)
            if (havePrev) canvas.drawLine(prevX, prevY, x, py, line)
            canvas.drawCircle(x, py, 2f, dot)
            prevX = x; prevY = py; havePrev = true
        }
        y = top + chartH + 6f
        if (data.trend.isNotEmpty()) {
            canvas.drawText(data.trend.first().date.toString(), left, y + 8f, paint(7f, FAINT))
            val lastLabel = data.trend.last().date.toString()
            val lp = paint(7f, FAINT)
            canvas.drawText(lastLabel, right - lp.measureText(lastLabel), y + 8f, lp)
        }
        y += 20f
    }

    fun distribution(data: ReportData) {
        ensure(20f + 5 * 16f)
        sectionLabel("Mood distribution")
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

    fun activityTable(data: ReportData) {
        ensure(20f + 14f)
        sectionLabel("Average mood by activity")
        data.activityStats.take(12).forEach { s ->
            ensure(14f)
            canvas.drawText("${s.name}  (${s.count})", margin, y + 9f, paint(8.5f, INK))
            val avg = String.format("%.1f", s.averageMood)
            val ap = paint(8.5f, SOFT)
            canvas.drawText(avg, pageW - margin - ap.measureText(avg), y + 9f, ap)
            y += 14f
        }
        y += 8f
    }

    fun entriesSection(data: ReportData, options: PdfExportOptions) {
        ensure(20f)
        sectionLabel("Entries (${data.entries.size})")
        // newest first reads better for a clinician scanning recent state
        data.entries.sortedByDescending { it.dateTime }.forEach { e ->
            val noteLines = if (e.note.isNotBlank()) wrap(e.note, paint(9f, INK), contentW - 16f) else emptyList()
            val blockH = 26f + noteLines.size * 12f + 10f
            ensure(blockH)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = moodColor(e.moodLevel) }
            canvas.drawCircle(margin + 4f, y + 5f, 4f, dot)
            val head = "${DateUtils.formatDate(e.dateTime)} · ${DateUtils.formatTime(e.dateTime)} · ${Mood.fromLevel(e.moodLevel).label}"
            canvas.drawText(head, margin + 14f, y + 9f, paint(9.5f, INK, bold = true))
            y += 16f
            if (e.activityNames.isNotEmpty()) {
                canvas.drawText(e.activityNames.joinToString(" · "), margin + 14f, y + 4f, paint(8.5f, SOFT))
                y += 12f
            }
            noteLines.forEach { ln ->
                canvas.drawText(ln, margin + 14f, y + 4f, paint(9f, INK))
                y += 12f
            }
            y += 6f
            canvas.drawLine(margin, y, pageW - margin, y, hairline)
            y += 8f
        }
    }

    fun journalSection(data: ReportData) {
        ensure(20f)
        sectionLabel("Journal")
        data.journal.sortedByDescending { it.dateTime }.forEach { j ->
            val bodyLines = wrap(j.body, paint(9f, INK), contentW)
            ensure(28f + bodyLines.size * 12f)
            canvas.drawText(j.title.ifBlank { "Untitled" }, margin, y + 9f, paint(10f, INK, bold = true))
            y += 14f
            canvas.drawText(
                "${DateUtils.formatDate(j.dateTime)} · ${DateUtils.formatTime(j.dateTime)}",
                margin, y + 3f, paint(7.5f, FAINT),
            )
            y += 13f
            bodyLines.forEach { ln -> canvas.drawText(ln, margin, y + 4f, paint(9f, INK)); y += 12f }
            y += 10f
        }
    }

    fun authenticityBlock(data: ReportData) {
        ensure(120f)
        canvas.drawLine(margin, y, pageW - margin, y, hairline)
        y += 14f
        sectionLabel("Authenticity")
        canvas.drawText("SHA-256: ${data.sha256Hex}", margin, y + 9f, paint(7.5f, SOFT))
        y += 16f
        val note = "This hash covers the report's underlying entries. It lets a recipient detect tampering; it does not verify that the self-reported data is accurate."
        wrap(note, paint(8f, FAINT), contentW - 110f).forEach { canvas.drawText(it, margin, y + 8f, paint(8f, FAINT)); y += 11f }
        // QR with a verification payload
        val payload = "daymark-verify:v1;range=${data.rangeLabel};sha256=${data.sha256Hex}"
        val qrSize = 90f
        QrEncoder.draw(canvas, payload, pageW - margin - qrSize, y - 40f, qrSize, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
    }

    private fun sectionLabel(text: String) {
        canvas.drawText(text.uppercase(), margin, y + 8f, paint(8f, FAINT, bold = true).apply { letterSpacing = 0.08f })
        y += 18f
    }

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
