package com.daymark.app.ui.settings

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence under "App lock (PIN)" must keep saying that the PIN does not reach the file.
 *
 * ## Why a source test
 *
 * The claim is about one string in one Compose `ListItem`. Asserting it behaviourally needs a
 * composition test, and this module has no Robolectric and no Compose test runner — so that test
 * would be written, never run, and quietly believed. Reading the source is a weaker claim than
 * "a person sees this on screen", and the gap is stated rather than papered over: the visible
 * rendering is checked by hand and by `/walkthrough`, never here.
 *
 * ## The change this exists to stop
 *
 * The row said "On" for most of this app's life. Every force acting on that string pushes it back
 * towards "On": it is shorter, it matches every other row in the section, it fits on one line on a
 * small phone, and a reviewer trimming copy has no way to tell from the diff that two sentences of
 * disclosure are load-bearing. They are. The gap between what "app lock" makes a person infer and
 * what the PIN actually does was this app's largest undisclosed weakness, and a sentence is all
 * that stands in it until the database is encrypted.
 *
 * The second assertion is the one with teeth. Reassurance is the natural edit here — somebody adds
 * "your entries are still protected by Android's storage encryption" to soften a sentence that
 * reads harshly at 2am — and the softening is what makes the disclosure stop working. So no line of
 * copy under this row may claim the file is protected, and the detector that says so is proved able
 * to see such a claim before it is trusted to report its absence.
 */
class LockDisclosureSourceTest {

    private companion object {
        const val REL = "app/src/main/java/com/daymark/app/ui/settings/SettingsScreen.kt"

        /** Issue #109's copy under B, verbatim. */
        const val DISCLOSURE =
            "The PIN guards the screen, not the file. " +
                "Anyone who can copy this phone's storage can read your entries without it."

        /**
         * The anchor the slice below starts at. If this ever stops matching, every assertion here
         * would run over an empty string and pass, so it is asserted rather than assumed.
         */
        const val HEADLINE = """headlineContent = { Text("App lock (PIN)") }"""

        /**
         * Words that, in user-facing copy under this row, would amount to a claim that the file
         * itself is protected. Substrings rather than whole words, so "encrypted", "encryption",
         * "protects" and "secured" are all caught by one entry each.
         *
         * "safe" is here and "lock" is not: the row is *called* a lock and the headline says so,
         * which is the honest part. Claiming the entries are safe is the dishonest part.
         */
        val PROTECTION_CLAIMS = listOf(
            "encrypt",
            "protect",
            "secure",
            "safe",
            "unreadable",
            "cannot be read",
            "can't be read",
            "no one can read",
            "nobody can read",
        )

        /** Adjacent Kotlin string literals joined by `+` become one string, as the user sees it. */
        fun joinLiterals(text: String): String = text.replace(Regex("\"\\s*\\+\\s*\""), "")

        /** Every string literal in a slice of Kotlin source, contents only. */
        fun literalsIn(text: String): List<String> =
            Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"").findAll(joinLiterals(text)).map { it.groupValues[1] }.toList()

        /** True when any of these strings makes a protection claim. */
        fun claimsProtection(strings: List<String>): String? {
            for (s in strings) {
                val lower = s.lowercase()
                for (claim in PROTECTION_CLAIMS) if (lower.contains(claim)) return claim
            }
            return null
        }
    }

    private val source = repoFile(REL).readText()

    /**
     * The copy under the row: everything between the headline and the switch. Comments are not in
     * it by construction — the comment block sits above the `ListItem`, and this starts inside it.
     */
    private val supportingSlice: String =
        source.substringAfter(HEADLINE, "").substringBefore("trailingContent", "")

    @Test
    fun `the source and the slice were actually found`() {
        // Guards every assertion below. A slice that failed to match would be "" and would satisfy
        // both the presence check (vacuously false, caught here) and the absence check (vacuously
        // true, which is the shape of a guard that reports green forever).
        assertTrue("SettingsScreen.kt is implausibly short", source.length > 5000)
        assertTrue("the App lock headline has moved or been renamed", source.contains(HEADLINE))
        assertTrue("the supporting slice is empty", supportingSlice.isNotEmpty())
        assertTrue("the supporting slice swallowed the rest of the file", supportingSlice.length < 1200)
        assertTrue("no copy found under the row", literalsIn(supportingSlice).isNotEmpty())
    }

    @Test
    fun `the row says the PIN does not reach the file`() {
        assertTrue(
            "The disclosure under \"App lock (PIN)\" is gone or reworded. It is issue #109's copy " +
                "under B and it is verbatim on purpose: \"$DISCLOSURE\"",
            literalsIn(supportingSlice).any { it == DISCLOSURE },
        )
    }

    @Test
    fun `no copy under the row claims the file is protected`() {
        val found = claimsProtection(literalsIn(supportingSlice))
        assertTrue(
            "Copy under \"App lock (PIN)\" contains \"$found\". The database is a plaintext SQLite " +
                "file (AppModule opens Room with no openHelperFactory), so any claim that the " +
                "entries themselves are protected is false. Reassurance is what stops a disclosure " +
                "working; say the limit once and do not argue with it.",
            found == null,
        )
    }

    /**
     * The positive control for the assertion above.
     *
     * An absence check that cannot see a planted example proves only that it is blind, and every
     * previous version of this guard in this repository failed exactly that way. So the detector is
     * run over the real slice with a reassuring sentence spliced into it, and must report it.
     */
    @Test
    fun `the protection-claim detector can see a planted claim`() {
        for (planted in listOf(
            "Your entries are encrypted on this device.",
            "Android's own storage encryption still applies.",
            "Your journal is protected even if the phone is lost.",
            "Nobody can read your entries without it.",
            "Your entries stay safe.",
        )) {
            val plantedSlice = supportingSlice.replace(
                "Text(",
                "Text(\n                    \"$planted\",\n                )\n                Text(",
            )
            assertTrue(
                "the detector did not see the planted claim: \"$planted\" — it is blind, so its " +
                    "clean report on the real copy proves nothing",
                claimsProtection(literalsIn(plantedSlice)) != null,
            )
        }
    }

    /** The other half of the control: the presence check must fail when the sentence is not there. */
    @Test
    fun `the disclosure check can see the sentence missing`() {
        val without = supportingSlice.replace("guards the screen", "guards everything")
        assertFalse(
            "the disclosure check passes over a reworded sentence, so it is not checking the wording",
            literalsIn(without).any { it == DISCLOSURE },
        )
    }

    /**
     * The comment above the row is the project's written position on why the file is not encrypted.
     * It says the sentence must be rewritten rather than deleted when that changes, and that
     * instruction is the thing most likely to be lost in the very change it is addressed to.
     */
    @Test
    fun `the row still carries its written position`() {
        val comment = source.substringBefore(HEADLINE).takeLast(4000)
        assertTrue(
            "the comment above \"App lock (PIN)\" no longer says the disclosure is rewritten, not " +
                "deleted, when the database is encrypted",
            comment.contains("REWRITTEN") && comment.contains("issue #109"),
        )
    }
}
