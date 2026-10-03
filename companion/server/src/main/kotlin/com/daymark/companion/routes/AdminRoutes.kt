package com.daymark.companion.routes

import com.daymark.companion.SetupMode
import com.daymark.companion.admin.AdminStore
import com.daymark.companion.admin.SetupCode
import com.daymark.companion.auth.AttemptBudget
import com.daymark.companion.auth.Totp
import com.daymark.companion.clientAddress
import com.daymark.companion.storage.AuditAction
import com.daymark.companion.storage.AuditActor
import com.daymark.companion.storage.AuditStore
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.Base64

private val log = LoggerFactory.getLogger("com.daymark.companion.audit")

/** The audit log is additive, never load-bearing: a logging bug must never fail a real request. */
private fun auditSafely(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        log.warn("admin audit append failed", e)
    }
}

/** The key every row in the server's own chain is filed under: there is one server. */
internal const val SERVER_AUDIT_REF = "server"

/** The administrator's session cookie. Distinct from the clinician's, so neither can stand in for the other. */
internal const val ADMIN_COOKIE = "daymark_admin"

@Serializable data class AdminStatusDto(val claimed: Boolean, val claimOpen: Boolean)
@Serializable data class AdminClaimRequest(val setupCode: String, val name: String, val totpSecret: String, val totpCode: String)
@Serializable data class AdminSignInRequest(val name: String, val code: String)
@Serializable data class AdminSessionDto(val name: String, val csrfToken: String, val absoluteExpiry: Long)
/** Who is signed in, the shape and a count; and the session's own anti-CSRF token, for a reloaded console. */
@Serializable data class AdminOverviewDto(val name: String, val setupMode: String, val administrators: Int, val csrfToken: String)

/**
 * The server administrator's routes (#322): claiming a new server with its setup code, signing in,
 * and what the server console reads once signed in.
 *
 * ## What an administrator can reach
 *
 * Membership, health and counts, and nothing else: never a key, a seed, a token, a ciphertext or
 * anything a person wrote. [AdminOverviewDto] is the whole of what this file returns about the
 * server, and its fields are the feature. The owner's token opens none of it: every route below asks
 * for [ADMIN_COOKIE] and reads no `Authorization` header, so the owner's token is not a credential
 * here, and an administrator's session is not one anywhere else.
 *
 * ## The claim
 *
 * `POST /v1/admin/claim` is the only way to make an administrator. It needs the live setup code
 * ([SetupCode]), a name, and a TOTP seed with a code proving the authenticator holds it. Everything
 * that can be wrong without the code is checked first, so a typo in the name or the six digits
 * keeps the code alive; the code is then spent in one step under its lock, so of any number of
 * requests holding it exactly one makes an administrator. A wrong code locks nobody out: attempts
 * are metered per address by [claimBudget] and nothing else.
 *
 * Refusals name the consequence and never echo what was sent.
 */
