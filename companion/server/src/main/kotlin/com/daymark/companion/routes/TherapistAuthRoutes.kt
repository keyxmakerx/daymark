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
@Serializable data class TotpEnrollRequest(val enrollTicket: String, val credentialId: String, val secret: String)
@Serializable data class TotpVerifyRequest(val credentialId: String, val code: String)
@Serializable data class SessionInfo(val csrfToken: String, val absoluteExpiry: Long)

/**
 * Namespace for the pairing surface's durable attempt windows inside the shared table. A bare word
 * rather than a route path so that renaming a URL cannot silently hand every source a fresh budget.
 */
internal const val PAIR_ATTEMPT_SCOPE = "pair"

/**
 * The pairing budget: FETCH AND RESPOND, per client address, per window.
 *
 * ## What it counts now, and why the old number was the wrong shape rather than too small
 *
 * It used to cover every credential-free touch on the pairing surface — fetch, respond, the status
 * poll and the report — at twelve per five minutes. The justification counted GUESSES; the budget
 * counted REQUESTS, and those are not the same population. An honest ceremony spent about 8.7 of
 * the twelve, of which two were proofs of the secret and roughly 6.7 were status polls that test
 * no secret and reveal nothing. So the window was exhausted by honest waiting, and the first thing
 * it then refused was the REPORT — the one call this product most needs to accept from someone it
 * cannot identify. Two clinicians behind one clinic NAT tripped it between them. Against the
 * attacker it was written for — someone who holds the link, from one address — it did nothing the
 * per-invite lockout was not already doing.
 *
 * So the surface is split three ways and this constant now meters only the two touches that carry
 * a secret and move a run along:
 *
 *  - **fetch and respond** — here. Twenty per five minutes. An honest ceremony spends TWO.
 *  - **the status poll** — keyed on the pairing RUN, not the address, because a poll is a cadence
 *    rather than a ration (`PairingRelayRoutes`).
 *  - **the report** — in no budget at all, behind a raw flood guard only (see the route below).
 *
 * ## Why twenty, and why this is not the control that stops guessing
 *
 * Twenty is ten honest ceremonies from one address inside five minutes — a clinic's worth of
 * afternoon, not one person's — and it is deliberately NOT sized against the guessing threshold,
 * because it is not what bounds guessing. That is the per-INVITE lockout: five wrong secrets
 * against one invitation, then a capped backoff out to an hour, durable, and shared by every door
 * that verifies that secret. A per-invitation dimension here would add nothing to it and would
 * make the NAT case worse, so there isn't one.
 *
 * Every allowed request is charged and a success NEVER resets the window: a limiter a link-holder
 * can clear by succeeding is one they can clear at will.
 *
 * `internal` so a test can assert against the real production number instead of restating it.
 */
internal const val PAIR_MAX_PER_WINDOW = 20
internal const val PAIR_WINDOW_MS = 5 * 60_000L

/**
 * The raw flood guard over the anonymous REPORT route, per client address.
 *
 * Not a guessing budget and not sized like one — the point of the report route is that it is
 * refused as rarely as the machinery allows. This is the "somebody is running a script" ceiling
 * and nothing more: a person reporting a link they did not expect clicks once, a whole clinic
 * behind one address clicks a handful of times a day, and thirty a minute is out of reach of both.
 *
 * It exists because the alternative is not "unmetered", it is "a 64 MiB Argon2id verification per
 * anonymous request, unmetered" — the report route hashes on every call by construction, and after
 * this change it hashes even while the invitation is locked, which is what makes a correct report
 * possible while somebody is guessing at it. `DAYMARK_RATE_LIMIT_RPS` does not cover this: it is a
 * parameter of [com.daymark.companion.auth.AuthGuard], so it meters the bearer-token surfaces and
 * nothing else. Verified rather than assumed, and written down here because the deployment docs
 * describe that knob as a per-source request limit, which it is not.
 *
 * In process memory on purpose ([com.daymark.companion.auth.AttemptBudget] sets out the test): it
 * shapes volume rather than bounding guesses at a secret, so a restart returns one minute of flood
 * and no secret becomes easier to guess.
 */
