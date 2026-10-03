package com.daymark.companion.auth

import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.SecureRandom
import java.util.Base64

/**
 * A phone's signed request (#186) and its pairing code (#189), byte for byte: what the phone builds and
 * what the server checks. DeviceSignatureVectorTest holds the fixed vector a phone's own implementation
 * must reproduce.
 *
 * THE KEY is a fresh Ed25519 pair the phone makes for this server. It is not derived from the master:
 * a stolen master must not also be server access, and revoking a device must never touch the owner's
 * pairing identity. Its id is [keyIdOf]: base64url, without padding, of BLAKE2b-128 of the 32-byte
 * public key — `fingerprint()` in `companion/web/src/lib/assignments/crypto.ts` computes the same.
 *
 * A SIGNED REQUEST carries four headers and never the token:
 *
 * ```
 * X-Device-Key        the key id: 22 characters
 * X-Device-Time       Unix time in whole seconds: decimal digits, no sign, no leading zero
 * X-Device-Nonce      16 bytes from the CSPRNG, base64url without padding: 22 characters
 * X-Device-Signature  the 64-byte Ed25519 signature, base64url without padding: 86 characters
 * ```
 *
 * The signature is over the UTF-8 bytes of eleven lines joined by one LF (0x0A), with none after the
 * last ([requestMessage]). No line can hold an LF: neither a request line nor a header's value can.
 *
 * ```
 * daymark-request-v1
 * <the method, as sent: GET>
 * <the request target, as sent: the path from /v1 on, and ? and the query when there is one>
 * <base64url of BLAKE2b-256 of the body's bytes; of no bytes when there is no body>
 * <X-Device-Time, as sent>
 * <X-Device-Nonce, as sent>
 * if-match:<the request's If-Match; nothing after the colon when it carries none>
 * if-none-match:<its If-None-Match, the same way>
 * x-rel-token:<its X-Rel-Token, the same way>
 * x-setting-key:<its X-Setting-Key, the same way>
 * x-share-meta:<its X-Share-Meta, the same way>
 * ```
 *
 * The target is the request line's, not decoded and not re-encoded: the server checks the target it
 * received, so the phone signs exactly the one it sends. A phone whose server address has a path —
 * a proxy that takes a prefix off — signs the target without that prefix, which is the one the server
 * receives; the API is always at `/v1`. Every base64url value here is the canonical encoding: no
 * padding, only `A–Z a–z 0–9 - _`, and no stray bits in the last character ([decodeCanonical]).
 *
 * THE SIGNED HEADERS ([SIGNED_HEADERS]) are every request header an owner route acts on: a key
 * document's precondition, a relationship's inbox token, a setting's name and a share's end. Each has
 * its line, in that order, whether the request carries it or not, so a header changed, added or taken
 * away on the path changes the message. Its name is lower case whatever case the request spells it in;
 * its value is the one the request carries, which HTTP gives without the whitespace around it. A
 * request carries each at most once and never empty ([signedHeaderValues]): one that carries a signed
 * header twice, or with no value, is refused. SignedHeaderCoverageTest holds the list to every header
 * the server reads.
 */
object DeviceSignature {
    const val KEY_HEADER = "X-Device-Key"
    const val TIME_HEADER = "X-Device-Time"
    const val NONCE_HEADER = "X-Device-Nonce"
    const val SIGNATURE_HEADER = "X-Device-Signature"

    /** The four, in the order a request lists them. Any one of them makes a request a signed one. */
    val HEADERS: List<String> = listOf(KEY_HEADER, TIME_HEADER, NONCE_HEADER, SIGNATURE_HEADER)

    /**
     * The request headers the signature covers besides its own, in the order of their lines: every one
     * an owner route acts on. A header added here changes every signed message, so a phone must sign
     * the new line before any request of it is taken again.
     */
    val SIGNED_HEADERS: List<String> = listOf("If-Match", "If-None-Match", "X-Rel-Token", "X-Setting-Key", "X-Share-Meta")

