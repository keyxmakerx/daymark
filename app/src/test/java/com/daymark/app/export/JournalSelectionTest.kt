package com.daymark.app.export

import com.daymark.app.ui.export.JournalSelection
import com.daymark.app.ui.export.JournalSelection.Candidate
import com.daymark.app.ui.export.JournalSelection.Form
import com.daymark.app.ui.export.JournalSelection.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The per-entry journal picker's decisions.
 *
 * The subject lives in `ui/export/JournalSelection.kt`; the test lives here because this is where
 * the report's tests are, and what is under test is what the report ends up saying about the
 * person's consent.
 *
 * ### What is being protected
 *
 * Side 3 is the person's private writing handed to a clinician, so the failure that matters is not
 * a crash — it is a report that discloses something the person did not tick, or that prints a
 * sentence describing a stronger consent than the one they gave. Every sweep below therefore ends
 * by *simulating the report* from the resolved options ([printedJournal], [sideThreePrints],
 * [wholeRangeSentence] restate the three production lines) and checking the pages against the
 * choice, rather than checking the option flags against themselves.
 *
 * ### Non-vacuity
 *
 * Each absence assertion is preceded by a construction that makes the detector fire:
 *
 *  - [theReportSimulationCanSeeADisclosureThePersonDidNotAgreeTo] hand-builds a conflated
 *    [Outcome] — per-entry form with the blanket flag left on — and asserts the mirror prints
 *    entries that were never ticked. Every "nothing unchosen was printed" assertion elsewhere leans
 *    on that mirror, and would be worthless if it could not see this.
 *  - [tickingEveryEntryIsStillPerEntryConsent] asserts the same window under the blanket switch
 *    resolves differently, so "it stayed per-entry" is a distinction and not a constant.
 *  - [theDefaultIsNothing] asserts one tick flips the result, so "it defaulted to nothing" is not
 *    true of every input.
 *  - [aPreviewIdentifiesAnEntryThatOpensWithALongWord] states what the plain last-space rule would
 *    have produced and asserts that is refused.
 *  - Both sweeps carry coverage guards and fail if they never generated the shape they exist for.
 */
class JournalSelectionTest {

    // Five entries, two of them written in the same millisecond so the tie-break is exercised.
    private val pool = listOf(
        Candidate(1L, 100L, "First", "the first body"),
        Candidate(2L, 200L, "Second", "the second body"),
        Candidate(3L, 300L, "Third", "the third body"),
        Candidate(4L, 300L, "Fourth", "the fourth body"),
        Candidate(5L, 400L, "Fifth", "the fifth body"),
    )

    private val window = JournalSelection.inRange(pool, 100L, 300L)

    // -------------------------------------------------------------------------------------------
    // The window.
    // -------------------------------------------------------------------------------------------

    @Test
    fun theRangeIsInclusiveAtBothEndsAndOrderedNewestFirst() {
        // Inclusive both ends, because ReportDataBuilder's filter is `dateTime in from..to`. A
        // picker that excluded an endpoint would hide an entry the export would then print.
        assertEquals(listOf(4L, 3L, 2L, 1L), window.map { it.id })
        assertTrue("the lower endpoint is in the window", window.any { it.id == 1L })
        assertTrue("the upper endpoint is in the window", window.any { it.id == 3L })
        assertFalse("an entry past the upper endpoint is not", window.any { it.id == 5L })

        // The tie is broken, not left to the order the DAO happened to return.
        assertEquals(
            "same-millisecond entries must come back in a fixed order",
            window.map { it.id },
            JournalSelection.inRange(pool.reversed(), 100L, 300L).map { it.id },
        )
        assertEquals(0, JournalSelection.inRange(pool, 301L, 399L).size)
    }

    // -------------------------------------------------------------------------------------------
    // The three states.
    // -------------------------------------------------------------------------------------------

