package com.daymark.companion.routes

import com.daymark.companion.auth.AttemptBudget
import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.PairingStore
import com.daymark.companion.auth.PersistentAttemptLimiter
import com.daymark.companion.clientAddress
import com.daymark.companion.storage.AuditAction
import com.daymark.companion.storage.AuditActor
import com.daymark.companion.storage.AuditStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.daymark.companion.audit")

/** The audit log is additive, never load-bearing: a logging bug must never fail a real request. */
private fun auditSafely(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        log.warn("audit log append failed", e)
    }
}

@Serializable data class PairingOpenRequest(val inviteId: String, val sidB64: String, val msgAB64: String)
@Serializable data class PairingOpenResponse(val exchangeId: String)

/** The owner's read: state, the reply once it exists, and the therapist's sealed offer beside it. */
@Serializable data class PairingExchangeView(
    val exchangeId: String,
    val state: String,
    val msgBB64: String? = null,
    val envB64: String? = null,
)
@Serializable data class PairingFetchRequest(val secret: String)

/**
 * relRef rides along because the web client folds it into the CPace channel identifier and the
 * owner's side derives the same CI from its own record. It is NOT what binds the run to an
 * invitation: the therapist only ever learns relRef from this response, so a server can make it
 * say whatever the owner used. The binding both sides hold independently is the invite id — the
 * owner chose it, the link carries it — and the client puts that in the CI too (audit,
 * 2026-09-01). Disclosure-wise this is nothing new: the redeem route's success body has always
 * returned relRef to a caller who proved the secret, and this response requires the same proof.
 */
@Serializable data class PairingFetchResponse(val exchangeId: String, val relRef: String, val sidB64: String, val msgAB64: String)

/**
 * The reply and, beside it, the therapist's offer sealed under the pairing key: their public
 * keys, a display name, and the enrolment ticket they chose. Opaque here; the owner's client is
 * the only thing that can open it, and only if the codes matched.
 */
@Serializable data class PairingRespondRequest(val secret: String, val msgBB64: String, val envB64: String)

/**
 * The owner's approve carries the ticket the therapist chose, exactly as it came out of the
 * envelope. The server learns a 32-byte value to hash and compare later — never the code, never
 * the key, never who chose it.
 */
@Serializable data class PairingApproveRequest(val enrolTicketB64: String)

/**
 * What the therapist's status poll says: WAITING while the owner has not decided, APPROVED once
 * they have (with the scope the invitation grants, which the old redeem response used to carry).
 * Everything else — cancelled, retired, reported, expired, never existed — is one flat 410, so a
 * poll cannot tell a link-holder that the owner saw something worth stopping.
 */
@Serializable data class PairingStatusResponse(val state: String, val scope: List<String>? = null)

@Serializable data class LatestExchangeView(val exchangeId: String, val state: String)

/**
 * One row of the owner's invitation list: where the invitation stands, how many wrong secrets
 * have been tried against it, how many of its exchanges are spent, and the newest run's state.
 * `expiresAt` and `createdAt` are epoch milliseconds, like the mint response.
 */
@Serializable data class InviteView(
    val inviteId: String,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val failCount: Int,
    val exchangeCount: Long,
    val latestExchange: LatestExchangeView? = null,
)

/** A CPace sid is 16 bytes, always (draft suite constant; both client impls refuse anything else). */
private const val SID_BYTES = 16

/**
 * A CPace message is lv_cat(point, AD): 32-byte point + small associated data. 200 bytes of
 * decoded room is several times what either client produces; past that it is not a pairing
 * message, it is somebody using the relay as storage.
 */
private const val MAX_MSG_BYTES = 200

/**
 * The therapist's sealed offer: version(1) | nonce(24) | ciphertext(payload + 16-byte tag) over a
 * small JSON document — two 32-byte keys, a name of at most 64 characters, a 32-byte ticket. A
 * few hundred bytes in practice. 4 KiB is generous room for a future version of the payload and
 * still a hard "no" to anyone treating the column as storage; it is its own bound because the
 * CPace message bound above is deliberately tight and must stay so.
 */
private const val MIN_ENV_BYTES = 41
private const val MAX_ENV_BYTES = 4096

/** The enrolment ticket the therapist chose: 32 random bytes, the same size the server mints. */
private const val ENROL_TICKET_BYTES = 32

