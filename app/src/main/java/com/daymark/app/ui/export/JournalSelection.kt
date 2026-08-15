package com.daymark.app.ui.export

/**
 * The per-entry journal picker's decisions, with **no imports at all** — the same discipline
 * `export/ReportLayout.kt` and `stats/InterruptionBudget.kt` hold, and for the same reason: this is
 * the code that decides what a person's private writing is disclosed to a clinician, and it must be
 * executable on a plain JVM so it can be checked without a device.
 *
 * ## The three states, and why they are three and not two
 *
 * Side 3 of the report is the person's own writing. There are three distinguishable things they can
 * have done, and the report's copy has to say which:
 *
 *  - [Form.NOTHING] — nothing chosen. Side 3 does not print. This is the default and stays the
 *    default: a person who opens the picker and closes it again has consented to nothing.
 *  - [Form.PER_ENTRY] — they ticked entries one at a time. The report prints exactly those.
 *  - [Form.WHOLE_RANGE] — they took the blanket "everything in this range" action instead.
 *
 * The last two are **different consents and must not be collapsed into each other.** In particular
 * [resolve] will never promote "they ticked every entry" into [Form.WHOLE_RANGE], even though the
 * printed pages would be identical: the report says out loud which of the two happened, and turning
 * a per-entry deliberation into a blanket switch would make that sentence a lie in the direction
 * that overstates what was agreed to. The reverse promotion — filling
 * `PdfExportOptions.includedJournalEntryIds` out of the blanket switch — is refused for the same
 * reason, so [Outcome.includedIds] is empty under [Form.WHOLE_RANGE].
 *
 * ## Why nothing here defaults to "all"
 *
 * [resolve] is total: every combination of inputs maps to one of the three, and the only inputs that
 * produce disclosure are a non-empty tick set or an explicit blanket flag. There is no path where an
 * omission, an empty set or an unrecognised state means "include it" — absence of a choice is not
 * consent, which is the same fail-closed argument `PdfExportOptions.includeAllJournalInRange`
 * records for the flag existing at all.
 *
 * ## Ids are pruned to the range, always
 *
 * A person can tick four entries over ninety days and then narrow the range to thirty. [prune]
 * drops the ticks that fall outside the window, so the count they are shown and the count that
 * prints are the same number. `ReportDataBuilder` filters by range too, so a stale id could not
 * have leaked an out-of-range entry — but it could have left the picker saying "4 chosen" over a
 * report that carried two, and a consent screen whose count is wrong is not a consent screen.
 * [inRange] uses the same inclusive `from..to` comparison `ReportDataBuilder` uses; if one moves,
 * the other has to.
 */
object JournalSelection {

    /** How much of a body the picker shows. Enough to recognise writing, not to re-read it. */
    const val PREVIEW_CHARS = 160

    /** Shown in place of a blank title. Matches what the journal list and the report already use. */
    const val UNTITLED = "Untitled"

    /**
     * Shown in place of a blank body. Stated rather than left blank: an empty row reads as a
     * rendering failure, and a person cannot decide about an entry the screen appears to have
     * failed to load.
     */
    const val NO_BODY = "This entry has no text."

    /** A journal entry in the chosen range, as the picker needs it. */
    data class Candidate(
        val id: Long,
        val dateTimeMillis: Long,
        val title: String,
        val body: String,
    )

    /** One row of the picker. [preview] is for the screen only — see [preview]. */
    data class Row(
        val id: Long,
        val dateTimeMillis: Long,
        val title: String,
        val preview: String,
        val selected: Boolean,
    )

    enum class Form { NOTHING, PER_ENTRY, WHOLE_RANGE }

    /**
     * What the picker's state means, in the three fields `PdfExportOptions` carries plus the counts
     * the screen shows. One place computes all of them, so the screen's summary and the exported
     * options cannot describe different decisions.
     */
    data class Outcome(
        val form: Form,
        val includeInTheirWords: Boolean,
        val includedIds: Set<Long>,
        val includeAllInRange: Boolean,
        /** How many entries will print. */
        val includedCount: Int,
        /** How many entries the range holds — the denominator in "4 of 26". */
        val availableCount: Int,
    )

    /**
     * The entries the range holds, newest first.
     *
     * Inclusive at both ends, matching `ReportDataBuilder`'s `it.dateTime in from..to`. Ties broken
     * by descending id so the order is fixed: two entries written in the same millisecond must not
     * swap places between the screen the person read and the report that was generated from it.
     */
    fun inRange(all: List<Candidate>, fromMillis: Long, toMillis: Long): List<Candidate> =
        all.filter { it.dateTimeMillis in fromMillis..toMillis }
            .sortedWith(compareByDescending<Candidate> { it.dateTimeMillis }.thenByDescending { it.id })