    /** The first line of every signed request, so no other message this key signs can pass for one. */
    const val REQUEST_CONTEXT = "daymark-request-v1"

    /** The first line of the proof a redemption carries ([redeemMessage]). */
    const val REDEEM_CONTEXT = "daymark-pairing-redeem-v1"

    /** What a pairing code is hashed under to make its id ([codeIdOf]). */
    const val CODE_CONTEXT = "daymark-pairing-code-v1"

    /**
     * How far a request's time may be from the server's, either way: about five minutes (#186). Wider
     * lets a captured request wait longer; narrower refuses a phone whose clock has drifted.
     */
    const val WINDOW_SECONDS = 300L
    const val WINDOW_MS = WINDOW_SECONDS * 1000

    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64
    const val NONCE_BYTES = 16
    const val KEY_ID_BYTES = 16

    private val B64URL = Base64.getUrlEncoder().withoutPadding()
    private val B64URL_DEC = Base64.getUrlDecoder()
    private val B64URL_CHARS = Regex("^[A-Za-z0-9_-]*$")
    private val TIME = Regex("^(0|[1-9][0-9]{0,14})$")

    fun b64url(bytes: ByteArray): String = B64URL.encodeToString(bytes)

    /**
     * [value] decoded, when it is the canonical base64url spelling of exactly [length] bytes; null for
     * anything else. One spelling per value, so a respelled nonce is not a new nonce and a respelled
     * key id is not a new key.
     */
    fun decodeCanonical(value: String, length: Int): ByteArray? {
        if (!B64URL_CHARS.matches(value)) return null
        val bytes = try { B64URL_DEC.decode(value) } catch (_: IllegalArgumentException) { return null }
        if (bytes.size != length || B64URL.encodeToString(bytes) != value) return null
        return bytes
    }

    /** A request time as sent, when it is one: whole seconds, digits only, no leading zero. */
    fun parseTime(value: String): Long? = if (TIME.matches(value)) value.toLongOrNull() else null

    /** The key's id: base64url of BLAKE2b-128 of the public key. */
    fun keyIdOf(publicKey: ByteArray): String = b64url(Secrets.blake2b(publicKey, KEY_ID_BYTES))

    /** The body's line: base64url of BLAKE2b-256 of its bytes. */
    fun bodyHash(body: ByteArray): String = b64url(Secrets.blake2b(body, 32))

