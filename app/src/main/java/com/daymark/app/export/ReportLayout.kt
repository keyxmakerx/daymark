package com.daymark.app.export

/**
 * The four-side report's page arithmetic, extracted from the renderer so it can be checked without
 * a device.
 *
 * **Why this file exists.** Every geometry defect the report has ever shipped — a rule struck
 * through the generation date, a hairline dropped into the footer band, a side that silently ran to
 * three physical pages while its header claimed to be one of four — was arithmetic, not drawing.
 * None of it was catchable by a test, because the arithmetic was entangled with
 * `android.graphics.Canvas` and therefore only executable on a device. So the arithmetic lives here
 * instead: **no Canvas, no Paint, no `android.*` import at all**, mirroring the discipline the
 * `stats/` package already holds. [PdfReportGenerator] keeps the drawing and asks this object where
 * things go.
 *
 * That split has one rule: a number that decides *where* something lands belongs here, and a number
 * that decides how something *looks* (a colour, a stroke width, a corner radius) stays with the
 * renderer. Text metrics are the boundary case — measuring a string needs a `Paint`, so the caller
 * measures and wraps, then hands this object line *counts* and text *sizes*, which are arithmetic
 * again.
 *
 * ### The page model
 *
 * A page is a margin-inset content box with a footer band reserved at the bottom. Content may
 * occupy `[MARGIN, contentLimit)`; the footer band below that belongs to the footer rule and the
 * footer text and nothing else may enter it. [fits] is the single predicate that decides whether a
 * block may start where it currently stands, and every reserve in this file is stated in the units
 * that predicate consumes.
 *
 * ### Sides are logical, pages are physical
 *
 * A *side* is a section with one job (`docs/PLAN_2026-08-NEXT.md` §1), not a sheet face. A side may
 * span several physical pages, so the renderer marks continuation pages and pays [CONTINUATION_H]
 * off the top of each one. [paginate] is the pure model of that flow: give it the blocks a side
 * wants to emit and it returns where the breaks fall and how many physical pages the side takes,
 * which is what makes "of N" a computed number rather than an assumed one.
 */
object ReportLayout {

    // -----------------------------------------------------------------------------------------
    // Page geometry.
    // -----------------------------------------------------------------------------------------

    /** Outer margin on all four edges, in PDF points. */
    const val MARGIN = 42f

    /**
     * Height of the band reserved at the foot of every page. Nothing but the footer rule and the
     * footer text may be drawn inside it — a stray hairline here reads as a rule the document did
     * not intend.
     */
    const val FOOTER_SPACE = 46f

    /** How far below the content limit the footer's own rule sits. */
    const val FOOTER_RULE_DROP = 6f

    /** How far above the bottom edge the footer text's baseline sits. */
    const val FOOTER_TEXT_RISE = 22f

    fun contentWidth(pageW: Float): Float = pageW - 2f * MARGIN

    /** The first y a block may not occupy. Content lives in `[MARGIN, contentLimit)`. */
    fun contentLimit(pageH: Float): Float = pageH - FOOTER_SPACE

    /** Usable vertical space on a page that carries no continuation strip. */
    fun contentHeight(pageH: Float): Float = contentLimit(pageH) - MARGIN

    fun footerRuleY(pageH: Float): Float = contentLimit(pageH) + FOOTER_RULE_DROP

    /**
     * The one break rule. A block of height [space] may start at [y] only when all of it lands
     * above the content limit.
     */
    fun fits(y: Float, space: Float, pageH: Float): Boolean = y + space <= contentLimit(pageH)

    // -----------------------------------------------------------------------------------------
    // Text metrics. Measuring a string needs a Paint and stays with the renderer; the leading it
    // implies is arithmetic and lives here.
    // -----------------------------------------------------------------------------------------

    const val LINE_SPACING = 1.35f

    fun lineHeight(textSize: Float): Float = textSize * LINE_SPACING

    // -----------------------------------------------------------------------------------------
    // The side header, and the continuation strip that identifies a side's later pages.
    // -----------------------------------------------------------------------------------------

    /** Eyebrow row ("SIDE 1 OF 4 · THE GLANCE"): its baseline drop, then its advance. */
    const val HEADER_EYEBROW_BASELINE = 8f
    const val HEADER_EYEBROW_ADVANCE = 16f

    /** The title row. Baseline is measured from the top of the row, as is the meta column. */
    const val HEADER_TITLE_BASELINE = 14f
    const val HEADER_TITLE_ADVANCE = 22f

    const val HEADER_SUBTITLE_BASELINE = 8f
    const val HEADER_SUBTITLE_ADVANCE = 14f

    /** The right-hand meta column starts level with the title row and steps down from there. */
    const val HEADER_META_BASELINE = 4f
    const val HEADER_META_STEP = 11f

