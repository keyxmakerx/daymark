package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium
import java.io.IOException

/**
 * The owner's half of pairing with a clinician, run from the phone (docs/COMPANION_PAIRING.md §13,
 * §14; #174). The Kotlin mirror of the owner's functions in `companion/web/src/lib/pairing/relay.ts`:
 * [open] draws nothing and sends MSGa, [collect] reads the reply and opens the clinician's offer,
 * [approve] forwards the enrol ticket with the owner's keys sealed back, and [cancel] withdraws.
 * Every request is signed by the phone's own key; the server takes a registered phone on these
 * routes as it takes the owner's token.
 *
 * WHY THE PHONE. The server serves the owner console's code, so a server that turned on its owner
 * could serve a page that reads the code as it is typed. It cannot rewrite an installed app. The
 * protocol and its bytes are unchanged; OwnerPairingVectorTest pins them to the web's.
 *
 * WHAT IS SECRET. The CPace scalar in [OwnerRun.start] is the only thing that can finish the run,
 * and the code, which this class never keeps. [OwnerRun] holds the scalar so a run can be finished
 * after the app restarts (the clinician may answer a day later); whoever keeps it keeps it as they
 * keep the sync key. The key a run derives is never kept: it is derived again from the scalar.
 *
 * ONE RUN, ONE KEY. Once a reply has produced the key it is pinned ([OwnerRun.pinnedMsgBB64]): a
 * later read showing a different reply, or the run gone back to waiting, is refused rather than
 * derived again, because that is the server contradicting its own record.
 *
 * A REPLY THAT DOES NOT OPEN is [Collected.Complete] with a null offer: a wrong code, a swapped
 * envelope and an attacker are the same one bit, and only a person decides what it means (§7).
 * Nothing here burns an invitation, retries faster, or tells the server the reply did not open.
 */
