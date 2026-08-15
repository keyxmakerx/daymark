package com.daymark.app.export

import com.daymark.app.export.ReportLayout.Block
import com.daymark.app.export.ReportLayout.Placement
import com.daymark.app.export.ReportLayout.SidePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.random.Random

/**
 * The four-side report's page arithmetic.
 *
 * [ReportLayout] exists because every geometry defect the report has shipped was arithmetic that
 * could only run on a device. This is the other half of that: the arithmetic, exercised on the
 * shapes that actually broke it.
 *
 * ### What is asserted, and what is deliberately not
 *
 * The page model has **two different promises**, and conflating them would produce a test that is
 * either vacuous or wrong:
 *
 *  - A block's **reserve** must always land above the content limit. This holds for every block
 *    without exception — it is what [ReportLayout.fits] decides, and it is the promise that keeps
 *    ink out of the footer band.
 *  - A block's **advance** may exceed its reserve, and then its bottom may pass the limit. That is
 *    not a defect: a table or a paragraph run reserves its fixed part and its children re-check for
 *    themselves, a line or a row at a time. So the "ends inside the box" assertion is made over the
 *    atomic blocks (`advance <= reserve`), and the "starts somewhere it fits" assertion is made over
 *    all of them.
 *
 * ### Non-vacuity
 *
 * The rules below are proved to be able to fail, by constructing the violating input first and
 * asserting the violation is seen:
 *
 *  1. [theOverflowCheckerFiresOnABlockPlacedPastTheLimit] — the checker every other test leans on,
 *     run against a hand-built plan that overflows by 30pt.
 *  2. [aBlockTallerThanAPageIsVisibleRatherThanSilent] — a block no page can hold, which
 *     [ReportLayout.paginate] can only place past the limit; asserted to be reported rather than
 *     swallowed.
 *  3. [theContinuationStripIsChargedOnEveryPageAfterTheFirst] — the same two blocks laid out twice,
 *     once with the strip charged and once with it free (the old constant). One overflows and the
 *     other does not, so the strip is demonstrably load-bearing.
 *  4. [fitsIsExactlyTheContentLimit] and [theHeaderRuleClearsWhicheverColumnEndsLower] — each states
 *     what the superseded rule would have allowed, and asserts it is now refused.
 *
 * ### The reserves need behavioural statements, not arithmetic ones
 *
 * The side fixtures take their block heights from [ReportLayout]'s own reserves, which is what
 * makes them track the renderer — and also means a reserve that *shrinks* changes both sides of
 * the comparison and stays invisible. Three reserves exist for a reason that a smaller number
 * silently breaks, so each is asserted as the behaviour it buys rather than as its value:
 * [aTableHeadIsNeverLeftAloneAtTheFootOfAPage], [aSeparatorRuleAlwaysCostsSomething] and
 * [aNoteBoxReservesPaddingOnBothSidesOfItsCopy]. A reserve added later needs the same treatment.
 */
class ReportLayoutTest {

    private val a4 = PaperSize.A4.heightPt.toFloat()
    private val letter = PaperSize.LETTER.heightPt.toFloat()

    // ---------------------------------------------------------------------------------------
    // The primitives, at their boundaries.
    // ---------------------------------------------------------------------------------------

    @Test
    fun fitsIsExactlyTheContentLimit() {
        listOf(a4, letter).forEach { pageH ->
            val height = ReportLayout.contentHeight(pageH)
            assertTrue(
                "a block of exactly the content height must fit from the top margin",
                ReportLayout.fits(ReportLayout.MARGIN, height, pageH),
            )
            assertFalse(
                "half a point more must not",
                ReportLayout.fits(ReportLayout.MARGIN, height + 0.5f, pageH),
            )

            // The rule this replaced let content run to the bottom margin, with the footer drawn
            // over whatever was there. Stated as the input it would have accepted, so the test
            // fails if the footer band ever stops being reserved.
            val marginToMargin = pageH - 2f * ReportLayout.MARGIN
            assertTrue("the footer band is real", marginToMargin > height)
            assertFalse(
                "a margin-to-margin block would be drawn through the footer",
                ReportLayout.fits(ReportLayout.MARGIN, marginToMargin, pageH),
            )
        }
    }

