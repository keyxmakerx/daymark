package com.daymark.synccrypto

/**
 * Everything the phone says about its server, fixed and written by a person (#432). No answer from the
 * server is ever shown: an answer is read for its status and its fields, and one of these is said. None
 * repeats a code, a passphrase, a key or anything else the person typed; the one value slotted into any
 * of them is the server's address, which the person typed or scanned and the screen already shows.
 *
 * Each refusal says what did or did not happen, never a cause the phone cannot know.
 */
object PhoneWords {

    /** The code's address is not https, and the phone pairs only over https (#189). Nothing was sent. */
    const val HTTP_ADDRESS =
        "This code points at an http address, and the phone pairs only over https. Nothing was stored."

    /** The code failed its check symbol, or is not ten symbols of the alphabet. Nothing was sent. */
    const val CODE_DOES_NOT_CHECK_OUT =
        "That code does not check out, so nothing was sent. Compare it with the one on the web, one character at a time."

    /** The server refused the redemption: the one refusal for a code it has no live record of. */
    const val CODE_NOT_TAKEN = "The server did not take this code, so nothing was stored. Ask for a new one on the web."

    /** Beside the six words, while the person compares them with the web's. */
    const val COMPARE_THE_WORDS = "Check that the web shows these six words, in this order, then confirm there."

    /** The phone stopped asking before the web confirmed, or the server stopped holding the key. */
    const val TIME_RAN_OUT = "The time to confirm ran out, so nothing was stored. Ask for a new code on the web."

    /** No answer came, or none the server itself gave. */
    const val UNREACHABLE = "The server could not be reached. Nothing was stored."

    /** The server registered this phone's key, and the phone kept it. */
    fun paired(address: String): String = "Paired with $address."

    /** The wrapped key's passphrase slot did not open. */
    const val WRONG_PASSPHRASE = "That passphrase does not open the key on your server. Nothing has changed."

    /** A signed request was refused once the phone was paired: the phone forgets the pairing. */
    const val DISCONNECTED = "This phone was disconnected from the server. Pair it again to keep syncing."

    /** The server took the copy. [time] is the phone's own, as the person reads times. */
    fun sent(time: String): String = "Sent a copy to your server at $time."

    /** The copy did not go, or its answer did not come back. */
    const val NOT_SENT = "The copy was not sent. Nothing on this phone has changed."

    // The words below are for states the designer's list does not name (#432).

    /** An address the verdict declines for anything but its scheme: a user name, a query, no host. */
    const val NOT_AN_ADDRESS =
        "That address is not one the phone can pair with. Type it as the web shows it, beginning https://. Nothing was stored."

    /** Pasted text that begins like the QR code's and is not one the phone reads. */
    const val NOT_PAIRING_TEXT = "That text is not a pairing code from the web. Nothing was stored."

    /** The server answered 429: its rate limit, or the lockout of this network address. */
    const val PAUSED = "The server is pausing requests from this network, so nothing has changed. Try again in a few minutes."

    /** Under the words, while the phone asks whether the web has confirmed. */
    const val WAITING = "Waiting for you to confirm on the web."

    /** Beside the passphrase field: Argon2id at 256 MiB takes a moment on a phone. */
    const val OPENING_TAKES_TIME = "Opening the key takes a few seconds."

    /** The server holds no key document at all. */
    const val NO_KEY_YET = "Your server has no key yet. Set up your passphrase on the web first. Nothing has changed."

    /**
     * The server holds only the key parameters, whose passphrase nothing can check before a copy is
     * sent under it: the owner console wraps them into the key when it is next unlocked there.
     */
    const val KEY_NOT_READY =
        "The key on your server is not ready for a phone yet. Unlock it once on the web, then try again. Nothing has changed."

    /** A key document the phone refuses to read, or an answer that is not one. */
    const val KEY_UNREADABLE = "The key on your server could not be read. Nothing has changed."

    /** The key document is no longer the one this phone opened. */
    const val KEY_CHANGED =
        "The key on your server has changed since this phone opened it. Enter your passphrase again. Nothing was sent."

    /** Argon2id itself failed: on a phone, the memory it needs was not there. */
    const val COULD_NOT_OPEN = "This phone could not open the key just now. Nothing has changed."

    /** The server registered the key, and the phone's keystore would not keep it: the web lists a phone that holds nothing. */
    const val COULD_NOT_KEEP = "This phone could not keep the pairing. Remove it on the web, then pair again with a new code."

    /** Under the last copy's time: the name the web's sync panel reads this phone's copies by. */
    fun named(lineage: String): String = "On the server, this phone's copies are named $lineage."
}
