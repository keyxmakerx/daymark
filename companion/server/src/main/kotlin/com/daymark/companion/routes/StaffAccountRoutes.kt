package com.daymark.companion.routes

import com.daymark.companion.auth.AttemptLimiter
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.DeviceSignature
import com.daymark.companion.clientAddress
import com.daymark.companion.storage.AuditAction
import com.daymark.companion.storage.AuditActor
import com.daymark.companion.storage.AuditStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.daymark.companion.audit")

@Serializable data class AccountRelationshipsDto(val accountId: String, val relationships: List<String>)
@Serializable data class AttachByInvitationRequest(val enrollTicket: String)
@Serializable data class AttachChallengeRequest(val relRef: String)
@Serializable data class AttachChallengeDto(
    val challengeId: String,
    val challenge: String,
    val accountId: String,
    val relRef: String,
    val address: String,
    val expiresAt: Long,
)
@Serializable data class AttachByKeyProofRequest(val challengeId: String, val relRef: String, val signature: String)

/** Every refusal of a key proof reads the same, so nothing says which check failed. */
const val ATTACH_REFUSED = "that did not prove the clinician key for this relationship; ask for a new challenge and try again"

/** How long an attach challenge may be signed for, as the owner's recovery challenge (#325). */
const val ATTACH_CHALLENGE_TTL_MS = 10 * 60 * 1000L

/** The first line of every message an attach signature covers; nothing else a clinician signs begins with it. */
const val ATTACH_CONTEXT = "daymark-staff-attach-v1"

/**
 * What a clinician signs to attach a relationship to their account: the context, the account, the
 * relationship, this server's public address and the challenge, one per line. Binding the account
 * stops a proof made for one account being spent on another; binding the address stops a challenge
 * relayed from another server. companion/web/src/lib/therapist/attachProof.ts builds the same bytes.
 */
fun attachMessage(accountId: String, relRef: String, address: String, challenge: String): ByteArray =
    "$ATTACH_CONTEXT\n$accountId\n$relRef\n$address\n$challenge".toByteArray(Charsets.UTF_8)

/**
 * A staff account's own routes (#314): which relationships it reaches, and the two ways a
 * relationship comes to be attached to it once the account exists.
 *
 * WHAT MUST STAY TRUE.
 * - THE ACCOUNT COMES FROM THE SESSION, never from the request. Session cookie on every route, and
 *   X-CSRF-Token on every one that writes.
 * - AN INVITATION ATTACHES ONLY A RELATIONSHIP NO ACCOUNT HAS EVER HELD (AuthStore.attachByInvitation),
 *   so a leftover link cannot attach a relationship to a second account.
 * - A KEY PROOF IS THE ONLY OTHER WAY. The clinician opens their saved key record with their reading
 *   passphrase and signs [attachMessage] with the Ed25519 key registered for the relationship, which
 *   is the key its owner pinned. A challenge is good once, for ten minutes, for this account and this
 *   relationship, and is spent before the signature is checked, so a replay, a wrong signature, a
 *   relationship with no registered key and one that cannot be attached all read [ATTACH_REFUSED].
 * - It is how a clinician whose credentials became one account each (auth.db version 2) gathers them
 *   onto one, and how a replacement account an administrator seated gets its relationships back.
 *   Nobody can do it for them: the key is in their browser alone.
 * - An attachment by key proof writes one row in that relationship's access log, so its owner sees it.
 * - No list of anybody else's relationships, and none of a practice's patients. The list here is the
 *   signed-in clinician's own, which their browser needs to find its key records.
 */
