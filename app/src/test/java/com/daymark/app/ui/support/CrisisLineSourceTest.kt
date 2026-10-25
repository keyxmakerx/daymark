package com.daymark.app.ui.support

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import com.daymark.app.ui.replaceOnce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The crisis line can be called from the app, only once it is confirmed, and never by the app
 * itself (#482). Read as text, because the buttons are intents this module cannot fire. Every check
 * is first shown a planted example in a copy of the real file (CLAUDE.md §5). Runs through
 * `tools/jvm-source-tests.sh`.
 */
class CrisisLineSourceTest {

    private companion object {
        const val LINE = "app/src/main/java/com/daymark/app/ui/support/CrisisLine.kt"
        const val SCREEN = "app/src/main/java/com/daymark/app/ui/support/CrisisResourcesScreen.kt"
        const val MORE = "app/src/main/java/com/daymark/app/ui/more/MoreHubScreen.kt"
        const val ONBOARDING = "app/src/main/java/com/daymark/app/ui/onboarding/OnboardingScreen.kt"

        /** What would place a call or send a message without the person pressing anything. */
        val PLACES_A_CALL = Regex("""\bACTION_CALL\b|\bCALL_PHONE\b|\bSEND_SMS\b|\bSmsManager\b""")
    }

    private fun mainSources(): List<File> =
        repoFile("app/src/main/AndroidManifest.xml").parentFile.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()

    @Test
    fun nothing_places_a_call_or_sends_a_text() {
        val files = mainSources()
        assertTrue(files.size > 100)
        val hits = files.filter { PLACES_A_CALL.containsMatchIn(codeOnly(it.readText())) }.map { it.name }
        assertEquals(emptyList<String>(), hits)
        // The control: the detector sees a planted call in a copy of the real buttons.
        val line = repoFile(LINE).readText()
        assertTrue(PLACES_A_CALL.containsMatchIn(codeOnly(replaceOnce(line, "Intent.ACTION_DIAL", "Intent.ACTION_CALL"))))
    }

    @Test
    fun the_buttons_open_the_persons_own_apps() {
        val code = codeOnly(repoFile(LINE).readText())
        assertTrue(code.contains("Intent.ACTION_DIAL"))
        assertTrue(code.contains("Intent.ACTION_SENDTO"))
        assertTrue("the Text button is not gated on the line taking texts", code.contains("if (resource.takesTexts)"))
    }

    /** The default is a US number, so a line nobody confirmed is never handed over as a one-tap call. */
    @Test
    fun the_call_button_waits_for_a_confirmed_line() {
        val real = codeOnly(repoFile(SCREEN).readText())
        fun gated(code: String) = Regex("""if \(confirmed\) \{\s*CrisisCallButtons\(""").containsMatchIn(code) &&
            Regex("""CrisisCallButtons\(""").findAll(code).count() == 1
        assertTrue(gated(real))
        assertFalse(gated(real.replace("if (confirmed) {", "if (true) {")))
        assertFalse(gated(real + "\nfun x() { CrisisCallButtons(r) }"))
    }

    /** More's first row is the crisis screen, never behind an opt-in. */
    @Test
    fun more_always_leads_to_the_crisis_screen() {
        // codeOnly keeps every offset, so the label's place in the raw text is its place in the code.
        val code = codeOnly(repoFile(MORE).readText())
        val track = repoFile(MORE).readText().indexOf("SectionLabel(\"Track\")")
        assertTrue(track > 0)
        fun first(code: String): Boolean = code.indexOf("onClick = onCrisis") in 0 until track
        assertTrue(first(code))
        assertFalse(first(code.replace("onClick = onCrisis", "onClick = onSettings")))
        val scaffold = codeOnly(repoFile("app/src/main/java/com/daymark/app/ui/DaymarkAppScaffold.kt").readText())
        assertTrue(scaffold.contains("onCrisis = { navController.navigate(Routes.CRISIS) }"))
    }

    /** Onboarding's three answers each record themselves, so Home never asks the same person again. */
    @Test
    fun every_onboarding_answer_is_recorded() {
        val code = codeOnly(repoFile(ONBOARDING).readText())
        for (call in listOf("viewModel.confirmCrisisLine()", "viewModel.saveCrisisLine(", "viewModel.skipCrisisLine()")) {
            assertTrue("$call is gone", code.contains(call))
        }
        assertFalse(code.replace("viewModel.skipCrisisLine()", "Unit").contains("viewModel.skipCrisisLine()"))
    }
}
