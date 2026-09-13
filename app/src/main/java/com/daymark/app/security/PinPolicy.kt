package com.daymark.app.security

/**
 * What a PIN has to be, in the one place that decides it.
 *
 * ## Why this is not just a length check on a text field
 *
 * The rule lived in two Compose files — the settings dialog and the onboarding step — as
 * `pin.length in 3..8`, written out twice. Nothing connected either of them to `PinManager`, so a
 * PIN could be set by any code path that did not happen to contain that expression, and the two
 * copies could drift apart without anything noticing. They are now both this object, and
 * [PinManager.setChosenPin] refuses anything it rejects, so the screens are a courtesy to the
 * person rather than the enforcement.
 *
 * ## Why six, and why it went up from three
 *
 * Until the journal was encrypted, the PIN guarded the screen. An attacker had to sit in front of
 * the phone and type, five free attempts at a time, against an exponential cool-down — so a short
 * PIN cost real hours and four digits was a reasonable trade against making somebody type.
 *
 * Once the data key is wrapped under a key derived from the PIN, that stops being true. Somebody who
 * copies the phone's storage takes the wrap away with them and guesses OFFLINE: no cool-down, no
 * five attempts, nobody to notice, and as much hardware as they care to rent. Ten thousand
 * four-digit PINs at that point is minutes. A million six-digit ones is not safety either — see the
 * note on the work factor in `DataKeyWraps` — but it is the shortest length at which the answer
 * stops being "immediately", and the settings copy tells the truth about the rest: a longer one
 * takes longer to guess.
 *
 * ## Twelve, not eight
 *
 * The old ceiling was eight digits and nothing rested on it. Since the copy invites somebody to
 * choose a longer PIN, the ceiling has to leave room to accept one. Raising it takes nothing away
 * from anybody and forces nothing on anybody; it only stops the app refusing a better answer than
 * it asked for.
 *
 * ## Nobody is thrown out of their own journal
 *
 * A PIN set by an older version, of three, four or five digits, still opens the screen and is still
 * upgraded to the stronger hash on the next unlock. What it does not do is hold the data key — see
 * `DataKeyStore` — and the settings row says so in that state rather than the app demanding a new
 * PIN from somebody who did not ask to be interrupted. This object is about PINS BEING CHOSEN, not
 * about PINs already in use.
 */
object PinPolicy {

    /** The shortest PIN the app will accept from somebody choosing one now. */
    const val MIN_DIGITS = 6

    /** The longest. Not a security limit; a limit on what one text field will hold. */
    const val MAX_DIGITS = 12

    /** Why a PIN was not accepted. Never shown as a code; the screens phrase it themselves. */
    enum class Rejection {
        TOO_SHORT,
        TOO_LONG,
        NOT_DIGITS,
    }

    /** Null when the PIN is acceptable, otherwise why not. */
    fun reject(pin: String): Rejection? = when {
        !pin.all { it in '0'..'9' } -> Rejection.NOT_DIGITS
        pin.length < MIN_DIGITS -> Rejection.TOO_SHORT
        pin.length > MAX_DIGITS -> Rejection.TOO_LONG
        else -> null
    }

    fun accepts(pin: String): Boolean = reject(pin) == null

    /**
     * Whether a text field should keep taking characters.
     *
     * Separate from [accepts] because a half-typed PIN is not a rejected one: the field has to let
     * somebody get to six digits without the app complaining at every keystroke on the way.
     */
    fun stillTypeable(typed: String): Boolean =
        typed.length <= MAX_DIGITS && typed.all { it in '0'..'9' }

    /** What the field says underneath itself. Fixed text; nothing is generated. */
    const val HELP = "Six digits or more. A longer one takes longer for someone else to guess."

    /** The field's own label. */
    const val LABEL = "PIN ($MIN_DIGITS–$MAX_DIGITS digits)"
}
