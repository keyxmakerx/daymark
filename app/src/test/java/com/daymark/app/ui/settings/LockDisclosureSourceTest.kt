package com.daymark.app.ui.settings

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the two Privacy rows may and may not claim.
 *
 * ## Why a source test
 *
 * The claim is a handful of strings in two Compose `ListItem`s. Asserting it behaviourally needs a
 * composition test, and this module has no Robolectric and no Compose test runner — so that test
 * would be written, never run, and quietly believed. Reading the source is a weaker claim than "a
 * person sees this on screen", and the gap is stated rather than papered over: what is rendered is
 * checked by hand and by `/walkthrough`, never here.
 *
 * ## The change this exists to stop
 *
 * For most of this app's life the lock row said "On" over a plaintext database. Every force acting
 * on a settings string pushes it back that way: shorter, matching the rows around it, fitting on one
 * line, and invisible in a diff as anything but a copy tweak.
 *
 * There are now two ways to get it wrong and this watches both.
 *
 *  - **Overclaiming for the PIN.** Issue #109 specifies copy saying the entries are "locked with a
 *    key made from this PIN" and that only a recovery code opens them if it is forgotten. That
 *    describes a wrap which IS BUILT AND IS NOT ARMED, so the sentence is false today. Writing it
 *    early would tell somebody their journal needs their PIN when it does not, and — far worse —
 *    that a forgotten PIN costs them their entries when it does not.
 *  - **Dropping the photos clause.** Entry photos are ordinary JPEGs in `filesDir/entry_photos` and
 *    nothing in the at-rest work touched them. "Encrypted", said over a journal whose pictures are
 *    sitting in the open, is the same inference-vs-truth gap this row exists to close.
 *
 * Both detectors are shown planted examples before either is trusted to report an absence.
 */
class LockDisclosureSourceTest {

    private companion object {
        const val REL = "app/src/main/java/com/daymark/app/ui/settings/SettingsScreen.kt"

        const val AT_REST_HEADLINE = """headlineContent = { Text("Your entries on this device") }"""
        const val PIN_HEADLINE = """headlineContent = { Text("App lock (PIN)") }"""

        /** The sentence shown when the journal on this phone really is encrypted. */
        const val ENCRYPTED_COPY =
            "Encrypted with a key only this phone holds, so copying its storage does not read " +
                "them. Photos attached to entries are not covered."

        /** The sentence shown when it is not, which is a state the app keeps working in. */
        const val PLAINTEXT_COPY =
            "Not encrypted on this device. Daymark tries again each time you open it."

        const val PIN_COPY =
            "The PIN guards the screen. It is not what your entries are encrypted with, so " +
                "forgetting it does not lose them."

        /**
         * Phrasings that would say the PIN is what holds the key. Every one of them is true of the
         * design that is built and false of the one that is armed.
         */
        val PIN_OVERCLAIMS = listOf(
            "made from this pin",
            "made from your pin",
            "locked with this pin",
            "locked with your pin",
            "encrypted with this pin",
            "encrypted with your pin",
            "only your recovery code",
            "guess the pin first",
        )

        /** Phrasings that would say the photos are covered. They are not. */
        val PHOTO_OVERCLAIMS = listOf(
            "including photos",
            "photos too",
            "photos are encrypted",
            "and your photos",
            "everything you attach",
        )

        fun joinLiterals(text: String): String = text.replace(Regex("\"\\s*\\+\\s*\""), "")

        fun literalsIn(text: String): List<String> =
            Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"").findAll(joinLiterals(text)).map { it.groupValues[1] }.toList()

