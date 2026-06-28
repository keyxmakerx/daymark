package com.daymark.app.export

/** Paper sizes in PDF points (1/72 inch). */
enum class PaperSize(val widthPt: Int, val heightPt: Int) {
    A4(595, 842),
    LETTER(612, 792),
}

/**
 * Options for the therapist PDF report. The defaults are the one-tap path; everything
 * else is exposed in an "advanced" expander.
 */
data class PdfExportOptions(
    val fromMillis: Long,
    val toMillis: Long,
    val rangeLabel: String,
    val includeNotes: Boolean = true,
    val includeCharts: Boolean = true,
    val includeJournal: Boolean = false,
    val paperSize: PaperSize = PaperSize.A4,
    val patientName: String = "",
)
