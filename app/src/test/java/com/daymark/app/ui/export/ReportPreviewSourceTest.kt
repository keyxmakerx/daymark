package com.daymark.app.ui.export

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import com.daymark.app.ui.functionBodyRange
import com.daymark.app.ui.replaceOnce
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report a person checks is the report that is saved (#198), and the journal reaches it only
 * through the picker (#303).
 *
 * Read as text through [codeOnly], because this module cannot run a view model that needs a
 * PdfRenderer. Each check is first shown a planted example (CLAUDE.md §5). Runs through
 * `tools/jvm-source-tests.sh`.
 */
class ReportPreviewSourceTest {

    private companion object {
        const val VM = "app/src/main/java/com/daymark/app/ui/export/ReportPreviewViewModel.kt"
        const val FLOW = "app/src/main/java/com/daymark/app/ui/export/ReportExportScreen.kt"
    }

    private val vm = repoFile(VM).readText()
    private val flow = repoFile(FLOW).readText()

    /** Save copies the previewed file; it never builds or renders a second report. */
    private fun saveRebuilds(source: String): Boolean {
        val code = codeOnly(source)
        val body = functionBodyRange(code, "saveTo") ?: error("saveTo is gone")
        return Regex("""\b(?:generate|build)\s*\(""").containsMatchIn(code.substring(body))
    }

    /** The journal fields are set by the picker's outcome and nowhere else in the flow. */
    private fun journalSetOutsideThePicker(source: String): Boolean =
        Regex("""\b(?:includeInTheirWords|includedJournalEntryIds|includeAllJournalInRange)\s*=""")
            .containsMatchIn(codeOnly(source))

    @Test
    fun save_copies_the_file_that_was_shown() {
        assertNotNull(functionBodyRange(codeOnly(vm), "preview"))
        assertTrue("the preview no longer generates the report", codeOnly(vm).contains("pdfReportGenerator.generate("))
        val planted = replaceOnce(
            vm,
            "val source = file ?: error(\"The report is no longer ready\")",
            "val source = file ?: error(\"The report is no longer ready\"); " +
                "pdfReportGenerator.generate(reportDataBuilder.build(built!!, 0L), built!!, java.io.ByteArrayOutputStream())",
        )
        assertTrue("the check cannot see a second build in saveTo", saveRebuilds(planted))
        assertFalse("saveTo builds a second report instead of copying the one shown", saveRebuilds(vm))
    }

    @Test
    fun the_flow_takes_the_journal_only_from_the_picker() {
        assertTrue("the flow no longer applies the picker's outcome", codeOnly(flow).contains("withJournalChoice("))
        val planted = replaceOnce(
            flow,
            "val chosen = writing?.let { options.withJournalChoice(it) } ?: options",
            "val chosen = writing?.let { options.withJournalChoice(it) } ?: options.copy(includeAllJournalInRange = true)",
        )
        assertTrue("the check cannot see a journal field set in the flow", journalSetOutsideThePicker(planted))
        assertFalse("the flow sets a journal field itself", journalSetOutsideThePicker(flow))
    }
}
