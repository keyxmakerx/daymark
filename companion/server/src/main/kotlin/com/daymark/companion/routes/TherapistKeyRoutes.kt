package com.daymark.companion.routes

import com.daymark.companion.auth.AttemptLimiter
import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
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
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.Base64

private val log = LoggerFactory.getLogger("com.daymark.companion.audit")

/** The audit log is additive, never load-bearing: a logging bug must never fail a real request. */
private fun auditSafely(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        log.warn("audit log append failed", e)
    }
}

@Serializable data class TherapistKeyRegistration(val boxPubB64: String, val signPubB64: String)
@Serializable data class TherapistKeyRecord(val boxPubB64: String, val signPubB64: String, val registeredAt: Long)

/**
 * Both keys are raw 32-byte public keys: X25519 for sealing, Ed25519 for signing. Not "at least"
 * and not "up to" — exactly 32, for both algorithms, which is why one constant covers both.
 */
private const val PUBLIC_KEY_BYTES = 32

/**
 * Therapist public-key registration and read-back: the one link the relationship was missing.
 *
 * WHAT THIS IS FOR. The owner's console (companion/web ShareBuilder.svelte, therapist/pinStore.ts)
 * already seals every share to a therapist's X25519 public key, and already refuses to seal to a
 * key it has not pinned. The therapist's browser already generates that keypair and wraps the
 * private halves. Neither of those was ever wired to the other, so the public halves had no way to
 * travel between the two people who need them — the seal had no target and the pin had nothing to
 * be taken against. These two routes carry them: the therapist posts, the owner reads.
 *
 * WHAT THIS SERVER IS NOT. It does not vouch for these keys, and nothing in this file should ever
 * be written as though it does. The server's job here is DELIVERY, NOT ATTESTATION. It accepted
 * two strings from whoever held a valid session for this relationship, it stored them without being
 * able to read anything with them, and it will hand back the same two strings; it cannot tell a
 * therapist's real key from one a hostile operator or a compromised session substituted, and it is
 * not trying to. What catches a substituted key is the OWNER, comparing the fingerprint words
 * against what their therapist reads back to them on another channel before they pin it — not any
 * check in this process. Every property this feature has flows from that, including the two below:
 *
 *  - INSERT-ONLY. The first registration for a relationship is the one that stands; a second is
 *    refused with 409 and changes nothing. It is enforced by `therapist_keys.rel_ref` being the
 *    primary key rather than by a check here, so it survives anyone rewriting this handler. The
 *    reason it matters is the shape of the attack it forecloses: a silent overwrite would let a
 *    stolen session swap the key AFTER the owner had pinned the real one, and the owner's next
 *    share — their journal, in their own words — would be sealed to the attacker while the console
 *    still said "sealed to their pinned key". A key that changes under a pin has to be a visible
 *    event, and 409 is that event. A therapist who genuinely re-keys goes through the owner's
 *    rotate-pin path, which deliberately costs an out-of-band check; it is not this route's job to
 *    make re-keying cheap.
 *
 *  - LENGTH IS CHECKED, TRUST IS NOT. A key that does not decode to exactly 32 bytes is refused,
 *    because at that point it is a client bug or a probe and never something to store and puzzle
 *    over months later when the owner's console cannot decode it. Do not mistake that for
 *    validation of the key: a 32-byte string the attacker chose passes this and is meant to. The
 *    length check keeps the store consistent; the fingerprint check keeps the person safe.
 *
 * The relRef the POST writes to comes from the SESSION, never from the path. See the handler.
 */
