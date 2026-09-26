package com.daymark.app.ui.settings

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.stringLiteralsIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Settings says about the PDF report: the row that offers it, and the dialog that makes it.
 *
 * ## Why a source test
 *
 * The same trade as [LockDisclosureSourceTest], stated the same way: the claim is a handful of
 * strings in Compose, and this module has no Robolectric and no Compose test runner, so a
 * composition test would be written, never run, and quietly believed. Reading the source is a weaker
 * claim than "a person sees this on screen"; what is rendered is checked by hand on a phone.
 *
 * ## Why slices and no comment stripper
 *
 * `SettingsScreen` passes a wildcard MIME type to a file picker — a slash and a star inside a
 * string — and the shared regex stripper reads those two characters as a block comment and deletes
 * up to the next comment close, which in this file would take the report row with it. So this reads
 * fixed slices between anchors, as [LockDisclosureSourceTest] does, and first asserts that each slice
 * was found and is no bigger than the thing it names.
 *
 * These run on a plain JVM in seconds through `tools/jvm-source-tests.sh`, and in CI with the rest.
 */
class ReportExportSourceTest {

    private companion object {
        const val REL = "app/src/main/java/com/daymark/app/ui/settings/SettingsScreen.kt"

        /** The row that opens the report dialog (#158). */
        const val ROW_HEADLINE = "Export a PDF report"
        const val ROW_SUPPORTING = "A printable copy to hand to a clinician. Not encrypted."

        /** What the row said before, used only to show each absence check a planted example. */
        const val OLD_HEADLINE = "Export PDF for therapist"
        const val OLD_SUPPORTING = "A printable mood report with an authenticity stamp"

        /** The dialog prints the report's own constant for what a report is (#336). */
        const val SENTENCE_REF = "com.daymark.app.export.Copy.WHAT_A_REPORT_IS"

        /** The sentence itself, which this file must never hold a copy of. */
        const val REPORT_SENTENCE = "A report is a copy. Once handed over, it cannot be taken back."

        const val NOTES_LABEL = "Include check-in notes"
        const val OLD_NOTES_LABEL = "Include notes"
        const val NOTES_START_OFF = "var notes by remember { mutableStateOf(false) }"
    }

    private val source: String = repoFile(REL).readText()

    /** The report row: after the `ListItem(` that opens it, up to the click that opens the dialog. */
    private fun rowOf(text: String): String =
        text.substringBefore("Modifier.clickable { showPdfDialog = true }", "")
            .substringAfterLast("ListItem(", "")

    private val row: String = rowOf(source)

    /**
     * The report dialog, from its declaration to the next function, with its line comments removed.
     * Line comments only: the slice holds no string with two slashes in it, and it is the block
     * stripper, not this one, that the wildcard MIME type breaks.
     */
    private fun dialogOf(text: String): String =
        text.substringAfter("private fun PdfOptionsDialog(", "")
            .substringBefore("private fun ToggleRow(", "")
            .replace(Regex("//[^\n]*"), " ")

    private val dialog: String = dialogOf(source)

    /** Where the dialog's body opens: the first thing in it must be the report's own sentence. */
    private val sentenceFirst =
        Regex("""text\s*=\s*\{\s*Column\s*(\([^{]*\))?\s*\{\s*Text\(\s*com\.daymark\.app\.export\.Copy\.WHAT_A_REPORT_IS\b""")

    private fun reportSentenceIn(text: String): String? =
        stringLiteralsIn(text).firstOrNull { it.contains("A report is a copy") || it.contains("cannot be taken back") }

    /** The first string in [text] that names a therapist, in any case. */
    private fun therapistIn(text: String): String? =
        stringLiteralsIn(text).firstOrNull { it.lowercase().contains("therapist") }

    /** The first string in [text] that claims an authenticity stamp. */
    private fun stampIn(text: String): String? =
        stringLiteralsIn(text).firstOrNull { it.lowercase().contains("authenticity") || it.lowercase().contains("stamp") }

    @Test
    fun `the source and the report row were found`() {
        // Guards everything below: a slice that failed to match is "", which passes every absence
        // check vacuously — the shape of a guard that reports green forever.
        assertTrue("SettingsScreen.kt is implausibly short", source.length > 5000)
        assertTrue("the report row, or the click that opens the dialog, has moved", row.isNotEmpty())
        assertTrue("the report row slice swallowed more than one row", row.length < 400)
        assertTrue("the report row slice holds no headline", row.contains("headlineContent"))
        assertTrue("the report row slice holds no strings", stringLiteralsIn(row).isNotEmpty())
    }

    /** #158: the one word for the person's professional is "clinician", and the file is a plain copy. */
    @Test
    fun `the report row names a clinician and says the file is not encrypted`() {
        assertEquals(
            "the report row does not say exactly the decided headline and line under it",
            listOf(ROW_HEADLINE, ROW_SUPPORTING),
            stringLiteralsIn(row),
        )
        assertTrue(
            "the headline is no longer the row's headline",
            row.contains("headlineContent = { Text(\"$ROW_HEADLINE\") }"),
        )
        assertTrue(
            "the line under it is no longer the row's supporting text",
            row.contains("supportingContent = { Text(\"$ROW_SUPPORTING\") }"),
        )
    }

