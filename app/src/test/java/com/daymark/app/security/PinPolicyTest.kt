package com.daymark.app.security

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PIN length rule, and the check that it is not written down anywhere else.
 *
 * ## The bug this exists for
 *
 * `pin.length in 3..8` was written out twice — once in the settings dialog, once in the onboarding
 * step — and connected to nothing. `PinManager.setPin` accepted anything at all, so the rule was
 * whatever the last screen to ask for a PIN happened to contain, and the two copies could drift
 * apart with nothing to notice. That is the shape the second half of this file is watching for: not
 * "is the number six" but "is the number written down in exactly one place".
 */
class PinPolicyTest {

    @Test
    fun `six digits is the shortest a PIN may now be`() {
        assertEquals(6, PinPolicy.MIN_DIGITS)
        for (short in listOf("", "1", "12", "123", "1234", "12345")) {
            assertEquals(
                "\"${short.length} digits\" was accepted; a four-digit PIN wrapping a data key is " +
                    "ten thousand offline guesses, which is minutes",
                PinPolicy.Rejection.TOO_SHORT,
                PinPolicy.reject(short),
            )
        }
        assertNull(PinPolicy.reject("123456"))
    }

    @Test
    fun `a longer PIN is allowed, because the copy invites one`() {
        assertEquals(12, PinPolicy.MAX_DIGITS)
        for (length in 6..12) {
            assertTrue("$length digits was refused", PinPolicy.accepts("9".repeat(length)))
        }
        assertEquals(PinPolicy.Rejection.TOO_LONG, PinPolicy.reject("9".repeat(13)))
    }

    @Test
    fun `a PIN is digits`() {
        for (notDigits in listOf("12345a", "12 345 6", "abcdef", "１２３４５６", "12345-6")) {
            assertEquals(
                "\"$notDigits\" was accepted as a PIN",
                PinPolicy.Rejection.NOT_DIGITS,
                PinPolicy.reject(notDigits),
            )
        }
    }

    @Test
    fun `a half-typed PIN is not a rejected one`() {
        // The field has to let somebody reach six digits without the app complaining at every
        // keystroke on the way.
        for (partial in listOf("", "1", "12345")) {
            assertTrue(PinPolicy.stillTypeable(partial))
            assertFalse(PinPolicy.accepts(partial))
        }
        assertFalse("the field kept taking characters past the maximum", PinPolicy.stillTypeable("9".repeat(13)))
        assertFalse(PinPolicy.stillTypeable("12a"))
    }

    @Test
    fun `the label and the help come from the numbers rather than repeating them`() {
        assertEquals("PIN (6–12 digits)", PinPolicy.LABEL)
        assertTrue(PinPolicy.LABEL.contains(PinPolicy.MIN_DIGITS.toString()))
        assertTrue(PinPolicy.LABEL.contains(PinPolicy.MAX_DIGITS.toString()))
        // No congratulation, no alarm, no promise the file is safe.
        for (word in listOf("secure", "safe", "protect", "encrypt", "strong", "weak", "warning")) {
            assertFalse("the help text says \"$word\"", PinPolicy.HELP.lowercase().contains(word))
        }
    }

    // ─── The rule is written down once ─────────────────────────────────────────────────────────

    private val sites = listOf(
        "app/src/main/java/com/daymark/app/ui/settings/SettingsScreen.kt",
        "app/src/main/java/com/daymark/app/ui/onboarding/OnboardingScreen.kt",
        // The unlock screen decides how long a PIN may be TYPED BACK, which is the same rule seen
        // from the other end. It was left off this list and drifted: it capped input at eight while
        // the policy accepted twelve, so a PIN the app invited you to choose could not be entered.
        "app/src/main/java/com/daymark/app/ui/lock/LockScreen.kt",
    )

    private fun codeOf(rel: String): String = repoFile(rel).readText()
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    @Test
    fun `no screen carries its own copy of the length rule`() {
        for (rel in sites) {
            val code = codeOf(rel)
            assertTrue("$rel is implausibly short", code.length > 1000)
            assertTrue("$rel no longer asks PinPolicy anything", code.contains("PinPolicy."))
            for (ownRule in listOf("in 3..8", "in 6..12", "length <= 8", "length <= 12", "length >= 6")) {
                assertFalse(
                    "$rel contains its own PIN length rule (\"$ownRule\"). There is one place that " +
                        "decides how long a PIN must be, and a second copy in a Compose file is how " +
                        "the two screens came to disagree in the first place.",
                    code.contains(ownRule),
                )
            }
        }
    }

    /** The positive control: the detector above must be able to see a rule planted back in. */
    @Test
    fun `the detector can see a length rule planted back into a screen`() {
        val code = codeOf(sites[0])
        val planted = code.replace("val valid = com.daymark.app.security.PinPolicy.accepts(pin)", "val valid = pin.length in 3..8")
        assertTrue("nothing was planted, so this control proves nothing", planted != code)
        assertTrue("the detector is blind to a hand-written length rule", planted.contains("in 3..8"))
    }

    @Test
    fun `the only door a screen may use enforces the policy`() {
        val manager = codeOf("app/src/main/java/com/daymark/app/security/PinManager.kt")
        assertTrue("setChosenPin is gone", manager.contains("fun setChosenPin"))
        assertTrue(
            "setChosenPin no longer asks PinPolicy — the length rule would then live only in the " +
                "two Compose files again",
            manager.substringAfter("fun setChosenPin").take(200).contains("PinPolicy.accepts"),
        )
        for (rel in listOf(
            "app/src/main/java/com/daymark/app/ui/settings/SettingsViewModel.kt",
            "app/src/main/java/com/daymark/app/ui/onboarding/OnboardingViewModel.kt",
        )) {
            val code = codeOf(rel)
            assertTrue("$rel does not go through setChosenPin", code.contains("setChosenPin"))
            assertFalse(
                "$rel calls setPin directly, which accepts anything — that door exists only for the " +
                    "legacy-hash upgrade inside PinManager.verify",
                code.contains("pinManager.setPin("),
            )
        }
    }
}
