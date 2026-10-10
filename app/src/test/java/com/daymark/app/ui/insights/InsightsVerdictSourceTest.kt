package com.daymark.app.ui.insights

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import com.daymark.app.ui.functionBodyRange
import com.daymark.app.ui.plantAfter
import com.daymark.app.ui.replaceOnce
import com.daymark.app.ui.stringLiteralsIn
import com.daymark.app.ui.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Insights tab describes what was logged and never marks it (#203): no average mood as a
 * headline, no period set against the one before it, no grid of how consistently someone logs, no
 * summary that opens with a mark, and headings over the "what goes with your mood" lists that claim
 * association rather than cause, drawn in ink rather than the Rad and Awful colours (#354, #358).
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
        )

        /** The views that went, by the names that drew them. */
        val GONE_CALLS = Regex("""\b(?:ConsistencyHeatmap|PeriodCompareCard|periodCompare|weekCompare|monthCompare|yearCompare|stats\.averageMood)\b""")

        val MOOD_COLOUR = Regex("""\b(?:moodColors|LocalMoodColors|MoodColors)\b""")
    }

    private fun findings(source: String): List<String> {
        val out = mutableListOf<String>()
        val strings = stringLiteralsIn(withoutComments(source)).map { it.lowercase() }
        for (phrase in VERDICT_COPY) if (strings.any { phrase in it }) out += "copy: $phrase"
        val code = codeOnly(source)
        GONE_CALLS.findAll(code).forEach { out += "call: ${it.value}" }
        val body = functionBodyRange(code, "FactorList")
        if (body == null) {
            out += "FactorList: not found"
        } else if (MOOD_COLOUR.containsMatchIn(code.substring(body))) {
            out += "FactorList: mood colour"
        }
        Regex("""\bFactorList\s*\(([^\n]*)""").findAll(code)
            .filter { MOOD_COLOUR.containsMatchIn(it.groupValues[1]) }
            .forEach { _ -> out += "FactorList call: mood colour" }
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
            "Entries", "Days with an entry", "Mood distribution", "Logged alongside higher moods",
            "Logged alongside lower moods", "By day of week", "By time of day",
        )) {
            assertTrue("\"$kept\" is gone", strings.any { kept in it })
        }
    }

    @Test
    fun each_check_sees_a_planted_example() {
        val anchor = "        // --- Correlations & patterns (associations, not causes) ---\n"
        val plants = mapOf(
            "copy: avg mood" to "        StatCard(\"Avg mood\", \"3.4\", Modifier)\n",
            "copy: logging consistency" to "        SectionCard(\"Logging consistency\") {}\n",
            "copy:  vs last" to "        SectionCard(\"This month vs last\") {}\n",
            "call: ConsistencyHeatmap" to "        ConsistencyHeatmap(emptyMap())\n",
            "call: weekCompare" to "        val c = extras.weekCompare\n",
            "call: stats.averageMood" to "        val a = stats.averageMood\n",
        )
        for ((expected, plant) in plants) {
            val found = findings(plantAfter(real, anchor, plant))
            assertTrue("$expected not seen in $found", expected in found)
        }
        val heading = findings(
            replaceOnce(real, "FactorList(\"Logged alongside higher moods\"", "FactorList(\"Lifts you up\""),
        )
        assertTrue("copy: lifts you up" in heading)
        val coloured = findings(
            replaceOnce(
                real,
                "FactorList(\"Logged alongside lower moods\", extras.topDown)",
                "FactorList(\"Logged alongside lower moods\", extras.topDown, MaterialTheme.moodColors.forLevel(1))",
            ),
        )
        assertTrue("FactorList call: mood colour" in coloured)
        val inBody = findings(
            replaceOnce(
                real,
                "Text(title, style = MaterialTheme.typography.labelLarge)",
                "Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.moodColors.forLevel(5))",
            ),
        )
        assertTrue("FactorList: mood colour" in inBody)
    }
}
