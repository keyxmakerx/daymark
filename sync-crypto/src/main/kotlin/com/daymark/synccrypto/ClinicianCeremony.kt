package com.daymark.synccrypto

/**
 * The owner's side of pairing with one clinician, as the phone's screen runs it (#174). Kotlin mirror
 * of `companion/web/src/lib/pairing/ownerCeremony.ts`, whose header carries the reasoning; this file
 * keeps the same order of effects and the same rules, so each is a plain-JVM test here.
 *
 * NOTHING RETRIES BY ITSELF (docs/COMPANION_PAIRING.md §4). Every function is one person's tap and
 * makes at most the requests that tap names. There is no timer, no backoff and no cadence here at
 * all: a device that asked more often after a reply failed to open would tell the server, in
 * traffic, that the code was wrong, and the server is the one party the design refuses to tell.
 *
 * A REPLY THAT DOES NOT OPEN IS A QUESTION ([Phase.Mismatch]), never a verdict: a typo and a stranger
 * holding the link are the same one bit. The person chooses between a new code, keeping the
 * invitation as it is, and ending it.
 *
 * THE ORDER OF AN APPROVAL IS THE POINT: the keys are checked against the record before anything is
 * written, recorded before anything is forwarded, and only then is the ticket sent with the owner's
 * keys sealed back. A record that refuses leaves the invitation exactly where it was.
 *
 * THE CODE IS SHOWN ONLY WHILE IT CAN DO SOMETHING. It lives in [Phase.Waiting] and nowhere else:
 * not kept ([Keep] has no place for it), not carried into the phases after a reply arrives, and a run
 * picked up after a restart ([Phase.Resumed]) cannot show it again. Its only honest remedy is a new
 * code.
 */
