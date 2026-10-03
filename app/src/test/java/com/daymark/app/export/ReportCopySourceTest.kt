package com.daymark.app.export

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.stringLiteralsIn
import com.daymark.app.ui.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the PDF report says, read from its source.
 *
 * ## Why a source test
 *
 * The report is drawn on an `android.graphics.Canvas`, and this module's unit tests have no device
 * and no Robolectric, so the source is the only thing a test here can read. That is a weaker claim
 * than "a clinician sees this on the page", and the gap is stated rather than papered over: what is
 * rendered is checked by hand on a phone. What CAN be checked here is that each fixed sentence is
 * the one decided, word for word; that nothing the report prints mentions a streak; and that the
 * check-in notes switch reaches side 2 through the few lines that decide its note column and its
 * note box. Those last checks pin the shape of the source that produces the page, not the page.
 *
 * Every absence check below is first shown a planted example (`CLAUDE.md` §5): a check that cannot
 * see the thing it forbids proves only that it is blind.
 *
 * These run on a plain JVM in seconds through `tools/jvm-source-tests.sh`, and in CI with the rest.
 */
class ReportCopySourceTest {

    private companion object {
        const val GENERATOR = "app/src/main/java/com/daymark/app/export/PdfReportGenerator.kt"

        /** The prompts side 4 prints verbatim, so their words are the report's words too. */
        const val PROMPTS = "app/src/main/java/com/daymark/app/stats/DiscussionPrompts.kt"

        const val OPTIONS = "app/src/main/java/com/daymark/app/export/PdfExportOptions.kt"

        /** Side 4's list of what the document does not hold, as decided for #304. */
        const val NOT_IN_REPORT =
            "Not in this report, and not obtainable from it. Location — the app collects none. " +
                "Anything written inside an exercise — those fields are excluded from sharing by " +
                "construction, not by preference. Any journal entry not on side 3."

        /** The sentence #304 removes. It is false: the app shows no streaks anywhere (#107). */
        const val FALSE_SENTENCE = "The app still shows them on the person's own screens."

        /** The clause it closed. Saying the report carries no streaks is still a mention of one. */
        const val STREAK_CLAUSE = "Streak counts — this report carries none"

        /** What a report is (#336). The export dialog prints this constant; it is not written twice. */
        const val WHAT_A_REPORT_IS = "A report is a copy. Once handed over, it cannot be taken back."

        /** Side 2's sentence when check-in notes are off, in the form side 4 uses for its prompts. */
        const val NOTES_OFF = "Check-in notes were switched off for this export."

        /** The paragraph as it was printed before #304, laid out exactly as it was in the source. */
        const val OLD_NOT_IN_REPORT_SOURCE = """
    const val NOT_IN_REPORT =
        "Not in this report, and not obtainable from it. Location — the app collects none. Anything " +
            "written inside an exercise — those fields are excluded from sharing by construction, " +
            "not by preference. Any journal entry not on side 3. Streak counts — this report " +
            "carries none, since a streak reports adherence to the app rather than anything about " +
            "the person. The app still shows them on the person's own screens."
"""
    }

    private val generatorRaw: String = repoFile(GENERATOR).readText()
    private val promptsRaw: String = repoFile(PROMPTS).readText()

    /** Comments removed, so a rule can be stated in the very words the code may not contain. */
    private val generator: String = withoutComments(generatorRaw)
    private val prompts: String = withoutComments(promptsRaw)

    /** The value of `const val [name]` in [source], its `+`-joined pieces read as the one sentence. */
    private fun constant(source: String, name: String): String? =
        stringLiteralsIn(source.substringAfter("const val $name =", "")).firstOrNull()

    /** The first line of [code] that mentions a streak in any case, or null. */
    private fun streakLine(code: String): String? =
        code.lines().firstOrNull { it.lowercase().contains("streak") }?.trim()

    /** Every string literal in [code] that contains [sentence], with split literals joined first. */
    private fun literalsHolding(code: String, sentence: String): List<String> =
        stringLiteralsIn(code).filter { it.contains(sentence) }

