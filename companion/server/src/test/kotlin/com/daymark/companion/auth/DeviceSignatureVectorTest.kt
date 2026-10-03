package com.daymark.companion.auth

import com.daymark.companion.TestPhone
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The signed request and the pairing code, byte for byte (#186, #189): the vector a phone's own
 * implementation reproduces. Every expected value below was made outside this code base, with
 * OpenSSL's Ed25519 and Python's BLAKE2b, and libsodium (`crypto_sign_seed_keypair`,
 * `crypto_sign_detached`, `crypto_generichash`) makes the same bytes; they are written here as
 * literals. This test holds the server's implementation to them, and a phone's implementation must
 * make the same bytes from the same inputs.
 *
 * Inputs: the Ed25519 seed 0x40..0x5f; `PUT /v1/snapshots/devA/7` with the body `{"hello":"daymark"}`,
 * at 1790000000 (seconds), with the nonce 0x60..0x6f, carrying none of the signed headers; a share,
 * `PUT /v1/rel/<relRef>/shares/journal/1` with the body `{"sealed":"daymark"}`, at the same time, with
 * the nonce 0x70..0x7f, carrying X-Rel-Token and X-Share-Meta; and the pairing code `K7M4RD96QA`.
 */
class DeviceSignatureVectorTest {

    private val seed = ByteArray(32) { (0x40 + it).toByte() }
    private val phone = TestPhone(seed)
    private val body = """{"hello":"daymark"}""".toByteArray(Charsets.UTF_8)
    private val nonce = DeviceSignature.b64url(ByteArray(16) { (0x60 + it).toByte() })

    private val publicKey = "JUO5L_EJVRFHatyDadtt3JM2ZaEZeN2hQE7hBmypVZ0"
    private val keyId = "DpNOAhg_l-DNg60Q1sS-Wg"
    private val bodyHash = "sXrdRRlOF-Z835D9OT4rIJaSylwF8-IU6r7zpHzHOcs"
    private val message =
        "daymark-request-v1\nPUT\n/v1/snapshots/devA/7\nsXrdRRlOF-Z835D9OT4rIJaSylwF8-IU6r7zpHzHOcs\n1790000000\nYGFiY2RlZmdoaWprbG1ubw\n" +
            "if-match:\nif-none-match:\nx-rel-token:\nx-setting-key:\nx-share-meta:"
    private val signature = "KX5heYKNVw0yF2iVi0SaOAQ61a1rx8wNM3hW5e5cCf-i8tNVy2LtBOCEwr2ypR9fNyz08EO0jQXm6NbwqzUACA"

    // The share: two of the signed headers carried, three not.
    private val inboxToken = "inbox-token-for-the-vector"
    private val relRef = "kKfcdWz4QtsLdoGu0LM0G9FQKJjJ8X8ZPL_3Z80e6X0"
    private val shareMeta = "eyJzaGFyZUlkIjoicyIsInZlcnNpb24iOjEsImV4cGlyeSI6MTc5MDYwNDgwMDAwMCwib3duZXJTaWduaW5nRnAiOiJmcCJ9"
    private val shareTarget = "/v1/rel/kKfcdWz4QtsLdoGu0LM0G9FQKJjJ8X8ZPL_3Z80e6X0/shares/journal/1"
    private val shareBody = """{"sealed":"daymark"}""".toByteArray(Charsets.UTF_8)
    private val shareBodyHash = "Eyi_Vohb7pq6qCoBcCN-kIqXNfhLMWNooCvDNCMS_oM"
    private val shareNonce = DeviceSignature.b64url(ByteArray(16) { (0x70 + it).toByte() })
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
    fun `the request vector`() {
        assertEquals(publicKey, phone.publicKeyB64)
        assertEquals(keyId, DeviceSignature.keyIdOf(phone.publicKey))
        assertEquals("YGFiY2RlZmdoaWprbG1ubw", nonce)
        assertEquals(bodyHash, DeviceSignature.bodyHash(body))
        assertEquals("DldRwCblQ7Loqy6wYJnaodHl30d3j3eH-qtFzfEv46g", DeviceSignature.bodyHash(ByteArray(0)), "no body hashes no bytes")
        assertEquals(listOf("If-Match", "If-None-Match", "X-Rel-Token", "X-Setting-Key", "X-Share-Meta"), DeviceSignature.SIGNED_HEADERS)
        val carriesNone = assertNotNull(DeviceSignature.signedHeaderValues { null })
        val built = DeviceSignature.requestMessage("PUT", "/v1/snapshots/devA/7", bodyHash, "1790000000", nonce, carriesNone)
        assertEquals(message, built.toString(Charsets.UTF_8))
        assertEquals(
            mapOf(
                "X-Device-Key" to keyId,
                "X-Device-Time" to "1790000000",
                "X-Device-Nonce" to nonce,
                "X-Device-Signature" to signature,
            ),
            phone.headers("PUT", "/v1/snapshots/devA/7", body, 1_790_000_000L, nonce),
        )
        assertTrue(DeviceSignature.verify(phone.publicKey, built, decode(signature, 64)), "the server takes the vector's signature")
    }

    @Test
    fun `the request vector that carries signed headers`() {
        assertEquals(relRef, Secrets.relRefOf(inboxToken))
        assertEquals("cHFyc3R1dnd4eXp7fH1-fw", shareNonce)
        assertEquals(shareBodyHash, DeviceSignature.bodyHash(shareBody))
        // The header names as a request may spell them: case does not matter, and the lines are lower case.
        val carried = mapOf("x-share-meta" to listOf(shareMeta), "X-REL-TOKEN" to listOf(inboxToken))
        val values = assertNotNull(DeviceSignature.signedHeaderValues { name -> carried.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value })
        assertEquals(listOf(null, null, inboxToken, null, shareMeta), values)
        val built = DeviceSignature.requestMessage("PUT", shareTarget, shareBodyHash, "1790000000", shareNonce, values)
        assertEquals(shareMessage, built.toString(Charsets.UTF_8))
        val headers = phone.headers(
            "PUT", shareTarget, shareBody, 1_790_000_000L, shareNonce,
            signed = mapOf("X-Rel-Token" to inboxToken, "X-Share-Meta" to shareMeta),
        )
        assertEquals(shareSignature, headers[DeviceSignature.SIGNATURE_HEADER])
        assertTrue(DeviceSignature.verify(phone.publicKey, built, decode(shareSignature, 64)), "the server takes the vector's signature")
    }

    @Test
    fun `a request carries each signed header at most once, and never empty`() {
        val name = DeviceSignature.SIGNED_HEADERS.first()
        fun carrying(vararg values: String) = DeviceSignature.signedHeaderValues { if (it == name) values.toList() else null }
        assertEquals(listOf("\"etag\"", null, null, null, null), carrying("\"etag\""), "control: carried once, it is its line's value")
        assertNull(carrying("\"etag\"", "\"etag\""), "carried twice, even with one value")
        assertNull(carrying("\"etag\"", "\"other\""), "carried twice, with two")
        assertNull(carrying(""), "carried empty")
        assertEquals(List(DeviceSignature.SIGNED_HEADERS.size) { null }, DeviceSignature.signedHeaderValues { emptyList() }, "no value at all is not carrying it")
    }

    @Test
    fun `the pairing vector`() {
        assertTrue(PairingCode.isCanonical(code))
        assertEquals("K7M4R-D96QA", PairingCode.display(code))
        assertEquals(codeId, DeviceSignature.codeIdOf(code))
        assertEquals(redeemProof, phone.redeemProof(code))
        assertTrue(DeviceSignature.verify(phone.publicKey, DeviceSignature.redeemMessage(codeId, publicKey), decode(redeemProof, 64)))
        assertTrue(DeviceSignature.isUsablePublicKey(phone.publicKey))
    }

    @Test
    fun `a change to any line of the message is refused`() {
        for ((signedMessage, signatureB64) in listOf(message to signature, shareMessage to shareSignature)) {
            val signed = decode(signatureB64, 64)
            val lines = signedMessage.split('\n')
            assertEquals(6 + DeviceSignature.SIGNED_HEADERS.size, lines.size)
            for (i in 1 until lines.size) {
                // Its first character replaced, and a character added at its end: a header's value as well as its name.
                for (changedLine in listOf(alter(lines[i]), lines[i] + "x")) {
                    val changed = lines.toMutableList().also { it[i] = changedLine }.joinToString("\n")
                    assertNotEquals(signedMessage, changed, "line $i must really change")
                    assertFalse(DeviceSignature.verify(phone.publicKey, changed.toByteArray(Charsets.UTF_8), signed), "line $i changed: ${lines[i]}")
                }
            }
            // Control: the message as signed is taken.
            assertTrue(DeviceSignature.verify(phone.publicKey, signedMessage.toByteArray(Charsets.UTF_8), signed))
        }
    }

    @Test
    fun `every base64 value has one spelling`() {
        val canonical = nonce
        assertNotNull(DeviceSignature.decodeCanonical(canonical, 16), "control: the canonical spelling decodes")
        assertNull(DeviceSignature.decodeCanonical("$canonical==", 16), "padded")
        assertNull(DeviceSignature.decodeCanonical(canonical.dropLast(1), 16), "short")
        // The last of 22 characters carries two bits of the 16 bytes; the four below must be zero.
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val last = alphabet.indexOf(canonical.last())
        val stray = canonical.dropLast(1) + alphabet[last or 1]
        assertNotEquals(canonical, stray, "the respelling must really differ")
        assertTrue(Base64.getUrlDecoder().decode(stray).contentEquals(Base64.getUrlDecoder().decode(canonical)), "control: a lenient decoder reads the same bytes")
        assertNull(DeviceSignature.decodeCanonical(stray, 16), "a second spelling of the same nonce is refused")
        // The standard alphabet's spelling of a value with a '-' or '_' in it.
        val withSymbols = DeviceSignature.b64url(ByteArray(16) { 0xFB.toByte() })
        assertTrue('-' in withSymbols || '_' in withSymbols, "the fixture needs a URL-safe symbol")
        assertNull(DeviceSignature.decodeCanonical(withSymbols.replace('-', '+').replace('_', '/'), 16), "standard base64")
        assertNotNull(DeviceSignature.decodeCanonical(withSymbols, 16))
    }

    @Test
    fun `a request time is whole seconds, as digits, with no leading zero`() {
        assertEquals(1_790_000_000L, DeviceSignature.parseTime("1790000000"))
        assertEquals(0L, DeviceSignature.parseTime("0"))
        for (bad in listOf("01790000000", "+1790000000", "-5", "1790000000.0", "", " 1790000000", "1e9")) {
            assertNull(DeviceSignature.parseTime(bad), "'$bad'")
        }
    }

    @Test
    fun `the check symbol catches every single wrong symbol and every swap of two`() {
        assertTrue(PairingCode.isCanonical(code), "control")
        for (i in 0 until PairingCode.SYMBOLS) {
            for (symbol in PairingCode.ALPHABET) {
                if (symbol == code[i]) continue
                val changed = code.substring(0, i) + symbol + code.substring(i + 1)
                assertFalse(PairingCode.isCanonical(changed), "symbol $i as $symbol")
            }
        }
        for (i in 0 until PairingCode.PAYLOAD_SYMBOLS) {
            for (j in i + 1 until PairingCode.PAYLOAD_SYMBOLS) {
                if (code[i] == code[j]) continue
                val swapped = code.toCharArray().also { it[i] = code[j]; it[j] = code[i] }.concatToString()
                assertNotEquals(code, swapped)
                assertFalse(PairingCode.isCanonical(swapped), "symbols $i and $j swapped")
            }
        }
        assertFalse(PairingCode.isCanonical(code.lowercase()), "the phone sends the canonical, upper-case form")
        repeat(500) {
            val drawn = PairingCode.generate()
            assertTrue(PairingCode.isCanonical(drawn), drawn)
        }
    }

    private fun decode(b64: String, length: Int): ByteArray = assertNotNull(DeviceSignature.decodeCanonical(b64, length))

    /** [line] with its first character replaced by a different one: a change, whatever the line holds. */
    private fun alter(line: String): String {
        val first = line[0]
        val replacement = if (first == 'A') 'B' else 'A'
        return replacement + line.substring(1)
    }
}