internal const val REPORT_MAX_PER_WINDOW = 30
internal const val REPORT_WINDOW_MS = 60_000L

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

    // The raw flood guard described on REPORT_MAX_PER_WINDOW. The store's clock, not the wall
    // clock, so a test that advances time advances this with it.
    val reportFloodGuard: AttemptBudget = AttemptLimiter(
        maxPerWindow = REPORT_MAX_PER_WINDOW,
        windowMs = REPORT_WINDOW_MS,
        clock = authStore::nowMs,
    )

    // Per-SOURCE budget for the credential-free PAIRING touches — the relay's fetch and respond —
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
    // Fetch and respond share ONE scope deliberately. They verify the same invite secret, so
    // separate budgets would let an attacker alternate routes and spend twice the attempts on it.
    // The report route no longer shares it, and the status poll never did; see the constants.
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

        /*
         * THE REDEEM ROUTE IS GONE, and its absence is the point (plan §3.7, 2026-09-04).
         *
         * It took the invitation secret — which the emailed link carries — and answered with an
         * enrolment ticket. So whoever read that email could enrol as the therapist, and the short
         * pairing code the design document describes secured nothing: it was a ceremony bolted to
         * the side of a door that was already open.
         *
         * An enrolment ticket is now minted in exactly one place: `POST
         * /v1/relations/{relRef}/pairing/{exchangeId}/approve`, which is owner-authenticated and
         * carries a ticket the therapist chose and sealed under the pairing key (PairingRelayRoutes).
         * Only someone who typed the owner's code can produce a sealing the owner can open, so only
         * they can have a ticket forwarded. A link-holder can still open a conversation on the relay
         * — fetch, and answer once — and gets ciphertext nobody approves.
         *
         * `AuthStore.redeemInvite` survives as a store function because the invite lockout and
         * burn-rule tests are written against it and its failure semantics are the ones
         * `checkInviteSecret` shares. NO ROUTE MAY CALL IT: TherapistAuthTest greps this package to
         * say so, and proves this path now answers as an unknown route.
         */

        /*
         * "This wasn't me." The one call this product most needs to accept from someone it cannot
         * identify, and therefore the one with the least standing in its way.
         *
         * IT IS IN NO ATTEMPT BUDGET. It used to share the pairing surface's per-source window,
         * which meant an honest ceremony's own waiting could spend the budget that a report then
         * needed — the refusal landing on the person trying to close a link they did not expect,
         * at the exact moment they had reason to be alarmed. Above it now there is only the raw
         * flood guard (REPORT_MAX_PER_WINDOW), which a human cannot reach.
         *
         * EVERY ANONYMOUS CALL GETS THE SAME ANSWER: 204, always. Right secret, wrong secret, no
         * secret, an invitation that is already reported, expired, consumed, or never existed —
         * one flat acknowledgement. The route hands nothing back, so it has nothing to say, and
         * saying nothing is what keeps it from being an oracle: a caller cannot learn from it
         * whether an invitation exists, whether it is still live, whether it is locked, or whether
         * the secret they hold is the right one. (What is left is timing — a live invitation costs
         * an Argon2 verification and a dead one does not — and that is a distinction only reachable
         * by someone who already holds a 256-bit invite id, which the relay's fetch route gives up
         * just as readily. Written down rather than mitigated: a dummy verification would flatten
         * it at the price of letting any caller spend 64 MiB of hashing on an id they made up.)
         *
         * A CORRECT REPORT IS HONOURED WHILE THE INVITATION IS LOCKED OUT. The lockout is there to
         * stop information leaking to a guesser; this route leaks none, and the invitation somebody
         * is guessing at is precisely the one its real holder most needs to be able to close. See
         * AuthStore.reportInvite.
         *
         * THE BURN IS IDEMPOTENT. A second correct report on the same invitation finds it already
         * REPORTED, writes nothing, and answers exactly as the first did.
         *
         * The OWNER path is unchanged and still speaks plainly (204 / 410): it is behind the bearer
         * token, so there is no anonymous caller on it to keep anything from.
         */
        post("/invite/{inviteId}/report") {
            call.response.header("Referrer-Policy", "no-referrer")
            val inviteId = call.parameters["inviteId"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing inviteId"))
            // Which caller this is has to be decided before anything is spent: the owner path is
            // already metered by AuthGuard's own bucket, and making the owner share the anonymous
            // guard would let an attacker on the same address spend the owner's ability to kill an
            // invite — handing the attacker the outcome this route exists to prevent.
            val presentingOwnerToken = call.request.headers[HttpHeaders.Authorization] != null
            if (!presentingOwnerToken && !reportFloodGuard.allow(call.clientAddress())) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
                return@post
            }
            // An owner reporting from a console button has nothing to put in a body, so an empty
            // one is a valid report rather than a malformed request. Only a non-empty body that is
            // not JSON earns the 400 — a fact about the request, not about any invitation.
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

            if (presentingOwnerToken) {
                if (!call.ownerAuthorized(ownerGuard)) return@post
                val result = authStore.reportInviteByOwner(inviteId)
                if (result.status == AuthStore.ReportStatus.OK) {
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
                        auditStore.append(result.relRef!!, AuditActor.OWNER, AuditAction.INVITE_REPORTED, meta = auditMeta(auditSourceIp, call))
                    }
                } else {
                    call.respond(HttpStatusCode.Gone, ErrorDto("invite unavailable"))
                }
                return@post
            }

            // The invited party. A body with no secret proves nothing and kills nothing; it takes
            // the same flat answer as a wrong one rather than a 401, because a 401 here would say
            // "there is something at this id worth authenticating to".
            val result = req.secret?.let {
                authStore.reportInvite(inviteId, it, totpLockoutFails, totpLockoutSeconds * 1000)
            }
            call.respond(HttpStatusCode.NoContent)
            when (result?.status) {
                AuthStore.ReportStatus.OK -> auditSafely {
                    auditStore.append(result.relRef!!, AuditActor.THERAPIST, AuditAction.INVITE_REPORTED, meta = auditMeta(auditSourceIp, call))
                }
                // A wrong secret here writes NO guess row. On the relay a wrong secret is somebody
                // working on the invitation and the owner should see it; on this route it is more
                // often a person with an old link doing the right thing, the answer teaches the
                // caller nothing either way, and this is the one anonymous surface with no attempt
                // budget in front of it — so guess rows here would be volume an attacker chooses,
                // written into a chain that never forgets, which is the finding LockedInviteAuditTest
                // exists for.
                //
                // The LOCKOUT row is the exception, and it is not optional: report and the relay
                // spend ONE shared fail counter, so the guess that arms a lockout can land here,
                // and a lockout the owner never learns about would make this the surface an
                // attacker chooses in order to arm them invisibly. One row per armed episode,
                // wherever it is armed — the rule the rest of this file keeps.
                AuthStore.ReportStatus.WRONG_SECRET -> if (result.lockoutArmed) {
                    result.relRef?.let { rel ->
                        auditSafely {
                            auditStore.append(rel, AuditActor.THERAPIST, AuditAction.LOCKOUT, meta = auditMeta(auditSourceIp, call))
                        }
                    }
                }
                // A knock on a locked door, a dead invitation, an id that never existed, or no
                // secret at all: nothing happened, so nothing is written.
                else -> Unit
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
            // The store's clock, not System.currentTimeMillis(): rec.lockedUntil was WRITTEN by
            // recordTotpFailure off the store's clock, so the question "is it still in force?"
            // must be asked of the same clock. Under the default they are both the wall clock;
            // when a test injects a clock, a route on a different one silently disagrees with
            // the store about now — which made this path untestable and was a real divergence
            // waiting for any deployment where the two opinions of time drift apart.
            val now = authStore.nowMs()
            if (rec.lockedUntil > now) {
                // Deliberately NO audit append here — the same control as the redeem route's
                // LOCKED branch, for the same reason. credentialId is a therapist-typed username,
                // so anyone who knows or observed it used to be able to append a LOCKOUT row to
                // the owner's chain per request while the credential was locked, metered only by
                // the in-memory source limiter above (which a restart clears and source rotation
                // sidesteps). The chain never forgets a row; attacker-paced volume in it buries
                // the one row that matters. The lockout is recorded once, at the moment it is
                // armed — the failure path below — and probes bouncing off it write nothing.
                call.respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked"))
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
 *  source IP only when the operator opted in (COMPANION_SECURITY.md §9 — IP off by default).
 *  Internal so the pairing relay's audit lines carry the same shape from the same code. */
internal fun auditMeta(sourceIpEnabled: Boolean, call: ApplicationCall, vararg extra: Pair<String, String>): Map<String, String> {
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