    /**
     * The ticked ids that are still in the window, in the window's own order.
     *
     * Intersects rather than trusts: the tick set outlives a change of date range, and the ids that
     * survive are the ones the person can currently see.
     */
    fun prune(ticked: Set<Long>, inRange: List<Candidate>): Set<Long> {
        if (ticked.isEmpty()) return emptySet()
        val out = LinkedHashSet<Long>()
        inRange.forEach { if (it.id in ticked) out.add(it.id) }
        return out
    }

    /**
     * The picker's state, resolved into what the report will be told.
     *
     * An empty range resolves to [Form.NOTHING] whichever way the blanket flag is set. "Include
     * everything in this range" over a range holding nothing has included nothing, and claiming
     * otherwise would put the whole-range sentence on a report with no side 3.
     */
    fun resolve(inRange: List<Candidate>, ticked: Set<Long>, wholeRange: Boolean): Outcome {
        val available = inRange.size
        if (available == 0) {
            return Outcome(Form.NOTHING, false, emptySet(), false, 0, 0)
        }
        if (wholeRange) {
            return Outcome(Form.WHOLE_RANGE, true, emptySet(), true, available, available)
        }
        val kept = prune(ticked, inRange)
        if (kept.isEmpty()) {
            return Outcome(Form.NOTHING, false, emptySet(), false, 0, available)
        }
        return Outcome(Form.PER_ENTRY, true, kept, false, kept.size, available)
    }

    /**
     * The state a picker opens in, and the one it returns to when everything is un-ticked.
     *
     * Stated as a call to [resolve] rather than as a hand-built [Outcome], so a screen's starting
     * value cannot drift away from what an empty picker actually resolves to — the default is the
     * one value in this file that must never quietly become "include it".
     */
    val NOTHING_CHOSEN: Outcome = resolve(emptyList(), emptySet(), wholeRange = false)

    /** The picker's rows for a window and a tick set. Membership is pruned by construction. */
    fun rows(inRange: List<Candidate>, ticked: Set<Long>, maxChars: Int = PREVIEW_CHARS): List<Row> =
        inRange.map {
            Row(
                id = it.id,
                dateTimeMillis = it.dateTimeMillis,
                title = it.title.ifBlank { UNTITLED },
                preview = preview(it.body, maxChars),
                selected = it.id in ticked,
            )
        }

    /**
     * A one-line opening of an entry's body, so the person can tell which piece of writing a row is.
     *
     * **This never reaches the PDF.** Side 3 prints entries whole; excerpting is exactly what that
     * side promises not to do. This is a recognition aid on the consent screen and nothing else.
     *
     * Always a literal prefix of the writing — collapsed to one line, cut, and marked with an
     * ellipsis. It is never rewritten, re-ordered or summarised, because a preview that did not
     * match the entry would have a person consenting to disclose something other than what they
     * read.
     *
     * The cut prefers the last word boundary, but falls back to cutting mid-word when that boundary
     * would throw away more than half the room — a body that opens `"I " + oneVeryLongWord` would
     * otherwise preview as `"I…"`, which identifies nothing.
     */
    fun preview(body: String, maxChars: Int = PREVIEW_CHARS): String {
        val collapsed = collapse(body)
        if (collapsed.isEmpty()) return NO_BODY
        if (collapsed.length <= maxChars) return collapsed
        val cut = collapsed.substring(0, maxChars.coerceAtLeast(1))
        val space = cut.lastIndexOf(' ')
        val head = if (space >= cut.length / 2) cut.substring(0, space) else cut
        return head.trimEnd() + "…"
    }

    /**
     * What the person is about to disclose, in their own second person.
     *
     * States the decision back rather than urging one: nothing here calls a choice incomplete, and
     * choosing nothing is a finished answer with its own sentence.
     */
    fun summary(outcome: Outcome): String = when (outcome.form) {
        Form.NOTHING ->
            if (outcome.availableCount == 0) {
                "You have no journal entries in this range."
            } else {
                "No journal writing will be in this report."
            }

        Form.PER_ENTRY ->
            "${entries(outcome.includedCount)} of ${outcome.availableCount} will be in this " +
                "report — the ones you ticked, each printed whole."

        Form.WHOLE_RANGE ->
            "Every entry you wrote in this range will be in this report " +
                "(${entries(outcome.availableCount)}), each printed whole."
    }

    /** "1 entry" / "4 entries". */
    fun entries(n: Int): String = if (n == 1) "1 entry" else "$n entries"

    /**
     * Whitespace runs — including the newlines a journal body is full of — flattened to single
     * spaces, with the ends trimmed, so a preview is one line.
     */
    private fun collapse(s: String): String {
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        for (c in s) {
            if (c.isWhitespace()) {
                // Only pending once something has been emitted, so a leading run produces no space.
                pendingSpace = sb.isNotEmpty()
            } else {
                if (pendingSpace) sb.append(' ')
                pendingSpace = false
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
