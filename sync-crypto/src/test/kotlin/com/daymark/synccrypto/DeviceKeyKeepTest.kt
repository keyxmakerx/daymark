package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Keeping the phone's key once the server has registered it (#432): [DeviceKey.seedToKeep] out, and
 * [DeviceKey.restore] back, to the same pair. The server's vector is the proof: the key restored from its
 * seed signs the vector's request, under the vector's nonce, to the server's own bytes.
 */
class DeviceKeyKeepTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val vectorSeed = ByteArray(32) { (0x40 + it).toByte() }
    private val vectorNonce = SyncCrypto.toBase64(ByteArray(16) { (0x60 + it).toByte() })
    private val body = """{"hello":"daymark"}""".toByteArray(Charsets.UTF_8)

    // The server's vector (DeviceSignatureVectorTest): its key id and its signature over PUT /v1/snapshots/devA/7.
    private val vectorKeyId = "DpNOAhg_l-DNg60Q1sS-Wg"
    private val vectorSignature = "KX5heYKNVw0yF2iVi0SaOAQ61a1rx8wNM3hW5e5cCf-i8tNVy2LtBOCEwr2ypR9fNyz08EO0jQXm6NbwqzUACA"

    private fun signVector(key: DeviceKey): String =
        key.signRequestWithNonce("PUT", "/v1/snapshots/devA/7", body, 1_790_000_000L, emptyList(), vectorNonce)
            .getValue(DeviceSignature.SIGNATURE_HEADER)

    @Test
    fun aRestoredKeySignsTheVectorsBytes() {
        val restored = DeviceKey.restore(sodium, vectorSeed)
        assertEquals(vectorKeyId, restored.keyId)
        assertEquals(vectorSignature, signVector(restored))
        assertArrayEquals("the seed kept is the seed the pair was made from", vectorSeed, restored.seedToKeep())

        // The positive control: one bit of the seed changed, derived from the seed, is another key that
        // signs other bytes, so the equalities above are about this seed and no other.
        val other = vectorSeed.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFalse(other.contentEquals(vectorSeed))
        val otherKey = DeviceKey.restore(sodium, other)
        assertNotEquals(vectorKeyId, otherKey.keyId)
        assertNotEquals(vectorSignature, signVector(otherKey))
    }

    @Test
    fun aGeneratedKeyKeptAndRestoredIsTheSamePair() {
        val key = DeviceKey.generate(sodium)
        val seed = key.seedToKeep()
        assertEquals(32, seed.size)
        val restored = DeviceKey.restore(sodium, seed)
        assertEquals(key.publicKeyB64, restored.publicKeyB64)
        assertEquals(key.keyId, restored.keyId)
        assertEquals(key.words(), restored.words())
        // Ed25519 is deterministic: the same message under the same nonce is the same signature.
        assertEquals(signVector(key), signVector(restored))
        // And what the restored key signs, the original key's public key verifies.
        val headers = restored.signRequest("GET", "/v1/devices/registration", ByteArray(0), 1_790_000_000L)
        val message = DeviceSignature.requestMessage(
            "GET",
            "/v1/devices/registration",
            DeviceKey.bodyHash(sodium, ByteArray(0)),
            "1790000000",
            headers.getValue(DeviceSignature.NONCE_HEADER),
            List(5) { null },
        )
        val signature = SyncCrypto.fromBase64(headers.getValue(DeviceSignature.SIGNATURE_HEADER))
        assertTrue(sodium.cryptoSignVerifyDetached(signature, message, message.size, key.publicKey))
    }

    @Test
    fun theSeedHandedOutIsACopy() {
        val key = DeviceKey.generate(sodium)
        val first = key.seedToKeep()
        val kept = first.copyOf()
        first.fill(0) // what a caller does once the seed is sealed
        assertArrayEquals("wiping the copy changed the key's own seed", kept, key.seedToKeep())
        assertEquals(key.keyId, DeviceKey.restore(sodium, key.seedToKeep()).keyId)
    }

    @Test
    fun aSeedOfAnotherLengthIsNotRestored() {
        for (size in listOf(0, 31, 33, 64)) {
            try {
                DeviceKey.restore(sodium, ByteArray(size) { 7 })
                fail("a $size-byte seed was restored")
            } catch (_: IllegalArgumentException) {
                // refused before libsodium reads past the end
            }
        }
        // The control: 32 bytes of the same value are a seed.
        assertEquals(22, DeviceKey.restore(sodium, ByteArray(32) { 7 }).keyId.length)
    }
}