    @Test
    fun theHeaderRuleClearsWhicheverColumnEndsLower() {
        val top = ReportLayout.MARGIN

        // Side 1's default path: patientName is empty, so there is no subtitle, and three meta
        // lines run down the right. This is the case that drew the rule through the last one.
        val leftColumnOnly = top + ReportLayout.HEADER_EYEBROW_ADVANCE +
            ReportLayout.HEADER_TITLE_ADVANCE + ReportLayout.HEADER_RULE_LEAD
        val threeMetaNoSubtitle = ReportLayout.headerRuleY(top, metaLines = 3, hasSubtitle = false)
        assertTrue(
            "the naive left-column rule at $leftColumnOnly would cross the third meta line; " +
                "the rule must sit lower, and sits at $threeMetaNoSubtitle",
            threeMetaNoSubtitle > leftColumnOnly,
        )
        val lastMetaBaseline = top + ReportLayout.HEADER_EYEBROW_ADVANCE +
            ReportLayout.HEADER_META_BASELINE + 2f * ReportLayout.HEADER_META_STEP
        assertTrue("the meta column needs clearance under it", ReportLayout.HEADER_META_CLEARANCE > 0f)
        assertTrue(
            "the rule must clear the last meta baseline's descenders, not land on it",
            threeMetaNoSubtitle > lastMetaBaseline,
        )
        assertTrue(
            "and clear it by the stated clearance",
            threeMetaNoSubtitle >= lastMetaBaseline + ReportLayout.HEADER_META_CLEARANCE,
        )

        // And the other branch is still reached: with a subtitle the left column finishes lower and
        // drives the rule, so the max() is not a one-sided no-op.
        val withSubtitle = ReportLayout.headerRuleY(top, metaLines = 1, hasSubtitle = true)
        assertTrue(
            "with a subtitle and one meta line the left column must drive the rule",
            withSubtitle > ReportLayout.headerRuleY(top, metaLines = 1, hasSubtitle = false),
        )

        // More meta lines can only push the rule down, never up.
        var previous = 0f
        for (lines in 0..6) {
            val rule = ReportLayout.headerRuleY(top, lines, hasSubtitle = false)
            assertTrue("meta line $lines pulled the rule back up", rule >= previous)
            previous = rule
        }

        assertEquals(
            "content starts one fixed gap below the rule",
            (threeMetaNoSubtitle + ReportLayout.HEADER_RULE_TO_CONTENT).toDouble(),
            ReportLayout.headerContentStart(top, metaLines = 3, hasSubtitle = false).toDouble(),
            EPS,
        )

        // And that gap is never nothing: a body starting on the rule puts its first line of copy
        // through the hairline. Checked over every header shape the four sides use.
        assertTrue("the header rule needs clearance under it", ReportLayout.HEADER_RULE_TO_CONTENT > 0f)
        for (lines in 1..4) {
            listOf(true, false).forEach { subtitle ->
                assertTrue(
                    "content starts on the rule for metaLines=$lines, subtitle=$subtitle",
                    ReportLayout.headerContentStart(top, lines, subtitle) >
                        ReportLayout.headerRuleY(top, lines, subtitle),
                )
            }
        }
    }

    @Test
    fun aBlockThatDoesNotFlowNeverReservesLessThanItDraws() {
        // The reserve/advance split is for blocks whose children re-check for themselves. A section
        // label and a table head do not flow — each draws a fixed height — so reserving less than
        // they draw would put them in the footer band with nothing left to catch it. This is the
        // one shape [overflows] cannot see, because it reads a larger advance as flowing.
        assertTrue(
            "a section label reserves ${ReportLayout.SECTION_LABEL_RESERVE} and draws " +
                "${ReportLayout.SECTION_LABEL_H}",
            ReportLayout.SECTION_LABEL_RESERVE >= ReportLayout.SECTION_LABEL_H,
        )
        assertTrue(
            "a table head reserves ${ReportLayout.TABLE_START_RESERVE} and draws " +
                "${ReportLayout.TABLE_HEAD_H}",
            ReportLayout.TABLE_START_RESERVE >= ReportLayout.TABLE_HEAD_H,
        )

        // Behaviourally: a label with one point less room than it draws must move to the next sheet.
        val limit = ReportLayout.contentLimit(a4)
        val blocks = listOf(
            Block("filler", limit - ReportLayout.MARGIN - ReportLayout.SECTION_LABEL_H + 1f),
            Block("label", ReportLayout.SECTION_LABEL_RESERVE, ReportLayout.SECTION_LABEL_H),
        )
        val plan = ReportLayout.paginate(a4, blocks)
        assertEquals(
            "the label must move rather than be drawn past the content limit",
            1,
            plan.placements[1].page,
        )
        assertTrue(plan.placements[1].bottom <= limit)
    }