class ClinicianCeremony(
    private val server: PairedServer,
    private val relRef: String,
    private val pairing: ClinicianPairing,
    private val invites: ClinicianInvites,
    /** The owner's own public keys, sealed back on every approval, a replacement included. */
    private val ownerKeys: PairingPayloads.OwnerKeys,
    private val keep: Keep,
    private val drawCode: () -> ClinicianCode,
) {

    /** What the ceremony keeps between taps and across a restart, and the record it checks keys against. */
    interface Keep {
        /** Keep the run as the sync key is kept: its scalar is the only thing that can finish it. */
        fun saveRun(run: ClinicianPairing.OwnerRun)
        fun loadRun(): ClinicianPairing.OwnerRun?
        fun forgetRun()

        /** The keys on file for this relationship, or null while it has none. */
        fun held(): ClinicianKeys.Held?

        /** Every other relationship's keys, with the owner's own name for each. */
        fun others(): List<ClinicianKeys.Named>

        /** Record the offer's keys, insert-only. False when nothing was written. */
        fun pin(offer: PairingPayloads.TherapistOffer): Boolean
    }

    sealed interface Phase {
        /** No invitation yet. */
        object Idle : Phase

        /** An invitation, and no run open for it. */
        class Invited(val invite: ClinicianInvites.Invite, val attemptsLeft: Int, val failCount: Long) : Phase

        /** A run is open and its code is on screen. */
        class Waiting(
            val invite: ClinicianInvites.Invite,
            val code: ClinicianCode,
            val run: ClinicianPairing.OwnerRun,
            val attemptsLeft: Int,
            val failCount: Long,
        ) : Phase

        /** A run picked up after a restart: it can be finished, and its code cannot be shown. */
        class Resumed(val invite: ClinicianInvites.Invite, val run: ClinicianPairing.OwnerRun, val attemptsLeft: Int, val failCount: Long) : Phase

        /** Someone answered, and what they sealed did not open. No diagnosis exists. */
        class Mismatch(val invite: ClinicianInvites.Invite, val run: ClinicianPairing.OwnerRun, val attemptsLeft: Int, val failCount: Long) : Phase

        /**
         * The offer opened. [keys] is what approving would do to the record, worked out before
         * anything is written. Holds the run's key until it is approved or left ([discard]).
         */
        class Answered internal constructor(
            val invite: ClinicianInvites.Invite,
            internal val complete: ClinicianPairing.Collected.Complete,
            val offer: PairingPayloads.TherapistOffer,
            val keys: ClinicianKeys.Check,
            val attemptsLeft: Int,
            val failCount: Long,
        ) : Phase

        /** Approved; the ticket is with the server and the clinician has still to finish. */
        class Approved(val invite: ClinicianInvites.Invite, val run: ClinicianPairing.OwnerRun?, val offer: PairingPayloads.TherapistOffer?, val replaced: Boolean) : Phase

        /** This run or this invitation is over. */
        class Ended(val invite: ClinicianInvites.Invite, val reason: EndReason) : Phase
    }

    enum class EndReason { CANCELLED, SUPERSEDED, EXPIRED, REPORTED, CAPPED }

    /** Why a tap did not move the ceremony. The screen has words for each; none is shown as it is. */
    enum class Halt {
        /** No answer. Nothing changed that the phone knows of, or it may have: the screen says which. */
        NO_ANSWER,

        /** The server answered, and not with what the step needed. Nothing changed. */
        REFUSED,

        /** The run changed between reading it and approving it. */
        RUN_CHANGED,

        /** The server contradicted a reply it had already given. The run is not to be trusted. */
        CONTRADICTED,

        /** The offered keys are another relationship's. Nothing was approved. */
        OTHER_PERSON,

        /** The offered keys could not be read, or not recorded. Nothing was approved. */
        NOT_RECORDED,
    }

    /** Where a tap left the ceremony, and why it stopped short when it did. */
    class Step(val phase: Phase, val halt: Halt? = null)

    /**
     * Mint an invitation. Draws no code and opens no run: the link goes one way and the code another,
     * and a code on a screen nobody is reading yet is only a code on a screen.
     */
    fun startInvitation(): Step = when (val minted = invites.mint(server, relRef, ClinicianInvites.READ_SHARE)) {
        is ClinicianInvites.Minted.Made -> Step(Phase.Invited(minted.invite, MAX_EXCHANGES_PER_INVITE, 0))
        ClinicianInvites.Minted.NoAnswer -> Step(Phase.Idle, Halt.NO_ANSWER)
        ClinicianInvites.Minted.Refused -> Step(Phase.Idle, Halt.REFUSED)
    }

    /** A fresh code and a run for it, kept before the screen shows the code. */
    fun openRun(state: Phase): Step {
        val (invite, attemptsLeft, failCount) = when (state) {
            is Phase.Invited -> Triple(state.invite, state.attemptsLeft, state.failCount)
            is Phase.Waiting -> Triple(state.invite, state.attemptsLeft, state.failCount)
            is Phase.Resumed -> Triple(state.invite, state.attemptsLeft, state.failCount)
            is Phase.Mismatch -> Triple(state.invite, state.attemptsLeft, state.failCount)
            else -> return Step(state, Halt.REFUSED)
        }
        val code = drawCode()
        return when (val opened = pairing.open(server, relRef, invite.inviteId, code)) {
            is ClinicianPairing.Opened.Running -> {
                keep.saveRun(opened.run)
                Step(Phase.Waiting(invite, code, opened.run, (attemptsLeft - 1).coerceAtLeast(0), failCount))
            }
            is ClinicianPairing.Opened.Stopped -> when (opened.stop) {
                ClinicianPairing.Stop.NO_OPEN_INVITATION -> ended(invite, EndReason.EXPIRED)
                ClinicianPairing.Stop.RUNS_USED -> ended(invite, EndReason.CAPPED)
                ClinicianPairing.Stop.UNREACHABLE -> Step(state, Halt.NO_ANSWER)
                else -> Step(state, Halt.REFUSED)
            }
        }
    }

    /**
     * Look for the reply: one read, whatever came before. The only place an offer is opened and the
     * only place a mismatch is named, and both return a phase rather than an error.
     */
    fun checkForReply(state: Phase): Step {
        val (invite, run, attemptsLeft, failCount) = when (state) {
            is Phase.Waiting -> Run(state.invite, state.run, state.attemptsLeft, state.failCount)
            is Phase.Resumed -> Run(state.invite, state.run, state.attemptsLeft, state.failCount)
            is Phase.Mismatch -> Run(state.invite, state.run, state.attemptsLeft, state.failCount)
            else -> return Step(state, Halt.REFUSED)
        }
        return when (val read = pairing.collect(server, run)) {
            ClinicianPairing.Collected.Waiting -> Step(state)
            ClinicianPairing.Collected.Cancelled -> ended(invite, EndReason.CANCELLED)
            ClinicianPairing.Collected.Superseded -> ended(invite, EndReason.SUPERSEDED)
            is ClinicianPairing.Collected.Stopped -> Step(
                state,
                when (read.stop) {
                    ClinicianPairing.Stop.UNREACHABLE -> Halt.NO_ANSWER
                    ClinicianPairing.Stop.CONTRADICTED -> Halt.CONTRADICTED
                    else -> Halt.REFUSED
                },
            )
            is ClinicianPairing.Collected.Complete -> {
                // Answered either way: keep the run with its reply pinned, so a restart cannot be shown another.
                keep.saveRun(read.run)
                val offer = read.offer
                if (offer == null) {
                    read.isk.fill(0)
                    Step(Phase.Mismatch(invite, read.run, attemptsLeft, failCount))
                } else {
                    val keys = ClinicianKeys.check(offer, keep.held(), keep.others())
                    Step(Phase.Answered(invite, read, offer, keys, attemptsLeft, failCount))
                }
            }
        }
    }

    /** Check, record, then forward with the owner's keys sealed back. In that order. */
    fun approve(state: Phase): Step {
        if (state !is Phase.Answered) return Step(state, Halt.REFUSED)
        when (state.keys) {
            is ClinicianKeys.Check.OtherPerson -> return Step(state, Halt.OTHER_PERSON)
            ClinicianKeys.Check.Unreadable -> return Step(state, Halt.NOT_RECORDED)
            else -> Unit
        }
        if (!keep.pin(state.offer)) return Step(state, Halt.NOT_RECORDED)
        val run = state.complete.run
        // approve() wipes the key whatever it answers; a retry derives it again from the kept run.
        return when (val approval = pairing.approve(server, state.complete, ownerKeys)) {
            ClinicianPairing.Approval.Approved -> {
                keep.forgetRun()
                Step(Phase.Approved(state.invite, run, state.offer, state.keys == ClinicianKeys.Check.Supersedes))
            }
            is ClinicianPairing.Approval.Stopped -> Step(
                Phase.Resumed(state.invite, run, state.attemptsLeft, state.failCount),
                when (approval.stop) {
                    ClinicianPairing.Stop.UNREACHABLE -> Halt.NO_ANSWER
                    ClinicianPairing.Stop.RUN_CHANGED -> Halt.RUN_CHANGED
                    else -> Halt.REFUSED
                },
            )
        }
    }

    /**
     * A new code: withdraw this run, then open a fresh one. Withdrawing first is what puts the
     * owner's own `pairing.cancelled` line in their log.
     */
    fun newCode(state: Phase): Step {
        val run = when (state) {
            is Phase.Waiting -> state.run
            is Phase.Resumed -> state.run
            is Phase.Mismatch -> state.run
            else -> return Step(state, Halt.REFUSED)
        }
        if (pairing.cancel(server, run) == null) return Step(state, Halt.NO_ANSWER)
        keep.forgetRun()
        return openRun(state)
    }

    /** Put a reply that did not open aside, and keep the invitation. Sends nothing and keeps nothing. */
    fun keepInvitation(state: Phase): Step =
        if (state is Phase.Mismatch) Step(Phase.Invited(state.invite, state.attemptsLeft, state.failCount))
        else Step(state, Halt.REFUSED)

    /** Take back an approval nobody finished: the invitation goes back to waiting for a code. */
    fun abandonApproval(state: Phase): Step {
        if (state !is Phase.Approved || state.run == null) return Step(state, Halt.REFUSED)
        if (pairing.cancel(server, state.run) == null) return Step(state, Halt.NO_ANSWER)
        keep.forgetRun()
        val live = invites.list(server, relRef)?.firstOrNull { it.inviteId == state.invite.inviteId }
        return Step(
            Phase.Invited(
                ClinicianInvites.Invite(state.invite.inviteId, null, state.invite.expiresAt),
                attemptsLeftOf(live?.exchangeCount ?: 0),
                live?.failCount ?: 0,
            ),
        )
    }

    /** End the invitation. The one act that kills one; a wrong code never does (§7). */
    fun stopInvitation(state: Phase): Step {
        val invite = inviteOf(state) ?: return Step(state, Halt.REFUSED)
        if (invites.report(server, invite.inviteId) == null) return Step(state, Halt.NO_ANSWER)
        discard(state)
        keep.forgetRun()
        return Step(Phase.Ended(invite, EndReason.REPORTED))
    }

    /**
     * Where this relationship stands on opening the screen, from the server's list and the kept run.
     * A kept run that is not the live invitation's newest is forgotten rather than shown.
     */
    fun restore(): Step {
        val list = invites.list(server, relRef) ?: return Step(Phase.Idle, Halt.NO_ANSWER)
        val live = list.firstOrNull { it.live }
        val kept = keep.loadRun()
        if (live == null) {
            if (kept != null) keep.forgetRun()
            return Step(Phase.Idle)
        }
        val invite = ClinicianInvites.Invite(live.inviteId, null, live.expiresAt)
        val attemptsLeft = attemptsLeftOf(live.exchangeCount)
        val latestId = live.latestExchangeId
        if (kept == null || latestId == null || kept.exchangeId != latestId || kept.inviteId != live.inviteId) {
            if (kept != null) keep.forgetRun()
            if (live.latestExchangeState == "CLOSED" && live.status == "REDEEMING") {
                // Approved before a restart; the clinician has still to finish. Nothing to show of the offer.
                return Step(Phase.Approved(invite, null, null, false))
            }
            return Step(Phase.Invited(invite, attemptsLeft, live.failCount))
        }
        return when (live.latestExchangeState) {
            "CANCELLED" -> { keep.forgetRun(); ended(invite, EndReason.CANCELLED) }
            "SUPERSEDED" -> { keep.forgetRun(); ended(invite, EndReason.SUPERSEDED) }
            else -> Step(Phase.Resumed(invite, kept, attemptsLeft, live.failCount))
        }
    }

    /** Wipe what a phase holds that must not outlive the screen: the run's key, while answered. */
    fun discard(state: Phase) {
        if (state is Phase.Answered) state.complete.isk.fill(0)
    }

    private fun ended(invite: ClinicianInvites.Invite, reason: EndReason): Step {
        keep.forgetRun()
        return Step(Phase.Ended(invite, reason))
    }

    private data class Run(
        val invite: ClinicianInvites.Invite,
        val run: ClinicianPairing.OwnerRun,
        val attemptsLeft: Int,
        val failCount: Long,
    )

    private fun inviteOf(state: Phase): ClinicianInvites.Invite? = when (state) {
        Phase.Idle -> null
        is Phase.Invited -> state.invite
        is Phase.Waiting -> state.invite
        is Phase.Resumed -> state.invite
        is Phase.Mismatch -> state.invite
        is Phase.Answered -> state.invite
        is Phase.Approved -> state.invite
        is Phase.Ended -> state.invite
    }

    companion object {
        /** Mirrors the server's PairingStore.MAX_EXCHANGES_PER_INVITE, which refuses past it regardless. */
        const val MAX_EXCHANGES_PER_INVITE = 8

        private fun attemptsLeftOf(used: Long): Int = (MAX_EXCHANGES_PER_INVITE - used).coerceIn(0, MAX_EXCHANGES_PER_INVITE.toLong()).toInt()
    }
}

