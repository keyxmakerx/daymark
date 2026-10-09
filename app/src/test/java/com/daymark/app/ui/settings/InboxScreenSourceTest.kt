package com.daymark.app.ui.settings

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clinician inbox's standing rules (#177), read from the `sync` flavour's source, since the screen
 * cannot run here:
 *
 *  - Nothing asks the server by itself: no timer, no delay, no repeat in the screen or its ViewModel.
 *  - A game plan is written into the app only from Accept; a suggestion only from Accept, or on its own
 *    when the owner's grant says so.
 *  - Declining sends nothing.
 *  - The owner's private box key is wiped once a check is over.
 *
 * Comments and string literals are blanked first, and every rule is shown a planted breach.
 */
class InboxScreenSourceTest {

    private companion object {
        const val SCREEN = "app/src/sync/java/com/daymark/app/sync/InboxScreen.kt"
        const val MODEL = "app/src/sync/java/com/daymark/app/sync/InboxViewModel.kt"

        val REPEATS = Regex("""\b(delay|LaunchedEffect|repeat|while|Timer|schedule\w*|postDelayed|tickerFlow)\s*[({]""")
        val STORES = Regex("""\bstore\(""")
        val SENDS = Regex("""\b(parts|server|links|clinicianItems)\b""")
    }

    private val screen = codeOnly(repoFile(SCREEN).readText())
    private val model = codeOnly(repoFile(MODEL).readText())

    private fun between(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("no $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("no $to after $from", end >= 0)
        return text.substring(start, end)
    }

    /** Where a check reads plans; where it reads suggestions; and the rest of the check. */
    private fun planLoop(m: String) = between(m, "for (f in plans.items)", "for (f in suggestions.items)")
    private fun suggestionLoop(m: String) = between(m, "for (f in suggestions.items)", "fun accept(")

    @Test
    fun `the scanners see what they look for`() {
        assertTrue(REPEATS.containsMatchIn("LaunchedEffect(Unit) { while (true) { delay(5000) } }"))
        assertFalse(REPEATS.containsMatchIn("viewModel.check()"))
        assertTrue(STORES.containsMatchIn("store(o)"))
        assertFalse(STORES.containsMatchIn("restore(o)"))
        assertTrue(SENDS.containsMatchIn("parts.clinicianItems.fetch(server)"))
    }

    @Test
    fun `nothing asks the server by itself`() {
        assertEquals("the screen repeats something", emptyList<String>(), REPEATS.findAll(screen).map { it.value }.toList())
        assertEquals("the ViewModel repeats something", emptyList<String>(), REPEATS.findAll(model).map { it.value }.toList())
        // The control: the one read is a tap's.
        assertTrue(screen.contains("onClick = viewModel::check"))
    }

    @Test
    fun `a plan is written only from Accept, and a suggestion only from Accept or its grant`() {
        assertEquals("a check writes a plan", 0, STORES.findAll(planLoop(model)).count())
        val suggestions = suggestionLoop(model)
        assertEquals(1, STORES.findAll(suggestions).count())
        val guard = suggestions.substring(0, suggestions.indexOf("store("))
        assertTrue("the one write in a check is not behind the grant", guard.substringAfterLast("if (").startsWith("cannotAdd == null && AssignmentRules.shouldAutoApply("))
        val accept = between(model, "fun accept(", "fun decline(")
        assertTrue(accept.contains("store(entry.plan)") && accept.contains("store(entry.assignment)"))
        // The dao's writes are inside the two store functions and nowhere else.
        assertEquals(1, Regex("""dao\.acceptGamePlan\(""").findAll(model).count())
        assertEquals(1, Regex("""dao\.insertAssignment\(""").findAll(model).count())
        assertTrue(between(model, "private suspend fun store(p:", "private suspend fun store(a:").contains("dao.acceptGamePlan("))

        // The controls: a planted write in the plan loop, and one taken out from behind the grant, are seen.
        val planted = model.replace("entries += InboxEntry.Plan(", "store(o); entries += InboxEntry.Plan(")
        assertTrue(planted != model)
        assertEquals(1, STORES.findAll(planLoop(planted)).count())
        val unguarded = model.replace("cannotAdd == null && AssignmentRules.shouldAutoApply(", "true || AssignmentRules.shouldAutoApply(")
        assertTrue(unguarded != model)
        assertFalse(suggestionLoop(unguarded).let { it.substring(0, it.indexOf("store(")) }.substringAfterLast("if (").startsWith("cannotAdd == null"))
    }

    @Test
    fun `declining sends nothing`() {
        val decline = between(model, "fun decline(", "private suspend fun store(")
        assertEquals(emptyList<String>(), SENDS.findAll(decline).map { it.value }.toList())
        assertTrue("the control: declining is kept", decline.contains("clinicianStore.decline(key)"))
    }

    @Test
    fun `the private box key is wiped when a check ends`() {
        val check = between(model, "private suspend fun checkAll(", "private class Checked")
        val fin = check.substring(check.lastIndexOf("finally"))
        assertTrue(fin.contains("secret.fill(0)"))
        // Every early return after the key is read wipes it first.
        val afterRead = between(check, "links.ownerBoxSecret()", "val owner =")
        assertEquals(2, Regex("""secret\.fill\(0\)""").findAll(afterRead).count())
        assertFalse("the control: the mutation is seen", check.replace("secret.fill(0)", "").contains("secret.fill(0)"))
    }
}
