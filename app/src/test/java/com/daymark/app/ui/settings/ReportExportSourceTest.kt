package com.daymark.app.ui.settings

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.stringLiteralsIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Settings says about the PDF report.
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
    }

    private val source: String = repoFile(REL).readText()

    /** The report row: after the `ListItem(` that opens it, up to the click that opens the dialog. */
    private fun rowOf(text: String): String =
        text.substringBefore("Modifier.clickable { showPdfDialog = true }", "")
            .substringAfterLast("ListItem(", "")

    private val row: String = rowOf(source)

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
}
