package com.daymark.app.export

/** Paper sizes in PDF points (1/72 inch). */
enum class PaperSize(val widthPt: Int, val heightPt: Int) {
    A4(595, 842),
    LETTER(612, 792),
}

/**
 * Options for the therapist PDF report. The defaults are the one-tap path; everything
 * else is exposed in an "advanced" expander.
 *
 * The report is four sides (see `docs/PLAN_2026-08-NEXT.md` §1). Sides 1, 2 and 4 always print —
 * they are scores, bands, entries and provenance. Side 3 is the person's own writing and is the
 * only side that is off unless they turn it on.
 */
data class PdfExportOptions(
    val fromMillis: Long,
    val toMillis: Long,
    val rangeLabel: String,
    val includeNotes: Boolean = true,
    val includeCharts: Boolean = true,
    /**
     * Whether side 3 ("In their words") is emitted at all. Default off.
     *
     * This replaced a blanket `includeJournal` flag. One switch over the whole journal made
     * "share my writing" a single yes/no over a body of text the person wrote at different times
     * for different reasons — so the safe answer was always no, and the side was dead weight.
     * Two controls (this, plus [includedJournalEntryIds]) let the answer be "these four", which
     * is the answer people actually have.
     */
    val includeInTheirWords: Boolean = false,
    /**
     * Ids of the journal entries the person chose, one at a time, to put in front of a clinician.
     *
     * Empty means side 3 prints nothing, even when [includeInTheirWords] is on — absence of a
     * choice is not consent. The software may *nominate* a set for this list (the plan allows
     * proposing a representative spread), but the set that reaches here is the one the person
     * confirmed after seeing it. Nothing may be added to it silently, and nothing here is
     * summarised or excerpted: an included entry prints whole.
     */
    val includedJournalEntryIds: Set<Long> = emptySet(),
    /**
     * The person chose "all of the entries in this range" rather than ticking them individually.
     *
     * This exists as its own flag rather than as an "empty set means everything" convention,
     * because that convention would be fail-OPEN on the most sensitive content in the product: any
     * future path that forgot to populate [includedJournalEntryIds] would silently export the whole
     * journal. Absence of a choice must mean nothing is shared, so "everything" has to be said out
     * loud.
     *
     * It is still the person's own explicit choice — it is what the "Include journal entries"
     * switch has always meant — so it is not the software selecting on their behalf. When the
     * per-entry picker lands, that flow sets [includedJournalEntryIds] and leaves this false.
     */
    val includeAllJournalInRange: Boolean = false,
    /**
     * Whether side 4 carries its discussion prompts.
     *
     * Prompts are observations about the person's own data phrased as questions for the two
     * humans to answer ("wellbeing entries were lower on the three weeks with no logged
     * activity — worth asking about?"). They are deliberately not advice: a report that tells a
     * clinician what to do is clinical decision support, which is a different regulatory
     * category than anything Daymark is. Kept as a switch because a prompt is still a nudge, and
     * some people will want their numbers to arrive without one.
     */
    val includeDiscussionPrompts: Boolean = true,
    val paperSize: PaperSize = PaperSize.A4,
    val patientName: String = "",
)
