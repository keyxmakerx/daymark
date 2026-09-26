package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium
import java.io.IOException

/**
 * Pairing this phone with its server, the phone's half (#432; docs/SYNC_PROTOCOL.md §2.2), in three
 * steps the screen takes one at a time, each ending in a state or in fixed words ([PhoneWords]):
 *
 * 1. [read] what the person typed, or the QR code's text they pasted, and decide before anything is
 *    sent. An address that is not https is declined here, and so is a code that fails its check symbol:
 *    both would otherwise reach the server, and every refusal counts toward the lockout of the phone's
 *    whole network address, which on a home connection pauses the console too.
 * 2. [redeem] the code with a new Ed25519 key made in memory for this server, and the proof that the
 *    phone holds it. The key's six words are what the person compares with the web's.
 * 3. [awaitRegistration]: ask about every [POLL_EVERY_MS] whether the web has confirmed, until the
 *    server says `registered` or the time to confirm is over. Only `registered` hands the key back to be
 *    kept; until then it lives in memory only, and nothing is stored.
 *
 * NO REFUSED REQUEST IS SENT AGAIN. The redemption is sent once. The poll stops at the first refusal
 * (401) and at the first 429, and asks again only after an answer that refuses nothing: `pending`, or
 * no answer at all, or a 5xx. None of those counts toward any lockout, and the poll ends at its
 * deadline whatever the answers are.
 *
 * THE DEADLINE. The server holds a redeemed key for [CONFIRM_WINDOW_MS] and answers `confirmBy` by its
 * own clock; a poll after that is refused, and would count. The phone cannot read the server's clock,
 * so it asks only until the earlier of the window, measured from the moment the redemption was sent (the
 * server's window starts after that), and `confirmBy` read on the phone's own clock, less
 * [LAST_ASK_MARGIN_MS] for a question still on its way. A phone whose clock runs ahead gives up early;
 * none asks after the server has stopped holding its key.
 */
