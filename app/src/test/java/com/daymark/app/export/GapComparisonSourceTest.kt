package com.daymark.app.export

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import com.daymark.app.ui.replaceOnce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report's gap comparison (#520) prints only when the person leaves it on, is offered as a
 * switch on the preview, and is computed from entry times alone. The line itself is
 * `stats/PriorGapTest`'s. Read as text, because the renderer draws on an Android Canvas. Each
 * check is first shown a planted breach (CLAUDE.md §5). Runs through `tools/jvm-source-tests.sh`.
 */
class GapComparisonSourceTest {

    private companion object {
        const val GENERATOR = "app/src/main/java/com/daymark/app/export/PdfReportGenerator.kt"
        const val DATA = "app/src/main/java/com/daymark/app/export/ReportData.kt"
        const val SCREEN = "app/src/main/java/com/daymark/app/ui/export/ReportExportScreen.kt"
    }

    @Test
    fun the_line_prints_only_with_the_switch_on() {
        val code = codeOnly(repoFile(GENERATOR).readText())
        fun gated(c: String): Boolean {
            val uses = Regex("""PriorGap\.line\(""").findAll(c).toList()
            val gate = c.indexOf("if (options.includeGapComparison && prior != null) {")
            return uses.size == 1 && gate >= 0 && uses.single().range.first in gate..(gate + 160)
        }
        assertTrue(gated(code))
        assertFalse(gated(replaceOnce(code, "if (options.includeGapComparison && prior != null) {", "if (prior != null) {")))
    }

    /** Which days had an entry, and when the first was: no mood, no note, no row is read for it. */
    @Test
    fun the_comparison_reads_entry_times_only() {
        val data = codeOnly(repoFile(DATA).readText())
        val body = data.substringAfter("private suspend fun priorLongestGap(").substringBefore("private fun gap(")
        assertTrue(body.contains("entryDao.dateTimesBetween(") && body.contains("entryDao.firstDateTime()"))
        val rows = Regex("""entryDao\.getBetween|entryDao\.getAll|moodLevel|\.note\b""")
        assertFalse(rows.containsMatchIn(body))
        assertTrue(rows.containsMatchIn(body + "entryDao.getBetween(a, b)"))
        val dao = repoFile("app/src/main/java/com/daymark/app/data/dao/EntryDao.kt").readText()
        assertTrue(dao.contains("@Query(\"SELECT dateTime FROM mood_entries WHERE dateTime BETWEEN :from AND :to\")"))
    }

    @Test
    fun the_preview_offers_the_switch_and_rebuilds_from_it() {
        val screen = codeOnly(repoFile(SCREEN).readText())
        assertTrue(screen.contains("val shown = options.copy(includeGapComparison = compareGaps)"))
        assertTrue(screen.contains("LaunchedEffect(shown) { viewModel.preview(shown) }"))
        assertEquals(1, Regex("""onCheckedChange = \{ compareGaps = it \}""").findAll(screen).count())
        assertTrue(screen.contains("if (s.gapComparable) {"))
    }
}