    @Test
    fun theDefaultIsNothing() {
        val o = JournalSelection.resolve(window, ticked = emptySet(), wholeRange = false)
        assertEquals(Form.NOTHING, o.form)
        assertFalse("side 3 must not be switched on by an absent choice", o.includeInTheirWords)
        assertFalse("and the blanket flag must stay down", o.includeAllInRange)
        assertEquals(emptySet<Long>(), o.includedIds)
        assertEquals(0, o.includedCount)
        assertEquals("the denominator is still the whole range", 4, o.availableCount)

        // Side 3 does not appear at all — which is the first of the three states.
        val printed = printedJournal(window, o)
        assertTrue(printed.isEmpty())
        assertFalse(sideThreePrints(printed, o))

        // The value a picker opens in must be the same "nothing" — a starting state that was not
        // this is how a screen ends up pre-ticked without anyone deciding it should be.
        val opening = JournalSelection.NOTHING_CHOSEN
        assertEquals(Form.NOTHING, opening.form)
        assertFalse(opening.includeInTheirWords)
        assertFalse(opening.includeAllInRange)
        assertEquals(emptySet<Long>(), opening.includedIds)
        assertEquals(0, opening.includedCount)

        // Non-vacuity: the same call with one tick must differ, or "it defaulted to nothing" is
        // only a restatement of what this function returns for every input.
        assertNotEquals(
            Form.NOTHING,
            JournalSelection.resolve(window, ticked = setOf(2L), wholeRange = false).form,
        )
    }

    @Test
    fun tickedEntriesArePerEntryConsentAndPrintExactlyThemselves() {
        val o = JournalSelection.resolve(window, ticked = setOf(1L, 3L), wholeRange = false)
        assertEquals(Form.PER_ENTRY, o.form)
        assertTrue(o.includeInTheirWords)
        assertFalse("per-entry consent must never raise the blanket flag", o.includeAllInRange)
        assertEquals(setOf(1L, 3L), o.includedIds)
        assertEquals(2, o.includedCount)
        assertEquals(4, o.availableCount)

        val printed = printedJournal(window, o)
        assertEquals("exactly the ticked entries print", listOf(3L, 1L), printed.map { it.id })
        assertTrue(sideThreePrints(printed, o))
        assertFalse(
            "the whole-range sentence must not print over a per-entry choice",
            wholeRangeSentence(printed, o),
        )
    }

    @Test
    fun tickingEveryEntryIsStillPerEntryConsent() {
        // The printed pages are identical to the blanket switch's, and the consent is not. The
        // report says which of the two happened, so promoting this to WHOLE_RANGE would put a
        // sentence on the page claiming a decision the person never made.
        val all = window.map { it.id }.toSet()
        val ticked = JournalSelection.resolve(window, ticked = all, wholeRange = false)
        assertEquals(Form.PER_ENTRY, ticked.form)
        assertFalse(ticked.includeAllInRange)
        assertEquals(all, ticked.includedIds)

        val blanket = JournalSelection.resolve(window, ticked = emptySet(), wholeRange = true)
        assertEquals(Form.WHOLE_RANGE, blanket.form)

        // Same pages, different sentence — which is the whole point, and proves the assertion
        // above is a distinction rather than a constant.
        assertEquals(
            "both routes print the same entries",
            printedJournal(window, ticked).map { it.id },
            printedJournal(window, blanket).map { it.id },
        )
        assertNotEquals(
            "and must not print the same sentence",
            wholeRangeSentence(printedJournal(window, ticked), ticked),
            wholeRangeSentence(printedJournal(window, blanket), blanket),
        )
    }

    @Test
    fun theBlanketSwitchCarriesNoPerEntryIds() {
        // The person ticked one entry, then took the whole-range action instead. The tick must not
        // ride along into the options: under the blanket switch the per-entry set is not what
        // carried the consent, and leaving a stale subset there would misdescribe the choice to
        // anything that inspected the options.
        val o = JournalSelection.resolve(window, ticked = setOf(2L), wholeRange = true)
        assertEquals(Form.WHOLE_RANGE, o.form)
        assertTrue(o.includeInTheirWords)
        assertTrue(o.includeAllInRange)
        assertEquals(emptySet<Long>(), o.includedIds)
        assertEquals("everything in range prints", 4, o.includedCount)
        assertEquals(4, o.availableCount)

        val printed = printedJournal(window, o)
        assertEquals(window.map { it.id }, printed.map { it.id })
        assertTrue(wholeRangeSentence(printed, o))
    }