    @Test
    fun `settings says clinician, never therapist`() {
        val planted = source.replace(ROW_HEADLINE, OLD_HEADLINE)
        assertNotEquals("the old headline was not planted", source, planted)
        assertNotNull("the check cannot see \"$OLD_HEADLINE\" planted in the row", therapistIn(planted))

        val found = therapistIn(source)
        assertNull(
            "Settings says \"$found\". A therapist, a doctor and a psychiatrist are one role to " +
                "Daymark, and the word a person reads for it is \"clinician\" (#158).",
            found,
        )
    }

    /** The hash is printed on the report but nothing checks it yet (#311), so the row claims nothing about it. */
    @Test
    fun `the report row claims no authenticity stamp`() {
        val planted = rowOf(source.replace(ROW_SUPPORTING, OLD_SUPPORTING))
        assertNotEquals("the old line was not planted", row, planted)
        assertNotNull("the check cannot see \"$OLD_SUPPORTING\" planted in the row", stampIn(planted))

        val found = stampIn(row)
        assertNull("the report row says \"$found\"", found)
    }

    @Test
    fun `the report dialog was found`() {
        assertTrue("the report dialog is gone or renamed", dialog.isNotEmpty())
        assertTrue("the dialog slice ran past the dialog", dialog.length < 4000)
        assertTrue("the dialog slice holds no dialog", dialog.contains("AlertDialog("))
        assertTrue("the dialog no longer builds the report's options", dialog.contains("PdfExportOptions("))
    }

    /** #336: what a report is, said first, directly under the title and before any choice. */
    @Test
    fun `the dialog says what a report is before anything else`() {
        // The check is first shown the dialog opening on the date range, as it used to.
        val planted = source.replace(SENTENCE_REF, "\"Date range\"")
        assertNotEquals("the sentence was not taken out", source, planted)
        assertFalse("the check passes a dialog that opens on the date range", sentenceFirst.containsMatchIn(dialogOf(planted)))

        assertTrue(
            "the dialog's title is no longer \"Export PDF report\"",
            dialog.contains("title = { Text(\"Export PDF report\") }"),
        )
        assertTrue(
            "the dialog no longer opens with what a report is. It is said under the title, before " +
                "any choice about what goes in the report (#336).",
            sentenceFirst.containsMatchIn(dialog),
        )
    }

    /**
     * The sentence made a tall dialog taller, and a Material dialog cuts off what does not fit, so the
     * body scrolls: on a short screen the last switch is reached rather than lost.
     */
    @Test
    fun `the dialog body scrolls`() {
        val scrolling = Regex("""text\s*=\s*\{\s*Column\(\s*Modifier\.verticalScroll\(\s*rememberScrollState\(\)\s*\)\s*\)\s*\{""")
        val planted = dialogOf(source.replace("Column(Modifier.verticalScroll(rememberScrollState())) {", "Column {"))
        assertNotEquals("the fixed-height body was not planted", dialog, planted)
        assertFalse("the check passes a body that cannot scroll", scrolling.containsMatchIn(planted))

        assertTrue("the report dialog's body no longer scrolls", scrolling.containsMatchIn(dialog))
    }

    /** One sentence, read from the report's fixed copy: this file never holds a second copy of it. */
    @Test
    fun `the dialog prints the report's own sentence, not a copy of it`() {
        val planted = source.replace(SENTENCE_REF, "\"$REPORT_SENTENCE\"")
        assertNotEquals("the copy was not planted", source, planted)
        assertNotNull("the check cannot see the sentence written into Settings", reportSentenceIn(planted))

        val found = reportSentenceIn(source)
        assertNull(
            "Settings holds its own copy of the report sentence (\"$found\"). It prints " +
                "Copy.WHAT_A_REPORT_IS, so the dialog and the report can never say two things.",
            found,
        )
    }

    /**
     * #336: a check-in note is the person's own words, so the switch starts off. The model's own
     * default is pinned beside the report, in `export/ReportCopySourceTest`.
     */
    @Test
    fun `check-in notes start off in the dialog, and the switch reaches the report`() {
        val planted = dialogOf(source.replace(NOTES_START_OFF, NOTES_START_OFF.replace("false", "true")))
        assertNotEquals("the old default was not planted", dialog, planted)
        assertFalse("the check accepts notes on by default", planted.contains(NOTES_START_OFF))

        assertTrue("check-in notes no longer start off in the report dialog (#336)", dialog.contains(NOTES_START_OFF))
        assertTrue("the notes switch no longer reaches the report's options", dialog.contains("includeNotes = notes,"))
    }

    @Test
    fun `the notes switch says check-in notes`() {
        val planted = source.replace("\"$NOTES_LABEL\"", "\"$OLD_NOTES_LABEL\"")
        assertNotEquals("the old label was not planted", source, planted)
        assertTrue("the check cannot see the old label", OLD_NOTES_LABEL in stringLiteralsIn(dialogOf(planted)))

        assertTrue(
            "the notes switch is no longer labelled \"$NOTES_LABEL\"",
            dialog.contains("ToggleRow(\"$NOTES_LABEL\", notes)"),
        )
        assertFalse("the dialog still says \"$OLD_NOTES_LABEL\"", OLD_NOTES_LABEL in stringLiteralsIn(dialog))
    }
}