    /**
     * The body of the first block-bodied function whose declaration starts with [signature], braces
     * balanced, or null. The renderer's templates keep their braces paired, so counting is enough.
     */
    private fun bodyOf(code: String, signature: String): String? {
        val at = code.indexOf(signature)
        if (at < 0) return null
        val open = code.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        return null
    }

    private fun count(code: String, text: String): Int = code.split(text).size - 1

    /**
     * Everything the renderer's source must hold for side 2 to print no note column and no dash
     * when check-in notes are off, and the tables as they were when they are on. Empty means it
     * holds. A detector rather than a list of asserts, so it can be shown planted breakages.
     */
    private fun noteColumnBreaches(code: String): List<String> {
        val breaches = mutableListOf<String>()
        if (count(code, "\"THEIR NOTE\"") != 1) {
            breaches += "the note column's head is written somewhere other than noteHead"
        }
        if (!Regex("""if\s*\(\s*notes\s*\)\s*arrayOf\(\s*x\s+to\s+"THEIR NOTE"\s*\)\s*else\s+emptyArray\(\)""")
                .containsMatchIn(code)
        ) {
            breaches += "noteHead no longer leaves the column out when notes are off"
        }
        if (count(code, "listOf(\"—\")") != 1) {
            breaches += "a dash stands in for a note somewhere other than noteText"
        }
        if (!Regex("""!notes\s*->\s*emptyList\(\)\s*note\.isNotBlank\(\)\s*->\s*wrap\(note,\s*p,\s*width\)\s*else\s*->\s*listOf\("—"\)""")
                .containsMatchIn(code)
        ) {
            breaches += "noteText no longer draws nothing, not a dash, when notes are off"
        }
        for (table in listOf("resultsTable", "entriesTable")) {
            val body = bodyOf(code, "private fun $table(")
            if (body == null) {
                breaches += "$table is gone"
                continue
            }
            if (!body.contains("*noteHead(notes, colNote)")) breaches += "$table does not take its note head from noteHead"
            if (!body.contains("noteText(notes, ")) breaches += "$table does not take its note lines from noteText"
        }
        val side = bodyOf(code, "fun sideTwoDetail(").orEmpty()
        if (!Regex("""val\s+notes\s*=\s*options\.includeNotes""").containsMatchIn(side)) {
            breaches += "side 2's switch is not the person's includeNotes choice"
        }
        if (!side.contains("resultsTable(data, notes)") || !side.contains("entriesTable(data, notes)")) {
            breaches += "side 2 does not hand both tables the notes switch"
        }
        if (!code.contains("ctx.sideTwoDetail(data, options, sides)")) {
            breaches += "side 2 is not handed the export options"
        }
        val beforeTags = code.substringBefore("\"ACTIVITY TAGS\"", "")
        if (count(code, "\"ACTIVITY TAGS\"") != 1 || !beforeTags.takeLast(200).contains("if (!notes && ")) {
            breaches += "the activity-tags head is not limited to notes off"
        }
        return breaches
    }

    /** What side 2's note box must hold: one choice between two sentences, each printed only there. */
    private fun noteBoxBreaches(code: String): List<String> {
        val breaches = mutableListOf<String>()
        val side = bodyOf(code, "fun sideTwoDetail(").orEmpty()
        if (!Regex("""if\s*\(\s*notes\s*\)\s*Copy\.NOTE_FIELD\s+else\s+Copy\.NOTES_OFF""").containsMatchIn(side)) {
            breaches += "side 2 no longer chooses between explaining a note and saying notes were off"
        }
        if (!side.contains("noteBox(listOf(paint(8f, SOFT) to noteSentence))")) {
            breaches += "side 2 no longer prints the sentence it chose"
        }
        if (count(code, "Copy.NOTE_FIELD") != 1) breaches += "the note explanation is printed somewhere else too"
        if (count(code, "Copy.NOTES_OFF") != 1) breaches += "the switched-off sentence is printed somewhere else too"
        return breaches
    }