fun Route.therapistKeyRoutes(
    authStore: AuthStore,
    ownerGuard: AuthGuard,
    sessionIdleSeconds: Long,
    auditStore: AuditStore,
    auditSourceIp: Boolean = false,
    /**
     * Per-source budget for authenticated cookie callers, built here for the same reason
     * RelationRoutes builds its own: `Application.module` has no knob for it, and the operator's
     * `DAYMARK_RATE_LIMIT_RPS` sizes AuthGuard's bearer bucket, which is a different resource.
     * Sized off the same constants so the two cookie surfaces do not drift apart.
     */
    therapistLimiter: AttemptLimiter = AttemptLimiter(THERAPIST_MAX_PER_WINDOW, THERAPIST_WINDOW_MS),
) {
    route("/v1/relations/{relRef}/therapist-keys") {

        /*
         * The therapist publishes their two public keys. Session cookie + X-CSRF-Token, validated
         * exactly the way POST /v1/session/logout validates them.
         *
         * THE RELREF COMES FROM THE SESSION. The one in the path is compared against it and then
         * discarded. This is the whole authorization story of the route, so it is worth being
         * precise about why the disagreement is a 403 and not a redirect, a warning, or a quiet
         * preference for the session's value:
         *
         * A session for relationship A posting to relationship B's path is not a client that got
         * confused about which URL to use. There is no honest way to reach it — the therapist
         * portal builds this URL from the session it just authenticated with — so it is either a
         * client bug that will corrupt a DIFFERENT person's relationship, or somebody probing to
         * see whether the path is what the server actually keys on. Silently writing to the
         * session's relRef would make both of those succeed quietly, and the second one would learn
         * nothing while getting exactly what it wanted. Refusing outright, loudly, is the only
         * answer that treats the request as what it is.
         *
         * It is a 403 rather than a 401 because the caller IS authenticated and re-authenticating
         * cannot help: their session is real and simply does not reach this relationship. That is
         * the same distinction RelationRoutes draws for a wrong-direction channel write.
         */
        post {
            val sessionId = call.request.cookies["daymark_session"]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
            // The anti-CSRF token MUST be present on any state-changing request. A missing header
            // is a rejection, not a bypass — a null header must never validate against a null
            // stored token. Same rule and same reasoning as /v1/session/logout.
            val csrf = call.request.headers["X-CSRF-Token"]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
            val validation = authStore.validateSession(sessionId, sessionIdleSeconds, requireCsrf = csrf)
            val session = validation.record
            if (validation.check != AuthStore.SessionCheck.OK || session == null) {
                // Non-enumerating, like the rest of this surface: expired, revoked, never-existed
                // and wrong-CSRF are one 401 between them. Which it was is the owner's to read in
                // their audit log, not the caller's to learn from a status code.
                return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
            }

            /*
             * Meter AFTER the session check, not before, and the ordering is deliberate — it is the
             * same argument RelationRoutes makes for charging its budget only once the inbox token
             * has been shown. Only a caller who already holds a real session can spend it, so an
             * anonymous flood cannot burn a working therapist's allowance from a shared address and
             * lock a clinician out of their own caseload. Everything in front of this line is one
             * hashed SELECT.
             *
             * There IS something to meter, and it is not the insert. After the first success every
             * later POST is a 409 that writes nothing — but it still appends an audit entry, and an
             * unmetered stream of those would let one session bury the owner's real audit history
             * under its own refusals. A log nobody can read through is a log that has been silenced,
             * so the budget is here to protect the record rather than the row.
             */
            if (!therapistLimiter.allow(call.clientAddress())) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
            }

            val pathRelRef = call.parameters["relRef"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            if (!Secrets.constantTimeEquals(session.relRef, pathRelRef)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorDto("session does not cover this relationship"))
            }
            // From here on the session's relRef is the only one used. The path parameter has done
            // its whole job by agreeing.
            val relRef = session.relRef

            val req = call.receiveCappedJson<TherapistKeyRegistration>() ?: return@post
            val boxPub = decodePublicKey(req.boxPubB64)
            val signPub = decodePublicKey(req.signPubB64)
            if (boxPub == null || signPub == null) {
                // One message for both keys and for every way either can be wrong. There is nothing
                // to gain from telling the caller which of their two keys was the wrong length,
                // and the generic body matches the rest of the surface. Nothing about the key —
                // not a length, not a prefix, not a fragment — goes anywhere near the log: this
                // server is a zero-knowledge relay and its logs say what happened, never what was
                // in it.
                return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid key"))
            }

            /*
             * Re-encode from the decoded bytes rather than storing the string as it arrived.
             *
             * This cannot change the key — it is the exact 32 bytes just validated, encoded again —
             * but it does guarantee the owner's console is handed one fixed spelling. The therapist
             * client emits libsodium's URLSAFE_NO_PADDING, whose decoder rejects padding it did not
             * expect, so a caller who sent a padded or otherwise valid variant would otherwise have
             * their key stored in a form the reader cannot parse. Normalising on the way in is the
             * one place that is cheap to fix; on the way out it would be a guess about what the
             * therapist meant.
             */
            val boxPubB64 = Secrets.b64url(boxPub)
            val signPubB64 = Secrets.b64url(signPub)

            when (authStore.registerTherapistKeys(relRef, boxPubB64, signPubB64)) {
                AuthStore.KeyRegistration.OK -> {
                    // Respond FIRST, audit second: the row has already committed, and a logging
                    // failure must never turn a successful registration into an error the therapist
                    // might read as "it didn't work" and retry — where the retry would meet the 409
                    // this route just earned. Same ordering as the enrol and report paths.
                    call.respond(HttpStatusCode.NoContent)
                    auditSafely {
                        auditStore.append(relRef, AuditActor.THERAPIST, AuditAction.THERAPIST_KEY_REGISTERED, meta = sourceIpMeta(auditSourceIp, call))
                    }
                }
                AuthStore.KeyRegistration.ALREADY_REGISTERED -> {
                    call.respond(HttpStatusCode.Conflict, ErrorDto("keys already registered"))
                    auditSafely {
                        auditStore.append(relRef, AuditActor.THERAPIST, AuditAction.THERAPIST_KEY_REFUSED, meta = sourceIpMeta(auditSourceIp, call))
                    }
                }
            }
        }

        /*
         * The owner collects the keys. Owner bearer token, gated exactly as POST /v1/invite is.
         *
         * Bearer-only, with no X-Rel-Token second factor — which is a real difference from the
         * audit-log read next door, so here is the reason rather than an omission to be discovered
         * later. What this returns is two PUBLIC keys and a timestamp. Public keys let the holder
         * seal something TO the therapist and verify signatures FROM them; they open nothing, and
         * the owner is going to read their fingerprints aloud on a phone call anyway. Requiring the
         * inbox token as well would be borrowing the shape of a control without its substance,
         * since the thing being protected is not confidential. The bearer token is what stops this
         * becoming an open directory of who has a therapist; that much is worth having.
         *
         * 404 when nothing has been registered, and it is the honest answer: the therapist has not
         * published yet. The owner console's job on a 404 is to wait, not to seal to anything.
         */
        get {
            if (!call.ownerAuthorized(ownerGuard)) return@get
            val relRef = call.parameters["relRef"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            val keys = authStore.therapistKeys(relRef)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorDto("no keys registered"))
            call.respond(TherapistKeyRecord(keys.boxPubB64, keys.signPubB64, keys.registeredAt))
            // Audited only on the hit. A miss is not audited on purpose: relRef on this route is a
            // raw path parameter, so appending for one would let any owner-token holder seed the
            // audit store with rows keyed on relationships that do not exist. AuditStore rejects a
            // relRef outside its charset, but "it would have thrown" is not a bound on the ones
            // that are well-formed and simply made up.
            auditSafely {
                auditStore.append(relRef, AuditActor.OWNER, AuditAction.THERAPIST_KEY_FETCHED, meta = sourceIpMeta(auditSourceIp, call))
            }
        }
    }
}

