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
 *   2. Every session-cookie route reads that row on every request (AuthStore.reaches), so the
 *      account reaches this relationship no more from the next request on, in every session on
 *      every machine, and no sign-in reopens it. The account itself, and its other relationships,
 *      carry on: one sign-in per person (#314).
 *   3. One line in the relationship's audit log, which is where the owner reads it.
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
 * ─── WHY ENDING IS NOT DELETING ─────────────────────────────────────────────────────────────────
 *
 * Attachments are insert-only, and a relationship that has ever been attached refuses an
 * invitation-made attachment. A DELETE of the attachment would undo that and hand anyone still
 * holding the invitation link a way to attach a relationship somebody has just left. So the ending
 * is a row in a SEPARATE insert-only table, keyed on the RELATIONSHIP, and ending one relationship
 * can reach no other patient's work. An ended relationship stays ended: a key proof cannot
 * re-attach it (AuthStore.attachByKeyProof).
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
         * IT REVEALS NOTHING TO AN ACCOUNT THAT DOES NOT HOLD THE RELATIONSHIP. Its path gets the
         * same 403 the therapist-keys route gives, with the same wording, before anything is read
         * or written, whether the relationship is another account's or never existed. 403 rather
         * than 401 because the caller IS authenticated and re-authenticating cannot help.
         *
         * ONE WRITE, AND IT TAKES EFFECT ON THE NEXT REQUEST. Nothing has to be cut in a second
         * step that a crash could leave undone, because every route asks for the ending fresh.
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
            // HOLDS, not reaches: an ended relationship is still held by the account that ended it,
            // so a second leave answers as the first did. A replacement account an administrator
            // seated holds nothing, and so can end nothing (#314).
            if (!authStore.holds(session.accountId, pathRelRef)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorDto("session does not cover this relationship"))
            }
            val relRef = pathRelRef
            val credentialId = session.accountId

            // One relationship closes, never the account: the account's other relationships and its
            // sessions carry on, and every data-plane route refuses this one from its next request
            // because AuthStore.reaches reads the ending.
            val write = authStore.endRelationship(relRef, credentialId)
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