class PhonePairing(
    private val sodium: LazySodium,
    private val transport: Transport,
    private val clock: PhoneClock = PhoneClock.SYSTEM,
) {

    /** What the person gave, read before anything is sent. */
    sealed interface Reading {
        /** Redeem the code at [address]. The code is never shown. */
        class Ready internal constructor(val address: String, internal val code: PairingCode) : Reading

        /** Nothing is sent, and [words] say why. */
        class Declined internal constructor(val words: String) : Reading
    }

    /** What the redemption came to. */
    sealed interface Redemption {
        /** The server holds the key until the web confirms it: show [pending]'s words and ask. */
        class Waiting internal constructor(val pending: PendingPairing) : Redemption

        /** Nothing is stored, and [words] say why. */
        class Stopped internal constructor(val words: String) : Redemption
    }

    /** What asking came to. */
    sealed interface Registration {
        /** The web confirmed: keep [server]'s key ([DeviceKey.seedToKeep]). */
        class Registered internal constructor(val server: PairedServer) : Registration

        /** Nothing is stored, and [words] say why. The key is dropped with this object. */
        class Stopped internal constructor(val words: String) : Registration
    }

    /**
     * What the person gave: the server's address and the code as they typed them, or, in the address
     * field, the QR code's text (`daymark-pair:v1?...`), which carries both and makes the code field
     * unread. Either way the verdict is [PairingVerdict]'s, so pasting the text does exactly what typing
     * its two values does.
     */
    fun read(addressOrText: String, code: String): Reading {
        val trimmed = addressOrText.trim { RecoveryCode.isJsWhitespace(it) }
        val scanned = trimmed.startsWith(PairingVerdict.QR_SCHEME)
        return when (val verdict = if (scanned) PairingVerdict.ofScan(trimmed) else PairingVerdict.ofTyped(addressOrText, code)) {
            is PairingVerdict.Redeem -> Reading.Ready(verdict.address, verdict.code)
            is PairingVerdict.Decline -> Reading.Declined(declineWords(verdict.reason, if (scanned) null else trimmed))
        }
    }

    /**
     * Redeems the code with a new key, once. The key lives in the [PendingPairing] and nowhere else.
     * Runs on the caller's thread and blocks until the answer.
     */
    fun redeem(ready: Reading.Ready): Redemption {
        val key = DeviceKey.generate(sodium)
        val body = (
            "{\"code\":\"${ready.code.canonical}\"," +
                "\"publicKey\":\"${key.publicKeyB64}\"," +
                "\"signature\":\"${key.redeemProof(ready.code)}\"}"
            ).toByteArray(Charsets.UTF_8)
        val sentAt = clock.elapsedMillis()
        val answer = try {
            transport.send("POST", ready.address + REDEEM, mapOf("Content-Type" to "application/json"), body)
        } catch (_: IOException) {
            return Redemption.Stopped(PhoneWords.UNREACHABLE)
        }
        return when (answer.status) {
            202 -> {
                val fields = Answers.jsonObject(answer.body)
                val confirmBy = Answers.wholeNumber(fields?.get("confirmBy"))
                // The key id the server made must be this key's: an answer about another key is no
                // answer about this one.
                if (fields == null || fields["keyId"] != key.keyId || confirmBy == null) {
                    return Redemption.Stopped(PhoneWords.CODE_NOT_TAKEN)
                }
                val window = minOf(CONFIRM_WINDOW_MS, (confirmBy - clock.nowMillis()).coerceAtLeast(0))
                Redemption.Waiting(PendingPairing(ready.address, key, key.words(), confirmBy, sentAt + window - LAST_ASK_MARGIN_MS))
            }
            429 -> Redemption.Stopped(PhoneWords.PAUSED)
            in 500..599 -> Redemption.Stopped(PhoneWords.UNREACHABLE)
            else -> Redemption.Stopped(PhoneWords.CODE_NOT_TAKEN)
        }
    }

    /**
     * Asks, signed with the pending key, whether the web has confirmed, about every [POLL_EVERY_MS]
     * until the deadline ([PendingPairing]). Blocks the caller's thread; a pause called off
     * ([PhoneClock.pause] throwing [InterruptedException]) ends it with nothing kept.
     */
    @Throws(InterruptedException::class)
    fun awaitRegistration(pending: PendingPairing): Registration {
        val server = PairedServer(pending.address, pending.key)
        val requests = SignedRequests(server, transport, clock)
        var lastWentUnanswered = false
        while (true) {
            val left = pending.askUntil - clock.elapsedMillis()
            if (left <= 0) break
            clock.pause(minOf(POLL_EVERY_MS, left))
            if (clock.elapsedMillis() >= pending.askUntil) break
            val answer = try {
                requests.get(REGISTRATION)
            } catch (_: IOException) {
                lastWentUnanswered = true
                continue
            }
            lastWentUnanswered = false
            when (answer.status) {
                200 -> return if (Answers.jsonObject(answer.body)?.get("state") == "registered") {
                    Registration.Registered(server)
                } else {
                    Registration.Stopped(PhoneWords.CODE_NOT_TAKEN)
                }
                202 -> if (Answers.jsonObject(answer.body)?.get("state") != "pending") return Registration.Stopped(PhoneWords.CODE_NOT_TAKEN)
                // Refused: the key is not held any more, or the phone's clock is too far from the
                // server's. Either way asking again would only be refused again, and counted.
                401 -> return Registration.Stopped(PhoneWords.TIME_RAN_OUT)
                429 -> return Registration.Stopped(PhoneWords.PAUSED)
                in 500..599 -> lastWentUnanswered = true
                else -> return Registration.Stopped(PhoneWords.CODE_NOT_TAKEN)
            }
        }
        return Registration.Stopped(if (lastWentUnanswered) PhoneWords.UNREACHABLE else PhoneWords.TIME_RAN_OUT)
    }

    companion object {
        /** The redemption, which carries no credential but the code and the key's proof. */
        const val REDEEM = "/v1/devices/redeem"

        /** The poll: the one route a key awaiting confirmation reaches. */
        const val REGISTRATION = "/v1/devices/registration"

        /** How often the phone asks while the person confirms on the web. */
        const val POLL_EVERY_MS = 2_000L

        /** How long the server holds a redeemed key for its confirmation: the server's CONFIRM_WINDOW_MS. */
        const val CONFIRM_WINDOW_MS = 120_000L

        /** How long before its deadline the phone stops asking, for a question still on its way. */
        const val LAST_ASK_MARGIN_MS = 1_000L

        private const val HTTP = "http://"

        /**
         * The words for a verdict's [reason]. [typedAddress] is the address as typed, or null for a
         * pasted QR text; an http address and an address with no scheme at all are both declined as
         * not https, and only the first is told that it is http.
         */
        internal fun declineWords(reason: PairingVerdict.Reason, typedAddress: String?): String = when (reason) {
            PairingVerdict.Reason.NOT_HTTPS ->
                if (typedAddress == null || asciiLower(typedAddress.take(HTTP.length)) == HTTP) {
                    PhoneWords.HTTP_ADDRESS
                } else {
                    PhoneWords.NOT_AN_ADDRESS
                }
            PairingVerdict.Reason.USER_NAME,
            PairingVerdict.Reason.NO_HOST,
            PairingVerdict.Reason.QUERY,
            PairingVerdict.Reason.FRAGMENT,
            PairingVerdict.Reason.NOT_AN_ADDRESS,
            -> PhoneWords.NOT_AN_ADDRESS
            PairingVerdict.Reason.NOT_A_PAIRING_QR,
            PairingVerdict.Reason.UNKNOWN_VERSION,
            PairingVerdict.Reason.EXTRA_PARAMETER,
            PairingVerdict.Reason.DUPLICATE_PARAMETER,
            PairingVerdict.Reason.MISSING_PARAMETER,
            PairingVerdict.Reason.NOT_ENCODED,
            -> PhoneWords.NOT_PAIRING_TEXT
            PairingVerdict.Reason.CODE_EMPTY,
            PairingVerdict.Reason.CODE_CONFUSABLE,
            PairingVerdict.Reason.CODE_NOT_IN_ALPHABET,
            PairingVerdict.Reason.CODE_TOO_SHORT,
            PairingVerdict.Reason.CODE_TOO_LONG,
            PairingVerdict.Reason.CODE_CHECK_SYMBOL,
            -> PhoneWords.CODE_DOES_NOT_CHECK_OUT
        }

        private fun asciiLower(text: String): String =
            buildString(text.length) { for (c in text) append(if (c in 'A'..'Z') c + ('a' - 'A') else c) }
    }
}

/**
 * A redeemed code waiting for the web's confirmation (#432): the key made for it, in memory and nowhere
 * else, and the six words both screens show. Dropped, the key is gone and the server lets it lapse.
 */
class PendingPairing internal constructor(
    /** The server's address, as the verdict read it. */
    val address: String,
    internal val key: DeviceKey,
    /** The six words of the key, in order, which the web shows too. */
    val words: List<String>,
    /** When the server stops holding the key, by the server's clock, in milliseconds. */
    val confirmBy: Long,
    /** When the phone stops asking, on [PhoneClock.elapsedMillis]. */
    internal val askUntil: Long,
) {
    override fun toString(): String = "PendingPairing($address, ${key.keyId})"
}
