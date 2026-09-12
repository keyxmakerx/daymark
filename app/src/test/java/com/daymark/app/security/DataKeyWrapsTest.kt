package com.daymark.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The wraps around the data key: three of them, each opened by a different thing, none of them
 * able to open another's.
 *
 * ## What is and is not proved here
 *
 * Everything in `DataKeyWraps` is ordinary JVM code and runs for real in these tests — the blob
 * format, the PBKDF2 derivation, the AES-GCM sealing, the refusals. What does NOT run here is the
 * one implementation of [Aead] whose key lives in the Android Keystore: that class cannot be
 * constructed off a phone. So these tests exercise the KEYSTORE wrap through an ordinary AES-GCM
 * key instead. That is a real gap and it is named rather than papered over: the blob format and the
 * open/seal contract are proved, the Keystore adapter behind them is not, and it has never run
 * anywhere.
 *
 * ## Iteration counts in here are deliberately tiny
 *
 * Most tests derive with a thousand iterations rather than the production four hundred thousand,
 * because a suite that takes a minute is a suite people stop running. One test uses the real
 * constant, so the production parameters are known to work rather than assumed to.
 */
class DataKeyWrapsTest {

    private val random = SecureRandom()
    private val dek = ByteArray(32) { (it * 7 + 3).toByte() }
    private val salt = ByteArray(16) { (it * 11 + 5).toByte() }
    private val fast = 1_000

    private fun aeadFor(key: ByteArray): Aead = AesGcmAead(key)

    private fun pinWrap(pin: String, iterations: Int = fast, s: ByteArray = salt): ByteArray =
        DataKeyWraps.sealWithSecret(WrapKind.PIN, pin.toCharArray(), dek, s, iterations, ::aeadFor)

    private fun openPin(blob: ByteArray, pin: String): ByteArray? =
        DataKeyWraps.openWithSecret(blob, WrapKind.PIN, pin.toCharArray(), ::aeadFor)

