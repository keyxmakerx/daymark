package com.daymark.companion.routes

import com.daymark.companion.auth.AttemptBudget
import com.daymark.companion.auth.AttemptLimiter
import com.daymark.companion.clientAddress
import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.PersistentAttemptLimiter
import com.daymark.companion.auth.Secrets
import com.daymark.companion.auth.Totp
import com.daymark.companion.mail.MailMessage
import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.OwnerNotifier
import com.daymark.companion.storage.AuditAction
import com.daymark.companion.storage.AuditActor
import com.daymark.companion.storage.AuditStore
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant
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

@Serializable data class InviteRequest(val relRef: String, val scope: List<String>, val email: String? = null, val ttlSeconds: Long? = null)
@Serializable data class InviteResponse(val inviteId: String, val link: String, val expiresAt: Long)
@Serializable data class RedeemRequest(val secret: String)
/**
 * "This wasn't me." The owner reports with their bearer token and no secret; the invited party
 * reports with the secret they were sent and no token. Both fields are therefore optional on the
 * wire and exactly one path is taken per request — see the report route for why the secret is
 * required of the second caller.
 */
@Serializable data class ReportRequest(val secret: String? = null)
@Serializable data class RedeemResult(val relRef: String, val scope: List<String>, val enrollTicket: String)
@Serializable data class TotpEnrollRequest(val enrollTicket: String, val credentialId: String, val secret: String)
@Serializable data class TotpVerifyRequest(val credentialId: String, val code: String)
@Serializable data class SessionInfo(val csrfToken: String, val absoluteExpiry: Long)

/**
 * Namespace for the pairing surface's durable attempt windows inside the shared table. A bare word
 * rather than a route path so that renaming a URL cannot silently hand every source a fresh budget.
 */
internal const val PAIR_ATTEMPT_SCOPE = "pair"

/**
 * The pairing budget, per source, per window.
 *
 * Sized against the per-INVITE backoff rather than against expected traffic, because the two are
 * the same control seen from different ends. That one arms at `DAYMARK_TOTP_LOCKOUT_FAILS`, which
 * `Config.kt` defaults to **5** — an earlier version of this comment said 3 and drew the sizing
 * conclusion from it, which is the kind of confident-but-wrong number that gets copied into the next
 * decision. So: a source that has spent twelve attempts has been wrong about roughly two and a half
 * invitations' worth of secrets inside five minutes, which is still nobody's honest afternoon. An
 * honest therapist spends one: the secret arrives in the link and is not typed.
 *
 * `internal` so a test can assert against the real production number instead of restating it.
 */
