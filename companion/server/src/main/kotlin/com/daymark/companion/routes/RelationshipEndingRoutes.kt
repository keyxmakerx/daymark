package com.daymark.companion.routes

import com.daymark.companion.auth.AttemptLimiter
import com.daymark.companion.auth.OwnerAuth
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.clientAddress
import com.daymark.companion.storage.AuditAction
import com.daymark.companion.storage.AuditActor
import com.daymark.companion.storage.AuditStore
import io.ktor.http.HttpStatusCode
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

@Serializable data class RelationshipEndingRecord(val endedAt: Long)

/**
 * A CLINICIAN PUTTING DOWN THEIR OWN ACCESS, AND THE OWNER FINDING OUT (issue #91).
 *
 * Two routes. The clinician ends the relationship from their own console; the owner reads back
 * whether it has ended. Between them they are the whole server side of self-leave.
 *
 * ─── WHY THIS EXISTS AT ALL, WHICH IS NOT THE OBVIOUS REASON ────────────────────────────────────
 *
 * An owner could always end a therapist's access; a therapist could never end their own. The
 * obvious reading is that this is a courtesy — a clinician who leaves a practice or retires ought
 * to be able to put the thing down. That is true and it is the smaller half.
 *
 * The larger half is that without it, "leaving" could only ever mean a clinician clearing their own
 * browser. Their wrapped keys live in that browser's storage and nowhere else, and the acceptance
 * screen deliberately offers the key record as text to keep somewhere — so a cleared browser plus a
 * saved copy of the record plus the passphrase plus the authenticator still opens the door from
 * anywhere. A clinician who needed to be CERTAIN they were out could not get there from a screen.
 * And the owner, meanwhile, would go on publishing to a key nobody intends to open: their console
 * would say somebody has standing access to their entries, which would be true about the delivery
 * and false about the reading.
 *
 * So the ending is a fact the SERVER holds. That is what makes leaving an off switch, and it is
 * what gives the owner something to meet.
 *
 * ─── WHAT THE ENDING DOES, AND THE EXACT LIST ───────────────────────────────────────────────────
 *
 *   1. One row in `relationship_endings`, insert-only by primary key.
 *   2. Every live session that credential holds is cut, so the ending is immediate rather than
 *      "within the next eight hours, depending when they last clicked".
 *   3. The TOTP verify path refuses this credential from then on, so there is no signing back in.
 *   4. One line in the relationship's audit log, which is where the owner reads it.
 *
 * And the list of what it does not touch is the point of the feature, not a footnote: no share is
 * withdrawn, no blob is written or deleted, no grant is altered, no key is rotated, and nothing at
 * all belonging to the owner moves. Their entries, the material they shared, and their record of
 * having shared it are exactly as they were. A clinician leaving must never be a route to reaching
 * into somebody else's records, and the way that is guaranteed is that the code to do it is not
 * here to be called. What the ending leaves in place still ends on the one clock every relationship
 * item follows (RelationStore.hasEnded, #332), and the sweep removes its stored copy then (#338),
 * exactly as it would had the relationship not ended.
 *
 * ─── AND WHAT IT CANNOT DO, WHICH THE COPY HAS TO SAY ───────────────────────────────────────────
 *
 * It does not un-read anything. Whatever the clinician already decrypted is on their machine and
 * beyond the reach of any row this server can write. The product's standing sentence for the
 * owner's direction — "Revoking does not un-send what was already read" — is deliberately NOT used
 * on the clinician's screen, because from that side there is no reader being cut off and the
 * clinician is not un-sending anything; the honest mirror is that leaving does not reach copies.
 * See companion/web/src/lib/therapist/leave.ts for that argument in full.
 *
 * ─── WHY CLOSING A CREDENTIAL IS NOT DELETING ONE ───────────────────────────────────────────────
 *
 * The `totp` table is insert-only, and both halves of that matter here. A DELETE would remove the
 * row whose unique `rel_ref` index is the only thing stopping a second enrolment — handing anyone
 * still holding the invitation link a way to enrol a fresh credential against a relationship
 * somebody has just left. An UPDATE ("disabled=1") would be an update path into the one table whose
 * whole safety property is that it has none. So the closure is a row in a SEPARATE insert-only
 * table, consulted on the way in. `totp` is never touched. See AuthStore's table header.
 *
 * ─── ONE CREDENTIAL PER RELATIONSHIP, WHICH IS WHY THIS IS SAFE ─────────────────────────────────
 *
 * `idx_totp_rel_ref` is a UNIQUE index, and `enrollTotp` refuses a second credential for a relRef.
 * So a clinician's credential belongs to exactly one relationship, and closing it can reach no
 * other patient's work. If that ever stopped being true, this route would be closing a credential
 * other relationships depend on and would need to become a per-relationship closure instead — which
 * is why the ending is keyed on the RELATIONSHIP rather than on the credential, so the property
 * lives in the schema rather than in the coincidence.
 */