    // ─── Round trips ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a PIN wrap gives back the same data key`() {
        val blob = pinWrap("604913")
        assertArrayEquals(dek, openPin(blob, "604913"))
    }

    @Test
    fun `a recovery-code wrap gives back the same data key`() {
        val code = RecoveryCodes.generate(random)
        val blob = DataKeyWraps.sealWithSecret(
            WrapKind.RECOVERY, code.canonical.toCharArray(), dek, salt, fast, ::aeadFor,
        )
        assertArrayEquals(
            dek,
            DataKeyWraps.openWithSecret(
                blob, WrapKind.RECOVERY, code.canonical.toCharArray(), ::aeadFor,
            ),
        )
    }

    @Test
    fun `a device-key wrap gives back the same data key`() {
        // Standing in for the Android Keystore, which cannot be reached from a JVM test.
        val deviceKey = AesGcmAead(ByteArray(32) { (it + 40).toByte() })
        val blob = DataKeyWraps.sealWithDeviceKey(dek, deviceKey)
        assertArrayEquals(dek, DataKeyWraps.openWithDeviceKey(blob, deviceKey))
    }

    @Test
    fun `the production iteration count works`() {
        // Slow on purpose, once. Everything else here runs at a thousand iterations, which would
        // not notice a production constant that some provider refuses.
        val blob = pinWrap("604913", DataKeyWraps.PIN_ITERATIONS)
        assertArrayEquals(dek, openPin(blob, "604913"))
        assertEquals(DataKeyWraps.PIN_ITERATIONS, DataKeyWraps.headerOf(blob)!!.iterations)
        // The work factor is at least what PinManager already spends verifying a PIN. A wrap that
        // is cheaper than the check standing in front of it is the wrong way round.
        assertTrue(DataKeyWraps.PIN_ITERATIONS >= 210_000)
        assertTrue(DataKeyWraps.RECOVERY_ITERATIONS >= 210_000)
    }

    // ─── Failing closed ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a wrong PIN opens nothing, and does not throw`() {
        val blob = pinWrap("604913")
        for (wrong in listOf("604914", "104913", "60491", "6049133", "", "604912")) {
            assertNull("\"$wrong\" opened a wrap sealed with a different PIN", openPin(blob, wrong))
        }
    }

    /**
     * THE MUTATION CONTROL for the test above.
     *
     * "A wrong PIN opens nothing" is an absence claim, and an absence claim is worth exactly as much
     * as the demonstration that the assertion can see the thing it says is absent. So the property
     * is broken on purpose here — the AEAD is replaced by one that ignores its key entirely — and
     * the wrong PIN must then succeed. If this test ever fails, the one above it has stopped
     * testing anything, whatever colour it is reporting.
     */
    @Test
    fun `with the cipher removed, a wrong PIN does open the wrap`() {
        val blind = { _: ByteArray -> NoCipher() as Aead }
        val blob = DataKeyWraps.sealWithSecret(
            WrapKind.PIN, "604913".toCharArray(), dek, salt, fast, blind,
        )
        val opened = DataKeyWraps.openWithSecret(blob, WrapKind.PIN, "000000".toCharArray(), blind)
        assertArrayEquals(
            "breaking the cipher did not make the wrong PIN succeed, so the test that says a " +
                "wrong PIN fails is not watching the cipher at all",
            dek,
            opened,
        )
    }

    /** Not an AEAD. Seals by copying, opens by copying, looks at no key and no associated data. */
    private class NoCipher : Aead {
        override fun seal(plaintext: ByteArray, associatedData: ByteArray) = plaintext.copyOf()
        override fun open(sealed: ByteArray, associatedData: ByteArray) = sealed.copyOf()
    }

    @Test
    fun `a wrap of one kind cannot be opened as another`() {
        val pin = "604913"
        val blob = pinWrap(pin)
        // The right secret, the right blob, the wrong door. The kind is inside the authenticated
        // header, so this is a real check rather than a courtesy.
        assertNull(DataKeyWraps.openWithSecret(blob, WrapKind.RECOVERY, pin.toCharArray(), ::aeadFor))
        assertNull(DataKeyWraps.openWithSecret(blob, WrapKind.KEYSTORE, pin.toCharArray(), ::aeadFor))
        assertNull(DataKeyWraps.openWithDeviceKey(blob, AesGcmAead(ByteArray(32))))
        // Positive control: the same blob through the right door does open.
        assertArrayEquals(dek, openPin(blob, pin))
    }

    @Test
    fun `a blob from an unknown format version is refused rather than guessed at`() {
        val blob = pinWrap("604913")
        // Positive control first: unmodified, it opens. Without this, "refused" below could mean
        // "everything is refused" and prove nothing.
        assertNotNull(openPin(blob, "604913"))
        for (version in listOf(0, 2, 99, 255)) {
            val other = blob.copyOf().also { it[0] = version.toByte() }
            assertNull("format version $version was read as if it were version 1", openPin(other, "604913"))
            assertNull(DataKeyWraps.headerOf(other))
        }
    }

    @Test
    fun `the header cannot be edited`() {
        val blob = pinWrap("604913", 50_000)
        assertNotNull(openPin(blob, "604913"))

        // Lowering the iteration count is the edit that matters: it would turn a PIN search that
        // costs real time into one that costs none, and the ciphertext would be untouched.
        val cheapened = blob.copyOf()
        cheapened[3] = 0; cheapened[4] = 0; cheapened[5] = 0; cheapened[6] = 1
        assertEquals(1, DataKeyWraps.headerOf(cheapened)!!.iterations)
        assertNull("the iteration count was rewritten and the blob still opened", openPin(cheapened, "604913"))

        // The salt, which decides which key a PIN derives to.
        val resalted = blob.copyOf().also { it[8] = (it[8] + 1).toByte() }
        assertNull("the salt was rewritten and the blob still opened", openPin(resalted, "604913"))

        // The kind byte, which is what keeps the three wraps apart.
        val relabelled = blob.copyOf().also { it[1] = WrapKind.RECOVERY.id }
        assertNull(
            "the kind was rewritten and the blob still opened",
            DataKeyWraps.openWithSecret(relabelled, WrapKind.RECOVERY, "604913".toCharArray(), ::aeadFor),
        )
    }

    @Test
    fun `a truncated or malformed blob is refused`() {
        val blob = pinWrap("604913")
        for (length in listOf(0, 1, 7, 8, 23, blob.size - 1)) {
            assertNull("a $length-byte blob was accepted", openPin(blob.copyOf(length), "604913"))
        }
        // A PBKDF2 blob claiming no salt would derive the same key for everybody on earth.
        val saltless = byteArrayOf(1, WrapKind.PIN.id, DataKeyWraps.KDF_PBKDF2_HMAC_SHA256, 0, 0, 0, 1, 0, 9, 9)
        assertNull(DataKeyWraps.headerOf(saltless))
        // A device wrap claiming a salt is not one this version writes.
        val saltedDevice = byteArrayOf(1, WrapKind.KEYSTORE.id, DataKeyWraps.KDF_NONE, 0, 0, 0, 0, 1, 7, 9)
        assertNull(DataKeyWraps.headerOf(saltedDevice))
        // An unknown key-derivation function.
        val unknownKdf = byteArrayOf(1, WrapKind.PIN.id, 7, 0, 0, 0, 1, 0, 9)
        assertNull(DataKeyWraps.headerOf(unknownKdf))
        // A body of nothing at all.
        val headerOnly = byteArrayOf(1, WrapKind.KEYSTORE.id, DataKeyWraps.KDF_NONE, 0, 0, 0, 0, 0)
        assertNull(DataKeyWraps.headerOf(headerOnly))
    }

    // ─── Independence, which is what makes changing a PIN cheap ────────────────────────────────

    @Test
    fun `the three wraps are independent`() {
        val deviceKey = AesGcmAead(ByteArray(32) { (it + 40).toByte() })
        val code = RecoveryCodes.generate(random)
        val keystore = DataKeyWraps.sealWithDeviceKey(dek, deviceKey)
        val pin = pinWrap("604913")
        val recovery = DataKeyWraps.sealWithSecret(
            WrapKind.RECOVERY, code.canonical.toCharArray(), dek, DataKeyWraps.newSalt(random), fast, ::aeadFor,
        )

        // All three hold the same key, which is the whole design: the database is encrypted once.
        assertArrayEquals(dek, DataKeyWraps.openWithDeviceKey(keystore, deviceKey))
        assertArrayEquals(dek, openPin(pin, "604913"))
        assertArrayEquals(
            dek,
            DataKeyWraps.openWithSecret(recovery, WrapKind.RECOVERY, code.canonical.toCharArray(), ::aeadFor),
        )

        // Destroying one leaves the others untouched. This is what "lost PIN, has code" rests on.
        val ruinedPin = pin.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(openPin(ruinedPin, "604913"))
        assertArrayEquals(dek, DataKeyWraps.openWithDeviceKey(keystore, deviceKey))
        assertArrayEquals(
            dek,
            DataKeyWraps.openWithSecret(recovery, WrapKind.RECOVERY, code.canonical.toCharArray(), ::aeadFor),
        )
    }

    @Test
    fun `changing the PIN rewrites one blob and leaves the data key alone`() {
        val code = RecoveryCodes.generate(random)
        val recovery = DataKeyWraps.sealWithSecret(
            WrapKind.RECOVERY, code.canonical.toCharArray(), dek, DataKeyWraps.newSalt(random), fast, ::aeadFor,
        )
        val before = pinWrap("604913", fast, DataKeyWraps.newSalt(random))
        val after = pinWrap("871226", fast, DataKeyWraps.newSalt(random))

        assertFalse("a new PIN wrap is byte-identical to the old one", before.contentEquals(after))
        assertNull("the old PIN still opens the new wrap", openPin(after, "604913"))
        assertArrayEquals(dek, openPin(after, "871226"))
        // The database is never re-encrypted, so the code written on paper months ago still works.
        assertArrayEquals(
            dek,
            DataKeyWraps.openWithSecret(recovery, WrapKind.RECOVERY, code.canonical.toCharArray(), ::aeadFor),
        )
    }

    @Test
    fun `two wraps of the same key under the same PIN are not the same bytes`() {
        // A fresh IV per seal, and a fresh salt per wrap. Identical blobs would mean one of the two
        // is fixed, and a fixed IV with a fixed key is the classic way to lose GCM entirely.
        val a = pinWrap("604913", fast, DataKeyWraps.newSalt(random))
        val b = pinWrap("604913", fast, DataKeyWraps.newSalt(random))
        assertFalse(a.contentEquals(b))
        val sameSaltA = pinWrap("604913")
        val sameSaltB = pinWrap("604913")
        assertFalse("the IV is not fresh per seal", sameSaltA.contentEquals(sameSaltB))
    }

    // ─── Keys and their spelling ───────────────────────────────────────────────────────────────

    @Test
    fun `a fresh data key is 32 bytes and is not the last one`() {
        val a = DataKeyWraps.newDataKey(random)
        val b = DataKeyWraps.newDataKey(random)
        assertEquals(32, a.size)
        assertEquals(32, b.size)
        assertFalse(a.contentEquals(b))
        assertFalse("a data key of all zeroes", a.all { it == 0.toByte() })
    }

    @Test
    fun `the raw-key spelling is what SQLCipher recognises`() {
        // Handed anything else, SQLCipher treats the bytes as a PASSPHRASE and runs its own PBKDF2
        // over them on every open. The x'...' wrapper and lower-case hex are what it matches on.
        val key = ByteArray(32) { it.toByte() }
        val spelling = String(DataKeyWraps.rawKeySpelling(key), Charsets.US_ASCII)
        assertEquals("x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'", spelling)
        assertEquals(67, spelling.length)
        assertTrue(spelling.startsWith("x'"))
        assertTrue(spelling.endsWith("'"))
        assertEquals(64, spelling.drop(2).dropLast(1).length)
        assertTrue("the hex must be lower case", spelling.drop(2).dropLast(1).none { it.isUpperCase() })

        // High bytes, where a signed-byte bug would show up as a negative number or a short string.
        val high = ByteArray(32) { 0xFF.toByte() }
        assertEquals("x'${"ff".repeat(32)}'", String(DataKeyWraps.rawKeySpelling(high), Charsets.US_ASCII))
    }

    @Test
    fun `derivation depends on the secret, the salt and the count`() {
        val base = DataKeyWraps.deriveKey("604913".toCharArray(), salt, fast)
        assertEquals(32, base.size)
        assertArrayEquals(base, DataKeyWraps.deriveKey("604913".toCharArray(), salt, fast))
        assertFalse(base.contentEquals(DataKeyWraps.deriveKey("604914".toCharArray(), salt, fast)))
        assertFalse(base.contentEquals(DataKeyWraps.deriveKey("604913".toCharArray(), DataKeyWraps.newSalt(random), fast)))
        assertFalse(base.contentEquals(DataKeyWraps.deriveKey("604913".toCharArray(), salt, fast + 1)))
    }
}