/**
 * The offered keys read against the phone's record, without writing anything. Mirror of `checkOffer`
 * in `companion/web/src/lib/owner/therapistKeys.ts`, whose header argues why a matching code on a
 * fresh invitation may replace the keys on file, and why keys already held for another person may not.
 */
object ClinicianKeys {

    /** Keys held for this relationship. */
    class Held(val boxPub: ByteArray, val signPub: ByteArray)

    /** Keys held for another relationship, with the owner's own name for it. */
    class Named(val displayName: String, val boxPub: ByteArray, val signPub: ByteArray)

    sealed interface Check {
        /** Nothing on file: these become the first record. */
        object First : Check
        /** Exactly the keys already held. */
        object Unchanged : Check
        /** Different keys are held for this person; approving supersedes them, and the screen says so first. */
        object Supersedes : Check
        /** Either key is on file for somebody else. Named by the owner's name for them, never the offer's. */
        class OtherPerson(val displayName: String) : Check
        /** Not two public keys. */
        object Unreadable : Check
    }

    fun check(offer: PairingPayloads.TherapistOffer, held: Held?, others: List<Named>): Check {
        val box = decode(offer.boxPubB64) ?: return Check.Unreadable
        val sign = decode(offer.signPubB64) ?: return Check.Unreadable
        for (other in others) {
            if (!usable(other.boxPub, other.signPub)) continue
            // Either key matching is enough: both mean two people filed under one identity.
            if (other.signPub.contentEquals(sign) || other.boxPub.contentEquals(box)) return Check.OtherPerson(other.displayName)
        }
        if (held == null || !usable(held.boxPub, held.signPub)) return Check.First
        if (held.boxPub.contentEquals(box) && held.signPub.contentEquals(sign)) return Check.Unchanged
        return Check.Supersedes
    }

    private fun usable(box: ByteArray, sign: ByteArray) = box.size == KEY_BYTES && sign.size == KEY_BYTES

    private fun decode(b64: String): ByteArray? = try {
        SyncCrypto.fromBase64(b64).takeIf { it.size == KEY_BYTES }
    } catch (_: IllegalArgumentException) {
        null
    }

    private const val KEY_BYTES = 32
}
