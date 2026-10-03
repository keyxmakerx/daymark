package com.daymark.synccrypto

/**
 * The code the owner's console shows to pair this phone with the server (#189, #432), read from what
 * the person typed or from the QR code's `code` parameter ([PairingVerdict]). Pure Kotlin with no
 * libsodium, so the verdict that reads it runs with no native library.
 *
 * THE FORMAT is the server's (`PairingCode` in
 * `companion/server/src/main/kotlin/com/daymark/companion/auth/DeviceSignature.kt`): ten symbols of
 * [ALPHABET], nine that carry the code's entropy and a check symbol, the sum of position × value over
 * positions 1 to 9, mod 31. The console shows it as two groups of five, `K7M4R-D96QA`.
 *
 * Reading what was typed:
 *  1. It is normalised exactly as a recovery code is ([RecoveryCode.normalize]): JavaScript's
 *     whitespace, hyphens and the U+2010..U+2015 dashes go, and the rest is upper-cased whatever the
 *     device's language. Nothing is folded: an O is never read as 0, nor an I or L as 1.
 *  2. It is refused, in this order, when nothing is left; at the first 0, O, 1, I or L; at the first
 *     other character outside [ALPHABET]; when it is shorter or longer than [SYMBOLS]; and when its
 *     last symbol is not the check symbol of the nine before it ([checkSymbol]).
 *
 * Because 31 is prime and the nine weights differ mod 31, the check symbol catches every single wrong
 * symbol, and every swap of two different adjacent symbols, the check symbol's own included, before
 * anything is sent. That matters here: a wrong code sent to the server counts toward the lockout of the
 * phone's whole network address (#432). A change to several symbols it catches only 30 times in 31.
 *
 * No refusal repeats what was typed. [PairingCodeException] carries a fault and a position, never a
 * character, and [toString] never shows the code: until it is taken, or two minutes pass, it is a
 * credential.
 */
class PairingCode private constructor(
    /** The ten symbols, upper case, without separators: what the redemption carries. */
    val canonical: String,
) {

    /** Why typed text is not a pairing code. One value per diagnosis, in the order they are checked. */
    enum class Fault {
        /** Nothing was typed, or only separators were. */
        EMPTY,

        /** A 0, O, 1, I or L, which no code contains. */
        CONFUSABLE,

        /** Some other character that is not a symbol of [ALPHABET]. */
        NOT_IN_ALPHABET,
        TOO_SHORT,
        TOO_LONG,

        /** The right length and alphabet, and the last symbol disagrees with the nine before it. */
        CHECK_SYMBOL,
    }

    override fun equals(other: Any?): Boolean = other is PairingCode && other.canonical == canonical

    override fun hashCode(): Int = canonical.hashCode()

    override fun toString(): String = "PairingCode(not shown)"

    companion object {
        /**
         * The 31 symbols, in the order that gives each its value (0..30): the recovery code's, and the
         * server's. The order is part of the format, because the check symbol is computed from it.
         */
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        /** Symbols that carry the code's entropy: 9 × log2(31), 44.6 bits. */
        const val PAYLOAD_SYMBOLS = 9

        /** The payload and one check symbol. */
        const val SYMBOLS = PAYLOAD_SYMBOLS + 1

        private const val NEVER_IN_A_CODE = "0O1IL"

        /**
         * What the person typed, or the QR code carried, as a code; or [PairingCodeException] naming why
         * it is not one. The position counts UTF-16 units of the normalised text, from 1, as a recovery
         * code's does.
         */
        fun parse(typed: String): PairingCode {
            val canonical = RecoveryCode.normalize(typed)
            if (canonical.isEmpty()) throw PairingCodeException(Fault.EMPTY, null)
            for (i in canonical.indices) {
                val c = canonical[i]
                if (c in NEVER_IN_A_CODE) throw PairingCodeException(Fault.CONFUSABLE, i + 1)
                if (c !in ALPHABET) throw PairingCodeException(Fault.NOT_IN_ALPHABET, i + 1)
            }
            if (canonical.length < SYMBOLS) throw PairingCodeException(Fault.TOO_SHORT, null)
            if (canonical.length > SYMBOLS) throw PairingCodeException(Fault.TOO_LONG, null)
            if (canonical[PAYLOAD_SYMBOLS] != checkSymbol(canonical.substring(0, PAYLOAD_SYMBOLS))) {
                throw PairingCodeException(Fault.CHECK_SYMBOL, null)
            }
            return PairingCode(canonical)
        }

        /** The check symbol of a nine-symbol payload: the sum of position × value, positions 1 to 9, mod 31. */
        internal fun checkSymbol(payload: String): Char {
            var sum = 0
            for (i in payload.indices) {
                val value = ALPHABET.indexOf(payload[i])
                if (value < 0) throw PairingCodeException(Fault.NOT_IN_ALPHABET, i + 1)
                sum = (sum + (i + 1) * value) % ALPHABET.length
            }
            return ALPHABET[sum]
        }
    }
}

/** Typed text that is not a pairing code, and why. It never carries what was typed. */
class PairingCodeException internal constructor(
    val fault: PairingCode.Fault,
    /** The 1-based position of the character at fault, for [PairingCode.Fault.CONFUSABLE] and [PairingCode.Fault.NOT_IN_ALPHABET]. */
    val at: Int?,
) : Exception("pairing code refused: ${fault.name}")
