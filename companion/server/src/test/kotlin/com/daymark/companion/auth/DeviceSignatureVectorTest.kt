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
 * literals. This test holds the server's implementation to them, and a Kotlin test in `sync-crypto`
 * holds the phone's.
 *
 * Inputs: the Ed25519 seed 0x40..0x5f; `PUT /v1/snapshots/devA/7` with the body `{"hello":"daymark"}`,
 * at 1790000000 (seconds), with the nonce 0x60..0x6f; and the pairing code `K7M4RD96QA`.
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
        "daymark-request-v1\nPUT\n/v1/snapshots/devA/7\nsXrdRRlOF-Z835D9OT4rIJaSylwF8-IU6r7zpHzHOcs\n1790000000\nYGFiY2RlZmdoaWprbG1ubw"
    private val signature = "je6R5djSAvXLmRTmBNjX6CwZJh0ODyleyh9rGVoI6yA76enkWScy3OjAlvmpQJnMfjyVTmh0EmryLl4ckafCCQ"
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
        val built = DeviceSignature.requestMessage("PUT", "/v1/snapshots/devA/7", bodyHash, "1790000000", nonce)
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
        val signed = decode(signature, 64)
        val lines = message.split('\n')
        assertEquals(6, lines.size)
        for (i in 1 until lines.size) {
            val changed = lines.toMutableList().also { it[i] = alter(it[i]) }.joinToString("\n")
            assertNotEquals(message, changed, "line $i must really change")
            assertFalse(DeviceSignature.verify(phone.publicKey, changed.toByteArray(Charsets.UTF_8), signed), "line $i changed: ${lines[i]}")
        }
        // Control: the message as signed is taken.
        assertTrue(DeviceSignature.verify(phone.publicKey, message.toByteArray(Charsets.UTF_8), signed))
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