fun Route.adminRoutes(
    adminStore: AdminStore,
    setupCode: SetupCode,
    shape: SetupMode,
    adminAudit: AuditStore,
    claimBudget: AttemptBudget,
    signInBudget: AttemptBudget,
    /** True only when the operator started the server asking for a fresh code (`DAYMARK_ADMIN_RESET`). */
    allowClaimWhenClaimed: Boolean,
    sessionIdleSeconds: Long,
    sessionAbsoluteSeconds: Long,
    lockoutFails: Int,
    lockoutSeconds: Long,
    cookieSecure: Boolean,
    auditSourceIp: Boolean,
) {
    get("/v1/admin/status") {
        call.respond(AdminStatusDto(claimed = adminStore.adminCount() > 0, claimOpen = setupCode.isLive()))
    }

    post("/v1/admin/claim") {
        if (!claimBudget.allow(call.clientAddress())) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorDto("too many attempts from here; wait a minute and try again"))
            return@post
        }
        val req = call.receiveCappedJson<AdminClaimRequest>() ?: return@post
        val name = req.name.trim()
        if (!validName(name)) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("the name must be 1 to 64 letters, digits, spaces, dots, dashes or underscores"))
            return@post
        }
        val secret = decodeSeed(req.totpSecret)
        if (secret == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("the authenticator key could not be read; start the claim again"))
            return@post
        }
        if (!setupCode.matches(req.setupCode)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("that setup code does not work; copy the newest one from the server's log"))
            return@post
        }
        if (!allowClaimWhenClaimed && adminStore.adminCount() > 0) {
            call.respond(HttpStatusCode.Conflict, ErrorDto("this server is already claimed; sign in instead"))
            return@post
        }
        if (adminStore.byName(name) != null) {
            call.respond(HttpStatusCode.Conflict, ErrorDto("that name is taken on this server; choose another"))
            return@post
        }
        val step = Totp.verifyStep(secret, req.totpCode.trim(), adminStore.nowMs() / 1000)
        if (step == null) {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorDto("the six digits did not match the authenticator; the setup code still works, so try the next six"),
            )
            return@post
        }
        if (!setupCode.spendIfMatches(req.setupCode)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("that setup code does not work; copy the newest one from the server's log"))
            return@post
        }
        val made = adminStore.claim(name, Base64.getUrlEncoder().withoutPadding().encodeToString(secret), step, allowClaimWhenClaimed)
        if (made.status != AdminStore.ClaimStatus.OK || made.adminId == null) {
            // Only a request racing this one reaches here, and the code is already spent; a restart
            // prints a fresh one while the server is unclaimed.
            call.respond(HttpStatusCode.Conflict, ErrorDto("this server is already claimed; sign in instead"))
            return@post
        }
        claimBudget.reset(call.clientAddress())
        auditSafely {
            adminAudit.append(SERVER_AUDIT_REF, AuditActor.PLATFORM, AuditAction.SERVER_CLAIMED, meta = auditMeta(auditSourceIp, call, "adminId" to made.adminId))
        }
        startSession(call, adminStore, made.adminId, name, sessionAbsoluteSeconds, cookieSecure)
    }

    post("/v1/admin/session") {
        if (!signInBudget.allow(call.clientAddress())) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorDto("too many attempts from here; wait a minute and try again"))
            return@post
        }
        val req = call.receiveCappedJson<AdminSignInRequest>() ?: return@post
        val admin = adminStore.byName(req.name.trim())
        if (admin == null) {
            // The same answer as a wrong code, so a name cannot be tested for.
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("that name and code do not match"))
            return@post
        }
        val now = adminStore.nowMs()
        if (admin.lockedUntil > now) {
            // No audit row: the lockout was recorded once, when it was armed, and a probe bouncing off
            // it writes nothing (the same rule as the clinician's sign-in).
            call.respond(HttpStatusCode.TooManyRequests, ErrorDto("sign-in is paused for this name for a few minutes"))
            return@post
        }
        val seed = decodeSeed(admin.secretB64)
        val step = seed?.let { Totp.verifyStep(it, req.code.trim(), now / 1000) }
        if (step == null || !adminStore.consumeStep(admin.adminId, step)) {
            val locked = adminStore.recordFailure(admin.adminId, lockoutFails, lockoutSeconds * 1000)
            if (locked > now) {
                auditSafely {
                    adminAudit.append(SERVER_AUDIT_REF, AuditActor.PLATFORM, AuditAction.LOCKOUT, meta = auditMeta(auditSourceIp, call, "adminId" to admin.adminId))
                }
                call.respond(HttpStatusCode.TooManyRequests, ErrorDto("sign-in is paused for this name for a few minutes"))
            } else {
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("that name and code do not match"))
            }
            return@post
        }
        adminStore.recordSuccess(admin.adminId)
        signInBudget.reset(call.clientAddress())
        auditSafely {
            adminAudit.append(SERVER_AUDIT_REF, AuditActor.PLATFORM, AuditAction.AUTH_SUCCESS, meta = auditMeta(auditSourceIp, call, "adminId" to admin.adminId))
        }
        startSession(call, adminStore, admin.adminId, admin.name, sessionAbsoluteSeconds, cookieSecure)
    }

    post("/v1/admin/session/logout") {
        requireAdmin(adminStore, sessionIdleSeconds, stateChanging = true) ?: return@post
        call.request.cookies[ADMIN_COOKIE]?.let(adminStore::revokeSession)
        clearCookie(call, cookieSecure)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/v1/admin/overview") {
        val admin = requireAdmin(adminStore, sessionIdleSeconds, stateChanging = false) ?: return@get
        val csrf = call.request.cookies[ADMIN_COOKIE]?.let(adminStore::csrfOf) ?: run {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("sign in as this server's administrator"))
            return@get
        }
        call.respond(AdminOverviewDto(name = admin.name, setupMode = shape.wire, administrators = adminStore.adminCount(), csrfToken = csrf))
    }
}

/**
 * The administrator a request's session belongs to, or null with a 401 already sent. A state-changing
 * request must also carry the session's anti-CSRF token in `X-CSRF-Token`; a missing one is a refusal.
 */
internal suspend fun RoutingContext.requireAdmin(
    adminStore: AdminStore,
    sessionIdleSeconds: Long,
    stateChanging: Boolean,
): AdminStore.AdminRecord? {
    val sessionId = call.request.cookies[ADMIN_COOKIE]
    val csrf = if (stateChanging) call.request.headers["X-CSRF-Token"] else null
    val admin = when {
        sessionId == null -> null
        stateChanging && csrf == null -> null
        else -> adminStore.validateSession(sessionId, sessionIdleSeconds, requireCsrf = csrf)
    }
    if (admin == null) call.respond(HttpStatusCode.Unauthorized, ErrorDto("sign in as this server's administrator"))
    return admin
}

private suspend fun startSession(
    call: ApplicationCall,
    adminStore: AdminStore,
    adminId: String,
    name: String,
    absoluteSeconds: Long,
    cookieSecure: Boolean,
) {
    val session = adminStore.createSession(adminId, absoluteSeconds)
    call.response.cookies.append(
        Cookie(
            name = ADMIN_COOKIE,
            value = session.sessionId,
            encoding = CookieEncoding.RAW,
            httpOnly = true,
            secure = cookieSecure,
            path = "/",
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
    call.respond(HttpStatusCode.OK, AdminSessionDto(name, session.csrfToken, session.absoluteExpiry))
}

private fun clearCookie(call: ApplicationCall, cookieSecure: Boolean) {
    call.response.cookies.append(
        Cookie(
            name = ADMIN_COOKIE,
            value = "",
            encoding = CookieEncoding.RAW,
            maxAge = 0,
            httpOnly = true,
            secure = cookieSecure,
            path = "/",
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}

private val NAME = Regex("""[\p{L}\p{N} ._-]{1,64}""")

internal fun validName(name: String): Boolean = NAME.matches(name)

/** A TOTP seed as base64url or base64, of 16 to 64 bytes (RFC 4226 asks for at least 128 bits), or null. */
internal fun decodeSeed(s: String): ByteArray? {
    val bytes = runCatching { Base64.getUrlDecoder().decode(s.trim()) }.getOrNull()
        ?: runCatching { Base64.getDecoder().decode(s.trim()) }.getOrNull()
        ?: return null
    return bytes.takeIf { it.size in 16..64 }
}