internal const val PAIR_MAX_PER_WINDOW = 12
internal const val PAIR_WINDOW_MS = 5 * 60_000L

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
    auditStore: AuditStore,
    auditSourceIp: Boolean = false,
) {
    // Per-SOURCE budget for the credential-free TOTP verify route, distinct from the
    // per-credential lockout. The lockout protects the secret from brute force; it does not stop
    // an attacker denying a therapist their own login, because `credentialId` is a typed username
    // and the attacker only has to burn its counter. Keyed on the client address (real client, not
    // the proxy — see ClientAddress), so sustained abuse costs the attacker rather than the victim.
    //
    // In process memory ON PURPOSE, and the reasoning is set out at length on AttemptBudget: what
    // bounds guessing at a TOTP secret is the per-credential counter, and that one has lived in
    // SQLite since it was written. This budget shapes volume, so a restart costs at most one window
    // of it and no secret becomes easier to guess.
    val totpSourceLimiter: AttemptBudget = AttemptLimiter(maxPerWindow = 20, windowMs = 5 * 60_000L)

    // Per-SOURCE budget for the credential-free PAIRING surface — invite redeem and invite report —
    // and the one budget in this file that is DURABLE.
    //
    // Two reasons, and the first is the one that made this a blocker rather than a nicety. §3.7's
    // pairing rests on a password-authenticated exchange whose entire security argument is "one
    // online guess per attempt, and no offline attack"; that argument is worth exactly as much as
    // the thing counting attempts, and an in-process map is cleared by any restart and duplicated
    // by any second instance. A limiter an attacker can reset by waiting for a deploy is not a
    // limiter. Second, and smaller: the per-invite backoff in AuthStore has always been durable,
    // so leaving the per-source half in memory would have made half of one control survive a
    // restart and the other half not — the confusing kind of partial guarantee that reads as
    // protection in a review and is not.
    //
    // Redeem and report share ONE scope deliberately. They verify the same invite secret, so
    // separate budgets would let an attacker alternate routes and spend twice the attempts on it.
    val pairSourceLimiter: AttemptBudget = PersistentAttemptLimiter(
        authStore,
        scope = PAIR_ATTEMPT_SCOPE,
        maxPerWindow = PAIR_MAX_PER_WINDOW,
        windowMs = PAIR_WINDOW_MS,
    )

    route("/v1") {

        // Owner mints a single-use invite. Best-effort email; the link is ALSO returned in-band
        // for OOB delivery (email is a convenience, not the security-bearing channel).
        post("/invite") {
            if (!call.ownerAuthorized(ownerGuard)) return@post
            val req = call.receiveCappedJson<InviteRequest>() ?: return@post
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
        //
        // A wrong secret NEVER consumes the invite, and that is a deliberate refusal rather than an
        // omission: with a PAKE a mistyped code and an attacker's guess are the same event, so
        // burning on failure would hand whoever holds the link a free veto over every invitation
        // the owner ever mints. The only thing that kills an invite is the report route below,
        // which a person has to reach for. See AuthStore.reportInviteByOwner for the full argument
        // and InviteBurnRuleTest for the regression guard.
        post("/invite/{inviteId}/redeem") {
            call.response.header("Referrer-Policy", "no-referrer")
            // Source budget BEFORE the body is read, for the same reason TOTP verify does it: this
            // route takes no credential, so without it an unlimited stream of guesses is free to
            // the attacker in both memory and per-invite lockout budget.
            if (!pairSourceLimiter.allow(call.clientAddress())) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
                return@post
            }
            val inviteId = call.parameters["inviteId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing inviteId"))
            val req = call.receiveCappedJson<RedeemRequest>() ?: return@post
            val lockoutBaseMs = totpLockoutSeconds * 1000
            val result = authStore.redeemInvite(inviteId, req.secret, totpLockoutFails, lockoutBaseMs)
            when (result.status) {
                AuthStore.RedeemStatus.OK -> {
                    pairSourceLimiter.reset(call.clientAddress())
                    call.respond(HttpStatusCode.OK, RedeemResult(result.relRef!!, result.scope, result.enrollTicket!!))
                }
                // The relRef never reaches the body here — it is carried back only so the guess can
                // be recorded against the right relationship. The caller still sees a bare 401.
                AuthStore.RedeemStatus.WRONG_SECRET -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                    result.relRef?.let { rel ->
                        auditSafely {
                            auditStore.append(rel, AuditActor.THERAPIST, AuditAction.PAIR_GUESS_FAILED, meta = auditMeta(auditSourceIp, call))
                        }
                    }
                }
                AuthStore.RedeemStatus.LOCKED -> {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked"))
                    result.relRef?.let { rel ->
                        auditSafely {
                            auditStore.append(rel, AuditActor.THERAPIST, AuditAction.LOCKOUT, meta = auditMeta(auditSourceIp, call))
                        }
                    }
                }
                AuthStore.RedeemStatus.GONE -> call.respond(HttpStatusCode.Gone, ErrorDto("invite unavailable"))
            }
        }

        /*
         * "This wasn't me." The only route in the system that destroys an invitation.
         *
         * It exists because the alternative that suggests itself — kill the invite when a
         * confirmation fails — is a denial-of-service primitive dressed as a security control. The
         * split it implements is between an event the server CANNOT interpret (a wrong code, which
         * is a typo and an attack in equal measure) and one it can (a human saying they did not
         * expect this). Only the second may burn.
         *
         * Two callers, one outcome:
         *
         *  - The owner, holding the bearer token, reporting an invitation they did not expect or
         *    that their therapist has told them never arrived. No secret: the owner was not the
         *    party handed one.
         *  - The invited party, holding the link, reporting a link they never asked for. They must
         *    present the CORRECT secret — which is not the DoS this route avoids, because anyone
         *    who can pass that gate could have redeemed the invite and become the therapist
         *    instead. A wrong secret takes the same capped backoff as a wrong redeem, so this
         *    cannot become an unmetered oracle for guessing the secret.
         *
         * Errors follow the non-enumerating style of the rest of the file: an unknown invite id, an
         * already-terminal invite and an invite whose TTL has run out are one 410 between them, and
         * a wrong secret is the same bare 401 a wrong redeem gets. The distinctions live in the
         * owner's audit log, which is the one place a caller cannot read them from.
         */
        post("/invite/{inviteId}/report") {
            call.response.header("Referrer-Policy", "no-referrer")
            val inviteId = call.parameters["inviteId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing inviteId"))
            // Which caller this is has to be decided before the budget is spent: the owner path is
            // already metered by AuthGuard's own bucket, and making the owner share the anonymous
            // pairing budget would let an attacker on the same address spend the owner's ability to
            // kill an invite — handing the attacker the outcome this route exists to prevent.
            val presentingOwnerToken = call.request.headers[HttpHeaders.Authorization] != null
            if (!presentingOwnerToken && !pairSourceLimiter.allow(call.clientAddress())) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
                return@post
            }
            // An owner reporting from a console button has nothing to put in a body, so an empty
            // one is a valid report rather than a malformed request. Only a non-empty body that is
            // not JSON earns the 400.
            val raw = call.readBodyCapped() ?: return@post
            val req = if (raw.isEmpty()) {
                ReportRequest()
            } else {
                try {
                    bodyJson.decodeFromString<ReportRequest>(raw.decodeToString())
                } catch (_: Exception) {
                    // Deliberately generic: a parser message can echo body content back.
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("malformed request"))
                    return@post
                }
            }

            val result: AuthStore.ReportResult
            val actor: AuditActor
            if (presentingOwnerToken) {
                if (!call.ownerAuthorized(ownerGuard)) return@post
                result = authStore.reportInviteByOwner(inviteId)
                actor = AuditActor.OWNER
            } else {
                // No token and no secret is not a report, it is an anonymous request to destroy
                // someone else's invitation. It gets the same 401 a wrong secret does.
                val secret = req.secret
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                result = authStore.reportInvite(inviteId, secret, totpLockoutFails, totpLockoutSeconds * 1000)
                actor = AuditActor.THERAPIST
            }

            when (result.status) {
                AuthStore.ReportStatus.OK -> {
                    // Respond FIRST, then audit: the burn has already committed in the store, and a
                    // logging failure must never turn a successful report into an error the caller
                    // might read as "it didn't work". Same ordering as the enrol path below.
                    //
                    // The design also asks for the owner to be alerted loudly on a report, and that
                    // is NOT wired here on purpose. The only owner-mail channel is
                    // MailMessage.ReviewKind, a fixed vocabulary the owner console renders and the
                    // owner opts into per event; adding a value to it is an owner-console change,
                    // and this step is server-only. Until it lands the report is visible in the
                    // audit log, which is where the owner reads what their access control did.
                    call.respond(HttpStatusCode.NoContent)
                    auditSafely {
                        auditStore.append(result.relRef!!, actor, AuditAction.INVITE_REPORTED, meta = auditMeta(auditSourceIp, call))
                    }
                }
                AuthStore.ReportStatus.WRONG_SECRET -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                    result.relRef?.let { rel ->
                        auditSafely {
                            auditStore.append(rel, AuditActor.THERAPIST, AuditAction.PAIR_GUESS_FAILED, meta = auditMeta(auditSourceIp, call))
                        }
                    }
                }
                AuthStore.ReportStatus.LOCKED -> call.respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked"))
                AuthStore.ReportStatus.GONE -> call.respond(HttpStatusCode.Gone, ErrorDto("invite unavailable"))
            }
        }

        // Enrol a TOTP credential (client-set, high-entropy secret). GATED on a single-use
        // enrollment ticket minted at invite redemption: the ticket pins the relRef (derived
        // server-side, NOT trusted from the body), is consumed on success, and drives the invite
        // to CONSUMED. Insert-only — a live credential is never silently overwritten. Fail-closed.
        post("/totp/enroll") {
            call.response.header("Referrer-Policy", "no-referrer")
            val req = call.receiveCappedJson<TotpEnrollRequest>() ?: return@post
            // Validate the secret is a plausible base64url key (structural, not content).
            val secretBytes = decodeSecret(req.secret) ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid secret"))
            if (secretBytes.size < 16) return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("secret too short"))
            val result = authStore.enrollTotp(req.enrollTicket, req.credentialId, req.secret)
            when (result.status) {
                AuthStore.EnrollStatus.OK -> {
                    // Respond FIRST: the audit append and notifier.notify() below are best-effort
                    // and must never delay or gate the enrollment response the therapist is waiting
                    // on (it already committed). Matches the ordering in RelationRoutes.kt.
                    call.respond(HttpStatusCode.NoContent)
                    auditSafely {
                        auditStore.append(
                            result.relRef!!, AuditActor.THERAPIST, AuditAction.ENROL_OK,
                            meta = auditMeta(auditSourceIp, call, "credentialId" to req.credentialId),
                        )
                    }
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
            // Source budget BEFORE the body is read: this route takes no credential, so an
            // unlimited stream of attempts was previously free to the attacker in both memory and
            // lockout budget.
            if (!totpSourceLimiter.allow(call.clientAddress())) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
                return@post
            }
            val req = call.receiveCappedJson<TotpVerifyRequest>() ?: return@post
            val rec = authStore.getTotp(req.credentialId)
            if (rec == null) {
                // Do not reveal whether the credential exists. No relRef is known, so there is
                // nothing meaningful to key an audit entry on.
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                return@post
            }
            val now = System.currentTimeMillis()
            if (rec.lockedUntil > now) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked"))
                auditSafely {
                    auditStore.append(rec.relRef, AuditActor.THERAPIST, AuditAction.LOCKOUT, meta = auditMeta(auditSourceIp, call, "credentialId" to req.credentialId))
                }
                return@post
            }
            val secretBytes = decodeSecret(rec.secretB64)
            // A correct code is not enough — it must also be UNSPENT. consumeTotpStep is the
            // atomic compare-and-set that makes it single-use (RFC 6238 5.2); a replayed code
            // therefore lands on the failure path below, indistinguishable to the caller from a
            // wrong one, and counts against the lockout like any other bad attempt.
            val matchedStep = secretBytes?.let { Totp.verifyStep(it, req.code, now / 1000) }
            val ok = matchedStep != null && authStore.consumeTotpStep(req.credentialId, matchedStep)
            if (!ok) {
                val locked = authStore.recordTotpFailure(req.credentialId, totpLockoutFails, totpLockoutSeconds * 1000)
                if (locked > now) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked"))
                    auditSafely {
                        auditStore.append(rec.relRef, AuditActor.THERAPIST, AuditAction.LOCKOUT, meta = auditMeta(auditSourceIp, call, "credentialId" to req.credentialId))
                    }
                } else {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                    auditSafely {
                        auditStore.append(rec.relRef, AuditActor.THERAPIST, AuditAction.AUTH_FAIL, meta = auditMeta(auditSourceIp, call, "credentialId" to req.credentialId))
                    }
                }
                return@post
            }
            authStore.recordTotpSuccess(req.credentialId)
            totpSourceLimiter.reset(call.clientAddress())
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
            auditSafely {
                auditStore.append(rec.relRef, AuditActor.THERAPIST, AuditAction.AUTH_SUCCESS, meta = auditMeta(auditSourceIp, call, "credentialId" to req.credentialId))
            }
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