fun Route.relationshipEndingRoutes(
    authStore: AuthStore,
    ownerGuard: OwnerAuth,
    sessionIdleSeconds: Long,
    auditStore: AuditStore,
    auditSourceIp: Boolean = false,
    /** Same budget and sizing as the other cookie surfaces; see TherapistKeyRoutes for why it is built here. */
    therapistLimiter: AttemptLimiter = AttemptLimiter(THERAPIST_MAX_PER_WINDOW, THERAPIST_WINDOW_MS),
) {
    route("/v1/relations/{relRef}/ending") {

        /*
         * The clinician ends it. Session cookie + X-CSRF-Token, validated exactly the way
         * POST /v1/session/logout validates them, and the session must be bound to THIS relationship.
         *
         * IDEMPOTENT, AND THE SECOND CALL IS NOT AN ERROR. A clinician who clicks twice, or whose
         * browser retries a request whose answer never arrived, must not meet a refusal for an act
         * that already succeeded — particularly this act, which cannot be retried in any meaningful
         * sense because there is nothing left to end. Both calls answer 204. What differs is
         * invisible from outside: the row is written once and the audit line is appended once, on
         * the ending itself rather than per call, which is the same rule the lockout path follows
         * and for the same reason — a log that grows a row per retry buries the row that matters.
         *
         * IT REVEALS NOTHING TO A SESSION BOUND ELSEWHERE. A session for relationship A calling
         * relationship B's path gets the same 403 the therapist-keys route gives, with the same
         * wording, before anything is read or written. There is no honest way for a client to reach
         * that: the portal builds this URL out of the session it just authenticated with. So it is
         * either a client bug about to end a DIFFERENT person's relationship, or somebody probing to
         * see whether the path is what the server keys on, and answering "not found" or quietly
         * acting on the session's own relRef would let both succeed. 403 rather than 401 because the
         * caller IS authenticated and re-authenticating cannot help.
         *
         * ORDER: THE ROW FIRST, THE SESSIONS SECOND. If the process dies between them the surviving
         * state is "ended, with a session still alive until it times out" — which is recoverable and
         * bounded, because the verify path already refuses any new sign-in. The other order would
         * leave "signed out, not ended", which looks exactly like a completed leave and is not one:
         * the clinician would sign straight back in.
         */
        post {
            val sessionId = call.request.cookies["daymark_session"]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
            // A missing anti-CSRF header is a rejection, not a bypass: a null header must never
            // validate against a null stored token. Same rule as /v1/session/logout.
            val csrf = call.request.headers["X-CSRF-Token"]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
            val validation = authStore.validateSession(sessionId, sessionIdleSeconds, requireCsrf = csrf)
            val session = validation.record
            if (validation.check != AuthStore.SessionCheck.OK || session == null) {
                // Non-enumerating, like the rest of this surface: expired, revoked, never-existed
                // and wrong-CSRF are one 401 between them.
                return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
            }

            // Metered after the session check, never before — only a caller who already holds a real
            // session can spend the budget, so an anonymous flood cannot burn a working clinician's
            // allowance from a shared address. Same ordering and the same argument as the other two
            // cookie surfaces.
            if (!therapistLimiter.allow(call.clientAddress())) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
            }

            val pathRelRef = call.parameters["relRef"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            if (!Secrets.constantTimeEquals(session.relRef, pathRelRef)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorDto("session does not cover this relationship"))
            }
            // From here the session's own values are the only ones used. The path parameter has done
            // its whole job by agreeing.
            val relRef = session.relRef
            val credentialId = session.credentialId

            val write = authStore.endRelationship(relRef, credentialId)
            // Every session this credential holds, not just the one that asked. A clinician saying
            // they are finished means finished on the clinic machine they walked away from too.
            authStore.revokeSessionsForCredential(credentialId)
            call.respond(HttpStatusCode.NoContent)
            if (write == AuthStore.EndingWrite.RECORDED) {
                auditSafely {
                    // The credential id is membership metadata, not a secret and not content — the
                    // same annotation the sign-in lines already carry. Nothing about what was read,
                    // shared or written goes anywhere near this row.
                    auditStore.append(
                        relRef,
                        AuditActor.THERAPIST,
                        AuditAction.RELATIONSHIP_ENDED,
                        meta = auditMeta(auditSourceIp, call, "credentialId" to credentialId),
                    )
                }
            }
        }

        /*
         * The owner reads whether it has ended. Owner bearer token, gated exactly as the
         * therapist-keys read is.
         *
         * WHY THE OWNER NEEDS A ROUTE FOR THIS AT ALL, given the audit log already carries the line.
         * The sharing strip is on every owner screen and cannot be dismissed, so what it says has to
         * be true on every screen — and the log is a paged fetch that would cost a request per
         * screen to keep it so. One small answer keyed on the relationship is what a permanently
         * visible fact can afford.
         *
         * 404 WHILE IT IS LIVE, which is the ordinary answer for almost every relationship. It is an
         * absence, not a failure, and the console draws it as one.
         *
         * WHAT THIS DISCLOSES. A timestamp, to a caller already holding the owner's bearer token.
         * No content, no key, no fragment of either — the same class of relationship metadata as
         * "these public keys are registered", which this server already answers on the same gate.
         */
        get {
            if (!call.ownerAuthorized(ownerGuard)) return@get
            val relRef = call.parameters["relRef"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            val ending = authStore.relationshipEnding(relRef)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("not ended"))
            // The credential id is deliberately NOT echoed. The owner has no use for it, it is the
            // clinician's sign-in name, and a route that hands one back is a route that can be asked
            // for one. The date is the whole of what a person needs to act on.
            call.respond(RelationshipEndingRecord(ending.endedAt))
        }
    }
}