    /** Clearance under the lowest meta baseline, so the rule cannot cross that line's descenders. */
    const val HEADER_META_CLEARANCE = 5f

    const val HEADER_RULE_LEAD = 2f
    const val HEADER_RULE_TO_CONTENT = 16f

    /**
     * Where the header's closing rule lands, given the header's top edge.
     *
     * The header sets two columns: a left one (eyebrow, title, optional subtitle) and a right one
     * (the meta lines). **They do not finish at the same height.** With a subtitle the left column
     * is lower and drives the rule; with a blank subtitle and three meta lines — side 1's default
     * path, since `patientName` defaults to empty — the *right* column finishes lower, and a rule
     * placed off the left column alone is drawn straight through the last meta line. So the rule
     * clears whichever column ends lower, always.
     */
    fun headerRuleY(top: Float, metaLines: Int, hasSubtitle: Boolean): Float {
        var left = top + HEADER_EYEBROW_ADVANCE + HEADER_TITLE_ADVANCE
        if (hasSubtitle) left += HEADER_SUBTITLE_ADVANCE
        left += HEADER_RULE_LEAD
        val lastMetaBaseline = top + HEADER_EYEBROW_ADVANCE + HEADER_META_BASELINE +
            (metaLines - 1).coerceAtLeast(0) * HEADER_META_STEP
        return maxOf(left, lastMetaBaseline + HEADER_META_CLEARANCE)
    }

    /** Where a side's body starts, given the header's top edge. */
    fun headerContentStart(top: Float, metaLines: Int, hasSubtitle: Boolean): Float =
        headerRuleY(top, metaLines, hasSubtitle) + HEADER_RULE_TO_CONTENT

    /**
     * The strip that identifies the second and later physical pages of a side, so that no page in
     * the document is unlabelled. Costs this much off the top of every continuation page.
     */
    const val CONTINUATION_BASELINE = 8f
    const val CONTINUATION_RULE_LEAD = 14f
    const val CONTINUATION_RULE_TO_CONTENT = 12f
    const val CONTINUATION_H = CONTINUATION_RULE_LEAD + CONTINUATION_RULE_TO_CONTENT

    /** Where content starts on a continuation page. */
    fun continuationContentStart(): Float = MARGIN + CONTINUATION_H

    // -----------------------------------------------------------------------------------------
    // Per-block reserves. Each is what the block *consumes*, so a reserve and the advance that
    // follows it cannot drift apart. Where a block's own children re-check for themselves (a
    // paragraph breaks a line at a time, a table row re-checks per row) the reserve covers the
    // block's fixed part only, and that is said at the call site.
    // -----------------------------------------------------------------------------------------

    /** A small-caps section label: its advance, and the reserve taken before drawing it. */
    const val SECTION_LABEL_H = 18f
    const val SECTION_LABEL_RESERVE = 20f

    /** A table's column-header row: label baseline, rule, then clearance. */
    const val TABLE_HEAD_H = 17f

    /**
     * The tallest of the per-table minimum row heights (the daily check-ins table's 18; the others
     * floor at 17). Taken as the worst case so one constant serves every table's start reserve.
     */
    const val TABLE_MIN_ROW_H = 18f

    /**
     * Taken before a table's head is drawn. It covers the head *and* one minimum row, because a
     * head alone at the foot of a page is an orphan: the first row re-checks for itself, breaks,
     * and redraws the head on the next page, leaving a column header over nothing.
     */
    const val TABLE_START_RESERVE = TABLE_HEAD_H + TABLE_MIN_ROW_H

    /** Bordered fixed-copy block: padding top and bottom, gap between blocks, trailing space. */
    const val NOTE_BOX_PAD = 8f
    const val NOTE_BOX_BLOCK_GAP = 3f
    const val NOTE_BOX_TRAILING = 12f

    /**
     * The bordered height of a note box, which is also its advance up to the trailing gap. Takes
     * the wrapped line count and text size of each block, since the wrapping itself needs a Paint.
     */
    fun noteBoxHeight(blocks: List<Pair<Int, Float>>): Float {
        var h = 2f * NOTE_BOX_PAD
        blocks.forEach { (lines, textSize) -> h += lines * lineHeight(textSize) + NOTE_BOX_BLOCK_GAP }
        return h
    }

    /** What a note box costs end to end — the border plus the space that follows it. */
    fun noteBoxReserve(blocks: List<Pair<Int, Float>>): Float =
        noteBoxHeight(blocks) + NOTE_BOX_TRAILING

    // ---- instrument plot ----