class ClinicianPairing(
    private val sodium: LazySodium,
    private val transport: Transport,
    private val clock: PhoneClock = PhoneClock.SYSTEM,
) {
    private val cpace = CpaceCrypto(sodium)
    private val envelope = PairingEnvelope(sodium)

    /** The phone's half of one run. Everything but [start] is public. */
    class OwnerRun(
        val relRef: String,
        val inviteId: String,
        val exchangeId: String,
        val sidB64: String,
        /** The scalar and MSGa. Secret; the only thing that can finish this run. */
        val start: CpaceCrypto.StartResult,
        /** The reply the key was derived from, once it has been. Public bytes. */
        val pinnedMsgBB64: String? = null,
    ) {
        override fun toString(): String = "OwnerRun($exchangeId)"
    }

    /** Why a step stopped. None of them is shown as it is; the screen has words for each. */
    enum class Stop {
        /** No answer from the server. Nothing changed that the phone knows of. */
        UNREACHABLE,
        /** No open invitation for this relationship: expired, used, or withdrawn. */
        NO_OPEN_INVITATION,
        /** This invitation's runs are all used; a new invitation is needed. */
        RUNS_USED,
        /** The server answered a step this phone was not told it could take. */
        REFUSED,
        /** The run changed between reading it and approving it; read it again. */
        RUN_CHANGED,
        /** The server contradicted a reply it had already given. The run is not to be trusted. */
        CONTRADICTED,
        /** An answer this phone does not read. */
        UNEXPECTED,
    }

    sealed interface Opened {
        class Running internal constructor(val run: OwnerRun) : Opened
        class Stopped internal constructor(val stop: Stop) : Opened
    }

    sealed interface Collected {
        object Waiting : Collected
        object Cancelled : Collected
        /** A fresh run for this invitation retired this one, unanswered. */
        object Superseded : Collected
        /**
         * The reply is in and the key derived; [run] has it pinned and is the one to keep from now on.
         * [offer] is the clinician's offer when their envelope opened under this key, and null when it
         * did not. The key itself is handed to [approve] and never kept.
         */
        class Complete internal constructor(val run: OwnerRun, val isk: ByteArray, val offer: PairingPayloads.TherapistOffer?) : Collected
        class Stopped internal constructor(val stop: Stop) : Collected
    }

    sealed interface Approval {
        object Approved : Approval
        class Stopped internal constructor(val stop: Stop) : Approval
    }

    /** Owner touch 1: derive MSGa from [code] and post it for the invitation. */
    fun open(server: PairedServer, relRef: String, inviteId: String, code: ClinicianCode): Opened {
        if (!ID.matches(relRef) || !ID.matches(inviteId)) return Opened.Stopped(Stop.UNEXPECTED)
        val sid = sodium.randomBytesBuf(SID_BYTES)
        val start = cpace.start(code.prs(), CpaceCrypto.channelIdentifier(relRef, inviteId), sid, AD_OWNER)
        val sidB64 = SyncCrypto.toBase64(sid)
        val body = """{"inviteId":"$inviteId","sidB64":"$sidB64","msgAB64":"${SyncCrypto.toBase64(start.msgA)}"}"""
        val answer = try {
            SignedRequests(server, transport, clock).post(pairingPath(relRef), body.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            start.ya.fill(0)
            return Opened.Stopped(Stop.UNREACHABLE)
        }
        val stop = when (answer.status) {
            201 -> null
            404 -> Stop.NO_OPEN_INVITATION
            409 -> Stop.RUNS_USED
            else -> Stop.REFUSED
        }
        val exchangeId = Answers.jsonObject(answer.body)?.get("exchangeId") as? String
        if (stop != null || exchangeId == null || !ID.matches(exchangeId)) {
            start.ya.fill(0)
            return Opened.Stopped(stop ?: Stop.UNEXPECTED)
        }
        return Opened.Running(OwnerRun(relRef, inviteId, exchangeId, sidB64, start))
    }

    /**
     * Owner touch 3: look for the reply, and when it is there derive the key and open the offer.
     * Takes no code: a run kept across a restart finishes here. Writes nothing to the server.
     */
    fun collect(server: PairedServer, run: OwnerRun): Collected {
        val answer = try {
            SignedRequests(server, transport, clock).get(runPath(run))
        } catch (_: IOException) {
            return Collected.Stopped(Stop.UNREACHABLE)
        }
        if (answer.status != 200) return Collected.Stopped(Stop.REFUSED)
        val body = Answers.jsonObject(answer.body) ?: return Collected.Stopped(Stop.UNEXPECTED)
        val state = body["state"] as? String
        val keyed = run.pinnedMsgBB64 != null
        if (keyed && (state == "OPEN" || state == "SUPERSEDED")) return Collected.Stopped(Stop.CONTRADICTED)
        when (state) {
            "OPEN" -> return Collected.Waiting
            "CANCELLED" -> return Collected.Cancelled
            "SUPERSEDED" -> return Collected.Superseded
            "RESPONDED", "CLOSED" -> Unit
            else -> return Collected.Stopped(Stop.UNEXPECTED)
        }
        val msgBB64 = body["msgBB64"] as? String ?: return Collected.Stopped(Stop.UNEXPECTED)
        if (run.pinnedMsgBB64 != null && run.pinnedMsgBB64 != msgBB64) return Collected.Stopped(Stop.CONTRADICTED)
        val isk = try {
            cpace.finish(SyncCrypto.fromBase64(run.sidB64), run.start, SyncCrypto.fromBase64(msgBB64))
        } catch (_: IllegalArgumentException) {
            return Collected.Stopped(Stop.UNEXPECTED)
        } catch (_: CpaceCrypto.CpaceException) {
            // A reply that is not a valid point cannot have come from the code; the same one bit as
            // an envelope that does not open would be, but there is no key to hand on.
            return Collected.Stopped(Stop.UNEXPECTED)
        }
        val pinned = OwnerRun(run.relRef, run.inviteId, run.exchangeId, run.sidB64, run.start, msgBB64)
        val sealed = (body["envB64"] as? String)?.let { b64 ->
            try {
                SyncCrypto.fromBase64(b64)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val opened = sealed?.let { envelope.open(isk, run.sidB64, PairingEnvelope.Direction.THERAPIST_TO_OWNER, it) }
        return Collected.Complete(pinned, isk, opened?.let(PairingPayloads::decodeTherapistOffer))
    }

    /**
     * Owner touch 4: approve, forwarding the offer's enrol ticket and sealing [ownerKeys] back to the
     * clinician under the same key. Sealed here and nowhere earlier, so "the owner said yes" and "the
     * clinician learned the owner's keys" are one event. [isk] is wiped either way.
     */
    fun approve(
        server: PairedServer,
        complete: Collected.Complete,
        ownerKeys: PairingPayloads.OwnerKeys,
    ): Approval {
        val offer = complete.offer
        try {
            if (offer == null) return Approval.Stopped(Stop.REFUSED)
            val e2 = envelope.seal(
                complete.isk,
                complete.run.sidB64,
                PairingEnvelope.Direction.OWNER_TO_THERAPIST,
                PairingPayloads.encodeOwnerKeys(ownerKeys),
            )
            val body = """{"enrolTicketB64":"${offer.enrolTicketB64}","envB64":"${SyncCrypto.toBase64(e2)}"}"""
            val answer = try {
                SignedRequests(server, transport, clock).post("${runPath(complete.run)}/approve", body.toByteArray(Charsets.UTF_8))
            } catch (_: IOException) {
                return Approval.Stopped(Stop.UNREACHABLE)
            }
            return when (answer.status) {
                204 -> Approval.Approved
                409 -> Approval.Stopped(Stop.RUN_CHANGED)
                else -> Approval.Stopped(Stop.REFUSED)
            }
        } finally {
            complete.isk.fill(0)
        }
    }

    /** The owner's Cancel. True when the server withdrew the run, false when nothing was left to; null when it could not tell. */
    fun cancel(server: PairedServer, run: OwnerRun): Boolean? {
        val answer = try {
            SignedRequests(server, transport, clock).post("${runPath(run)}/cancel", null)
        } catch (_: IOException) {
            return null
        }
        return when (answer.status) {
            204 -> true
            410 -> false
            else -> null
        }
    }

    private fun pairingPath(relRef: String) = "/v1/relations/$relRef/pairing"

    private fun runPath(run: OwnerRun) = "${pairingPath(run.relRef)}/${run.exchangeId}"

    companion object {
        private const val SID_BYTES = 16

        /** The CPace associated data each side contributes, as the web's AD_OWNER. */
        private val AD_OWNER = "owner".toByteArray(Charsets.UTF_8)

        /**
         * The ids the server mints: URL-safe and short. Anything else is refused before it is put in a
         * path or a body, so neither needs escaping.
         */
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

/**
 * The code the owner says to the clinician (docs/COMPANION_PAIRING.md §5): seven symbols of the
 * recovery code's alphabet and a check symbol, drawn uniformly. Mirrors `newPairingCode` in
 * `companion/web/src/lib/pairing/pairingCode.ts`. One per run; never kept, never logged.
 */
class ClinicianCode private constructor(val canonical: String) {

    /** In groups of four, as the person reads it out. */
    val display: String get() = canonical.chunked(GROUP).joinToString("-")

    internal fun prs(): ByteArray = canonical.toByteArray(Charsets.UTF_8)

    override fun toString(): String = "ClinicianCode(not shown)"

    companion object {
        const val PAYLOAD_SYMBOLS = 7
        private const val GROUP = 4
        private val ALPHABET = PairingCode.ALPHABET

        /** A fresh code, by rejection sampling so no symbol is favoured: bytes at or over 248 are drawn again. */
        fun draw(sodium: LazySodium): ClinicianCode {
            val limit = ALPHABET.length * (256 / ALPHABET.length)
            val symbols = StringBuilder()
            while (symbols.length < PAYLOAD_SYMBOLS) {
                val bytes = sodium.randomBytesBuf(PAYLOAD_SYMBOLS)
                for (b in bytes) {
                    val v = b.toInt() and 0xff
                    if (v < limit && symbols.length < PAYLOAD_SYMBOLS) symbols.append(ALPHABET[v % ALPHABET.length])
                }
                bytes.fill(0)
            }
            return of(symbols.toString())
        }

        internal fun of(payload: String): ClinicianCode {
            require(payload.length == PAYLOAD_SYMBOLS && payload.all { it in ALPHABET })
            return ClinicianCode(payload + PairingCode.checkSymbol(payload))
        }
    }
}