        fun firstMatch(strings: List<String>, claims: List<String>): String? {
            for (s in strings) {
                val lower = s.lowercase()
                for (claim in claims) if (lower.contains(claim)) return claim
            }
            return null
        }
    }

    private val source = repoFile(REL).readText()

    /** The copy under the at-rest row: between its headline and the row that follows. */
    private val atRestSlice: String = source.substringAfter(AT_REST_HEADLINE, "").substringBefore(PIN_HEADLINE, "")

    /** The copy under the PIN row: between its headline and the switch beside it. */
    private val pinSlice: String = source.substringAfter(PIN_HEADLINE, "").substringBefore("trailingContent", "")

    @Test
    fun `the source and both slices were actually found`() {
        // Guards every assertion below. A slice that failed to match would be "" and would satisfy
        // both the presence checks (vacuously false, caught here) and the absence checks (vacuously
        // true, which is the shape of a guard that reports green forever).
        assertTrue("SettingsScreen.kt is implausibly short", source.length > 5000)
        assertTrue("the at-rest headline has moved or been renamed", source.contains(AT_REST_HEADLINE))
        assertTrue("the App lock headline has moved or been renamed", source.contains(PIN_HEADLINE))
        assertTrue("the at-rest slice is empty", atRestSlice.isNotEmpty())
        assertTrue("the PIN slice is empty", pinSlice.isNotEmpty())
        assertTrue("the at-rest slice swallowed the rest of the file", atRestSlice.length < 1600)
        assertTrue("the PIN slice swallowed the rest of the file", pinSlice.length < 1200)
        assertTrue(literalsIn(atRestSlice).isNotEmpty())
        assertTrue(literalsIn(pinSlice).isNotEmpty())
    }

    @Test
    fun `the at-rest row carries both sentences, and only those`() {
        val literals = literalsIn(atRestSlice)
        assertTrue(
            "the encrypted sentence is gone or reworded: \"$ENCRYPTED_COPY\"",
            literals.any { it == ENCRYPTED_COPY },
        )
        assertTrue(
            "the sentence for a device that is NOT encrypted is gone. A migration that has not " +
                "succeeded leaves a plaintext file and the app keeps working; that person must not " +
                "be shown the encrypted sentence.",
            literals.any { it == PLAINTEXT_COPY },
        )
    }

    @Test
    fun `the at-rest row names the photos it does not cover`() {
        assertTrue(
            "the photos clause is gone from the encrypted sentence. Entry photos are ordinary " +
                "JPEGs in filesDir/entry_photos and nothing in the at-rest work touched them, so " +
                "\"encrypted\" without that clause is the same inference-vs-truth gap this row " +
                "exists to close.",
            literalsIn(atRestSlice).any { it.contains("Photos attached to entries are not covered") },
        )
        val overclaim = firstMatch(literalsIn(atRestSlice), PHOTO_OVERCLAIMS)
        assertTrue("the at-rest copy claims photos are covered (\"$overclaim\")", overclaim == null)
    }

    @Test
    fun `the PIN row says what the PIN does and does not claim to hold the key`() {
        val literals = literalsIn(pinSlice)
        assertTrue("the PIN row's sentence is gone or reworded: \"$PIN_COPY\"", literals.any { it == PIN_COPY })
        val overclaim = firstMatch(literals, PIN_OVERCLAIMS)
        assertTrue(
            "the PIN row says \"$overclaim\". That describes the PIN wrap, which is BUILT AND NOT " +
                "ARMED — the key is held by the phone's keystore, not made from the PIN. Written " +
                "early, it tells somebody a forgotten PIN costs them their entries when it does not.",
            overclaim == null,
        )
    }

    /**
     * The positive control for both absence checks.
     *
     * An absence check that cannot see a planted example proves only that it is blind, and every
     * previous version of this guard in this repository failed exactly that way. So each detector is
     * run over the real slice with the forbidden sentence spliced into it, and must report it.
     */
    @Test
    fun `both detectors can see a planted overclaim`() {
        for (planted in listOf(
            "Your entries are locked with a key made from this PIN.",
            "If you forget the PIN, only your recovery code opens them.",
            "Someone who copies this phone's storage would have to guess the PIN first.",
        )) {
            val plantedSlice = pinSlice.replace("Text(", "Text(\n                    \"$planted\",\n                )\n                Text(")
            assertTrue(
                "the PIN detector did not see the planted claim: \"$planted\"",
                firstMatch(literalsIn(plantedSlice), PIN_OVERCLAIMS) != null,
            )
        }
        for (planted in listOf(
            "Encrypted on this device, including photos.",
            "Your entries and your photos are encrypted.",
            "Everything you attach is covered.",
        )) {
            val plantedSlice = atRestSlice.replace("Text(", "Text(\n                    \"$planted\",\n                )\n                Text(")
            assertTrue(
                "the photo detector did not see the planted claim: \"$planted\"",
                firstMatch(literalsIn(plantedSlice), PHOTO_OVERCLAIMS) != null,
            )
        }
    }

    /** The other half: a presence check must fail when the sentence is not there. */
    @Test
    fun `the presence checks can see a sentence missing`() {
        val reworded = atRestSlice.replace("only this phone holds", "this phone holds")
        assertFalse(
            "the presence check passes over a reworded sentence, so it is not checking the wording",
            literalsIn(reworded).any { it == ENCRYPTED_COPY },
        )
    }

    @Test
    fun `the rows still carry their written position`() {
        val comment = source.substringBefore(AT_REST_HEADLINE).takeLast(5000)
        // Two instructions that the very change they are addressed to would otherwise remove: the
        // photos exception, and the fact that the issue's PIN copy is false until the wrap is armed.
        assertTrue(
            "the comment above the Privacy rows no longer explains why the photos clause is there",
            comment.contains("PHOTOS CLAUSE"),
        )
        assertTrue(
            "the comment no longer says the issue's PIN copy is false until the wrap is armed",
            comment.contains("FALSE TODAY") && comment.contains("#109"),
        )
    }

    /** Exports are a plain file the person asked for. They do not belong in a claim about storage. */
    @Test
    fun `neither row talks about exports`() {
        for (slice in listOf(atRestSlice, pinSlice)) {
            for (word in listOf("export", "backup", "csv", "pdf")) {
                assertFalse(
                    "a Privacy row mentions \"$word\". A backup is a file the person asked for and " +
                        "put where they chose; folding it into a sentence about what the app does " +
                        "to its own storage either overclaims or turns a row into a lecture.",
                    literalsIn(slice).any { it.lowercase().contains(word) },
                )
            }
        }
    }
}
