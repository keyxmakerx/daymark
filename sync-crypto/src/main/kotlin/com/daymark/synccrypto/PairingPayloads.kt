package com.daymark.synccrypto

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * What travels inside the pairing envelope (docs/COMPANION_PAIRING.md §13.4). Kotlin mirror of
 * `companion/web/src/lib/pairing/payloads.ts`, whose header carries the reasoning; the phone holds
 * the owner's half, so it reads E1 (the clinician's offer) and writes E2 (the owner's two public
 * keys).
 *
 * The offer is authenticated by its envelope opening, never by anything inside it. Reading is
 * strict: an unknown `v`, a missing or extra field, a key or ticket of the wrong length, or a name
 * with control, format or bidi characters is refused whole, never repaired. A refusal is null and
 * says nothing about which rule it broke.
 *
 * Base64 is read canonically ([SyncCrypto.fromBase64]), where the web's `atob` would also take a
 * last character with stray low bits. Every writer, on both sides, writes the canonical form, so
 * the difference only refuses bytes nobody sends.
 */
object PairingPayloads {

    const val OFFER_VERSION = 1
    const val OWNER_KEYS_VERSION = 1
    const val DISPLAY_NAME_MAX_CODEPOINTS = 64
    const val ENROL_TICKET_BYTES = 32
    private const val KEY_BYTES = 32

    /** The clinician's offer. [displayName] is what they typed, shown labelled as such and trusted for nothing. */
    data class TherapistOffer(
        val boxPubB64: String,
        val signPubB64: String,
        val displayName: String,
        val enrolTicketB64: String,
    )

    /** The owner's public halves, and nothing else. */
    data class OwnerKeys(val boxPubB64: String, val signPubB64: String)

    /** At most 64 code points, and no control, format, surrogate, private-use or unassigned character. */
    fun validDisplayName(name: String): Boolean =
        name.codePointCount(0, name.length) <= DISPLAY_NAME_MAX_CODEPOINTS && !OTHER.containsMatchIn(name)

    /** The offer, or null for anything that is not exactly a v1 offer. */
    fun decodeTherapistOffer(bytes: ByteArray): TherapistOffer? {
        val fields = versioned(bytes, OFFER_VERSION, setOf("boxPubB64", "signPubB64", "displayName", "enrolTicketB64"))
            ?: return null
        val box = fields["boxPubB64"] as? String ?: return null
        val sign = fields["signPubB64"] as? String ?: return null
        val ticket = fields["enrolTicketB64"] as? String ?: return null
        val name = fields["displayName"] as? String ?: return null
        if (decodedLength(box) != KEY_BYTES || decodedLength(sign) != KEY_BYTES) return null
        if (decodedLength(ticket) != ENROL_TICKET_BYTES || !validDisplayName(name)) return null
        return TherapistOffer(box, sign, name, ticket)
    }

    /** The E2 bytes: `{"v":1,"boxPubB64":…,"signPubB64":…}`, exactly as the web writes them. */
    fun encodeOwnerKeys(keys: OwnerKeys): ByteArray {
        require(decodedLength(keys.boxPubB64) == KEY_BYTES && decodedLength(keys.signPubB64) == KEY_BYTES) {
            "refusing to encode invalid owner keys"
        }
        // Base64url needs no JSON escaping, so the text is assembled rather than serialised.
        return """{"v":$OWNER_KEYS_VERSION,"boxPubB64":"${keys.boxPubB64}","signPubB64":"${keys.signPubB64}"}"""
            .toByteArray(Charsets.UTF_8)
    }

    /** The E2 the phone seals for one identity. */
    fun ownerKeysOf(identity: SyncCrypto.OwnerIdentity): OwnerKeys =
        OwnerKeys(SyncCrypto.toBase64(identity.boxPublicKey), SyncCrypto.toBase64(identity.signPublicKey))

    /**
     * The object's fields without `v`, when the bytes are UTF-8 JSON, an object, `v` equals
     * [version] as a number (as `===` compares it on the web, so `1.0` is 1), and the other names
     * are exactly [names].
     */
    private fun versioned(bytes: ByteArray, version: Int, names: Set<String>): Map<String, Any?>? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        val parsed = try {
            StrictJson.parse(text)
        } catch (_: StrictJson.JsonException) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val obj = parsed as? Map<String, Any?> ?: return null
        val v = obj["v"] as? StrictJson.Number ?: return null
        if (v.lexeme.toDoubleOrNull() != version.toDouble()) return null
        val rest = obj - "v"
        return if (rest.keys == names) rest else null
    }

    private fun decodedLength(b64: String): Int? = try {
        SyncCrypto.fromBase64(b64).size
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Unicode general category C, as `\p{C}` matches it in the web's `u`-flag regex. */
    private val OTHER = Regex("""\p{C}""")
}