    /**
     * The bytes a request's signature is over. See this object's header. [headerValues] is the value of
     * each of [SIGNED_HEADERS], in order, null for one the request does not carry ([signedHeaderValues]).
     */
    fun requestMessage(method: String, target: String, bodyHash: String, time: String, nonce: String, headerValues: List<String?>): ByteArray {
        require(headerValues.size == SIGNED_HEADERS.size) { "one value, or null, for each signed header" }
        val headerLines = SIGNED_HEADERS.zip(headerValues) { name, value -> "${name.lowercase()}:${value.orEmpty()}" }
        return (listOf(REQUEST_CONTEXT, method, target, bodyHash, time, nonce) + headerLines).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /**
     * The value a request carries for each of [SIGNED_HEADERS], in order, null for one it does not
     * carry; or null for the whole when it carries one of them more than once, or with no value, which
     * a phone never sends. [valuesOf] gives every value a request carries for a header, or null for none.
     */
    fun signedHeaderValues(valuesOf: (String) -> List<String>?): List<String?>? =
        SIGNED_HEADERS.map { name ->
            val values = valuesOf(name)?.takeIf { it.isNotEmpty() } ?: return@map null
            values.singleOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        }

    /** A pairing code's id: base64url of BLAKE2b-256 of `daymark-pairing-code-v1`, LF, and the code. */
    fun codeIdOf(code: String): String = b64url(Secrets.blake2b("$CODE_CONTEXT\n$code".toByteArray(Charsets.UTF_8), 32))

    /**
     * The bytes a redemption's proof is signed over: `daymark-pairing-redeem-v1`, the code's id and the
     * public key as the request carries it, joined by LF. Signing it proves the phone holds the key it
     * asks to have registered, for this code and no other.
     */
    fun redeemMessage(codeId: String, publicKeyB64: String): ByteArray =
        "$REDEEM_CONTEXT\n$codeId\n$publicKeyB64".toByteArray(Charsets.UTF_8)

    /** Whether [signature] is [publicKey]'s over [message]. Strict: a non-canonical signature is refused. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        return try {
            Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * Whether [publicKey] is a point of the full prime-order group: not the identity, not of small order.
     * Checked once, at redemption, because a key of small order can be made to verify a signature over
     * any message at all.
     */
    fun isUsablePublicKey(publicKey: ByteArray): Boolean =
        publicKey.size == PUBLIC_KEY_BYTES && runCatching { Ed25519.validatePublicKeyFull(publicKey, 0) }.getOrDefault(false)
}

/**
 * The code the owner console shows to pair a phone (#189): ten symbols as two groups of five,
 * `K7M4R-D96QX`. Nine carry entropy, 9 × log2(31) = 44.6 bits, and the tenth is a check symbol.
 *
 * The alphabet is the one every code a person types in Daymark already uses — digits 2–9 and the
 * letters A–Z without I, L and O — so the phone reads it with the same rules as the recovery code
 * (`sync-crypto/.../RecoveryCode.kt`): whitespace, hyphens and dashes dropped, upper-cased. Thirty-one
 * symbols because 31 is prime: the check symbol (the sum of position × value, positions 1–9, mod 31)
 * then catches every single wrong symbol and every swap of two, on the phone, before a request is
 * made. That matters here because a wrong code counts toward the lockout like a wrong token, and every
 * refusal reads the same.
 *
 * The server holds only the code's id ([DeviceSignature.codeIdOf]), and a code lives two minutes and
 * is taken once.
 */
object PairingCode {
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val PAYLOAD_SYMBOLS = 9
    const val SYMBOLS = PAYLOAD_SYMBOLS + 1

    private val rng = SecureRandom()

    /** A fresh code: nine symbols drawn uniformly (a byte of 248 or more is drawn again) and the check symbol. */
    fun generate(): String {
        val limit = ALPHABET.length * (256 / ALPHABET.length) // 248
        val payload = StringBuilder(PAYLOAD_SYMBOLS)
        val draw = ByteArray(PAYLOAD_SYMBOLS)
        while (payload.length < PAYLOAD_SYMBOLS) {
            rng.nextBytes(draw)
            for (b in draw) {
                val v = b.toInt() and 0xFF
                if (v < limit && payload.length < PAYLOAD_SYMBOLS) payload.append(ALPHABET[v % ALPHABET.length])
            }
        }
        draw.fill(0)
        return payload.toString() + checkSymbol(payload.toString())
    }

    /** Whether [code] is a code as the phone sends one: ten symbols of the alphabet, upper case, check symbol right. */
    fun isCanonical(code: String): Boolean =
        code.length == SYMBOLS &&
            code.all { it in ALPHABET } &&
            code[PAYLOAD_SYMBOLS] == checkSymbol(code.substring(0, PAYLOAD_SYMBOLS))

    /** The check symbol of a nine-symbol payload: the sum of position × value, positions 1 to 9, mod 31. */
    fun checkSymbol(payload: String): Char {
        var sum = 0
        for (i in payload.indices) {
            val value = ALPHABET.indexOf(payload[i])
            require(value >= 0) { "not a symbol of the alphabet" }
            sum = (sum + (i + 1) * value) % ALPHABET.length
        }
        return ALPHABET[sum]
    }

    /** Two groups of five, for the console to show: `K7M4R-D96QX`. */
    fun display(code: String): String = code.chunked(5).joinToString("-")
}