    @Test
    fun ticksOutsideTheChosenRangeAreDroppedRatherThanRidingAlong() {
        // Ticked over a wide range, then narrowed. The count the person is shown and the count that
        // prints have to be the same number.
        val narrowed = JournalSelection.inRange(pool, 250L, 350L)
        val o = JournalSelection.resolve(narrowed, ticked = setOf(1L, 2L, 3L), wholeRange = false)
        assertEquals(Form.PER_ENTRY, o.form)
        assertEquals(setOf(3L), o.includedIds)
        assertEquals(1, o.includedCount)
        assertEquals("the denominator follows the narrowed range too", 2, o.availableCount)
        assertEquals(listOf(3L), printedJournal(narrowed, o).map { it.id })

        // And when every tick falls outside, the answer is nothing — not "the ones I remember".
        val allStale = JournalSelection.resolve(narrowed, ticked = setOf(1L, 2L), wholeRange = false)
        assertEquals(Form.NOTHING, allStale.form)
        assertFalse(allStale.includeInTheirWords)
        assertTrue(printedJournal(narrowed, allStale).isEmpty())
    }

    @Test
    fun selectAllOverAnEmptyRangeIncludesNothing() {
        // "Include everything in this range" over a range holding nothing has included nothing.
        // Claiming otherwise would set the whole-range flag on a report with no side 3 to carry it.
        val empty = JournalSelection.inRange(pool, 301L, 399L)
        val o = JournalSelection.resolve(empty, ticked = setOf(1L, 2L), wholeRange = true)
        assertEquals(Form.NOTHING, o.form)
        assertFalse(o.includeInTheirWords)
        assertFalse(o.includeAllInRange)
        assertEquals(0, o.availableCount)
        assertFalse(sideThreePrints(printedJournal(empty, o), o))

        // Non-vacuity: the same flag over a non-empty range does resolve to WHOLE_RANGE.
        assertEquals(
            Form.WHOLE_RANGE,
            JournalSelection.resolve(window, ticked = emptySet(), wholeRange = true).form,
        )
    }

    // -------------------------------------------------------------------------------------------
    // The mirror this file's absence assertions depend on.
    // -------------------------------------------------------------------------------------------

    @Test
    fun theReportSimulationCanSeeADisclosureThePersonDidNotAgreeTo() {
        // A hand-built conflation: per-entry form, one entry ticked, and the blanket flag left up.
        // No path in JournalSelection produces this — that is the point. If the mirror below cannot
        // see it, every "only the ticked entries printed" assertion in this file is worthless.
        val conflated = Outcome(
            form = Form.PER_ENTRY,
            includeInTheirWords = true,
            includedIds = setOf(1L),
            includeAllInRange = true,
            includedCount = 1,
            availableCount = 4,
        )
        val printed = printedJournal(window, conflated)
        assertEquals("the blanket flag prints the whole window", 4, printed.size)
        assertTrue(
            "the mirror must see entries printed that were never ticked",
            printed.any { it.id !in conflated.includedIds },
        )

        // And the opposite shape — the side switched off with ids still populated — must print
        // nothing, or the mirror would over-report and every sweep would fail for the wrong reason.
        val switchedOff = conflated.copy(includeInTheirWords = false)
        assertTrue(printedJournal(window, switchedOff).isEmpty())
        assertFalse(sideThreePrints(printedJournal(window, switchedOff), switchedOff))
    }

