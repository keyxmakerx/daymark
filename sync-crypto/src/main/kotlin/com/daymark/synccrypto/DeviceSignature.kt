package com.daymark.synccrypto

/**
 * The phone's signed request and its redemption proof (#186, #189, #432), byte for byte as the server
 * builds and checks them: the server's `DeviceSignature`, in
 * `companion/server/src/main/kotlin/com/daymark/companion/auth/DeviceSignature.kt`, whose vector
 * DeviceSignatureVectorTest reproduces. Pure Kotlin: the bytes that are signed and the checks made
 * before signing. [DeviceKey] signs.
 *
 * A SIGNED REQUEST carries four headers ([HEADERS]) and never the owner's token:
 *
 * ```
 * X-Device-Key        the key id: 22 characters
 * X-Device-Time       Unix time in whole seconds: decimal digits, no sign, no leading zero
 * X-Device-Nonce      16 bytes from the CSPRNG, base64url without padding: 22 characters
 * X-Device-Signature  the 64-byte Ed25519 signature, base64url without padding: 86 characters
 * ```
 *
 * The signature is over the UTF-8 bytes of eleven lines joined by one LF (0x0A), with none after the
 * last ([requestMessage]):
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
 * The server checks the target it received, neither decoded nor re-encoded, so the phone signs exactly
 * the one it sends. Behind a proxy that takes a prefix off the server's address, that is the target
 * without the prefix: the API is always at `/v1`. Each of [SIGNED_HEADERS] has its line, in that order,
 * whether the request carries it or not, so a header changed, added or taken away on the path changes
 * the message.
 *
 * REFUSED BEFORE ANYTHING IS SIGNED, with a [DeviceSignatureException]. Every signed request the server
 * refuses counts toward the lockout of the phone's whole network address, which on a home connection
 * pauses the console too (#432), so the phone signs nothing the server would read differently from how it
 * was signed: a method that is not upper-case letters, as every method of the API is; a target that is
 * not a path from `/v1/` spelled as it is sent; a time outside what the server reads; one of
 * [SIGNED_HEADERS] given twice, in any spelling of its name, or given empty, which the server refuses
 * outright; and a value the server would not read back as the same text, because HTTP drops the
 * whitespace at either end of a value and the server reads a value as ASCII. So no line can hold a line
 * feed. A header of any other name is refused too: the signer takes only the headers it signs, so a
 * misspelt one cannot go out unsigned. No refusal repeats a value: X-Rel-Token's is a credential.
 */
object DeviceSignature {
    const val KEY_HEADER = "X-Device-Key"
    const val TIME_HEADER = "X-Device-Time"
    const val NONCE_HEADER = "X-Device-Nonce"
    const val SIGNATURE_HEADER = "X-Device-Signature"

    /** The four, in the order a request lists them. */
    val HEADERS: List<String> = listOf(KEY_HEADER, TIME_HEADER, NONCE_HEADER, SIGNATURE_HEADER)

    /**
     * The request headers the signature covers besides its own, in the order of their lines: every one an
     * owner route acts on, as the server lists them. A header added there changes every signed message.
     */
    val SIGNED_HEADERS: List<String> = listOf("If-Match", "If-None-Match", "X-Rel-Token", "X-Setting-Key", "X-Share-Meta")

    /** The first line of every signed request, so no other message this key signs can pass for one. */
    const val REQUEST_CONTEXT = "daymark-request-v1"

    /** The first line of the proof a redemption carries ([redeemMessage]). */
    const val REDEEM_CONTEXT = "daymark-pairing-redeem-v1"

    /** What a pairing code is hashed under to make its id ([codeIdMessage]). */
    const val CODE_CONTEXT = "daymark-pairing-code-v1"

    /** How far the phone's clock may be from the server's, either way, before the server refuses (#186). */
    const val WINDOW_SECONDS = 300L

    /** The latest time the server reads: fifteen digits. */
    const val LATEST_TIME = 999_999_999_999_999L

    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64
    const val NONCE_BYTES = 16
    const val KEY_ID_BYTES = 16

    /** BLAKE2b-256: the body's line and the code's id. */
    const val HASH_BYTES = 32

    /** RFC 3986 path and query characters, less the percent sign, which is checked on its own. */
    private const val TARGET_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/?"
    private const val HEX = "0123456789ABCDEFabcdef"