    const val PLOT_TITLE_H = 14f
    const val PLOT_DIRECTION_H = 12f
    const val PLOT_H = 84f
    const val PLOT_AXIS_GAP = 4f
    const val PLOT_AXIS_H = 12f
    const val DENSITY_H = 8f
    const val DENSITY_GAP = 3f
    const val DENSITY_LABEL_H = 12f
    const val PLOT_TRAILING = 6f

    /**
     * One instrument plot, exactly. Both optional rows are conditional in the renderer, so they are
     * conditional here too — a fixed worst-case reserve would break pages earlier than the drawing
     * needs and leave the foot of a page unused.
     *
     * The band-wording paragraphs that may follow a plot are not counted: they flow a line at a
     * time and re-check for themselves.
     */
    fun instrumentPlotReserve(hasDirection: Boolean, hasDensity: Boolean): Float =
        PLOT_TITLE_H +
            (if (hasDirection) PLOT_DIRECTION_H else 0f) +
            PLOT_H + PLOT_AXIS_GAP + PLOT_AXIS_H +
            (if (hasDensity) DENSITY_H + DENSITY_GAP + DENSITY_LABEL_H else 0f) +
            PLOT_TRAILING

    // ---- side 1 tail ----

    /** Section label plus the four figure cells. */
    const val COMPLETION_SUMMARY_RESERVE = SECTION_LABEL_H + 42f

    const val DISTRIBUTION_ROW_H = 16f

    /** Section label, one row per mood level (five of them), and the gap that closes the block. */
    const val DISTRIBUTION_RESERVE = SECTION_LABEL_H + 5f * DISTRIBUTION_ROW_H + 8f

    // ---- side 2 ----

    /** A project's title row plus one line of whatever follows it. */
    const val PROJECT_HEAD_RESERVE = 26f
    const val PROJECT_STEP_H = 12f

    /**
     * The rule that closes a project. Guarded rather than unconditional: the last step can leave y
     * flush against the content limit, and an unguarded advance would drop the rule into the footer
     * band.
     */
    const val PROJECT_SEPARATOR_H = 8f

    const val ACTIVITY_ROW_H = 14f
    const val COVERAGE_GAP_ROW_H = 12f

    // ---- side 3 ----

    /** A journal entry's title and timestamp, before its body flows. */
    const val JOURNAL_ENTRY_RESERVE = 34f

    /**
     * The rule between two journal entries: the lead-in gap, the rule, and the gap after it. It is
     * reserved rather than assumed because [PdfReportGenerator]'s paragraph flow can finish a body
     * flush against the content limit, and side 3's whole promise is that the person's writing is
     * printed untouched — a hairline in its footer band is exactly the kind of mark that promise
     * rules out.
     */
    const val JOURNAL_SEPARATOR_H = 20f

    // -----------------------------------------------------------------------------------------
    // Pagination — the pure model of the renderer's flow.
    // -----------------------------------------------------------------------------------------

    /**
     * One block a side wants to emit.
     *
     * [reserve] is what the block asks for before it starts, and [advance] is what it actually
     * moves y by. They are the same for most blocks; they differ where a block reserves its fixed
     * part and then flows (a table, a paragraph run).
     */
    data class Block(val id: String, val reserve: Float, val advance: Float = reserve)

    /** Where a block ended up. [page] is zero-based *within the side*, not within the document. */
    data class Placement(val id: String, val page: Int, val top: Float, val bottom: Float)

    /**
     * What a side costs: how many physical pages it takes, where each block landed, and where y is
     * left afterwards.
     */
    data class SidePlan(val pages: Int, val placements: List<Placement>, val endY: Float) {

        /** The ids of the blocks that had to open a fresh page — i.e. where the breaks fall. */
        fun breaksBefore(): List<String> {
            val out = ArrayList<String>()
            var page = 0
            placements.forEach { p ->
                if (p.page > page) {
                    page = p.page
                    out.add(p.id)
                }
            }
            return out
        }
    }

    /**
     * Lays [blocks] out down a page of height [pageH], breaking by the same [fits] rule the
     * renderer uses and paying the continuation strip on every page after the first.
     *
     * This is the answer to "how many sheets does side 1 actually take" without a device, and it is
     * the reason the document can print a computed page total instead of asserting one.
     */
    fun paginate(
        pageH: Float,
        blocks: List<Block>,
        startY: Float = MARGIN,
        continuationStart: Float = continuationContentStart(),
    ): SidePlan {
        var page = 0
        var y = startY
        val placements = ArrayList<Placement>(blocks.size)
        blocks.forEach { b ->
            if (!fits(y, b.reserve, pageH)) {
                page++
                y = continuationStart
            }
            placements.add(Placement(b.id, page, y, y + b.advance))
            y += b.advance
        }
        return SidePlan(page + 1, placements, y)
    }
}