/**
 * How often a therapist may poll the status route without spending their own budget: the shared
 * per-source window allows [PAIR_MAX_PER_WINDOW] touches per [PAIR_WINDOW_MS], fetch and respond
 * cost two, and every allowed request is charged. At 45 seconds, five minutes hold six polls with
 * room to spare; anything under 30 seconds locks an honest therapist out of their own ceremony.
 * The client is written to this number and treats a 429 as "still waiting".
 */
const val PAIRING_STATUS_POLL_SECONDS = 45L

/**
 * The store-and-forward relay for the CPace pairing exchange (plan §3.7.3 — "owner posts,
 * therapist fetches and responds, owner finishes next time they open the app") and, since the
 * screens were planned, the approval that turns a matched code into an enrolment.
 *
 * WHAT THE SERVER IS HERE, in one line: a parcel shelf between two people who share a code it
 * will never see. The security argument of the whole §3.7 design is that the server cannot
 * participate in the exchange because it does not have the code (§3.7.4) — so every handler in
 * this file treats the messages and the envelope as opaque bytes, sized but never parsed, and
 * nothing in any request or response carries the code in any form. The web client has the test
 * that PROVES no request ever contains it; this file's job is to have nowhere to put it.
 *
 * HOW A TICKET COMES TO EXIST. The therapist's reply carries, sealed under the key only a right
 * code derives, an enrolment ticket they chose. The owner's client opens it — or cannot, which is
 * how a wrong code is finally seen, by a person — and on Approve hands the ticket to this server.
 * That is the ONLY way an enrolment ticket is minted once the secret-based redeem route is gone:
 * the link alone opens a run and answers it, and gets nothing else. A link-holder who answers
 * first produces an envelope the owner cannot open; the owner sees a reply that did not match,
 * never a therapist.
 *
 * WHO MAY TOUCH WHAT. The owner side rides the bearer token, like every owner surface. The
 * therapist side has no credential yet — pairing is how they get one — so their touches prove
 * possession of the INVITE SECRET, exactly as redeem does, through AuthStore.checkInviteSecret:
 * the same Argon2 verify, the same shared per-invite fail counter (one secret, one counter,
 * however many doors), the same capped backoff that never burns, and the same one-LOCKOUT-row-
 * per-armed-episode audit rule. Fetch and respond CONSUME NOTHING: an invite stays PENDING
 * through any number of protocol runs, because a mistyped code costs a fresh run, not a fresh
 * invitation. The status poll is the one touch allowed against a REDEEMING invite, and it yields
 * a state word and nothing else.
 *
 * WHY THE PER-SOURCE BUDGET IS THE SAME SCOPE AS REDEEM'S. The relay verifies the same secret
 * redeem verifies, so a separate budget would hand an attacker double the guesses by
 * alternating surfaces. [PersistentAttemptLimiter] keeps its state in the attempt_windows
 * table keyed by scope, so a second instance over the same scope IS the same budget — shared
 * durable state by construction, not by careful wiring. It is never reset by a relay success:
 * a status poll succeeds repeatedly, and a reset on success would let a link-holder clear the
 * window at will.
 *
 * THE FAILURE ANSWERS ARE DELIBERATELY FLAT. A wrong secret, a right secret against an invite
 * with no open exchange, and a right secret against an invite that never existed must not be
 * distinguishable beyond what the status codes already give redeem: this surface must not
 * become an oracle for whether an invitation exists or how far a ceremony has progressed.
 * Owner-side misses are 404 or 410 with no body detail for the same reason in the other
 * direction.
 */