    /**
     * The bytes a request's signature is over. [headerValues] is the value of each of [SIGNED_HEADERS],
     * in order, null for one the request does not carry ([signedHeaderValues]).
     */
    internal fun requestMessage(method: String, target: String, bodyHash: String, time: String, nonce: String, headerValues: List<String?>): ByteArray {
        require(headerValues.size == SIGNED_HEADERS.size) { "one value, or null, for each signed header" }
        val headerLines = SIGNED_HEADERS.zip(headerValues) { name, value -> "${name.lowercase()}:${value.orEmpty()}" }
        return (listOf(REQUEST_CONTEXT, method, target, bodyHash, time, nonce) + headerLines).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /**
     * The value [headers] give each of [SIGNED_HEADERS], in order, null for one they do not give. A name
     * matches whatever its case. Refused: a name not on the list, a signed header given twice or empty, and
     * a value the server would read differently.
     */
    internal fun signedHeaderValues(headers: List<Pair<String, String>>): List<String?> {
        for ((name, _) in headers) {
            if (SIGNED_HEADERS.none { sameName(it, name) }) throw DeviceSignatureException(DeviceSignatureException.Reason.NOT_A_SIGNED_HEADER)
        }
        return SIGNED_HEADERS.map { name ->
            val values = headers.filter { sameName(name, it.first) }.map { it.second }
            when {
                values.isEmpty() -> null
                values.size > 1 -> throw DeviceSignatureException(DeviceSignatureException.Reason.HEADER_TWICE, name)
                values[0].isEmpty() -> throw DeviceSignatureException(DeviceSignatureException.Reason.HEADER_EMPTY, name)
                !readBackAsSent(values[0]) -> throw DeviceSignatureException(DeviceSignatureException.Reason.HEADER_VALUE, name)
                else -> values[0]
            }
        }
    }

    /**
     * The bytes a redemption's proof is signed over: `daymark-pairing-redeem-v1`, the code's id and the
     * public key as the request carries it, joined by LF. Signing it proves the phone holds the key it asks
     * to have registered, for this code and no other.
     */
    internal fun redeemMessage(codeId: String, publicKeyB64: String): ByteArray =
        "$REDEEM_CONTEXT\n$codeId\n$publicKeyB64".toByteArray(Charsets.UTF_8)

    /** What a code's id is BLAKE2b-256 of: `daymark-pairing-code-v1`, LF, and the code's ten symbols. */
    internal fun codeIdMessage(code: PairingCode): ByteArray = "$CODE_CONTEXT\n${code.canonical}".toByteArray(Charsets.UTF_8)

    /** A method as sent: upper-case ASCII letters, `GET`, `PUT`. */
    internal fun requireMethod(method: String) {
        if (method.isEmpty() || method.any { it !in 'A'..'Z' }) throw DeviceSignatureException(DeviceSignatureException.Reason.METHOD)
    }

    /** A target as sent: a path from `/v1/`, and RFC 3986 path and query characters, a percent sign only before two hex digits. */
    internal fun requireTarget(target: String) {
        if (!target.startsWith("/v1/")) throw DeviceSignatureException(DeviceSignatureException.Reason.TARGET)
        var i = 0
        while (i < target.length) {
            val c = target[i]
            if (c == '%') {
                if (i + 2 >= target.length || target[i + 1] !in HEX || target[i + 2] !in HEX) throw DeviceSignatureException(DeviceSignatureException.Reason.TARGET)
                i += 3
            } else {
                if (c !in TARGET_CHARS) throw DeviceSignatureException(DeviceSignatureException.Reason.TARGET)
                i++
            }
        }
    }

    /** X-Device-Time for [timeSeconds]: digits with no sign and no leading zero, as the server reads it. */
    internal fun timeLine(timeSeconds: Long): String {
        if (timeSeconds !in 0..LATEST_TIME) throw DeviceSignatureException(DeviceSignatureException.Reason.TIME)
        return timeSeconds.toString()
    }

    /** Whether [a] and [b] name one header: the same letters, A-Z and a-z alike, and nothing folded beyond ASCII. */
    private fun sameName(a: String, b: String): Boolean =
        a.length == b.length && a.indices.all { asciiLower(a[it]) == asciiLower(b[it]) }

    private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c

    /** ASCII the server reads as it is: printable characters and tabs, and no space or tab at either end. */
    private fun readBackAsSent(value: String): Boolean =
        value.all { it == '\t' || it in ' '..'~' } && value.first() !in " \t" && value.last() !in " \t"
}

/** A request the phone does not sign, and why ([DeviceSignature] lists the refusals). It never carries a value. */
class DeviceSignatureException internal constructor(
    val reason: Reason,
    /** Which of [DeviceSignature.SIGNED_HEADERS], in the server's spelling, for a header's refusal. */
    val header: String? = null,
) : Exception("request not signed: ${reason.description}${if (header != null) " ($header)" else ""}") {

    enum class Reason(internal val description: String) {
        METHOD("a method that is not upper-case letters"),
        TARGET("a target that is not a path from /v1/ as it is sent"),
        TIME("a time the server does not read"),
        NOT_A_SIGNED_HEADER("a header the signature does not cover"),
        HEADER_TWICE("a signed header given twice"),
        HEADER_EMPTY("a signed header given empty"),
        HEADER_VALUE("a value the server would read differently"),
    }
}
