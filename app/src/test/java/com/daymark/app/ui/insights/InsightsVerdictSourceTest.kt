package com.daymark.app.ui.insights

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import com.daymark.app.ui.plantAfter
import com.daymark.app.ui.stringLiteralsIn
import com.daymark.app.ui.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Insights tab describes what was logged and never marks it (#203): no average mood as a
 * headline, no period set against the one before it, no grid of how consistently someone logs, no
 * summary that opens with a mark (#354, #358), and no list ranking activities or trackers by the moods
 * logged beside them, with or without an `r` (§D6, #483). The per-activity table stays, by name.
 *
 * Read as text, because this module cannot compose the screen: the copy through the string literals
 * of the comment-free source, so a comment may name what is gone; the calls through [codeOnly], so a
 * string cannot fake one. Every check is first shown a planted example in a copy of the real file
 * (CLAUDE.md §5). What a person sees is checked by hand. Runs through `tools/jvm-source-tests.sh`.
 */
class InsightsVerdictSourceTest {

    private companion object {
        const val INSIGHTS = "app/src/main/java/com/daymark/app/ui/insights/InsightsScreen.kt"

        /** Copy that marks the person's data, lower-cased. */
        val VERDICT_COPY = listOf(
            "avg mood", "lifts you up", "weighs you down", "in review", "logging consistency", " vs last",
            "logged alongside", "what goes with your mood", "r=",
        )

        /** The views that went, by the names that drew them. */
        val GONE_CALLS = Regex("""\b(?:ConsistencyHeatmap|PeriodCompareCard|periodCompare|weekCompare|monthCompare|yearCompare|stats\.averageMood|FactorList|topUp|topDown|trackerCorrelations)\b""")
    }

    private fun findings(source: String): List<String> {
        val out = mutableListOf<String>()
        val strings = stringLiteralsIn(withoutComments(source)).map { it.lowercase() }
        for (phrase in VERDICT_COPY) if (strings.any { phrase in it }) out += "copy: $phrase"
        val code = codeOnly(source)
        GONE_CALLS.findAll(code).forEach { out += "call: ${it.value}" }
        return out
    }

    private val real = repoFile(INSIGHTS).readText()

    @Test
    fun insights_marks_nothing_it_shows() {
        assertEquals(emptyList<String>(), findings(real))
    }

    @Test
    fun the_kept_views_are_still_there() {
        val strings = stringLiteralsIn(withoutComments(real))
        for (kept in listOf(
            "Entries", "Days with an entry", "Mood distribution", "Average mood by activity",
            "By day of week", "By time of day",
        )) {
            assertTrue("\"$kept\" is gone", strings.any { kept in it })
        }
    }

    @Test
    fun each_check_sees_a_planted_example() {
        val anchor = "        // --- Patterns (association, not cause) ---\n"
        val plants = mapOf(
            "copy: avg mood" to "        StatCard(\"Avg mood\", \"3.4\", Modifier)\n",
            "copy: logging consistency" to "        SectionCard(\"Logging consistency\") {}\n",
            "copy:  vs last" to "        SectionCard(\"This month vs last\") {}\n",
            "call: ConsistencyHeatmap" to "        ConsistencyHeatmap(emptyMap())\n",
            "call: weekCompare" to "        val c = extras.weekCompare\n",
            "call: stats.averageMood" to "        val a = stats.averageMood\n",
            "copy: what goes with your mood" to "        SectionCard(\"What goes with your mood\") {}\n",
            "copy: logged alongside" to "        Text(\"Logged alongside higher moods\")\n",
            "copy: r=" to "        Text(\"higher mood  r=+0.32\")\n",
            "call: FactorList" to "        FactorList(\"Up\", rows)\n",
            "call: topUp" to "        val up = extras.topUp\n",
            "call: trackerCorrelations" to "        val t = extras.trackerCorrelations\n",
        )
        for ((expected, plant) in plants) {
            val found = findings(plantAfter(real, anchor, plant))
            assertTrue("$expected not seen in $found", expected in found)
        }
    }
}