fun Route.pairingRelayRoutes(
    authStore: AuthStore,
    pairingStore: PairingStore,
    ownerGuard: AuthGuard,
    auditStore: AuditStore,
    /** The same two values redeem passes to the store, so the SHARED per-invite counter arms
     *  identically whichever surface a guess lands on. */
    totpLockoutFails: Int,
    totpLockoutSeconds: Long,
    auditSourceIp: Boolean = false,
    pairSourceLimiter: AttemptBudget = PersistentAttemptLimiter(
        authStore,
        scope = PAIR_ATTEMPT_SCOPE,
        maxPerWindow = PAIR_MAX_PER_WINDOW,
        windowMs = PAIR_WINDOW_MS,
    ),
) {
    /** Decode-and-size check; the relay checks shape, never meaning. */
    fun decodedSize(b64: String, min: Int, max: Int): Boolean {
        val bytes = try {
            java.util.Base64.getUrlDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return bytes.size in min..max
    }

    route("/v1/relations/{relRef}/pairing") {

        // The owner opens an exchange: posts sid + MSGa for one of their PENDING invites.
        post {
            if (!call.ownerAuthorized(ownerGuard)) return@post
            val relRef = call.parameters["relRef"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            val req = call.receiveCappedJson<PairingOpenRequest>() ?: return@post
            if (!decodedSize(req.sidB64, SID_BYTES, SID_BYTES)) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("sid must decode to 16 bytes"))
            }
            if (!decodedSize(req.msgAB64, 34, MAX_MSG_BYTES)) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("implausible message size"))
            }
            val invite = authStore.inviteMetaFor(req.inviteId)
            // An invite that is not this relationship's, not PENDING, or already dead gets one
            // flat answer. The owner holds the bearer token, so this is not secrecy from the
            // owner — it is refusing to let the OPEN route double as an invite-status oracle
            // keyed by guessed invite ids, which the console has an authorized surface for.
            if (invite == null || invite.relRef != relRef || invite.status != "PENDING") {
                return@post call.respond(HttpStatusCode.NotFound, ErrorDto("no open invite"))
            }
            val opened = pairingStore.open(req.inviteId, relRef, req.sidB64, req.msgAB64, invite.expiry)
            when (opened.status) {
                // Same flat words as the missing-invite case above: expired is just another way
                // for there to be no open invite here.
                PairingStore.OpenStatus.INVITE_DEAD -> call.respond(HttpStatusCode.NotFound, ErrorDto("no open invite"))
                PairingStore.OpenStatus.TOO_MANY -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorDto("exchange limit reached for this invite — mint a fresh invitation"),
                )
                PairingStore.OpenStatus.OK -> {
                    call.respond(HttpStatusCode.Created, PairingOpenResponse(opened.exchangeId!!))
                    auditSafely {
                        auditStore.append(relRef, AuditActor.OWNER, AuditAction.PAIRING_OPENED, meta = auditMeta(auditSourceIp, call))
                    }
                }
            }
        }

        // The owner reads one exchange back: state, and the reply and offer once they exist.
        get("/{exchangeId}") {
            if (!call.ownerAuthorized(ownerGuard)) return@get
            val relRef = call.parameters["relRef"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            val exchangeId = call.parameters["exchangeId"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing exchangeId"))
            val exchange = pairingStore.exchangeFor(exchangeId, relRef)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("no such exchange"))
            call.respond(PairingExchangeView(exchange.exchangeId, exchange.state.name, exchange.msgBB64, exchange.envToOwnerB64))
        }

        /*
         * The owner approves the reply. The invitation goes to REDEEMING and the therapist's
         * chosen ticket becomes live (AuthStore), then the run goes RESPONDED -> CLOSED
         * (PairingStore). Two databases, no shared transaction, so the order is the one whose
         * failure is recoverable: the invitation first, and if the run refuses to move (a cancel
         * raced it) the invitation is put straight back by abandonRedeem and the owner is told to
         * read the run again. The other order could leave a CLOSED run with no ticket behind it,
         * which no route can repair.
         */
        post("/{exchangeId}/approve") {
            if (!call.ownerAuthorized(ownerGuard)) return@post
            val relRef = call.parameters["relRef"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            val exchangeId = call.parameters["exchangeId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing exchangeId"))
            val req = call.receiveCappedJson<PairingApproveRequest>() ?: return@post
            if (!decodedSize(req.enrolTicketB64, ENROL_TICKET_BYTES, ENROL_TICKET_BYTES)) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("enrol ticket must decode to 32 bytes"))
            }
            val exchange = pairingStore.exchangeFor(exchangeId, relRef)
                ?: return@post call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
            if (exchange.state != PairingStore.State.RESPONDED) {
                return@post call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
            }
            val approved = authStore.approveRedeem(exchange.inviteId, req.enrolTicketB64)
            if (approved.status != AuthStore.ApproveStatus.OK) {
                return@post call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
            }
            when (pairingStore.approve(exchangeId, relRef)) {
                PairingStore.TransitionStatus.OK -> {
                    call.respond(HttpStatusCode.NoContent)
                    auditSafely {
                        auditStore.append(relRef, AuditActor.OWNER, AuditAction.PAIRING_APPROVED, meta = auditMeta(auditSourceIp, call))
                    }
                }
                PairingStore.TransitionStatus.GONE -> {
                    authStore.abandonRedeem(exchange.inviteId)
                    call.respond(HttpStatusCode.Conflict, ErrorDto("the run changed underneath the approval — read it again"))
                }
            }
        }

        /*
         * The owner cancels. From OPEN or RESPONDED it is 4.0a's owner Cancel. From CLOSED it is
         * an ABANDON: an approval nobody finished (the therapist lost the tab, the ticket sat
         * unused, the owner changed their mind) puts the invitation back to PENDING and takes the
         * ticket with it, so the owner can start over with a new code rather than being left with
         * an invitation that is neither open nor enrolled. Only while the invitation is still
         * REDEEMING: once it is CONSUMED there is a credential behind it, and that is revoked,
         * not abandoned.
         */
        post("/{exchangeId}/cancel") {
            if (!call.ownerAuthorized(ownerGuard)) return@post
            val relRef = call.parameters["relRef"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            val exchangeId = call.parameters["exchangeId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing exchangeId"))
            val exchange = pairingStore.exchangeFor(exchangeId, relRef)
                ?: return@post call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
            when (exchange.state) {
                PairingStore.State.OPEN, PairingStore.State.RESPONDED -> when (pairingStore.cancel(exchangeId, relRef)) {
                    PairingStore.TransitionStatus.OK -> {
                        call.respond(HttpStatusCode.NoContent)
                        auditSafely {
                            auditStore.append(relRef, AuditActor.OWNER, AuditAction.PAIRING_CANCELLED, meta = auditMeta(auditSourceIp, call))
                        }
                    }
                    PairingStore.TransitionStatus.GONE -> call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
                }
                PairingStore.State.CLOSED -> {
                    val invite = authStore.inviteMetaFor(exchange.inviteId)
                    if (invite == null || invite.relRef != relRef || invite.status != "REDEEMING") {
                        return@post call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
                    }
                    if (!authStore.abandonRedeem(exchange.inviteId)) {
                        return@post call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
                    }
                    // The invitation is back and the ticket is gone whatever the run says now;
                    // the run's own move is bookkeeping for the console.
                    pairingStore.abandon(exchangeId, relRef)
                    call.respond(HttpStatusCode.NoContent)
                    auditSafely {
                        auditStore.append(relRef, AuditActor.OWNER, AuditAction.PAIRING_ABANDONED, meta = auditMeta(auditSourceIp, call))
                    }
                }
                PairingStore.State.CANCELLED, PairingStore.State.SUPERSEDED ->
                    call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
            }
        }
    }

    // The owner's invitation list: where each one stands, for the console's waiting / in
    // progress / finished / dead rendering and its "6 of 8 attempts left" line. Owner-only, and
    // the reason AuthStore.inviteStatusFor is on no anonymous route: this IS the invite-status
    // surface, behind the bearer token.
    route("/v1/relations/{relRef}/invites") {
        get {
            if (!call.ownerAuthorized(ownerGuard)) return@get
            val relRef = call.parameters["relRef"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            val views = authStore.invitesFor(relRef).map { inv ->
                val latest = pairingStore.latestExchangeFor(inv.inviteId)
                InviteView(
                    inviteId = inv.inviteId,
                    status = inv.status,
                    createdAt = inv.createdAt,
                    expiresAt = inv.expiry,
                    failCount = inv.failCount,
                    exchangeCount = pairingStore.exchangeCountFor(inv.inviteId),
                    latestExchange = latest?.let { LatestExchangeView(it.exchangeId, it.state.name) },
                )
            }
            call.respond(views)
        }
    }

    route("/v1/invite/{inviteId}/pairing") {

        /**
         * Verify the invite secret for one relay touch, answering the failure cases in place.
         * Returns the store's verdict on success (relRef and scope), null after a response has
         * been written. The audit choreography — guess row per failure, one LOCKOUT row on the
         * failure that arms it, silence while locked — is redeem's, kept in step by using the
         * same store verdicts; InviteBurnRuleTest and LockedInviteAuditTest own the semantics.
         * [allowRedeeming] is passed through for the status poll only; see checkInviteSecret.
         */
        suspend fun io.ktor.server.application.ApplicationCall.relayAuthorized(secret: String, allowRedeeming: Boolean = false): AuthStore.RedeemResult? {
            val inviteId = parameters["inviteId"] ?: run {
                respond(HttpStatusCode.BadRequest, ErrorDto("missing inviteId"))
                return null
            }
            val result = authStore.checkInviteSecret(inviteId, secret, totpLockoutFails, totpLockoutSeconds * 1000, allowRedeeming)
            return when (result.status) {
                AuthStore.RedeemStatus.OK -> result
                AuthStore.RedeemStatus.WRONG_SECRET -> {
                    respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                    result.relRef?.let { rel ->
                        auditSafely {
                            auditStore.append(rel, AuditActor.THERAPIST, AuditAction.PAIR_GUESS_FAILED, meta = auditMeta(auditSourceIp, this))
                        }
                        if (result.lockoutArmed) {
                            auditSafely {
                                auditStore.append(rel, AuditActor.THERAPIST, AuditAction.LOCKOUT, meta = auditMeta(auditSourceIp, this))
                            }
                        }
                    }
                    null
                }
                AuthStore.RedeemStatus.LOCKED -> {
                    // No audit append — the locked-door rule (see TherapistAuthRoutes' LOCKED branch).
                    respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked"))
                    null
                }
                AuthStore.RedeemStatus.GONE -> {
                    respond(HttpStatusCode.Gone, ErrorDto("invite unavailable"))
                    null
                }
            }
        }

        // The therapist collects the owner's opening message.
        post("/fetch") {
            call.response.header("Referrer-Policy", "no-referrer")
            if (!pairSourceLimiter.allow(call.clientAddress())) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
            }
            val req = call.receiveCappedJson<PairingFetchRequest>() ?: return@post
            call.relayAuthorized(req.secret) ?: return@post
            val inviteId = call.parameters["inviteId"]!!
            val exchange = pairingStore.openExchangeFor(inviteId)
                // The same words the dead-invite path uses on purpose: "right secret, nothing
                // waiting" and "no such invite" must read identically from outside.
                ?: return@post call.respond(HttpStatusCode.Gone, ErrorDto("invite unavailable"))
            call.respond(PairingFetchResponse(exchange.exchangeId, exchange.relRef, exchange.sidB64, exchange.msgAB64))
        }

        // The therapist posts the reply and their sealed offer. One reply per exchange, ever —
        // the store enforces it.
        post("/{exchangeId}/respond") {
            call.response.header("Referrer-Policy", "no-referrer")
            if (!pairSourceLimiter.allow(call.clientAddress())) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
            }
            val req = call.receiveCappedJson<PairingRespondRequest>() ?: return@post
            if (!decodedSize(req.msgBB64, 34, MAX_MSG_BYTES)) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("implausible message size"))
            }
            if (!decodedSize(req.envB64, MIN_ENV_BYTES, MAX_ENV_BYTES)) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("implausible envelope size"))
            }
            val verdict = call.relayAuthorized(req.secret) ?: return@post
            val inviteId = call.parameters["inviteId"]!!
            val exchangeId = call.parameters["exchangeId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing exchangeId"))
            when (pairingStore.respond(exchangeId, inviteId, req.msgBB64, req.envB64)) {
                PairingStore.RespondStatus.OK -> {
                    call.respond(HttpStatusCode.NoContent)
                    auditSafely {
                        auditStore.append(verdict.relRef!!, AuditActor.THERAPIST, AuditAction.PAIRING_RESPONDED, meta = auditMeta(auditSourceIp, call))
                    }
                }
                PairingStore.RespondStatus.GONE -> call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
            }
        }

        // The therapist asks whether the owner has decided. The one touch that works against a
        // REDEEMING invite, because that is what approval makes it; poll at
        // PAIRING_STATUS_POLL_SECONDS, never faster (see the constant). No audit line: a poll
        // is a question, and the answer is a state word.
        post("/{exchangeId}/status") {
            call.response.header("Referrer-Policy", "no-referrer")
            if (!pairSourceLimiter.allow(call.clientAddress())) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
            }
            val req = call.receiveCappedJson<PairingFetchRequest>() ?: return@post
            val verdict = call.relayAuthorized(req.secret, allowRedeeming = true) ?: return@post
            val inviteId = call.parameters["inviteId"]!!
            val exchangeId = call.parameters["exchangeId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing exchangeId"))
            val exchange = pairingStore.exchangeForInvite(exchangeId, inviteId)
            when (exchange?.state) {
                PairingStore.State.RESPONDED -> call.respond(PairingStatusResponse("WAITING"))
                PairingStore.State.CLOSED -> call.respond(PairingStatusResponse("APPROVED", verdict.scope))
                // Cancelled, retired, never answered, not theirs, not there: one flat answer.
                else -> call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
            }
        }
    }
}
