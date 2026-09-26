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
 * the one decided, word for word, and that nothing the report prints mentions a streak.
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

        /** Side 4's list of what the document does not hold, as decided for #304. */
        const val NOT_IN_REPORT =
            "Not in this report, and not obtainable from it. Location — the app collects none. " +
                "Anything written inside an exercise — those fields are excluded from sharing by " +
                "construction, not by preference. Any journal entry not on side 3."

        /** The sentence #304 removes. It is false: the app shows no streaks anywhere (#107). */
        const val FALSE_SENTENCE = "The app still shows them on the person's own screens."

        /** The clause it closed. Saying the report carries no streaks is still a mention of one. */
        const val STREAK_CLAUSE = "Streak counts — this report carries none"

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

    /** The source a reader of the page is affected by: comments go, so a rule can name what it forbids. */
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
}