fun Route.staffAccountRoutes(
    authStore: AuthStore,
    sessionIdleSeconds: Long,
    auditStore: AuditStore,
    publicBaseUrl: String?,
    auditSourceIp: Boolean = false,
    limiter: AttemptLimiter = AttemptLimiter(THERAPIST_MAX_PER_WINDOW, THERAPIST_WINDOW_MS),
) {
    val address = publicBaseUrl?.trimEnd('/')

    get("/v1/account/relationships") {
        val session = call.staffSession(authStore, sessionIdleSeconds, requireCsrf = false, limiter) ?: return@get
        call.respond(AccountRelationshipsDto(session.accountId, authStore.relationshipsOf(session.accountId)))
    }

    post("/v1/account/attach/invitation") {
        val session = call.staffSession(authStore, sessionIdleSeconds, requireCsrf = true, limiter) ?: return@post
        val req = call.receiveCappedJson<AttachByInvitationRequest>() ?: return@post
        val result = authStore.attachByInvitation(req.enrollTicket, session.accountId)
        when (result.status) {
            AuthStore.EnrollStatus.OK -> {
                call.respond(HttpStatusCode.NoContent)
                safely {
                    auditStore.append(
                        result.relRef!!, AuditActor.THERAPIST, AuditAction.ENROL_OK,
                        meta = auditMeta(auditSourceIp, call, "credentialId" to session.accountId),
                    )
                }
            }
            AuthStore.EnrollStatus.NO_TICKET -> call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
            AuthStore.EnrollStatus.ALREADY_ENROLLED -> call.respond(HttpStatusCode.Conflict, ErrorDto("credential already enrolled"))
        }
    }

    post("/v1/account/attach/challenge") {
        val session = call.staffSession(authStore, sessionIdleSeconds, requireCsrf = true, limiter) ?: return@post
        if (address == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto(NO_ATTACH_ADDRESS_MESSAGE))
            return@post
        }
        val req = call.receiveCappedJson<AttachChallengeRequest>() ?: return@post
        // Issued whatever the relationship is: refusing here would answer "does this relationship
        // exist?" to anyone with an account, and the attach below refuses every case alike.
        val issued = authStore.issueAttachChallenge(session.accountId, req.relRef, ATTACH_CHALLENGE_TTL_MS)
        call.respond(AttachChallengeDto(issued.challengeId, issued.challenge, session.accountId, req.relRef, address, issued.expiresAt))
    }

    post("/v1/account/attach") {
        val session = call.staffSession(authStore, sessionIdleSeconds, requireCsrf = true, limiter) ?: return@post
        if (address == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto(NO_ATTACH_ADDRESS_MESSAGE))
            return@post
        }
        val req = call.receiveCappedJson<AttachByKeyProofRequest>() ?: return@post
        // Spent whatever happens next, so a challenge is never checked twice.
        val challenge = authStore.spendAttachChallenge(req.challengeId, session.accountId, req.relRef)
        val publicKey = authStore.therapistKeys(req.relRef)?.let { DeviceSignature.decodeCanonical(it.signPubB64, DeviceSignature.PUBLIC_KEY_BYTES) }
        val signature = DeviceSignature.decodeCanonical(req.signature, DeviceSignature.SIGNATURE_BYTES)
        val proved = challenge != null && publicKey != null && signature != null &&
            DeviceSignature.verify(publicKey, attachMessage(session.accountId, req.relRef, address, challenge), signature)
        if (!proved) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto(ATTACH_REFUSED))
            return@post
        }
        when (authStore.attachByKeyProof(req.relRef, session.accountId)) {
            AuthStore.KeyProofAttach.ATTACHED -> {
                call.respond(HttpStatusCode.NoContent)
                safely {
                    auditStore.append(
                        req.relRef, AuditActor.THERAPIST, AuditAction.RELATIONSHIP_ATTACHED,
                        meta = auditMeta(auditSourceIp, call, "credentialId" to session.accountId),
                    )
                }
            }
            AuthStore.KeyProofAttach.ALREADY_HERE -> call.respond(HttpStatusCode.NoContent)
            // An ended relationship or a closed account: the proof was good and changes nothing.
            AuthStore.KeyProofAttach.NOT_ATTACHABLE -> call.respond(HttpStatusCode.Unauthorized, ErrorDto(ATTACH_REFUSED))
        }
    }
}

const val NO_ATTACH_ADDRESS_MESSAGE =
    "this server has no public address set, so a key proof cannot be bound to it; its operator sets DAYMARK_PUBLIC_BASE_URL"

/**
 * The signed-in staff account behind this request, or null with the refusal already sent. One 401
 * for a missing cookie, a missing CSRF header on a write, and an expired, revoked, closed or unknown
 * session, so a status code says nothing about which. Metered after the session check, so only a
 * caller who holds a real session can spend the budget.
 */
internal suspend fun ApplicationCall.staffSession(
    authStore: AuthStore,
    sessionIdleSeconds: Long,
    requireCsrf: Boolean,
    limiter: AttemptLimiter,
): AuthStore.SessionRecord? {
    val sessionId = request.cookies["daymark_session"]
    val csrf = if (requireCsrf) request.headers["X-CSRF-Token"] else null
    val validation = sessionId?.takeIf { !requireCsrf || csrf != null }?.let { authStore.validateSession(it, sessionIdleSeconds, requireCsrf = csrf) }
    val record = validation?.record
    if (validation?.check != AuthStore.SessionCheck.OK || record == null) {
        respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
        return null
    }
    if (!limiter.allow(clientAddress())) {
        respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
        return null
    }
    return record
}

/** The audit log is additive, never load-bearing: a logging bug must never fail a real request. */
private fun safely(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        log.warn("audit log append failed", e)
    }
}
