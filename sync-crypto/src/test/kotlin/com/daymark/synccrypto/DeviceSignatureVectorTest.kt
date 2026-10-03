package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's vector, which the phone reproduces byte for byte (#186, #189, #432). Every expected value
 * below is the server's own, from
 * `companion/server/src/test/kotlin/com/daymark/companion/auth/DeviceSignatureVectorTest.kt`, written
 * here as a literal and never read from there: OpenSSL's Ed25519 and Python's BLAKE2b make the same
 * bytes. A change to the wire format on either side turns the other side's copy of this vector red.
 *
 * Inputs: the Ed25519 seed 0x40..0x5f; `PUT /v1/snapshots/devA/7` with the body `{"hello":"daymark"}`,
 * at 1790000000 (seconds), with the nonce 0x60..0x6f, carrying none of the signed headers; a share,
 * `PUT /v1/rel/<relRef>/shares/journal/1` with the body `{"sealed":"daymark"}`, at the same time, with
 * the nonce 0x70..0x7f, carrying X-Rel-Token and X-Share-Meta; and the pairing code `K7M4RD96QA`.
 */
class DeviceSignatureVectorTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val phone = DeviceKey.fromSeed(sodium, ByteArray(32) { (0x40 + it).toByte() })

    private val body = """{"hello":"daymark"}""".toByteArray(Charsets.UTF_8)
    private val nonce = SyncCrypto.toBase64(ByteArray(16) { (0x60 + it).toByte() })

    private val publicKey = "JUO5L_EJVRFHatyDadtt3JM2ZaEZeN2hQE7hBmypVZ0"
    private val keyId = "DpNOAhg_l-DNg60Q1sS-Wg"
    private val bodyHash = "sXrdRRlOF-Z835D9OT4rIJaSylwF8-IU6r7zpHzHOcs"
    private val message =
        "daymark-request-v1\nPUT\n/v1/snapshots/devA/7\nsXrdRRlOF-Z835D9OT4rIJaSylwF8-IU6r7zpHzHOcs\n1790000000\nYGFiY2RlZmdoaWprbG1ubw\n" +
            "if-match:\nif-none-match:\nx-rel-token:\nx-setting-key:\nx-share-meta:"
    private val signature = "KX5heYKNVw0yF2iVi0SaOAQ61a1rx8wNM3hW5e5cCf-i8tNVy2LtBOCEwr2ypR9fNyz08EO0jQXm6NbwqzUACA"

    // The share: two of the signed headers carried, three not.
    private val inboxToken = "inbox-token-for-the-vector"
    private val shareMeta = "eyJzaGFyZUlkIjoicyIsInZlcnNpb24iOjEsImV4cGlyeSI6MTc5MDYwNDgwMDAwMCwib3duZXJTaWduaW5nRnAiOiJmcCJ9"
    private val shareTarget = "/v1/rel/kKfcdWz4QtsLdoGu0LM0G9FQKJjJ8X8ZPL_3Z80e6X0/shares/journal/1"
    private val shareBody = """{"sealed":"daymark"}""".toByteArray(Charsets.UTF_8)
    private val shareBodyHash = "Eyi_Vohb7pq6qCoBcCN-kIqXNfhLMWNooCvDNCMS_oM"
    private val shareNonce = SyncCrypto.toBase64(ByteArray(16) { (0x70 + it).toByte() })
    private val shareMessage =
        "daymark-request-v1\nPUT\n/v1/rel/kKfcdWz4QtsLdoGu0LM0G9FQKJjJ8X8ZPL_3Z80e6X0/shares/journal/1\n" +
            "Eyi_Vohb7pq6qCoBcCN-kIqXNfhLMWNooCvDNCMS_oM\n1790000000\ncHFyc3R1dnd4eXp7fH1-fw\n" +
            "if-match:\nif-none-match:\nx-rel-token:inbox-token-for-the-vector\nx-setting-key:\n" +
            "x-share-meta:eyJzaGFyZUlkIjoicyIsInZlcnNpb24iOjEsImV4cGlyeSI6MTc5MDYwNDgwMDAwMCwib3duZXJTaWduaW5nRnAiOiJmcCJ9"
    private val shareSignature = "OZI9TMh_v6bZs8u46Juv93SkKQZtU3SJ0tGW6_CYIf7Xqb82UYUdZseP_rx6dSUkjlkMksSNhvDdltN5fdghBg"

    private val code = "K7M4RD96QA"
    private val codeId = "qKclqxCSwn6LS9WmmVUZBOUpoG_OG3vHiPIojmHUiS0"
    private val redeemProof = "HHOrC3VgdC9p_7L2WNN4xsTdyAfXFhwOtLObaFSoc2vnqHX5tqvlqgqw8oTAsqJJO08ae-OQpJPNTgYFh7rMBg"

    @Test
    fun theRequestVector() {
        assertEquals(publicKey, phone.publicKeyB64)
        assertEquals(keyId, phone.keyId)
        assertEquals(keyId, DeviceKey.keyIdOf(sodium, phone.publicKey))
        assertEquals("YGFiY2RlZmdoaWprbG1ubw", nonce)
        assertEquals(bodyHash, DeviceKey.bodyHash(sodium, body))
        assertEquals("no body hashes no bytes", "DldRwCblQ7Loqy6wYJnaodHl30d3j3eH-qtFzfEv46g", DeviceKey.bodyHash(sodium, ByteArray(0)))
        assertEquals(listOf("If-Match", "If-None-Match", "X-Rel-Token", "X-Setting-Key", "X-Share-Meta"), DeviceSignature.SIGNED_HEADERS)
        val carriesNone = DeviceSignature.signedHeaderValues(emptyList())
        assertEquals(List<String?>(5) { null }, carriesNone)
        val built = DeviceSignature.requestMessage("PUT", "/v1/snapshots/devA/7", bodyHash, "1790000000", nonce, carriesNone)
        assertEquals(message, built.toString(Charsets.UTF_8))
        assertEquals(
            "the four headers, in the server's order",
            listOf(
                "X-Device-Key" to keyId,
                "X-Device-Time" to "1790000000",
                "X-Device-Nonce" to nonce,
                "X-Device-Signature" to signature,
            ),
            phone.signRequestWithNonce("PUT", "/v1/snapshots/devA/7", body, 1_790_000_000L, emptyList(), nonce).toList(),
        )
        assertTrue("libsodium takes the vector's signature", verifies(built, signature))
    }

    @Test
    fun theRequestVectorThatCarriesSignedHeaders() {
        assertEquals("cHFyc3R1dnd4eXp7fH1-fw", shareNonce)
        assertEquals(shareBodyHash, DeviceKey.bodyHash(sodium, shareBody))
        // The header names as a request may spell them: case does not matter, and the lines are lower case.
        val carried = listOf("x-share-meta" to shareMeta, "X-REL-TOKEN" to inboxToken)
        val values = DeviceSignature.signedHeaderValues(carried)
        assertEquals(listOf(null, null, inboxToken, null, shareMeta), values)
        val built = DeviceSignature.requestMessage("PUT", shareTarget, shareBodyHash, "1790000000", shareNonce, values)
        assertEquals(shareMessage, built.toString(Charsets.UTF_8))
        assertEquals(
            "the four headers, then the signed ones carried, under the server's spelling",
            listOf(
                "X-Device-Key" to keyId,
                "X-Device-Time" to "1790000000",
                "X-Device-Nonce" to shareNonce,
                "X-Device-Signature" to shareSignature,
                "X-Rel-Token" to inboxToken,
                "X-Share-Meta" to shareMeta,
            ),
            phone.signRequestWithNonce("PUT", shareTarget, shareBody, 1_790_000_000L, carried, shareNonce).toList(),
        )
        assertTrue("libsodium takes the vector's signature", verifies(built, shareSignature))
    }

    @Test
    fun thePairingVector() {
        val parsed = PairingCode.parse(code)
        assertEquals(code, parsed.canonical)
        assertEquals(codeId, DeviceKey.codeIdOf(sodium, parsed))
        assertEquals(
            "daymark-pairing-redeem-v1\n$codeId\n$publicKey",
            DeviceSignature.redeemMessage(codeId, publicKey).toString(Charsets.UTF_8),
        )
        assertEquals(redeemProof, phone.redeemProof(parsed))
        assertTrue("libsodium takes the vector's proof", verifies(DeviceSignature.redeemMessage(codeId, publicKey), redeemProof))
        // The code as the console shows it, typed back in lower case, is the same code and the same proof.
        assertEquals(redeemProof, phone.redeemProof(PairingCode.parse("k7m4r-d96qa")))
    }

    private fun verifies(message: ByteArray, signatureB64: String): Boolean =
        sodium.cryptoSignVerifyDetached(SyncCrypto.fromBase64(signatureB64), message, message.size, SyncCrypto.fromBase64(publicKey))
}