    @Test
    fun everyResolutionProducesAReportThatDescribesIt() {
        val rng = Random(20_260_815)
        var sawNothing = false
        var sawPerEntry = false
        var sawWholeRange = false
        var sawStaleTicks = false

        repeat(4_000) { case ->
            val size = rng.nextInt(0, 8)
            val all = (0 until size).map { i ->
                Candidate(i.toLong(), rng.nextLong(0L, 500L), "t$i", "b$i")
            }
            var lo = rng.nextLong(0L, 500L)
            var hi = rng.nextLong(0L, 500L)
            if (lo > hi) { val t = lo; lo = hi; hi = t }
            val win = JournalSelection.inRange(all, lo, hi)
            val ticked = all.map { it.id }.filter { rng.nextBoolean() }.toSet()
            val blanket = rng.nextInt(4) == 0

            if (ticked.any { id -> win.none { it.id == id } }) sawStaleTicks = true

            val o = JournalSelection.resolve(win, ticked, blanket)
            val printed = printedJournal(win, o)
            val where = "case $case (window=${win.size}, ticked=${ticked.size}, blanket=$blanket)"

            when (o.form) {
                Form.NOTHING -> sawNothing = true
                Form.PER_ENTRY -> sawPerEntry = true
                Form.WHOLE_RANGE -> sawWholeRange = true
            }

            // Nothing is ever added to the person's choice.
            assertTrue(
                "$where: an id the person did not tick reached the options",
                o.includedIds.all { it in ticked },
            )
            assertTrue(
                "$where: an id outside the window reached the options",
                o.includedIds.all { id -> win.any { it.id == id } },
            )

            // Side 3 appears exactly when something was chosen, and never otherwise.
            assertEquals(
                "$where: side 3's presence disagrees with the choice",
                o.form != Form.NOTHING,
                sideThreePrints(printed, o),
            )

            // The sentence names the consent that was actually given.
            assertEquals(
                "$where: the opt-in sentence disagrees with the choice",
                o.form == Form.WHOLE_RANGE,
                wholeRangeSentence(printed, o),
            )

            // The counts the person read are the counts that print.
            assertEquals("$where: printed count", o.includedCount, printed.size)
            assertEquals("$where: denominator", o.availableCount, win.size)
            assertTrue("$where: more printed than the range holds", printed.size <= win.size)

            if (o.form == Form.PER_ENTRY) {
                assertEquals(
                    "$where: the printed entries are not the ticked ones",
                    o.includedIds,
                    printed.map { it.id }.toSet(),
                )
            }
            if (o.form == Form.WHOLE_RANGE) {
                assertEquals(
                    "$where: the blanket switch must print the whole window",
                    win.map { it.id },
                    printed.map { it.id },
                )
            }

            // The summary is one of the three and never says "every" about a subset.
            val summary = JournalSelection.summary(o)
            assertTrue("$where: empty summary", summary.isNotEmpty())
            if (o.form == Form.PER_ENTRY && o.includedCount < o.availableCount) {
                assertFalse(
                    "$where: a partial selection must not be described as everything",
                    summary.contains("Every"),
                )
            }
        }

        assertTrue("the sweep never produced an empty choice", sawNothing)
        assertTrue("the sweep never produced a per-entry choice", sawPerEntry)
        assertTrue("the sweep never produced a whole-range choice", sawWholeRange)
        assertTrue("the sweep never produced a tick left over from a wider range", sawStaleTicks)
    }

    // -------------------------------------------------------------------------------------------
    // Previews — the recognition aid a person consents against.
    // -------------------------------------------------------------------------------------------

    @Test
    fun anEntryWithNoTextSaysSoRatherThanShowingNothing() {
        assertEquals(JournalSelection.NO_BODY, JournalSelection.preview("", 40))
        assertEquals(JournalSelection.NO_BODY, JournalSelection.preview("   \n\t \n ", 40))
        // Non-vacuity: a body with one character in it is not reported as textless.
        assertEquals("x", JournalSelection.preview("  x  ", 40))

        // A blank title falls back too, so no row is unidentifiable.
        val rows = JournalSelection.rows(
            listOf(Candidate(9L, 1L, "  ", "written")),
            ticked = emptySet(),
        )
        assertEquals(JournalSelection.UNTITLED, rows.single().title)
        assertEquals("written", rows.single().preview)
        assertFalse("rows default to unticked", rows.single().selected)
    }

    @Test
    fun aPreviewIsOneLineAndKeepsTheWriting() {
        assertEquals(
            "newlines are flattened so a row is one line",
            "one two three",
            JournalSelection.preview("one\ntwo\n\n   three", 40),
        )
        assertEquals(
            "a body that fits is reproduced exactly, not trimmed to look tidy",
            "exactly twenty chars",
            JournalSelection.preview("exactly twenty chars", 20),
        )
        assertEquals(
            "a longer body is cut at the last word boundary",
            "aaaa bbbb cccc dddd…",
            JournalSelection.preview("aaaa bbbb cccc dddd ee", 20),
        )
    }

    @Test
    fun aPreviewIdentifiesAnEntryThatOpensWithALongWord() {
        // The plain "cut at the last space" rule would preview this as "I…", which identifies
        // nothing — and a person cannot consent to disclosing writing they cannot recognise. Stated
        // as the output the superseded rule would have produced, so the fallback cannot be dropped
        // without this failing.
        val body = "I " + "u".repeat(60)
        val naive = "I…"
        val actual = JournalSelection.preview(body, 20)
        assertNotEquals("the last-space rule must not win here", naive, actual)
        assertEquals("I uuuuuuuuuuuuuuuuuu…", actual)
        assertTrue(
            "the preview must fill most of the room it was given",
            actual.length - 1 >= 20 / 2,
        )
    }