    @Test
    fun `both sources were found, and the stripper neither ate them nor did nothing`() {
        assertTrue("PdfReportGenerator.kt is implausibly short", generatorRaw.length > 20_000)
        assertTrue("DiscussionPrompts.kt is implausibly short", promptsRaw.length > 5_000)
        // Did nothing? The renderer's KDoc states the rule in the very word the code may not hold.
        assertTrue(
            "the renderer's KDoc no longer says \"No streaks.\", so the stripper has nothing to prove " +
                "itself against",
            generatorRaw.contains("No streaks."),
        )
        assertFalse("the comment stripper left the renderer's KDoc behind", generator.contains("No streaks."))
        // Ate it?
        assertTrue("the stripper ate the renderer", generator.contains("class PdfReportGenerator"))
        assertTrue("the stripper ate the fixed copy", generator.contains("object Copy"))
        assertTrue("the stripper ate the prompts", prompts.contains("object DiscussionPrompts"))
        assertTrue("no strings were found, so every check below is vacuous", stringLiteralsIn(generator).size > 50)
    }

    /**
     * #304. Streaks were removed from the whole app (#107), and a document a clinician reads must not
     * describe a count the person never sees. The scan is over code, not only over string literals, so
     * a field or a template that carries a streak is caught as surely as a sentence is.
     */
    @Test
    fun `nothing the report prints mentions a streak`() {
        val renderer = streakLine(generator)
        assertNull("the report's renderer mentions a streak: $renderer", renderer)
        val prompt = streakLine(prompts)
        assertNull("the discussion prompts the report prints mention a streak: $prompt", prompt)
    }

    @Test
    fun `the streak detector sees a planted streak`() {
        for ((name, code) in listOf("renderer" to generator, "prompts" to prompts)) {
            val planted = "$code\nprivate const val PLANTED = \"A 6-day Streak of check-ins.\"\n"
            assertNotEquals("the $name plant changed nothing", code, planted)
            assertNotNull("the streak detector cannot see a streak planted in the $name", streakLine(planted))
            // And the stripper does not hide one: it removes comments, never strings.
            assertNotNull(
                "the stripper removed a planted string from the $name",
                streakLine(withoutComments(planted)),
            )
        }
        // The paragraph #304 replaced is the example that matters most.
        assertNotNull(
            "the streak detector cannot see the paragraph #304 removed",
            streakLine(generator + OLD_NOT_IN_REPORT_SOURCE),
        )
    }

    /** #304, as decided: three items, all true, and nothing that describes a thing the app lacks. */
    @Test
    fun `side 4 lists what the report does not hold in the decided words`() {
        assertEquals(
            "NOT_IN_REPORT is not the decided paragraph",
            NOT_IN_REPORT,
            constant(generator, "NOT_IN_REPORT"),
        )
    }

    @Test
    fun `the false sentence and the streak clause are gone from the report`() {
        // The checks first see the old paragraph planted, laid out as it was, split across lines.
        val planted = generator + OLD_NOT_IN_REPORT_SOURCE
        assertTrue("the planted paragraph was not planted", planted.length > generator.length)
        assertTrue(
            "the sentence check cannot see the old paragraph's last sentence",
            literalsHolding(planted, FALSE_SENTENCE).isNotEmpty(),
        )
        assertTrue(
            "the clause check cannot see the old paragraph's streak clause",
            literalsHolding(planted, STREAK_CLAUSE).isNotEmpty(),
        )

        assertTrue(
            "the report still says \"$FALSE_SENTENCE\" — the app shows no streaks anywhere (#107)",
            literalsHolding(generator, FALSE_SENTENCE).isEmpty(),
        )
        assertTrue(
            "the report still carries the streak clause (#304)",
            literalsHolding(generator, STREAK_CLAUSE).isEmpty(),
        )
    }

    /** #336: one sentence says what a report is, kept with the report's fixed copy. */
    @Test
    fun `a report says what it is in the report's own fixed copy`() {
        assertEquals(
            "WHAT_A_REPORT_IS is not the decided sentence",
            WHAT_A_REPORT_IS,
            constant(generator, "WHAT_A_REPORT_IS"),
        )
        assertTrue(
            "the sentence is no longer part of the report's fixed copy",
            bodyOf(generator, "object Copy")?.contains("const val WHAT_A_REPORT_IS") == true,
        )
        assertFalse(
            "the fixed copy is private again, so the export dialog cannot print the sentence from it",
            generator.contains("private object Copy"),
        )
    }

