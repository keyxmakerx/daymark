package com.daymark.companion.routes

import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.auth.Totp
import com.daymark.companion.mail.MailMessage
import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.OwnerNotifier
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Instant
import java.util.Base64

@Serializable data class InviteRequest(val relRef: String, val scope: List<String>, val email: String? = null, val ttlSeconds: Long? = null)
@Serializable data class InviteResponse(val inviteId: String, val link: String, val expiresAt: Long)
@Serializable data class RedeemRequest(val secret: String)
@Serializable data class RedeemResult(val relRef: String, val scope: List<String>, val enrollTicket: String)
@Serializable data class TotpEnrollRequest(val enrollTicket: String, val credentialId: String, val secret: String)
@Serializable data class TotpVerifyRequest(val credentialId: String, val code: String)
@Serializable data class SessionInfo(val csrfToken: String, val absoluteExpiry: Long)

/**
 * Therapist auth: single-use invites, TOTP enrol/verify, opaque server-side sessions, and
 * documented WebAuthn scaffold stubs. Owner-facing routes (mint invite) are gated on the owner
 * bearer token; therapist-facing routes use capped-backoff rate limiting.
 *
 * @param publicBaseUrl absolute base for building the invite link (e.g. https://host/base). If
 *   null, the link is built from the request's own scheme/host as a best effort.
 */
fun Route.therapistAuthRoutes(
    authStore: AuthStore,
    ownerGuard: AuthGuard,
    mailer: Mailer,
    inviteTtlSeconds: Long,
    sessionIdleSeconds: Long,
    sessionAbsoluteSeconds: Long,
    totpLockoutFails: Int,
    totpLockoutSeconds: Long,
    publicBaseUrl: String?,
    notifier: OwnerNotifier,
    cookieSecure: Boolean = true,
) {
    route("/v1") {

        // Owner mints a single-use invite. Best-effort email; the link is ALSO returned in-band
        // for OOB delivery (email is a convenience, not the security-bearing channel).
        post("/invite") {
            if (!call.ownerAuthorized(ownerGuard)) return@post
            val req = call.receive<InviteRequest>()
            val ttl = req.ttlSeconds ?: inviteTtlSeconds
            val minted = authStore.mintInvite(req.relRef, req.scope, ttl)
            val link = buildInviteLink(call, publicBaseUrl, minted.inviteId, minted.secret)
            // Fire-and-forget notification; a Disabled/Failed result never gates invite creation.
            if (req.email != null) {
                runCatching {
                    mailer.send(MailMessage.TherapistInvite(req.email, URI(link), Instant.ofEpochMilli(minted.expiresAt)))
                }
            }
            call.respond(HttpStatusCode.Created, InviteResponse(minted.inviteId, link, minted.expiresAt))
        }

        // Therapist redeems the invite secret. Capped backoff, never burn-after-N. No-referrer.
        post("/invite/{inviteId}/redeem") {
            call.response.header("Referrer-Policy", "no-referrer")
            val inviteId = call.parameters["inviteId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing inviteId"))
            val req = call.receive<RedeemRequest>()
            val lockoutBaseMs = totpLockoutSeconds * 1000
            val result = authStore.redeemInvite(inviteId, req.secret, totpLockoutFails, lockoutBaseMs)
            when (result.status) {
                AuthStore.RedeemStatus.OK -> call.respond(HttpStatusCode.OK, RedeemResult(result.relRef!!, result.scope, result.enrollTicket!!))
                AuthStore.RedeemStatus.WRONG_SECRET -> call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                AuthStore.RedeemStatus.LOCKED -> call.respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked"))
                AuthStore.RedeemStatus.GONE -> call.respond(HttpStatusCode.Gone, ErrorDto("invite unavailable"))
            }
        }

        // Enrol a TOTP credential (client-set, high-entropy secret). GATED on a single-use
        // enrollment ticket minted at invite redemption: the ticket pins the relRef (derived
        // server-side, NOT trusted from the body), is consumed on success, and drives the invite
        // to CONSUMED. Insert-only — a live credential is never silently overwritten. Fail-closed.
        post("/totp/enroll") {
            call.response.header("Referrer-Policy", "no-referrer")
            val req = call.receive<TotpEnrollRequest>()
            // Validate the secret is a plausible base64url key (structural, not content).
            val secretBytes = decodeSecret(req.secret) ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid secret"))
            if (secretBytes.size < 16) return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("secret too short"))
            val result = authStore.enrollTotp(req.enrollTicket, req.credentialId, req.secret)
            when (result.status) {
                AuthStore.EnrollStatus.OK -> {
                    // Respond FIRST: notifier.notify() is best-effort and must never delay or
                    // gate the enrollment response the therapist is waiting on (it already
                    // committed). Matches the ordering in RelationRoutes.kt.
                    call.respond(HttpStatusCode.NoContent)
                    notifier.notify(MailMessage.ReviewKind.THERAPIST_ENROLLED, portalUrlFor(call, publicBaseUrl))
                }
                // Do not distinguish a bad/expired ticket from a missing one (non-enumerating).
                AuthStore.EnrollStatus.NO_TICKET -> call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                // A credential already exists for this relationship/credential — refuse to overwrite.
                AuthStore.EnrollStatus.ALREADY_ENROLLED -> call.respond(HttpStatusCode.Conflict, ErrorDto("credential already enrolled"))
            }
        }

        // Verify a TOTP code; on success issue an opaque session cookie + anti-CSRF token.
        post("/totp/verify") {
            val req = call.receive<TotpVerifyRequest>()
            val rec = authStore.getTotp(req.credentialId)
            if (rec == null) {
                // Do not reveal whether the credential exists.
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                return@post
            }
            val now = System.currentTimeMillis()
            if (rec.lockedUntil > now) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked"))
                return@post
            }
            val secretBytes = decodeSecret(rec.secretB64)
            val ok = secretBytes != null && Totp.verify(secretBytes, req.code, now / 1000)
            if (!ok) {
                val locked = authStore.recordTotpFailure(req.credentialId, totpLockoutFails, totpLockoutSeconds * 1000)
                if (locked > now) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked"))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                }
                return@post
            }
            authStore.recordTotpSuccess(req.credentialId)
            val session = authStore.createSession(req.credentialId, rec.relRef, sessionIdleSeconds, sessionAbsoluteSeconds)
            call.response.cookies.append(
                Cookie(
                    name = "daymark_session",
                    value = session.sessionId,
                    encoding = CookieEncoding.RAW,
                    httpOnly = true,
                    secure = cookieSecure,
                    path = "/",
                    extensions = mapOf("SameSite" to "Strict"),
                ),
            )
            call.respond(HttpStatusCode.OK, SessionInfo(session.csrfToken, session.absoluteExpiry))
        }

        // Logout: requires the session cookie + matching anti-CSRF header; hard-deletes the session.
        post("/session/logout") {
            val sessionId = call.request.cookies["daymark_session"]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("no session"))
            // The anti-CSRF token MUST be present on any state-changing request. A missing
            // header is a rejection, not a bypass.
            val csrf = call.request.headers["X-CSRF-Token"]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
            val v = authStore.validateSession(sessionId, sessionIdleSeconds, requireCsrf = csrf)
            if (v.check != AuthStore.SessionCheck.OK) {
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                return@post
            }
            authStore.revokeSession(sessionId)
            call.respond(HttpStatusCode.NoContent)
        }

        // ---- WebAuthn SCAFFOLD ONLY -------------------------------------------------
        // RP-ID / origin are config-pinned elsewhere (Config.webauthnRpId/Origins) so the
        // eventual implementation cannot regress to Host-header derivation. Attestation /
        // assertion verification is OUT OF SCOPE for headless verification — these return 501.
        val webauthnStub: suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
            call.respond(
                HttpStatusCode.NotImplemented,
                ErrorDto("webauthn attestation/assertion verification out of scope for headless verification"),
            )
        }
        post("/webauthn/register/begin", webauthnStub)
        post("/webauthn/register/finish", webauthnStub)
        post("/webauthn/assert/begin", webauthnStub)
        post("/webauthn/assert/finish", webauthnStub)
        // Also answer GET for the scaffold so a probe sees the documented 501 either way.
        get("/webauthn/register/begin", webauthnStub)
    }
}

