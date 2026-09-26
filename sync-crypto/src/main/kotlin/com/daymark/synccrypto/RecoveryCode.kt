package com.daymark.synccrypto

/**
 * A recovery code, read exactly as the web reads one (`companion/web/src/lib/recovery/recoveryCode.ts`,
 * `parseRecoveryCode`). What the person types becomes the Argon2id input of the wrapped key's
 * recovery slot (#403), so the same typed text must reach the derivation as the same 30 bytes on
 * both sides, or the code opens nothing on one of them.
 *
 * Reading what was typed:
 *  1. Every JavaScript whitespace character (`\s`: [isJsWhitespace]), every hyphen-minus and every
 *     U+2010..U+2015 dash goes; a word processor or a chat client puts those dashes where the printed
 *     hyphens were. Then the rest is upper-cased with full Unicode case mapping, independent of
 *     locale: `toUpperCase()` there, [String.uppercase] here, so `ß` becomes `SS` on both sides.
 *     Nothing else is folded: an O is never read as 0, nor an I or L as 1.
 *  2. The result is refused, in this order, when it is empty; when it holds a 0, O, 1, I or L (never
 *     in a code, so a definite error at a definite position); when it holds any other character
 *     outside [ALPHABET]; when it is shorter or longer than [CODE_SYMBOLS]; and when its last symbol
 *     is not the check symbol of the 29 before it ([checkSymbol]).
 *  3. The 30 symbols that remain, upper case and without separators, are the code: that string is
 *     the Argon2id input.
 *
 * A code is refused before any key is derived, which is the point of the check symbol: one mistyped
 * character is named at once, rather than surfacing seconds later as a key that did not open.
 *
 * No refusal repeats what was typed. [RecoveryCodeException] carries a fault and a position, never a
 * character, and [toString] never shows the code.
 */
class RecoveryCode private constructor(
    /** The 30 symbols, upper case, no separators: the key derivation's input. A secret. */
    internal val canonical: String,
) {

    /** Why a typed code is not a code. One value per diagnosis, as in the web's `RecoveryCodeFault`. */
    enum class Fault {
        /** Nothing was typed, or only separators were. */
        EMPTY,

        /** A 0, O, 1, I or L, which no code contains. */
        CONFUSABLE,

        /** Some other character that is not a symbol of [ALPHABET]. */
        NOT_IN_ALPHABET,
        TOO_SHORT,
        TOO_LONG,

        /** The right length and alphabet, and the last symbol disagrees with the rest. */
        CHECKSUM,
    }

    override fun toString(): String = "RecoveryCode(not shown)"

    companion object {
        /**
         * The 31 symbols, in the order that gives each its value (0..30). The order is part of the
         * format: the check symbol is computed from it, so reordering it invalidates every code.
         */
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        /** Symbols that carry the code's entropy. */
        const val PAYLOAD_SYMBOLS = 29

        /** The payload and one check symbol. */
        const val CODE_SYMBOLS = PAYLOAD_SYMBOLS + 1

        private const val NEVER_IN_A_CODE = "0O1IL"

        /**
         * What the person typed, as a code, or [RecoveryCodeException] naming why it is not one. The
         * position in the exception counts UTF-16 units of the normalised text, from 1, as the web's
         * `at` does.
         */
        fun parse(typed: String): RecoveryCode {
            val canonical = normalize(typed)
            if (canonical.isEmpty()) throw RecoveryCodeException(Fault.EMPTY, null)
            for (i in canonical.indices) {
                val c = canonical[i]
                if (c in NEVER_IN_A_CODE) throw RecoveryCodeException(Fault.CONFUSABLE, i + 1)
                if (c !in ALPHABET) throw RecoveryCodeException(Fault.NOT_IN_ALPHABET, i + 1)
            }
            if (canonical.length < CODE_SYMBOLS) throw RecoveryCodeException(Fault.TOO_SHORT, null)
            if (canonical.length > CODE_SYMBOLS) throw RecoveryCodeException(Fault.TOO_LONG, null)
            if (canonical[PAYLOAD_SYMBOLS] != checkSymbol(canonical.substring(0, PAYLOAD_SYMBOLS))) {
                throw RecoveryCodeException(Fault.CHECKSUM, null)
            }
            return RecoveryCode(canonical)
        }

        /** Step 1 above: the web's `normalizeRecoveryInput`, character for character. */
        internal fun normalize(typed: String): String {
            val kept = StringBuilder(typed.length)
            for (c in typed) {
                if (isJsWhitespace(c) || c == '-' || c in '\u2010'..'\u2015') continue
                kept.append(c)
            }
            return kept.toString().uppercase()
        }

        /**
         * The characters JavaScript's `\s` matches (WhiteSpace and LineTerminator in ECMA-262):
         * U+0009..U+000D, the Space_Separator category (U+0020, U+00A0, U+1680, U+2000..U+200A,
         * U+202F, U+205F, U+3000), U+2028, U+2029 and U+FEFF. That is the set Node 22 matches over
         * every code point. Java's own `\s` is ASCII-only, and [Character.isWhitespace] leaves out
         * the no-break spaces and adds U+001C..U+001F, so neither can stand in for it.
         */
        internal fun isJsWhitespace(c: Char): Boolean = when (c) {
            in '\u0009'..'\u000D', ' ', '\u00A0', '\u1680', in '\u2000'..'\u200A',
            '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
            -> true
            else -> false
        }

        /**
         * The check symbol of a 29-symbol payload: the sum of position × value, positions 1..29, mod
         * 31. Because 31 is prime and no two weights are equal mod 31, it catches every single-symbol
         * error and every transposition of two different payload symbols. It catches a change to
         * several symbols only 30 times in 31.
         */
        internal fun checkSymbol(payload: String): Char {
            var sum = 0
            for (i in payload.indices) {
                val value = ALPHABET.indexOf(payload[i])
                if (value < 0) throw RecoveryCodeException(Fault.NOT_IN_ALPHABET, i + 1)
                sum = (sum + (i + 1) * value) % ALPHABET.length
            }
            return ALPHABET[sum]
        }
    }
}

/** A typed recovery code that is not a code, and why. It never carries what was typed. */
class RecoveryCodeException internal constructor(
    val fault: RecoveryCode.Fault,
    /** The 1-based position of the character at fault, for [RecoveryCode.Fault.CONFUSABLE] and [RecoveryCode.Fault.NOT_IN_ALPHABET]. */
    val at: Int?,
) : Exception("recovery code refused: ${fault.name}")