    /** #336: a check-in note is the person's own words, and goes into a report only when switched on. */
    @Test
    fun `check-in notes are off unless the person switches them on`() {
        val options = withoutComments(repoFile(OPTIONS).readText())
        val offByDefault = Regex("""val\s+includeNotes\s*:\s*Boolean\s*=\s*false\s*,""")
        // The check is first shown the default as it was, and must refuse it.
        val planted = options.replace(Regex("""(val\s+includeNotes\s*:\s*Boolean\s*=\s*)false"""), "\$1true")
        assertNotEquals("the old default was not planted", options, planted)
        assertFalse("the check accepts notes on by default", offByDefault.containsMatchIn(planted))

        assertTrue(
            "PdfExportOptions.includeNotes no longer defaults to false, so a report built without " +
                "naming it prints the person's notes",
            offByDefault.containsMatchIn(options),
        )
    }

    @Test
    fun `side 2 and both of its tables were found whole`() {
        val side = bodyOf(generator, "fun sideTwoDetail(")
        assertNotNull("sideTwoDetail is gone", side)
        assertTrue("sideTwoDetail was cut short", side!!.contains("coverage(data)"))
        assertTrue(
            "resultsTable was not found whole",
            bodyOf(generator, "private fun resultsTable(")?.contains("Self-check results") == true,
        )
        assertTrue(
            "entriesTable was not found whole",
            bodyOf(generator, "private fun entriesTable(")?.contains("Daily check-ins") == true,
        )
    }

    /**
     * #336: with notes off, side 2 says so in the box that would have explained a note, in the form
     * side 4 uses for its prompts. With notes on it explains a note, as it always has.
     */
    @Test
    fun `with notes off side 2 says so where it would have explained a note`() {
        assertEquals("NOTES_OFF is not the decided sentence", NOTES_OFF, constant(generator, "NOTES_OFF"))

        val choice = "if (notes) Copy.NOTE_FIELD else Copy.NOTES_OFF"
        for ((what, planted) in listOf(
            "the explanation printed whatever the switch" to generator.replace(choice, "Copy.NOTE_FIELD"),
            "the two sentences swapped" to generator.replace(choice, "if (notes) Copy.NOTES_OFF else Copy.NOTE_FIELD"),
            "the explanation printed a second time" to generator.replace(
                "suggestions(data)",
                "noteBox(listOf(paint(8f, SOFT) to Copy.NOTE_FIELD))\n        suggestions(data)",
            ),
        )) {
            assertNotEquals("\"$what\" was not planted", generator, planted)
            assertTrue("the check cannot see $what", noteBoxBreaches(planted).isNotEmpty())
        }

        assertEquals("side 2's note box", emptyList<String>(), noteBoxBreaches(generator))
    }

    /**
     * #336: with notes off neither table has a note column — not a column of dashes, which would draw
     * the person's choice as a gap on every row. The entries table keeps the activity tags that shared
     * that column, under their own head, because switching notes off leaves out notes only.
     */
    @Test
    fun `with notes off neither table has a note column, and nothing stands in for one`() {
        for ((what, planted) in listOf(
            "a note head written straight into a table" to
                generator.replaceFirst("*noteHead(notes, colNote),", "colNote to \"THEIR NOTE\","),
            "the column kept whatever the switch" to
                generator.replace("if (notes) arrayOf(x to \"THEIR NOTE\") else emptyArray()", "arrayOf(x to \"THEIR NOTE\")"),
            "a dash drawn with notes off" to generator.replace("!notes -> emptyList()", "!notes -> listOf(\"—\")"),
            "a table told notes are always on" to generator.replace("entriesTable(data, notes)", "entriesTable(data, true)"),
            "the tags head drawn with notes on" to generator.replace("if (!notes && ", "if ("),
        )) {
            assertNotEquals("\"$what\" was not planted", generator, planted)
            assertTrue("the check cannot see $what", noteColumnBreaches(planted).isNotEmpty())
        }

        assertEquals("side 2's note column", emptyList<String>(), noteColumnBreaches(generator))
    }
}
