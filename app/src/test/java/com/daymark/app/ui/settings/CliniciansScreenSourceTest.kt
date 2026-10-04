package com.daymark.app.ui.settings

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Clinicians screens' standing rules (#174), read from the `sync` flavour's source, since the screen
 * cannot run here:
 *
 *  - Nothing asks the server by itself: no timer, no delay, no repeat in the screen or its ViewModel.
 *  - The code and the sign-in key are each drawn inside a phase that makes the window secure.
 *  - Nothing on these screens can be selected, and the share carries one extra: the link.
 *
 * Comments and string literals are blanked first, and every rule is shown a planted breach.
 */
class CliniciansScreenSourceTest {

    private companion object {
        const val SCREEN = "app/src/sync/java/com/daymark/app/sync/CliniciansScreen.kt"
        const val MODEL = "app/src/sync/java/com/daymark/app/sync/CliniciansViewModel.kt"

        val REPEATS = Regex("""\b(delay|LaunchedEffect|repeat|while|Timer|schedule\w*|postDelayed|tickerFlow)\s*[({]""")
        val SELECTABLE = Regex("""\bSelectionContainer\b""")
        val EXTRAS = Regex("""\.putExtra\(""")
    }

    private val screen = codeOnly(repoFile(SCREEN).readText())
    private val model = codeOnly(repoFile(MODEL).readText())

    /** The source of the `is Phase.<name> ->` branch, up to the next phase's branch. */
    private fun branch(name: String): String {
        val start = screen.indexOf("is Phase.$name ->")
        assertTrue("no branch for $name", start >= 0)
        val end = screen.indexOf("is Phase.", start + 1).let { if (it < 0) screen.length else it }
        return screen.substring(start, end)
    }

    @Test
    fun `the scanners see what they look for`() {
        assertTrue(REPEATS.containsMatchIn("LaunchedEffect(Unit) { while (true) { delay(5000) } }"))
        assertTrue(REPEATS.containsMatchIn("handler.postDelayed({ check() }, 1000)"))
        assertFalse(REPEATS.containsMatchIn("viewModel.checkForReply()"))
        assertTrue(SELECTABLE.containsMatchIn("SelectionContainer { Text(code) }"))
        assertEquals(2, EXTRAS.findAll("Intent().putExtra(a, b).putExtra(c, d)").count())
    }

    @Test
    fun `nothing asks the server by itself`() {
        assertEquals("the screen repeats something", emptyList<String>(), REPEATS.findAll(screen).map { it.value }.toList())
        assertEquals("the ViewModel repeats something", emptyList<String>(), REPEATS.findAll(model).map { it.value }.toList())
        // The control: the one read is a tap's.
        assertTrue(screen.contains("onClick = viewModel::checkForReply"))
    }

    @Test
    fun `the code and the sign-in key are shown only in a secure window`() {
        val waiting = branch("Waiting")
        assertTrue("the code is not in the waiting branch", waiting.contains("phase.code.display"))
        assertTrue("the waiting branch does not secure the window", waiting.contains("SecureWhileShown()"))
        assertEquals("the code is drawn somewhere else", 1, Regex("""\.code\.display""").findAll(screen).count())

        val key = screen.substring(screen.indexOf("private fun SignInKey("), screen.indexOf("private fun Invitation("))
        assertTrue(key.contains("SecureWhileShown()") && key.contains("added.inboxToken"))
        assertEquals("the sign-in key is drawn somewhere else", 2, Regex("""\.inboxToken\b""").findAll(screen).count())

        // The control: the helper really sets the flag, and the mutation of the branch is seen.
        assertTrue(screen.contains("addFlags(WindowManager.LayoutParams.FLAG_SECURE)"))
        assertFalse(waiting.replace("SecureWhileShown()", "").contains("SecureWhileShown()"))
    }

    @Test
    fun `nothing is selectable, and the share carries the link alone`() {
        assertFalse(SELECTABLE.containsMatchIn(screen))
        val share = screen.substring(screen.indexOf("private fun share("))
        assertEquals(1, EXTRAS.findAll(share).count())
        assertTrue(share.contains("putExtra(Intent.EXTRA_TEXT, link)"))
        // The code is never handed to the clipboard.
        assertFalse(Regex("""copy\([^)]*code""").containsMatchIn(screen))
        assertTrue("the control: the copy call is seen", Regex("""copy\([^)]*link""").containsMatchIn(screen))
    }
}