/**
 * Decode a base64url public key, returning null unless it is EXACTLY [PUBLIC_KEY_BYTES] long.
 *
 * Every rejection is null and the caller turns all of them into one 400. A key that is 31 or 33
 * bytes, or is not base64url at all, is a client bug or an attack — never a value to store and be
 * puzzled by later, when the symptom would surface as the owner's console failing to seal with no
 * explanation of why.
 *
 * The length guard before the decode is not the size check; the JSON body is already capped at 64
 * KiB by [receiveCappedJson]. It is there so an obviously-wrong input is refused without allocating
 * for it, matching how `parseShareExpiry` treats an oversized header. 64 characters is generous
 * room over the 43 an unpadded 32-byte key takes.
 */
private fun decodePublicKey(b64url: String): ByteArray? {
    if (b64url.isEmpty() || b64url.length > 64) return null
    val bytes = runCatching { Base64.getUrlDecoder().decode(b64url) }.getOrNull() ?: return null
    return bytes.takeIf { it.size == PUBLIC_KEY_BYTES }
}

/** Source IP only when the operator opted in (COMPANION_SECURITY.md §9 — IP off by default). */
private fun sourceIpMeta(enabled: Boolean, call: ApplicationCall): Map<String, String>? =
    if (enabled) mapOf("sourceIp" to call.clientAddress()) else null
