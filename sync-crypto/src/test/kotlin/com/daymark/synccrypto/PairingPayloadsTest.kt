package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The phone reads E1 and writes E2 as `companion/web/src/lib/pairing/payloads.ts` does; the cases mirror `payloads.test.ts`. */
class PairingPayloadsTest {

    private fun b64(n: Int, fill: Int = 0) = SyncCrypto.toBase64(ByteArray(n) { fill.toByte() })

    private val box = b64(32, 1)
    private val sign = b64(32, 2)
    private val ticket = b64(32, 3)

    /** The offer as the web's JSON.stringify writes it, with fields replaced or (null) dropped. */
    private fun offerJson(vararg patch: Pair<String, String?>): ByteArray {
        val fields = linkedMapOf(
            "v" to "1",
            "boxPubB64" to "\"$box\"",
            "signPubB64" to "\"$sign\"",
            "displayName" to "\"Dr Example\"",
            "enrolTicketB64" to "\"$ticket\"",
        )
        for ((k, v) in patch) if (v == null) fields.remove(k) else fields[k] = v
        return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }.toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `the web's offer decodes to its fields`() {
        assertEquals(
            PairingPayloads.TherapistOffer(box, sign, "Dr Example", ticket),
            PairingPayloads.decodeTherapistOffer(offerJson()),
        )
    }

    @Test
    fun `refuses every malformed offer, and the control decodes`() {
        val bad = listOf(
            "unknown version" to offerJson("v" to "2"),
            "version as a string" to offerJson("v" to "\"1\""),
            "missing version" to offerJson("v" to null),
            "missing key" to offerJson("boxPubB64" to null),
            "missing ticket" to offerJson("enrolTicketB64" to null),
            "extra field" to offerJson("extra" to "\"x\""),
            "short key" to offerJson("boxPubB64" to "\"${b64(31)}\""),
            "long key" to offerJson("signPubB64" to "\"${b64(33)}\""),
            "short ticket" to offerJson("enrolTicketB64" to "\"${b64(16)}\""),
            "not base64url" to offerJson("enrolTicketB64" to "\"not/base64url+\""),
            "name too long" to offerJson("displayName" to "\"${"x".repeat(65)}\""),
            "name with a control character" to offerJson("displayName" to "\"Dr\\u0000Example\""),
            "name with a bidi override" to offerJson("displayName" to "\"Dr‮Example\""),
            "name not a string" to offerJson("displayName" to "5"),
            "not an object" to "[1,2]".toByteArray(),
            "not json" to "{nope".toByteArray(),
            "not utf-8" to byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x7b),
        )
        for ((why, bytes) in bad) assertNull("should refuse: $why", PairingPayloads.decodeTherapistOffer(bytes))
        assertNotNull(PairingPayloads.decodeTherapistOffer(offerJson()))
    }

    @Test
    fun `a version written 1·0 is 1, as the web compares it`() {
        assertNotNull(PairingPayloads.decodeTherapistOffer(offerJson("v" to "1.0")))
    }

    @Test
    fun `names count code points, not UTF-16 units`() {
        val face = String(Character.toChars(0x1F600))
        assertTrue(PairingPayloads.validDisplayName(face.repeat(64)))
        assertFalse(PairingPayloads.validDisplayName(face.repeat(65)))
        assertTrue(PairingPayloads.validDisplayName("Dr Ana-María"))
        assertFalse(PairingPayloads.validDisplayName("Dr​Example")) // zero-width space, a format character
    }

    @Test
    fun `the owner's keys are written byte for byte as the web writes them`() {
        val bytes = PairingPayloads.encodeOwnerKeys(PairingPayloads.OwnerKeys(box, sign))
        assertEquals("""{"v":1,"boxPubB64":"$box","signPubB64":"$sign"}""", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `the owner's keys are never written from a wrong-length key`() {
        for (keys in listOf(PairingPayloads.OwnerKeys(b64(31), sign), PairingPayloads.OwnerKeys(box, "not/base64"))) {
            try {
                PairingPayloads.encodeOwnerKeys(keys)
                fail("encoded $keys")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `E2 carries the derived identity's public halves`() {
        val crypto = SyncCrypto(LazySodiumJava(SodiumJava()))
        val keys = PairingPayloads.ownerKeysOf(crypto.ownerIdentityFromMaster(ByteArray(32) { it.toByte() }))
        assertEquals("Zd9mRefJZVYSFKZIzfgyItz7yvoRPvxVy2AiO7ObPjg", keys.boxPubB64)
        assertEquals("Ps0OIIdt4nHg8dUlJTef98V9EJ_s6jsPMUI4LHoYOTQ", keys.signPubB64)
    }

    @Test
    fun `the offer decoder refuses the owner's keys, so one is never read as the other`() {
        val e2 = PairingPayloads.encodeOwnerKeys(PairingPayloads.OwnerKeys(box, sign))
        assertNull(PairingPayloads.decodeTherapistOffer(e2))
    }
}