/**
 * Owner-token gate for the mint route. Non-enumerating errors, source-keyed lockout.
 *
 * `internal` rather than private because the therapist public-key read (TherapistKeyRoutes.kt) is
 * specified as being gated "exactly as POST /v1/invite is", and the only way to keep that promise
 * literally true is to call the same function rather than write a third copy of it that can drift.
 * Nothing about the gate changed in making it visible.
 */
internal suspend fun ApplicationCall.ownerAuthorized(guard: AuthGuard): Boolean {
    val sourceId = clientAddress()
    val presented = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    return when (guard.authorize(sourceId, presented)) {
        AuthGuard.Result.OK -> true
        AuthGuard.Result.RATE_LIMITED -> { respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited")); false }
        AuthGuard.Result.LOCKED -> { respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked")); false }
        AuthGuard.Result.BAD_TOKEN -> { respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); false }
    }
}

/** Small fixed non-content annotations for an audit entry: the acting credential id, plus the
 *  source IP only when the operator opted in (COMPANION_SECURITY.md §9 — IP off by default). */
private fun auditMeta(sourceIpEnabled: Boolean, call: ApplicationCall, vararg extra: Pair<String, String>): Map<String, String> {
    val meta = extra.toMap().toMutableMap()
    if (sourceIpEnabled) meta["sourceIp"] = call.clientAddress()
    return meta
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
