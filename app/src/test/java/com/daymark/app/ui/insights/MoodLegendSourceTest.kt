package com.daymark.app.ui.insights

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.withoutComments
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mood legend is drawn with the same mark as the dots it keys, and the week card says what it
 * shows (#427).
 *
 * A mood colour can be faint on the sheet, so a dot carries a ring (`MoodDot`, #412). A legend swatch
 * with no ring is the key to a mark it does not look like. Read as text, comments removed; each check
 * is shown the old shape planted.
 */
class MoodLegendSourceTest {

    private companion object {
        const val INSIGHTS = "app/src/main/java/com/daymark/app/ui/insights/InsightsScreen.kt"
        val LEGEND = Regex("""private fun MoodLegend\([^)]*\)\s*\{""")
    }

    private val code = withoutComments(repoFile(INSIGHTS).readText())

    private fun legendBody(source: String): String {
        val start = LEGEND.find(source) ?: error("MoodLegend is not in the Insights screen")
        var depth = 0
        val open = start.range.last
        for (i in open until source.length) {
            if (source[i] == '{') depth++
            if (source[i] == '}' && --depth == 0) return source.substring(open, i)
        }
        return source.substring(open)
    }

    private fun swatchIsADot(source: String) = Regex("""\bMoodDot\(""").containsMatchIn(legendBody(source))

    @Test
    fun `the legend draws each mood with MoodDot`() {
        assertTrue("the legend's swatches are not MoodDot, so they have no ring (#427)", swatchIsADot(code))
        val planted = code.replace(Regex("""\bMoodDot\(mood\.level\)"""), "Box(Modifier.size(11.dp).background(c))")
        assertTrue("nothing was planted", planted != code)
        assertTrue("the check cannot see a legend with a plain square", !swatchIsADot(planted))
    }

    @Test
    fun `the week card is titled for the seven days it shows`() {
        assertTrue("the week card is titled for a calendar week it does not show", !code.contains("\"This week\""))
        assertTrue("the week card no longer says what it shows", code.contains("\"Last seven days\""))
    }
}
