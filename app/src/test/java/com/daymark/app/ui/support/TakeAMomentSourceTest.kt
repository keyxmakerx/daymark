package com.daymark.app.ui.support

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import com.daymark.app.ui.firstPhraseIn
import com.daymark.app.ui.plantAfter
import com.daymark.app.ui.replaceOnce
import com.daymark.app.ui.stringLiteralsIn
import com.daymark.app.ui.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Take a moment" and its calm activities keep the screen's own rules (`docs/FEATURES.md` §7.2).
 *
 *  - The menu's order: "Not right now" first, the crisis resource last (#166, #352).
 *  - Nothing repeats an animation, on the menu or in an activity (rule 3).
 *  - The calm activities are listed only behind their switch, and record nothing (#170).
 *  - Their words never score, cheer or say how the person feels (`CLAUDE.md` §4).
 *
 * Every absence check is first shown a planted example, because a check that cannot see one
 * proves only that it is blind (`CLAUDE.md` §5). This reads source; what a person sees is checked by
 * hand and by `/walkthrough`.
 */
class TakeAMomentSourceTest {

    private companion object {
        const val DIR = "app/src/main/java/com/daymark/app/ui/support"
        const val MENU = "$DIR/SupportScreen.kt"
        val CALM = listOf("$DIR/CalmActivityFrame.kt", "$DIR/CalmBlocksScreen.kt", "$DIR/SandRakeScreen.kt")

        val ENDLESS = listOf("rememberInfiniteTransition", "infiniteRepeatable")

        /** Anything that turns a calm activity into something to win, keep up or be cheered for. */
        val SCORING = listOf(
            "score", "point", "level", "streak", "best", "record", "congrat", "well done", "great",
            "nice", "win", "lose", "game over", "try again", "complete", "finished", "unlock",
        )

        /** Anything that says how the person feels, which no activity can know. */
        val FEELING = listOf("you feel", "feel better", "calmer", "relaxed", "better now", "helped")

        /** What would let a visit be recorded. */
        val RECORDING = listOf("Repository", "hiltViewModel", "ViewModel", "Dao")
    }

    private fun read(path: String) = repoFile(path).readText()

    private fun endlessIn(source: String): String? {
        val code = codeOnly(source)
        return ENDLESS.firstOrNull { code.contains(it) }
    }

    @Test
    fun `nothing on the menu or in an activity repeats an animation`() {
        val menu = read(MENU)
        // Planted control: an endless bob is seen.
        assertNotNull(
            endlessIn(plantAfter(menu, "    var shown by remember { mutableStateOf(false) }\n", "    val t = rememberInfiniteTransition(label = \"x\")\n")),
        )
        // And a mention in a comment is not, so the check reads code and not the rule's own words.
        assertNull(endlessIn("// no rememberInfiniteTransition here\nval x = 1"))
        assertNull(endlessIn(menu))
        CALM.forEach { assertNull(it, endlessIn(read(it))) }
    }

    /** Where in the menu's code each option is composed, by its title. */
    private fun order(source: String): List<String> {
        val code = withoutComments(source)
        val body = code.substring(code.indexOf("fun SupportScreen("), code.indexOf("private fun supportClick("))
        val marks = listOf(
            "Not right now" to body.indexOf("MenuOption(\"Not right now\""),
            "engine" to body.indexOf("mainOptions.forEach"),
            "small thing" to body.indexOf("SmallThingOption("),
            "calm" to body.indexOf("MenuOption(\"Falling blocks\""),
            "safety plan" to body.indexOf("MenuOption(\"My safety plan\""),
            "crisis" to body.indexOf("MenuOption(it.title, it.body, onCrisis)"),
        )
        marks.forEach { (name, at) -> assertTrue("$name is composed", at >= 0) }
        return marks.sortedBy { it.second }.map { it.first }
    }

    @Test
    fun `Not right now comes first and the crisis resource last`() {
        val expected = listOf("Not right now", "engine", "small thing", "calm", "safety plan", "crisis")
        val menu = read(MENU)
        assertEquals(expected, order(menu))
        // Planted control: moving "Not right now" to the end of the menu is seen.
        val line = "            MenuOption(\"Not right now\", \"That's okay too.\", onClose)\n"
        val moved = plantAfter(
            replaceOnce(menu, line, ""),
            "                MenuOption(it.title, it.body, onCrisis)\n            }\n",
            line,
        )
        assertEquals("Not right now", order(moved).last())
    }

    @Test
    fun `the calm activities are listed only behind their switch`() {
        val code = withoutComments(read(MENU))
        val gate = code.indexOf("if (viewModel.showCalmActivities) {")
        assertTrue(gate >= 0)
        val gated = code.substring(gate, code.indexOf('}', gate))
        assertTrue(gated.contains("\"Falling blocks\"") && gated.contains("\"Sand to rake\""))
        // Nowhere else in the menu.
        assertEquals(1, code.split("\"Falling blocks\"").size - 1)
        assertEquals(1, code.split("\"Sand to rake\"").size - 1)
    }

    @Test
    fun `an activity's words never score, cheer or say how the person feels`() {
        val blocks = read(CALM[1])
        // Planted controls, one per list.
        val anchor = "            Text(\"Clear\")\n"
        assertNotNull(firstPhraseIn(stringLiteralsIn(plantAfter(blocks, anchor, "Text(\"Rows: 3\")\nText(\"Best so far\")\n")), SCORING))
        assertNotNull(firstPhraseIn(stringLiteralsIn(plantAfter(blocks, anchor, "Text(\"Feel better?\")\n")), FEELING))
        // The frame's own words are the fixed lines in `calm/SandRake.kt`'s CalmSession.
        val worded = listOf(CALM[1], CALM[2], "app/src/main/java/com/daymark/app/calm/SandRake.kt")
        worded.forEach { path ->
            val strings = stringLiteralsIn(withoutComments(read(path)))
            assertTrue("$path has words to read", strings.isNotEmpty())
            assertNull(path, firstPhraseIn(strings, SCORING))
            assertNull(path, firstPhraseIn(strings, FEELING))
        }
        val menuRows = listOf("Turn and place slow shapes. Nothing is counted.", "Draw lines in the sand, then smooth it flat.")
        assertNull(firstPhraseIn(menuRows, SCORING))
        assertNull(firstPhraseIn(menuRows, FEELING))
    }

    @Test
    fun `an activity records nothing`() {
        fun recorder(source: String) = codeOnly(source).let { code -> RECORDING.firstOrNull { code.contains(it) } }
        // Planted control: a view model in an activity is seen.
        val planted = plantAfter(read(CALM[1]), "    val context = LocalContext.current\n", "    val vm: SupportViewModel = hiltViewModel()\n")
        assertNotNull(recorder(planted))
        CALM.forEach { assertNull(it, recorder(read(it))) }
    }

    @Test
    fun `the way back is on screen before anything else in an activity`() {
        val code = withoutComments(read(CALM[0]))
        val back = code.indexOf("Text(CalmSession.BACK_TO_MENU)")
        val content = code.indexOf("content()")
        assertTrue(back in 0 until content)
        // Both activities sit inside the frame.
        listOf(CALM[1], CALM[2]).forEach { assertTrue(it, withoutComments(read(it)).contains("CalmActivityFrame(")) }
    }
}
