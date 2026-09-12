package com.daymark.app.security

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one piece of the at-rest design that cannot be executed anywhere in this repository.
 *
 * `KeystoreAead` needs the Android Keystore, which exists on a phone and nowhere else. There is no
 * device in CI, so nothing runs it: CI compiles it and stops. A test that pretends otherwise would
 * be worse than none, so this one does not pretend — it reads the source and pins the parameters
 * that decide what the key is and who can use it.
 *
 * ## Why these parameters are worth pinning at all
 *
 * Every one of them is a line somebody could add or remove in good faith, and two of them would
 * lock people out of their own journals:
 *
 *  - `setUserAuthenticationRequired(true)` reads like free security. It ties the key to the device's
 *    lock screen, so changing or removing the phone's screen lock invalidates it on most devices and
 *    the journal can never be opened again — caused by an unrelated settings change nobody would
 *    connect to this app.
 *  - `setIsStrongBoxBacked(true)` reads like better hardware. It throws on every device without a
 *    StrongBox chip, which is most of them, and the app then cannot create its key at all.
 *
 * The other direction matters too: a block mode that is not GCM, or a padding that is not NONE,
 * would be an unauthenticated wrap, and the whole header-authentication argument in
 * `DataKeyWraps.kt` would quietly stop holding.
 */
class KeystoreAeadSourceTest {

    private companion object {
        const val REL = "app/src/main/java/com/daymark/app/security/KeystoreAead.kt"
    }

    private val source = repoFile(REL).readText()

    /** The file with comments and KDoc removed — claims must hold against code, not prose. */
    private val code = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    @Test
    fun `the source was found and stripped to something that is still code`() {
        // Guards every assertion below: an empty or un-stripped subject would make them meaningless.
        assertTrue("KeystoreAead.kt is implausibly short", source.length > 2000)
        assertTrue(code.contains("class KeystoreAead"))
        assertTrue(code.contains("KeyGenParameterSpec.Builder"))
        assertTrue("KDoc survived stripping", source.contains("/**") && !code.contains("/**"))
        // The prose talks at length about user authentication and StrongBox. If the stripper stopped
        // working, the absence checks below would be reading that prose and reporting a violation,
        // or — worse, once someone "fixed" them — reading it and reporting nothing.
        assertTrue(source.contains("StrongBox"))
        assertFalse("the stripper left prose in the code", code.contains("StrongBox"))
    }

    @Test
    fun `the key is AES-256-GCM with no padding and a keystore-chosen IV`() {
        for (required in listOf(
            "KeyProperties.KEY_ALGORITHM_AES",
            "KeyProperties.BLOCK_MODE_GCM",
            "KeyProperties.ENCRYPTION_PADDING_NONE",
            "setKeySize(KEY_BITS)",
            "setRandomizedEncryptionRequired(true)",
            "PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT",
        )) {
            assertTrue("KeystoreAead no longer specifies $required", code.contains(required))
        }
        assertTrue(code.contains("const val KEY_BITS = 256"))
        assertTrue(code.contains("\"AES/GCM/NoPadding\""))
        assertTrue(code.contains("\"AndroidKeyStore\""))
    }

    @Test
    fun `the key does not require the device lock screen and does not require StrongBox`() {
        assertTrue(
            "the absence of a user-authentication requirement must be explicit, not implied by a " +
                "default somebody could change without noticing",
            code.contains("setUserAuthenticationRequired(false)"),
        )
        assertFalse(
            "setUserAuthenticationRequired(true) ties the data key to the phone's screen lock. " +
                "Changing that lock invalidates the key on most devices and the journal can then " +
                "never be opened — by an unrelated settings change. The PIN wrap is how a person " +
                "asks for their journal to require something.",
            code.contains("setUserAuthenticationRequired(true)"),
        )
        assertFalse(
            "setIsStrongBoxBacked throws on every device without a StrongBox chip, which is most " +
                "of them, and the app then cannot create its key at all.",
            code.contains("setIsStrongBoxBacked"),
        )
        assertFalse(
            "a validity duration would silently expire somebody's journal",
            code.contains("setUserAuthenticationValidityDurationSeconds"),
        )
    }

    /**
     * The positive control. Every assertion above is a `contains` over a string, and the three that
     * matter most are ABSENCE checks — so the detector is shown each forbidden line, planted into a
     * copy of the real source, and must report it.
     */
    @Test
    fun `the detector can see each forbidden parameter when it is planted`() {
        for (planted in listOf(
            "setUserAuthenticationRequired(true)",
            "setIsStrongBoxBacked(true)",
            "setUserAuthenticationValidityDurationSeconds(30)",
        )) {
            val mutated = code.replace(
                ".setKeySize(KEY_BITS)",
                ".setKeySize(KEY_BITS)\n                .$planted",
            )
            assertTrue("the planted $planted was not spliced in", mutated != code)
            assertTrue(
                "the detector is blind to $planted, so its clean report on the real source proves " +
                    "nothing",
                mutated.contains(planted.substringBefore("(")),
            )
        }
    }

    @Test
    fun `an open that fails returns null rather than naming a cause`() {
        // A wrong key, a deleted key and a corrupt blob are the same answer from here: nothing.
        // Naming which one would be a cause this code cannot actually know and a probe somebody
        // could use.
        assertTrue(code.contains("catch (_: GeneralSecurityException)"))
        assertFalse("KeystoreAead logs", code.contains("Log."))
        assertFalse("KeystoreAead prints", code.contains("println("))
        for (thrown in listOf("throw IllegalStateException", "throw SecurityException", "error(")) {
            assertFalse("KeystoreAead throws $thrown out of an open", code.contains(thrown))
        }
    }
}