/** Owner-token gate for the mint route. Non-enumerating errors, source-keyed lockout. */
private suspend fun ApplicationCall.ownerAuthorized(guard: AuthGuard): Boolean {
    val sourceId = request.origin.remoteAddress
    val presented = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    return when (guard.authorize(sourceId, presented)) {
        AuthGuard.Result.OK -> true
        AuthGuard.Result.RATE_LIMITED -> { respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited")); false }
        AuthGuard.Result.LOCKED -> { respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked")); false }
        AuthGuard.Result.BAD_TOKEN -> { respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); false }
    }
}

/** Accept a secret encoded as base64url (no pad), base64, or raw utf-8 of sufficient length. */
private fun decodeSecret(s: String): ByteArray? {
    runCatching { return Base64.getUrlDecoder().decode(s) }
    runCatching { return Base64.getDecoder().decode(s) }
    return s.toByteArray(Charsets.UTF_8).takeIf { it.size >= 16 }
}

private fun buildInviteLink(call: ApplicationCall, publicBaseUrl: String?, inviteId: String, secret: String): String {
    return "${resolveBaseUrl(call, publicBaseUrl)}/portal/invite#id=$inviteId&s=$secret"
}

/** Best-effort absolute URL to the owner console root, for "something to review" notifications. */
private fun portalUrlFor(call: ApplicationCall, publicBaseUrl: String?): URI {
    return URI("${resolveBaseUrl(call, publicBaseUrl)}/")
}