    @Test
    fun theVerificationBlockFitsAboveTheFooterOnBothPaperSizes() {
        // Side 4 anchors verification to the bottom of the page. The renderer's anchor coerces the
        // top back to the margin if the block is taller than the page — which would put the QR and
        // the hash into the footer band silently. It cannot fire while this holds.
        val qrSize = 90f
        val textH = 16f + 4f * 11f
        val blockH = 28f + maxOf(qrSize, textH) + 8f
        listOf(a4, letter).forEach { pageH ->
            assertTrue(
                "verification (${blockH}pt) must fit inside the content box of a ${pageH}pt page",
                ReportLayout.fits(ReportLayout.MARGIN, blockH, pageH),
            )
            assertTrue(
                "and must still fit under a continuation strip",
                ReportLayout.fits(ReportLayout.continuationContentStart(), blockH, pageH),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Non-vacuity: the checker, and the two constants everything else depends on.
    // ---------------------------------------------------------------------------------------

    @Test
    fun theOverflowCheckerFiresOnABlockPlacedPastTheLimit() {
        val limit = ReportLayout.contentLimit(a4)
        val subject = Block("past-the-limit", reserve = 40f)
        val broken = SidePlan(
            pages = 1,
            placements = listOf(Placement("past-the-limit", page = 0, top = limit - 10f, bottom = limit + 30f)),
            endY = limit + 30f,
        )
        assertTrue(
            "the checker every other test leans on must see a 30pt overflow",
            overflows(a4, listOf(subject), broken).isNotEmpty(),
        )

        // And a block that sits inside the box must not be reported, or the checker is just noise.
        val fine = SidePlan(
            pages = 1,
            placements = listOf(Placement("past-the-limit", page = 0, top = limit - 60f, bottom = limit - 20f)),
            endY = limit - 20f,
        )
        assertTrue(
            "a block inside the box must not be reported",
            overflows(a4, listOf(subject), fine).isEmpty(),
        )

        // A continuation page whose content starts inside the strip is a page with no side on it.
        val insideTheStrip = SidePlan(
            pages = 2,
            placements = listOf(
                Placement("past-the-limit", page = 0, top = ReportLayout.MARGIN, bottom = ReportLayout.MARGIN + 40f),
                Placement("past-the-limit", page = 1, top = ReportLayout.MARGIN, bottom = ReportLayout.MARGIN + 40f),
            ),
            endY = ReportLayout.MARGIN + 40f,
        )
        assertTrue(
            "content drawn over the strip that names the side must be reported",
            overflows(a4, listOf(subject), insideTheStrip).isNotEmpty(),
        )
    }

    @Test
    fun aBlockTallerThanAPageIsVisibleRatherThanSilent() {
        // Nothing can hold this. What matters is that it is reported, not that it is prevented:
        // a caller handing the layout an unbreakable block taller than a page has a bug the layout
        // cannot fix for it, and swallowing it is how a rule ends up in the footer band.
        val giant = Block("giant", ReportLayout.contentHeight(a4) + 40f)
        val plan = ReportLayout.paginate(a4, listOf(giant))
        assertEquals("it must not spin looking for a page", 2, plan.pages)
        assertTrue(
            "a block no page can hold must be reported, not placed quietly",
            overflows(a4, listOf(giant), plan).isNotEmpty(),
        )
    }

    @Test
    fun theContinuationStripIsChargedOnEveryPageAfterTheFirst() {
        // Sized so it fits on a first page but cannot fit under a continuation strip — by exactly
        // one point. Two of them therefore overflow when the strip is charged and do not when it
        // is free, which is the whole difference the strip makes.
        val height = ReportLayout.contentHeight(a4) - ReportLayout.CONTINUATION_H + 1f
        val two = listOf(Block("first", height), Block("second", height))

        val charged = ReportLayout.paginate(a4, two)
        assertEquals(2, charged.pages)
        assertEquals(
            "the second block must open a continuation page",
            listOf("second"),
            charged.breaksBefore(),
        )
        assertEquals(
            "and start below the strip",
            ReportLayout.continuationContentStart().toDouble(),
            charged.placements[1].top.toDouble(),
            EPS,
        )
        assertTrue(
            "with the strip charged, the second block cannot fit and must be reported",
            overflows(a4, two, charged).isNotEmpty(),
        )

        // The superseded model: continuation pages started at the plain margin, so the same block
        // fitted and the page carried no mark saying which side it belonged to.
        val free = ReportLayout.paginate(a4, two, continuationStart = ReportLayout.MARGIN)
        assertTrue(
            "the un-striped model must accept what the striped one refuses, or this test proves nothing",
            free.placements[1].bottom <= ReportLayout.contentLimit(a4),
        )
        assertNotEquals(
            "the two models must actually differ",
            charged.placements[1].top,
            free.placements[1].top,
        )
    }

    @Test
    fun aSectionReservesMoreThanItsHeading() {
        // Each of these is a heading with content under it, reserved as one block so the two cannot
        // be split. A reserve that covers only the heading is a break that puts the heading at the
        // foot of one sheet and the figures it names on the next.
        assertTrue(
            "the completion summary reserves nothing under its heading",
            ReportLayout.COMPLETION_SUMMARY_RESERVE > ReportLayout.SECTION_LABEL_H,
        )
        assertTrue(
            "the distribution must reserve its heading and one row per mood level",
            ReportLayout.DISTRIBUTION_RESERVE >=
                ReportLayout.SECTION_LABEL_H + 5f * ReportLayout.DISTRIBUTION_ROW_H,
        )
        assertTrue(
            "a project's head must reserve more than one step row",
            ReportLayout.PROJECT_HEAD_RESERVE > ReportLayout.PROJECT_STEP_H,
        )

        // A plot has to reserve at least the plot body it draws, and both optional rows only ever
        // cost more — a conditional reserve that forgot to add one would break pages too late.
        val bare = ReportLayout.instrumentPlotReserve(hasDirection = false, hasDensity = false)
        assertTrue("a plot must reserve at least its own body", bare > ReportLayout.PLOT_H)
        assertTrue(ReportLayout.instrumentPlotReserve(true, false) > bare)
        assertTrue(ReportLayout.instrumentPlotReserve(false, true) > bare)
        assertTrue(
            ReportLayout.instrumentPlotReserve(true, true) >
                ReportLayout.instrumentPlotReserve(true, false),
        )
    }

    @Test
    fun aTableHeadIsNeverLeftAloneAtTheFootOfAPage() {
        // A column header with nothing under it is a heading over nothing: the first row re-checks
        // for itself, breaks, and redraws the head on the next page. The head's reserve therefore
        // has to cover itself *and* one shortest row.
        //
        // Stated as behaviour rather than as the constant, because the fixtures elsewhere in this
        // file take their heights from the same constants and so cannot see one shrink.
        val limit = ReportLayout.contentLimit(a4)
        val roomForTheHeadAlone = ReportLayout.TABLE_HEAD_H + ReportLayout.TABLE_MIN_ROW_H - 1f
        val blocks = listOf(
            Block("filler", limit - ReportLayout.MARGIN - roomForTheHeadAlone),
            Block("head", ReportLayout.TABLE_START_RESERVE, ReportLayout.TABLE_HEAD_H),
            Block("row", ReportLayout.TABLE_MIN_ROW_H),
        )
        val plan = ReportLayout.paginate(a4, blocks)
        val head = plan.placements.first { it.id == "head" }
        val row = plan.placements.first { it.id == "row" }
        assertEquals(
            "the head was left at the foot of sheet ${head.page} with its first row on ${row.page}",
            head.page,
            row.page,
        )
        assertTrue(overflows(a4, blocks, plan).isEmpty())
    }

    @Test
    fun aSeparatorRuleAlwaysCostsSomething() {
        // A zero-height block always fits — including at the content limit itself, where "fits"
        // is true and the ink is in the footer band. Both separators close a block whose flow can
        // finish flush against the limit, which is exactly when that happens.
        assertTrue("the journal separator must reserve space", ReportLayout.JOURNAL_SEPARATOR_H > 0f)
        assertTrue("the project separator must reserve space", ReportLayout.PROJECT_SEPARATOR_H > 0f)

        val limit = ReportLayout.contentLimit(a4)
        val blocks = listOf(
            Block("body", limit - ReportLayout.MARGIN),
            Block("rule", ReportLayout.JOURNAL_SEPARATOR_H),
        )
        val plan = ReportLayout.paginate(a4, blocks)
        assertEquals("the body must fill the sheet exactly", 0, plan.placements[0].page)
        assertEquals(
            "a rule after a body that ends flush against the limit must move, not sit on the limit",
            1,
            plan.placements[1].page,
        )
        assertEquals(2, plan.pages)
    }

    @Test
    fun aNoteBoxReservesPaddingOnBothSidesOfItsCopy() {
        // The border is drawn round the copy, so the height has to clear the first line above and
        // the last line below. One pad instead of two puts the rule through a line of fixed copy —
        // and every note box in this report carries fixed copy.
        val parts = listOf(2 to 9f, 3 to 8f)
        val copy = parts.sumOf { (lines, size) -> (lines * ReportLayout.lineHeight(size)).toDouble() } +
            parts.size * ReportLayout.NOTE_BOX_BLOCK_GAP
        val height = ReportLayout.noteBoxHeight(parts).toDouble()
        assertEquals(
            "the border must clear the copy above and below by the same padding",
            copy + 2.0 * ReportLayout.NOTE_BOX_PAD,
            height,
            EPS,
        )
        assertEquals(
            "the reserve is the border plus the space that follows it",
            height + ReportLayout.NOTE_BOX_TRAILING,
            ReportLayout.noteBoxReserve(parts).toDouble(),
            EPS,
        )
        // More copy is never a shorter box.
        assertTrue(
            ReportLayout.noteBoxHeight(listOf(4 to 9f)) > ReportLayout.noteBoxHeight(listOf(3 to 9f)),
        )
        assertTrue(
            ReportLayout.noteBoxHeight(listOf(1 to 9f, 1 to 9f)) > ReportLayout.noteBoxHeight(listOf(2 to 9f)),
        )
    }

    @Test
    fun aSideThatEmitsNothingStillCostsOneIdentifiedPage() {
        val plan = ReportLayout.paginate(a4, emptyList())
        assertEquals("a side is never zero pages", 1, plan.pages)
        assertTrue(plan.placements.isEmpty())
        assertEquals(0, plan.breaksBefore().size)
        assertEquals(ReportLayout.MARGIN.toDouble(), plan.endY.toDouble(), EPS)
    }

    @Test
    fun aSideWhoseFirstBlockCannotFitUnderItsHeaderStillIdentifiesBothPages() {
        val start = ReportLayout.headerContentStart(ReportLayout.MARGIN, metaLines = 3, hasSubtitle = true)
        // Fits on a bare page, but not under a header. It has to move, and the page it leaves
        // behind carries only the header — which is what identifies it.
        val tall = Block("tall", ReportLayout.contentLimit(a4) - start + 1f)
        val plan = ReportLayout.paginate(a4, listOf(tall), startY = start)
        assertEquals(2, plan.pages)
        assertEquals("it must move off the header page", 1, plan.placements[0].page)
        assertEquals(
            ReportLayout.continuationContentStart().toDouble(),
            plan.placements[0].top.toDouble(),
            EPS,
        )
        assertTrue(overflows(a4, listOf(tall), plan).isEmpty())
        assertEveryPageIsAttributed(listOf("side 1" to plan))
    }

    // ---------------------------------------------------------------------------------------
    // The realistic export — the shape the hand-trace used.
    // ---------------------------------------------------------------------------------------

    @Test
    fun aRealisticExportPaginatesWithoutRunningPastTheContentLimit() {
        // ~19 check-ins over five weeks, two scored instruments (so three plots), four journal
        // entries the person ticked, four assignment outcomes, two projects.
        val one = sideOneBlocks(scoredInstruments = 2)
        val two = sideTwoBlocks(
            resultRows = 10,
            entryRows = 19,
            outcomeRows = 4,
            projectSteps = listOf(3, 4),
            activityRows = 6,
            namedGaps = 2,
        )
        val three = sideThreeBlocks(journalBodyLines = listOf(10, 10, 10, 10))
        val four = sideFourBlocks(promptCount = 3, promptLines = 2, provenanceRows = 5)

        val planOne = ReportLayout.paginate(a4, one, startY = SIDE_ONE_START)
        val planTwo = ReportLayout.paginate(a4, two, startY = SIDE_TWO_START)
        val planThree = ReportLayout.paginate(a4, three, startY = SIDE_THREE_START)
        val planFour = ReportLayout.paginate(a4, four, startY = SIDE_FOUR_START)

        assertClean("side 1", a4, one, planOne, SIDE_ONE_START)
        assertClean("side 2", a4, two, planTwo, SIDE_TWO_START)
        assertClean("side 3", a4, three, planThree, SIDE_THREE_START)
        assertClean("side 4", a4, four, planFour, SIDE_FOUR_START)

        // Side 1 is short enough to trace by hand, so it is pinned: header to 112, the two fixed
        // note boxes and the flag gap to 365.5, the section label to 383.5, the mood plot and the
        // first scored plot to 681.5, and the second scored plot cannot start there (681.5 + 155 >
        // 796), so it opens the second page with 40pt of slack either side of the break.
        assertEquals("side 1 takes two sheets for three plots", 2, planOne.pages)

        val sides = listOf(
            "side 1" to planOne,
            "side 2" to planTwo,
            "side 3" to planThree,
            "side 4" to planFour,
        )
        assertEveryPageIsAttributed(sides)

        // "SIDE 1 OF 4" counts sides; the footer counts sheets. A dense export runs to more sheets
        // than sides, which is the case that used to be printed as though it had not.
        assertTrue("side 2 cannot hold 19 check-ins and two tables on one sheet", planTwo.pages > 1)
        assertTrue(
            "a realistic export runs past one sheet per side",
            sides.sumOf { it.second.pages } > sides.size,
        )
    }

    @Test
    fun oneInstrumentAndThreeMoveTheBreakPointWithoutOverflowing() {
        val plans = (1..3).map { scored ->
            val blocks = sideOneBlocks(scoredInstruments = scored)
            val plan = ReportLayout.paginate(a4, blocks, startY = SIDE_ONE_START)
            assertClean("side 1 with $scored scored instruments", a4, blocks, plan, SIDE_ONE_START)
            Triple(scored, blocks, plan)
        }

        // More instruments can never mean fewer sheets.
        for (i in 1 until plans.size) {
            assertTrue(
                "${plans[i].first} instruments took fewer pages than ${plans[i - 1].first}",
                plans[i].third.pages >= plans[i - 1].third.pages,
            )
        }

        val (_, blocksOne, planOne) = plans[0]
        val (_, blocksTwo, planTwo) = plans[1]
        val (_, blocksThree, planThree) = plans[2]

        // With one scored instrument the plots all fit and the break falls at the distribution
        // chart. Adding a second pulls it up to the plot that no longer fits.
        assertEquals(listOf("distribution"), planOne.breaksBefore())
        assertNotEquals(
            "adding an instrument must move the break",
            planOne.breaksBefore(),
            planTwo.breaksBefore(),
        )
        assertTrue(
            "the break must move to a plot, not stay at the tail",
            planTwo.breaksBefore().all { it.startsWith("plot:") },
        )
        assertEquals(
            "a third bundled screener breaks at the same plot and pushes more onto the second sheet",
            planTwo.breaksBefore(),
            planThree.breaksBefore(),
        )

        // The break moves earlier in the block sequence as instruments are added, never later.
        val firstBreakIndex = { blocks: List<Block>, plan: SidePlan ->
            plan.breaksBefore().firstOrNull()?.let { id -> blocks.indexOfFirst { it.id == id } } ?: blocks.size
        }
        val indices = listOf(
            firstBreakIndex(blocksOne, planOne),
            firstBreakIndex(blocksTwo, planTwo),
            firstBreakIndex(blocksThree, planThree),
        )
        for (i in 1 until indices.size) {
            assertTrue("the break moved later when content grew: $indices", indices[i] <= indices[i - 1])
        }

        // And the last sheet carries more as instruments are added.
        val onLastSheet = { plan: SidePlan -> plan.placements.count { it.page == plan.pages - 1 } }
        assertTrue(
            "three instruments must leave more on the last sheet than one does",
            onLastSheet(planThree) > onLastSheet(planOne),
        )
    }

    @Test
    fun anEmptyRangeFillsIdentifiedPagesAndOverflowsNothing() {
        // No entries, no instruments, no journal — so no side 3, and three sides in the document.
        val one = sideOneBlocks(scoredInstruments = 0, moodPlotHasDensity = false)
        val two = sideTwoBlocks(
            resultRows = 0,
            entryRows = 0,
            outcomeRows = 0,
            projectSteps = emptyList(),
            activityRows = 0,
            namedGaps = 0,
        )
        val four = sideFourBlocks(promptCount = 0, promptLines = 2, provenanceRows = 1)

        val planOne = ReportLayout.paginate(a4, one, startY = SIDE_ONE_START)
        val planTwo = ReportLayout.paginate(a4, two, startY = SIDE_TWO_START)
        val planFour = ReportLayout.paginate(a4, four, startY = SIDE_FOUR_START)

        assertClean("empty side 1", a4, one, planOne, SIDE_ONE_START)
        assertClean("empty side 2", a4, two, planTwo, SIDE_TWO_START)
        assertClean("empty side 4", a4, four, planFour, SIDE_FOUR_START)

        // An empty range is still three sides of stated absence. Every one of them exists, is a
        // whole sheet, and is identified — an absence that reads as an oversight is the failure
        // this page arrangement exists to avoid.
        assertEquals("side 1 of an empty range is one sheet", 1, planOne.pages)
        assertEquals("side 2 of an empty range is one sheet", 1, planTwo.pages)
        assertEquals("side 4 of an empty range is one sheet", 1, planFour.pages)
        assertEquals(0, planOne.breaksBefore().size)
        assertEquals(0, planTwo.breaksBefore().size)
        assertEquals(0, planFour.breaksBefore().size)

        assertEveryPageIsAttributed(
            listOf("side 1" to planOne, "side 2" to planTwo, "side 4" to planFour),
        )
    }

    @Test
    fun aVeryLargeRangePaginatesRatherThanRunningOff() {
        val two = sideTwoBlocks(
            resultRows = 60,
            entryRows = 200,
            outcomeRows = 20,
            projectSteps = listOf(5, 6, 4),
            activityRows = 12,
            namedGaps = 9,
        )
        val three = sideThreeBlocks(journalBodyLines = List(40) { 6 + it % 9 })

        val planTwo = ReportLayout.paginate(a4, two, startY = SIDE_TWO_START)
        val planThree = ReportLayout.paginate(a4, three, startY = SIDE_THREE_START)

        assertClean("side 2, 200 entries", a4, two, planTwo, SIDE_TWO_START)
        assertClean("side 3, 40 journal entries", a4, three, planThree, SIDE_THREE_START)

        assertTrue("200 entries cannot be one sheet", planTwo.pages > 1)
        assertTrue("40 journal entries cannot be one sheet", planThree.pages > 1)

        // Independent of how the breaks fall: no page can hold more than the content height, so
        // the total advance sets a floor on the sheet count. This catches a paginator that stops
        // breaking and lets the rest run off the page.
        assertAtLeastEnoughPages("side 2, 200 entries", a4, two, planTwo)
        assertAtLeastEnoughPages("side 3, 40 journal entries", a4, three, planThree)

        assertEveryPageIsAttributed(listOf("side 2" to planTwo, "side 3" to planThree))
    }

    // ---------------------------------------------------------------------------------------
    // The property sweep.
    // ---------------------------------------------------------------------------------------

    @Test
    fun everyPlacedBlockStartsWhereItFitsOverManyRandomSides() {
        val rng = Random(20_260_815)
        val papers = listOf(a4, letter)
        var sawFlowingBlock = false
        var sawMultiPage = false
        var sawHeaderStart = false

        repeat(600) { case ->
            val pageH = papers[rng.nextInt(papers.size)]
            // The tallest block any page can hold once its continuation strip is paid. Bounding at
            // this keeps the sweep to inputs a correct paginator can satisfy; the case above it is
            // covered deterministically by aBlockTallerThanAPageIsVisibleRatherThanSilent.
            val tallest = ReportLayout.contentLimit(pageH) - ReportLayout.continuationContentStart()
            val count = rng.nextInt(0, 60)
            val blocks = (0 until count).map { i ->
                val reserve = 4f + rng.nextFloat() * (tallest - 4f)
                // One block in four flows past its reserve, as a table or a paragraph run does.
                val advance = if (rng.nextInt(4) == 0) {
                    sawFlowingBlock = true
                    reserve * (1f + rng.nextFloat())
                } else {
                    reserve
                }
                Block("b$i", reserve, advance)
            }
            val startY = if (rng.nextBoolean()) {
                ReportLayout.MARGIN
            } else {
                sawHeaderStart = true
                ReportLayout.headerContentStart(
                    ReportLayout.MARGIN,
                    metaLines = rng.nextInt(1, 4),
                    hasSubtitle = rng.nextBoolean(),
                )
            }

            val plan = ReportLayout.paginate(pageH, blocks, startY = startY)
            if (plan.pages > 1) sawMultiPage = true
            assertClean("case $case (pageH=$pageH, blocks=$count, startY=$startY)", pageH, blocks, plan, startY)
            assertEveryPageIsAttributed(listOf("case $case" to plan))
        }

        // The sweep is worthless if it never generated the interesting shapes.
        assertTrue("the sweep never produced a flowing block", sawFlowingBlock)
        assertTrue("the sweep never produced a side that broke", sawMultiPage)
        assertTrue("the sweep never started a side under a header", sawHeaderStart)
    }

    // =======================================================================================
    // Checks.
    // =======================================================================================

    /**
     * Every way a plan can be wrong, collected rather than asserted one at a time so a failure
     * names all of them.
     */
    private fun overflows(pageH: Float, blocks: List<Block>, plan: SidePlan): List<String> {
        val byId = blocks.associateBy { it.id }
        val limit = ReportLayout.contentLimit(pageH)
        val strip = ReportLayout.continuationContentStart()
        val out = ArrayList<String>()
        var lastPage = 0
        plan.placements.forEach { p ->
            val b = byId[p.id] ?: run {
                out.add("${p.id} was placed but is not one of the blocks")
                return@forEach
            }
            if (p.top + b.reserve > limit) {
                out.add(
                    "${p.id} starts at ${p.top} and reserves ${b.reserve}, reaching " +
                        "${p.top + b.reserve} past the content limit $limit",
                )
            }
            if (b.advance <= b.reserve && p.bottom > limit) {
                out.add("${p.id} ends at ${p.bottom}, past the content limit $limit")
            }
            if (p.top < ReportLayout.MARGIN) {
                out.add("${p.id} starts at ${p.top}, above the top margin ${ReportLayout.MARGIN}")
            }
            if (p.page > 0 && p.top < strip) {
                out.add(
                    "${p.id} sits at ${p.top} on continuation page ${p.page}, inside the " +
                        "$strip strip that says which side the page belongs to",
                )
            }
            if (p.page < lastPage) out.add("${p.id} runs backwards, to page ${p.page}")
            lastPage = p.page
        }
        return out
    }

    /** The whole contract for one side's plan. */
    private fun assertClean(
        label: String,
        pageH: Float,
        blocks: List<Block>,
        plan: SidePlan,
        startY: Float,
    ) {
        val bad = overflows(pageH, blocks, plan)
        assertTrue("$label: ${bad.joinToString(" | ")}", bad.isEmpty())

        assertEquals("$label: every block must be placed", blocks.size, plan.placements.size)
        assertEquals(
            "$label: the reported sheet count must match the sheets blocks were placed on",
            (plan.placements.maxOfOrNull { it.page } ?: 0) + 1,
            plan.pages,
        )
        assertEquals(
            "$label: one break for every sheet after the first",
            plan.pages - 1,
            plan.breaksBefore().size,
        )
        assertEquals(
            "$label: the plan must leave y where the last block ended",
            (plan.placements.lastOrNull()?.bottom ?: startY).toDouble(),
            plan.endY.toDouble(),
            EPS,
        )
        assertEquals(
            "$label: the sheet count must match an independent walk of the same break rule",
            referencePages(pageH, blocks, startY),
            plan.pages,
        )
    }

    /**
     * The break rule restated from the constants rather than from [ReportLayout.paginate], so the
     * page count has a second opinion. Catches the four ways this arithmetic goes wrong: an
     * off-by-one in the total, breaking on the advance instead of the reserve, resetting to the
     * plain margin instead of below the continuation strip, and not resetting at all.
     */
    private fun referencePages(pageH: Float, blocks: List<Block>, startY: Float): Int {
        val limit = pageH - ReportLayout.FOOTER_SPACE
        val continuation = ReportLayout.MARGIN + ReportLayout.CONTINUATION_H
        var pages = 1
        var y = startY
        blocks.forEach { b ->
            if (y + b.reserve > limit) {
                pages++
                y = continuation
            }
            y += b.advance
        }
        return pages
    }

    /** No sheet can hold more than the content height, whatever the breaks do. */
    private fun assertAtLeastEnoughPages(
        label: String,
        pageH: Float,
        blocks: List<Block>,
        plan: SidePlan,
    ) {
        val total = blocks.sumOf { it.advance.toDouble() }
        val floor = ceil(total / ReportLayout.contentHeight(pageH).toDouble()).toInt()
        assertTrue(
            "$label: ${plan.pages} sheets cannot hold ${total}pt of content",
            plan.pages >= floor,
        )
    }

    /**
     * Every physical sheet in the document belongs to exactly one side. A side always costs at
     * least one sheet, its sheets are numbered contiguously from zero, and nothing is placed on a
     * sheet the side does not claim — so no sheet can reach a reader without a side on it.
     */
    private fun assertEveryPageIsAttributed(sides: List<Pair<String, SidePlan>>) {
        val owner = HashMap<Int, String>()
        var docPage = 0
        sides.forEach { (name, plan) ->
            assertTrue("$name: a side is never zero sheets", plan.pages >= 1)
            for (within in 0 until plan.pages) {
                val previous = owner.put(docPage, "$name sheet $within")
                assertTrue("document sheet $docPage claimed twice, by $previous", previous == null)
                docPage++
            }
            plan.placements.forEach { p ->
                assertTrue(
                    "$name: ${p.id} is on sheet ${p.page}, outside the ${plan.pages} the side claims",
                    p.page >= 0 && p.page < plan.pages,
                )
            }
        }
        assertEquals("every document sheet must have exactly one side", docPage, owner.size)
    }

    // =======================================================================================
    // Fixtures — the renderer's flow, expressed as the blocks each side emits.
    //
    // Line counts stand in for what the renderer's wrap() produces at A4 width; the arithmetic
    // under test is how the reserves compose, not how a string measures.
    // =======================================================================================

    private fun blocks(name: String, body: MutableList<Block>.() -> Unit): List<Block> {
        val out = ArrayList<Block>()
        out.body()
        val ids = out.map { it.id }
        require(ids.size == ids.toSet().size) { "$name has duplicate block ids" }
        return out
    }

    private fun MutableList<Block>.block(id: String, reserve: Float, advance: Float = reserve) {
        add(Block(id, reserve, advance))
    }

    private fun MutableList<Block>.sectionLabel(id: String) {
        block("label:$id", ReportLayout.SECTION_LABEL_RESERVE, ReportLayout.SECTION_LABEL_H)
    }

    private fun MutableList<Block>.noteBox(id: String, vararg parts: Pair<Int, Float>) {
        block("note:$id", ReportLayout.noteBoxReserve(parts.toList()))
    }

    /** A table head reserves itself plus one minimum row, then advances only by its own height. */
    private fun MutableList<Block>.tableHead(id: String) {
        block("head:$id", ReportLayout.TABLE_START_RESERVE, ReportLayout.TABLE_HEAD_H)
    }

    private fun MutableList<Block>.tableRows(id: String, count: Int, rowH: Float) {
        repeat(count) { block("row:$id:$it", rowH) }
    }

    private fun MutableList<Block>.plot(id: String, hasDirection: Boolean, hasDensity: Boolean) {
        block("plot:$id", ReportLayout.instrumentPlotReserve(hasDirection, hasDensity))
    }

    private fun MutableList<Block>.paragraph(id: String, lines: Int, textSize: Float) {
        repeat(lines) { block("para:$id:$it", ReportLayout.lineHeight(textSize)) }
    }

    private fun sideOneBlocks(
        scoredInstruments: Int,
        moodPlotHasDensity: Boolean = true,
    ): List<Block> = blocks("side 1") {
        noteBox("what-this-is", 1 to 8.5f, 5 to 8.5f)
        noteBox("flag", 1 to 8f, 2 to 10f, 3 to 8.5f, 3 to 8f)
        // The gap that closes the flag zone. Nothing above it competes with the flag.
        block("flag-zone-gap", 6f)
        sectionLabel("shared-self-checks")
        plot("mood", hasDirection = false, hasDensity = moodPlotHasDensity)
        repeat(scoredInstruments) { plot("scored$it", hasDirection = true, hasDensity = true) }
        block("completion", ReportLayout.COMPLETION_SUMMARY_RESERVE)
        block("distribution", ReportLayout.DISTRIBUTION_RESERVE)
    }

    private fun sideTwoBlocks(
        resultRows: Int,
        entryRows: Int,
        outcomeRows: Int,
        projectSteps: List<Int>,
        activityRows: Int,
        namedGaps: Int,
    ): List<Block> = blocks("side 2") {
        if (resultRows > 0) {
            sectionLabel("results")
            tableHead("results")
            tableRows("results", resultRows, ReportLayout.TABLE_MIN_ROW_H)
        }
        sectionLabel("entries")
        tableHead("entries")
        if (entryRows == 0) {
            block("no-entries", 18f)
        } else {
            // A check-in row carries a wrapped note and its activity tags, so it is taller than
            // the minimum row a table guarantees.
            tableRows("entries", entryRows, 29f)
        }
        noteBox("note-field", 3 to 8f)

        if (outcomeRows > 0) {
            sectionLabel("suggested")
            tableHead("suggested")
            tableRows("suggested", outcomeRows, ReportLayout.TABLE_MIN_ROW_H)
            noteBox("declines-shown", 2 to 8f)
        }
        if (projectSteps.isNotEmpty()) {
            sectionLabel("projects")
            projectSteps.forEachIndexed { i, steps ->
                block("project:$i", ReportLayout.PROJECT_HEAD_RESERVE)
                repeat(steps) { block("project:$i:step:$it", ReportLayout.PROJECT_STEP_H) }
                block("project:$i:rule", ReportLayout.PROJECT_SEPARATOR_H)
            }
        }
        if (outcomeRows == 0 && projectSteps.isEmpty()) {
            noteBox("not-recorded-yet", 2 to 8f)
        }
        if (activityRows > 0) {
            sectionLabel("activities")
            repeat(minOf(activityRows, 12)) { block("activity:$it", ReportLayout.ACTIVITY_ROW_H) }
        }
        sectionLabel("coverage")
        paragraph("coverage-lead", 2, 9f)
        if (namedGaps == 0) {
            paragraph("coverage-none", 1, 9f)
        } else {
            paragraph("coverage-count", 1, 9f)
            repeat(namedGaps) { block("gap:$it", ReportLayout.COVERAGE_GAP_ROW_H) }
        }
    }

    private fun sideThreeBlocks(journalBodyLines: List<Int>): List<Block> = blocks("side 3") {
        noteBox("journal-opt-in", 4 to 8.5f)
        journalBodyLines.forEachIndexed { i, lines ->
            block("journal:$i", ReportLayout.JOURNAL_ENTRY_RESERVE)
            paragraph("journal:$i:body", lines, 9f)
            block("journal:$i:rule", ReportLayout.JOURNAL_SEPARATOR_H)
        }
    }

    private fun sideFourBlocks(
        promptCount: Int,
        promptLines: Int,
        provenanceRows: Int,
    ): List<Block> = blocks("side 4") {
        sectionLabel("worth-asking-about")
        if (promptCount == 0) {
            paragraph("prompts-none", 2, 9f)
        } else {
            repeat(promptCount) { i ->
                block("prompt:$i:bullet", ReportLayout.lineHeight(9.5f) + 6f)
                paragraph("prompt:$i", promptLines, 9.5f)
                block("prompt:$i:gap", 5f)
            }
        }
        noteBox("prompts-caveat", 3 to 8f)

        sectionLabel("the-tools")
        tableHead("provenance")
        repeat(provenanceRows) { block("provenance:$it", 28f) }
        noteBox("provenance-note", 2 to 8f, 3 to 8f)
        noteBox("not-in-report", 4 to 8f)

        // Verification is anchored to the foot of the page rather than flowed, but it breaks by
        // the same rule, so it costs the same arithmetic.
        block("verification", 28f + 90f + 8f)
    }

    private companion object {
        const val EPS = 0.001

        /** Side 1: no subtitle unless a name was entered, three meta lines down the right. */
        val SIDE_ONE_START: Float =
            ReportLayout.headerContentStart(ReportLayout.MARGIN, metaLines = 3, hasSubtitle = true)
        val SIDE_TWO_START: Float =
            ReportLayout.headerContentStart(ReportLayout.MARGIN, metaLines = 2, hasSubtitle = true)
        val SIDE_THREE_START: Float =
            ReportLayout.headerContentStart(ReportLayout.MARGIN, metaLines = 2, hasSubtitle = true)
        val SIDE_FOUR_START: Float =
            ReportLayout.headerContentStart(ReportLayout.MARGIN, metaLines = 2, hasSubtitle = true)
    }
}