    @Test
    fun aPreviewIsAlwaysAPrefixOfTheWritingAndNeverAnEdit() {
        val rng = Random(11_071_984)
        val alphabet = "abcdefg \n\t.,"
        var sawUntouched = false
        var sawWordCut = false
        var sawHardCut = false
        var sawTextless = false

        repeat(6_000) { case ->
            val body = buildString {
                repeat(rng.nextInt(0, 400)) { append(alphabet[rng.nextInt(alphabet.length)]) }
            }
            val max = rng.nextInt(1, 200)
            val p = JournalSelection.preview(body, max)
            val where = "case $case (max=$max)"

            assertTrue("$where: a preview is never empty", p.isNotEmpty())
            assertFalse("$where: a preview is never multi-line", p.contains('\n') || p.contains('\t'))
            assertFalse("$where: a preview never carries a double space", p.contains("  "))
            assertEquals("$where: a preview never ends in whitespace", p.trimEnd(), p)
            assertFalse("$where: a preview never starts with a space", p.startsWith(" "))

            val collapsed = body.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            when {
                collapsed.isEmpty() -> {
                    sawTextless = true
                    assertEquals("$where", JournalSelection.NO_BODY, p)
                }

                collapsed.length <= max -> {
                    sawUntouched = true
                    assertEquals("$where: a short body must be reproduced whole", collapsed, p)
                }

                else -> {
                    assertTrue("$where: a cut preview must be marked", p.endsWith("…"))
                    val head = p.dropLast(1)
                    assertTrue(
                        "$where: the preview is not a prefix of the writing — <$head>",
                        collapsed.startsWith(head),
                    )
                    assertTrue("$where: overran the room it was given", p.length <= max + 1)
                    assertTrue(
                        "$where: threw away more than half the room (head=${head.length})",
                        head.length * 2 >= max - 1,
                    )
                    if (head.length == max) sawHardCut = true else sawWordCut = true
                }
            }
        }

        assertTrue("the sweep never left a body untouched", sawUntouched)
        assertTrue("the sweep never cut at a word boundary", sawWordCut)
        assertTrue("the sweep never had to cut mid-word", sawHardCut)
        assertTrue("the sweep never produced a textless body", sawTextless)
    }

    @Test
    fun theSummarySaysWhichOfTheThreeThingsHappened() {
        val nothing = JournalSelection.summary(
            JournalSelection.resolve(window, emptySet(), wholeRange = false),
        )
        val perEntry = JournalSelection.summary(
            JournalSelection.resolve(window, setOf(1L, 2L), wholeRange = false),
        )
        val whole = JournalSelection.summary(
            JournalSelection.resolve(window, emptySet(), wholeRange = true),
        )
        assertEquals(
            "the three states must read as three different sentences",
            3,
            setOf(nothing, perEntry, whole).size,
        )
        assertTrue("the per-entry sentence carries the count", perEntry.contains("2 entries of 4"))
        assertTrue("the whole-range sentence says it is everything", whole.startsWith("Every entry"))

        // An empty range is stated as an empty range, not as a choice the person failed to make.
        val emptyRange = JournalSelection.summary(
            JournalSelection.resolve(JournalSelection.inRange(pool, 301L, 399L), emptySet(), false),
        )
        assertNotEquals(nothing, emptyRange)
        assertTrue(emptyRange.contains("no journal entries in this range"))

        assertEquals("1 entry", JournalSelection.entries(1))
        assertEquals("0 entries", JournalSelection.entries(0))
        assertEquals("2 entries", JournalSelection.entries(2))
    }

    // ===========================================================================================
    // The production lines this file simulates, restated so a change to either side shows up here.
    // ===========================================================================================

    /** `ReportDataBuilder.build`'s side-3 filter, restated over the same window. */
    private fun printedJournal(window: List<Candidate>, o: Outcome): List<Candidate> =
        if (!o.includeInTheirWords) {
            emptyList()
        } else {
            window.filter { o.includeAllInRange || it.id in o.includedIds }
        }

    /** `PdfReportGenerator.render`'s gate for whether side 3 exists in the document at all. */
    private fun sideThreePrints(printed: List<Candidate>, o: Outcome): Boolean =
        o.includeInTheirWords && printed.isNotEmpty()

    /**
     * `ReportData.journalIncludedAsWholeRange` — which of the two opt-in sentences side 3 carries.
     * True means `Copy.JOURNAL_OPT_IN_ALL`, false means `Copy.JOURNAL_OPT_IN_PER_ENTRY`.
     */
    private fun wholeRangeSentence(printed: List<Candidate>, o: Outcome): Boolean =
        o.includeAllInRange && printed.isNotEmpty()
}
