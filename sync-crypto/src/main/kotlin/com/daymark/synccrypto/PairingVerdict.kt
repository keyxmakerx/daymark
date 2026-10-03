package com.daymark.synccrypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Whether the phone redeems a pairing code, and where (#189, #432): the QR code's text, or the address
 * and code the person typed, in; [Redeem] or [Decline] out, before anything is sent. Pure Kotlin with no
 * libsodium, so it runs with no native library.
 *
 * THE HTTPS GATE. The phone redeems a code only at an `https://` address. Pairing is when the phone's
 * signing key is established, and on plain http anyone on the same network is on the path of both
 * screens: they can forward their own key to the server, answer the phone themselves, and rewrite the
 * console page so that its words match. The phone is the one party the network cannot rewrite, so the
 * phone refuses (#189). Nothing here lets http through, and nothing here is a setting.
 *
 * THE QR CODE'S TEXT is `daymark-pair:v1?server=<address>&code=<code>`, as the owner's console writes it
 * (#431): the two parameters in either order, each exactly once and nothing else beside them, and each
 * value spelled exactly as JavaScript's `encodeURIComponent` spells it, which keeps the whole text in
 * ASCII, where no scanner's guess at a character set can change it. Another version is refused, not
 * guessed at. The QR is routing only: a scan reads the two values and then decides exactly as typing
 * them would ([ofTyped]), so a scan never does more than typing does (#189).
 *
 * THE ADDRESS, once the whitespace around it is gone, is refused unless it is `https://` in any case, a
 * host, an optional port and an optional path: no user name, no query and no fragment. The host is ASCII
 * letters, digits, hyphens and dots, or an IPv6 address in brackets; the port is 1 to 65535; the path is
 * RFC 3986 path characters. Every other character, whitespace, a backslash or a percent sign in the host
 * among them, is refused rather than read the way one parser or another would read it. What is redeemed
 * is the address with its scheme and host in lower case and its trailing slashes gone, which is where
 * the API's `/v1` goes.
 *
 * THE CODE is read by [PairingCode.parse], whether it was typed or scanned.
 *
 * No verdict repeats what it was given beyond the address it redeems at: a [Decline] carries only its
 * [Reason], and a [Redeem] never shows its code.
 */
sealed interface PairingVerdict {

    /** Redeem [code] at [address]: `https://`, the host and any port and path, without a trailing slash. */
    data class Redeem(val address: String, val code: PairingCode) : PairingVerdict

    /** Send nothing, because of [reason]. */
    data class Decline(val reason: Reason) : PairingVerdict

    /** Why a code is not redeemed: one value per diagnosis. [ofScan] and [ofTyped] give the order they are checked in. */
    enum class Reason {
        /** The text does not begin `daymark-pair:`. */
        NOT_A_PAIRING_QR,

        /** `daymark-pair:`, and not version `v1`. */
        UNKNOWN_VERSION,

        /** A parameter that is neither `server=` nor `code=`, an empty one included. */
        EXTRA_PARAMETER,

        /** `server=` or `code=` more than once. */
        DUPLICATE_PARAMETER,

        /** No parameters, or no `server=` or no `code=`. */
        MISSING_PARAMETER,

        /** A value not spelled as `encodeURIComponent` spells it, or not UTF-8 once decoded. */
        NOT_ENCODED,

        /** The address does not begin `https://`: http, another scheme, or none. */
        NOT_HTTPS,

        /** The address names a user, or a user and a password, before its host. */
        USER_NAME,

        /** `https://` and no host. */
        NO_HOST,

        /** The address has a query. */
        QUERY,

        /** The address has a fragment. */
        FRAGMENT,

        /** Any other address the phone does not read: a character, a host or a port it does not take. */
        NOT_AN_ADDRESS,

        /** The code, by [PairingCode.Fault]. */
        CODE_EMPTY,
        CODE_CONFUSABLE,
        CODE_NOT_IN_ALPHABET,
        CODE_TOO_SHORT,
        CODE_TOO_LONG,
        CODE_CHECK_SYMBOL,
    }

    companion object {
        /** What the QR code's text begins with; the version follows it, then `?`. */
        const val QR_SCHEME = "daymark-pair:"

        /** The one version this phone reads. */
        const val QR_VERSION = "v1"

        private const val SERVER = "server="
        private const val CODE = "code="
        private const val HTTPS = "https://"

        /** The characters `encodeURIComponent` leaves as they are. */
        private const val LEFT_AS_IS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
        private const val HEX = "0123456789ABCDEF"
        private const val HOST_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-."
        private const val IPV6_CHARS = "0123456789ABCDEFabcdef:."

        /** RFC 3986 `pchar` without the percent sign, and `/`: unreserved, sub-delims, `:` and `@`. */
        private const val PATH_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/"

        /**
         * The QR code's text: its form, then [ofTyped] on the two values it carries. The form is checked
         * in this order: the scheme, the version, no parameters at all, a parameter of another name, a
         * parameter twice, a parameter missing, and then each value's spelling.
         */
        fun ofScan(text: String): PairingVerdict {
            if (!text.startsWith(QR_SCHEME)) return Decline(Reason.NOT_A_PAIRING_QR)
            val rest = text.substring(QR_SCHEME.length)
            val question = rest.indexOf('?')
            val version = if (question < 0) rest else rest.substring(0, question)
            if (version != QR_VERSION) return Decline(Reason.UNKNOWN_VERSION)
            val query = if (question < 0) "" else rest.substring(question + 1)
            if (query.isEmpty()) return Decline(Reason.MISSING_PARAMETER)
            val servers = ArrayList<String>(1)
            val codes = ArrayList<String>(1)
            for (parameter in query.split('&')) {
                when {
                    parameter.startsWith(SERVER) -> servers.add(parameter.substring(SERVER.length))
                    parameter.startsWith(CODE) -> codes.add(parameter.substring(CODE.length))
                    else -> return Decline(Reason.EXTRA_PARAMETER)
                }
            }
            if (servers.size > 1 || codes.size > 1) return Decline(Reason.DUPLICATE_PARAMETER)
            val server = servers.singleOrNull() ?: return Decline(Reason.MISSING_PARAMETER)
            val code = codes.singleOrNull() ?: return Decline(Reason.MISSING_PARAMETER)
            val address = decodeURIComponent(server) ?: return Decline(Reason.NOT_ENCODED)
            val typed = decodeURIComponent(code) ?: return Decline(Reason.NOT_ENCODED)
            return ofTyped(address, typed)
        }

        /**
         * An address and a code as the person typed them: the address first, then the code. The address
         * is checked in this order: `https://`, a user name, a host at all, the host and port, a query or
         * a fragment, whichever comes first, and then the path.
         */
        fun ofTyped(address: String, code: String): PairingVerdict {
            val read = readAddress(address)
            val redeemAt = read.address ?: return Decline(read.refusal!!)
            val pairingCode = try {
                PairingCode.parse(code)
            } catch (e: PairingCodeException) {
                return Decline(codeReason(e.fault))
            }
            return Redeem(redeemAt, pairingCode)
        }

        /** The address to redeem at, or why not: exactly one of the two is null. */
        private class AddressRead(val address: String?, val refusal: Reason?)

        private fun refused(reason: Reason) = AddressRead(null, reason)

        private fun readAddress(typed: String): AddressRead {
            val address = typed.trim { RecoveryCode.isJsWhitespace(it) }
            if (address.length < HTTPS.length || asciiLowerCase(address.substring(0, HTTPS.length)) != HTTPS) {
                return refused(Reason.NOT_HTTPS)
            }
            val rest = address.substring(HTTPS.length)
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd)
            if ('@' in authority) return refused(Reason.USER_NAME)
            val (host, port) = hostAndPort(authority) ?: return refused(Reason.NOT_AN_ADDRESS)
            if (host.isEmpty()) return refused(Reason.NO_HOST)
            if (!isHost(host) || (port != null && !isPort(port))) return refused(Reason.NOT_AN_ADDRESS)
            val path = rest.substring(authorityEnd)
            val delimiter = path.indexOfFirst { it == '?' || it == '#' }
            if (delimiter >= 0) return refused(if (path[delimiter] == '?') Reason.QUERY else Reason.FRAGMENT)
            if (!isPath(path)) return refused(Reason.NOT_AN_ADDRESS)
            val portPart = if (port == null) "" else ":$port"
            return AddressRead(HTTPS + asciiLowerCase(host) + portPart + path.trimEnd('/'), null)
        }

        /** The host and the port, if any, of an authority with no user name; null when it cannot be split. */
        private fun hostAndPort(authority: String): Pair<String, String?>? {
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                val after = authority.substring(close + 1)
                val host = authority.substring(0, close + 1)
                return when {
                    after.isEmpty() -> host to null
                    after.startsWith(":") -> host to after.substring(1)
                    else -> null
                }
            }
            val colon = authority.indexOf(':')
            return if (colon < 0) authority to null else authority.substring(0, colon) to authority.substring(colon + 1)
        }

        /** A DNS name or an IPv4 address with no empty label, or an IPv6 address in brackets. */
        private fun isHost(host: String): Boolean =
            if (host.startsWith("[")) {
                host.length > 2 && host.endsWith("]") && ':' in host && host.substring(1, host.length - 1).all { it in IPV6_CHARS }
            } else {
                host.all { it in HOST_CHARS } && host.split('.').none { it.isEmpty() }
            }

        /** 1 to 65535, in digits with no leading zero. */
        private fun isPort(port: String): Boolean =
            port.length in 1..5 && port.all { it in '0'..'9' } && port[0] != '0' && port.toInt() <= 65535

        /** Empty, or `/` and RFC 3986 path characters, a percent sign only before two hex digits. */
        private fun isPath(path: String): Boolean {
            if (path.isEmpty()) return true
            if (path[0] != '/') return false
            var i = 0
            while (i < path.length) {
                val c = path[i]
                if (c == '%') {
                    if (i + 2 >= path.length || hexValue(path[i + 1]) < 0 || hexValue(path[i + 2]) < 0) return false
                    i += 3
                } else {
                    if (c !in PATH_CHARS) return false
                    i++
                }
            }
            return true
        }

        private fun codeReason(fault: PairingCode.Fault): Reason = when (fault) {
            PairingCode.Fault.EMPTY -> Reason.CODE_EMPTY
            PairingCode.Fault.CONFUSABLE -> Reason.CODE_CONFUSABLE
            PairingCode.Fault.NOT_IN_ALPHABET -> Reason.CODE_NOT_IN_ALPHABET
            PairingCode.Fault.TOO_SHORT -> Reason.CODE_TOO_SHORT
            PairingCode.Fault.TOO_LONG -> Reason.CODE_TOO_LONG
            PairingCode.Fault.CHECK_SYMBOL -> Reason.CODE_CHECK_SYMBOL
        }

        /**
         * [value] as JavaScript's `encodeURIComponent` writes it: each character outside [LEFT_AS_IS] as
         * the percent-encoded bytes of its UTF-8, in upper-case hex. Null for an unpaired surrogate, where
         * JavaScript throws.
         */
        internal fun encodeURIComponent(value: String): String? {
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val codePoint = when {
                    c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> Character.toCodePoint(c, value[i + 1])
                    c.isSurrogate() -> return null
                    else -> c.code
                }
                i += Character.charCount(codePoint)
                if (codePoint < 0x80 && codePoint.toChar() in LEFT_AS_IS) {
                    out.append(codePoint.toChar())
                } else {
                    for (b in String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)) {
                        val v = b.toInt() and 0xFF
                        out.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
                    }
                }
            }
            return out.toString()
        }

        /**
         * The text [raw] spells, when [raw] is exactly what [encodeURIComponent] writes for it; null for
         * anything else: a percent sign not before two hex digits, lower-case hex, a character that should
         * have been encoded, or bytes that are not UTF-8.
         */
        internal fun decodeURIComponent(raw: String): String? {
            val bytes = ByteArrayOutputStream(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '%') {
                    if (i + 2 >= raw.length) return null
                    val high = hexValue(raw[i + 1])
                    val low = hexValue(raw[i + 2])
                    if (high < 0 || low < 0) return null
                    bytes.write(high * 16 + low)
                    i += 3
                } else {
                    if (c.code !in 0x21..0x7E) return null
                    bytes.write(c.code)
                    i++
                }
            }
            val decoded = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            return if (encodeURIComponent(decoded) == raw) decoded else null
        }

        private fun hexValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'F' -> c - 'A' + 10
            in 'a'..'f' -> c - 'a' + 10
            else -> -1
        }

        /** A-Z to a-z and nothing else, so no other script's letter can fold into `https`. */
        private fun asciiLowerCase(text: String): String =
            buildString(text.length) { for (c in text) append(if (c in 'A'..'Z') c + ('a' - 'A') else c) }
    }
}
