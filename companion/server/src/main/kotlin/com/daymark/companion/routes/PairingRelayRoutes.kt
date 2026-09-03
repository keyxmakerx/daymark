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
@Serializable data class PairingExchangeView(val exchangeId: String, val state: String, val msgBB64: String? = null)
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
@Serializable data class PairingRespondRequest(val secret: String, val msgBB64: String)

/** A CPace sid is 16 bytes, always (draft suite constant; both client impls refuse anything else). */
private const val SID_BYTES = 16

/**
 * A CPace message is lv_cat(point, AD): 32-byte point + small associated data. 200 bytes of
 * decoded room is several times what either client produces; past that it is not a pairing
 * message, it is somebody using the relay as storage.
 */
private const val MAX_MSG_BYTES = 200

/**
 * The store-and-forward relay for the CPace pairing exchange (plan §3.7.3 — "owner posts,
 * therapist fetches and responds, owner finishes next time they open the app").
 *
 * WHAT THE SERVER IS HERE, in one line: a parcel shelf between two people who share a code it
 * will never see. The security argument of the whole §3.7 design is that the server cannot
 * participate in the exchange because it does not have the code (§3.7.4) — so every handler in
 * this file treats the messages as opaque bytes, sized but never parsed, and nothing in any
 * request or response carries the code in any form. The web client has the test that PROVES no
 * request ever contains it; this file's job is to have nowhere to put it.
 *
 * WHO MAY TOUCH WHAT. The owner side rides the bearer token, like every owner surface. The
 * therapist side has no credential yet — pairing is how they get one — so their touches prove
 * possession of the INVITE SECRET, exactly as redeem does, through AuthStore.checkInviteSecret:
 * the same Argon2 verify, the same shared per-invite fail counter (one secret, one counter,
 * however many doors), the same capped backoff that never burns, and the same one-LOCKOUT-row-
 * per-armed-episode audit rule. Crucially it CONSUMES NOTHING: an invite stays PENDING through
 * any number of relay touches, because a mistyped code costs a fresh protocol run, not a fresh
 * invitation.
 *
 * WHY THE PER-SOURCE BUDGET IS THE SAME SCOPE AS REDEEM'S. The relay verifies the same secret
 * redeem verifies, so a separate budget would hand an attacker double the guesses by
 * alternating surfaces. [PersistentAttemptLimiter] keeps its state in the attempt_windows
 * table keyed by scope, so a second instance over the same scope IS the same budget — shared
 * durable state by construction, not by careful wiring.
 *
 * THE FAILURE ANSWERS ARE DELIBERATELY FLAT. A wrong secret, a right secret against an invite
 * with no open exchange, and a right secret against an invite that never existed must not be
 * distinguishable beyond what the status codes already give redeem: this surface must not
 * become an oracle for whether an invitation exists or how far a ceremony has progressed.
 * Owner-side misses are 404 with no body detail for the same reason in the other direction.
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

        // The owner reads one exchange back: state, and the reply once it exists.
        get("/{exchangeId}") {
            if (!call.ownerAuthorized(ownerGuard)) return@get
            val relRef = call.parameters["relRef"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            val exchangeId = call.parameters["exchangeId"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing exchangeId"))
            val exchange = pairingStore.exchangeFor(exchangeId, relRef)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("no such exchange"))
            call.respond(PairingExchangeView(exchange.exchangeId, exchange.state.name, exchange.msgBB64))
        }

        // The owner acknowledges the reply (RESPONDED -> CLOSED). Bookkeeping for the console's
        // waiting / in progress / finished rendering; the crypto outcome lives client-side.
        post("/{exchangeId}/close") {
            if (!call.ownerAuthorized(ownerGuard)) return@post
            val relRef = call.parameters["relRef"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            val exchangeId = call.parameters["exchangeId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing exchangeId"))
            when (pairingStore.close(exchangeId, relRef)) {
                PairingStore.TransitionStatus.OK -> call.respond(HttpStatusCode.NoContent)
                PairingStore.TransitionStatus.GONE -> call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
            }
        }

        // The owner cancels an in-flight exchange — 4.0a's owner Cancel.
        post("/{exchangeId}/cancel") {
            if (!call.ownerAuthorized(ownerGuard)) return@post
            val relRef = call.parameters["relRef"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            val exchangeId = call.parameters["exchangeId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing exchangeId"))
            when (pairingStore.cancel(exchangeId, relRef)) {
                PairingStore.TransitionStatus.OK -> {
                    call.respond(HttpStatusCode.NoContent)
                    auditSafely {
                        auditStore.append(relRef, AuditActor.OWNER, AuditAction.PAIRING_CANCELLED, meta = auditMeta(auditSourceIp, call))
                    }
                }
                PairingStore.TransitionStatus.GONE -> call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
            }
        }
    }

    route("/v1/invite/{inviteId}/pairing") {

        /**
         * Verify the invite secret for one relay touch, answering the failure cases in place.
         * Returns the invite's relRef on success, null after a response has been written.
         * The audit choreography — guess row per failure, one LOCKOUT row on the failure that
         * arms it, silence while locked — is redeem's, kept in step by using the same store
         * verdicts; InviteBurnRuleTest and LockedInviteAuditTest own the semantics.
         */
        suspend fun io.ktor.server.application.ApplicationCall.relayAuthorized(secret: String): String? {
            val inviteId = parameters["inviteId"] ?: run {
                respond(HttpStatusCode.BadRequest, ErrorDto("missing inviteId"))
                return null
            }
            val result = authStore.checkInviteSecret(inviteId, secret, totpLockoutFails, totpLockoutSeconds * 1000)
            return when (result.status) {
                AuthStore.RedeemStatus.OK -> result.relRef
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

        // The therapist posts the reply. One reply per exchange, ever — the store enforces it.
        post("/{exchangeId}/respond") {
            call.response.header("Referrer-Policy", "no-referrer")
            if (!pairSourceLimiter.allow(call.clientAddress())) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
            }
            val req = call.receiveCappedJson<PairingRespondRequest>() ?: return@post
            if (!decodedSize(req.msgBB64, 34, MAX_MSG_BYTES)) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("implausible message size"))
            }
            val relRef = call.relayAuthorized(req.secret) ?: return@post
            val inviteId = call.parameters["inviteId"]!!
            val exchangeId = call.parameters["exchangeId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing exchangeId"))
            when (pairingStore.respond(exchangeId, inviteId, req.msgBB64)) {
                PairingStore.RespondStatus.OK -> {
                    call.respond(HttpStatusCode.NoContent)
                    auditSafely {
                        auditStore.append(relRef, AuditActor.THERAPIST, AuditAction.PAIRING_RESPONDED, meta = auditMeta(auditSourceIp, call))
                    }
                }
                PairingStore.RespondStatus.GONE -> call.respond(HttpStatusCode.Gone, ErrorDto("exchange unavailable"))
            }
        }
    }
}
