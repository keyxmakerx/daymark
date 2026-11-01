package com.daymark.companion.routes

import com.daymark.companion.auth.AttemptBudget
import com.daymark.companion.auth.CredentialKind
import com.daymark.companion.auth.DeviceSignature
import com.daymark.companion.auth.OWNER_COOKIE
import com.daymark.companion.auth.OwnerAuth
import com.daymark.companion.auth.OwnerPrincipal
import com.daymark.companion.auth.OwnerSignIn
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
        log.warn("owner audit append failed", e)
    }
}

/** Whether the owner's account exists, and so whether the shared token is still a credential. */
@Serializable data class OwnerAccountStatusDto(val exists: Boolean, val tokenAccepted: Boolean)
/**
 * Making the account: a name, a TOTP seed and a code from it, and the owner's recovery public key
 * (#325), the one key that can later replace the sign-in. It is derived from the master the console
 * has just unlocked, so the account cannot be made without it.
 */
@Serializable data class OwnerAccountCreateRequest(val name: String, val totpSecret: String, val totpCode: String, val recoveryPublicKey: String)
@Serializable data class OwnerSignInRequest(val name: String, val code: String)
@Serializable data class OwnerSessionDto(val name: String, val csrfToken: String, val absoluteExpiry: Long)
@Serializable data class OwnerOverviewDto(val name: String, val csrfToken: String)
@Serializable data class CliMintRequest(val label: String)
@Serializable data class CliMintedDto(val credentialId: String, val token: String, val createdAt: Long)
@Serializable data class CliCredentialDto(val credentialId: String, val label: String, val createdAt: Long, val revokedAt: Long? = null)
@Serializable data class CliCredentialList(val credentials: List<CliCredentialDto>)

/** The one answer on an account-only route to any other owner credential: a phone, the CLI, or the token. */
const val ACCOUNT_ONLY_MESSAGE = "sign in to your account in the owner console to do this"

/**
 * The owner's own account (#324): making it, signing in to it, and the command-line writer's
 * credentials it mints.
 *
 * MAKING IT. `POST /v1/owner/account` takes the shared token, once, on an install that predates
 * accounts: a name, a fresh TOTP seed, six digits proving the authenticator holds it, and the public
 * half of the owner's recovery key (OwnerRecoveryRoutes), without which nothing is made. The account
 * takes the owner's one id, so every stored snapshot, key document, relationship and paired phone is
 * already its own and nothing is moved. The insert is the token's retirement: from that transaction
 * on, OwnerAuth refuses the token on every owner route with the same 401 as a wrong one. A second
 * account is refused.
 *
 * SIGNING IN is the administrator's shape (#322): the name and a current code, one answer for every
 * refusal so a stranger learns no name, a lockout after repeated wrong codes with one audit row on
 * arming, and a session that is an HttpOnly, SameSite=Strict cookie with an anti-CSRF token for every
 * request that changes anything. No administrator, staff or clinician session is read here or by
 * OwnerAuth, so none of them reaches an owner route.
 *
 * ACCOUNT-ONLY ROUTES. Minting, listing and revoking CLI credentials, and the session's own reads,
 * take the account's session and nothing else: a phone, a CLI credential and the token are each
 * answered 403 [ACCOUNT_ONLY_MESSAGE]. So a stolen CLI credential cannot mint itself a successor.
 *
 * Nothing here logs a name, a seed, a code, a session id or a CLI token, and no refusal repeats one.
 */
fun Route.ownerAccountRoutes(
    auth: OwnerAuth,
    signIn: OwnerSignIn,
    ownerAudit: AuditStore,
    createBudget: AttemptBudget,
    signInBudget: AttemptBudget,
    sessionAbsoluteSeconds: Long,
    lockoutFails: Int,
    lockoutSeconds: Long,
    cookieSecure: Boolean,
    auditSourceIp: Boolean,
) {
    val clock = signIn::now
    get("/v1/owner/account") {
        val exists = signIn.exists()
        call.respond(OwnerAccountStatusDto(exists = exists, tokenAccepted = !exists))
    }

    post("/v1/owner/account") {
        if (!createBudget.allow(call.clientAddress())) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorDto("too many attempts from here; wait a minute and try again"))
            return@post
        }
        val principal = call.owner(auth) ?: return@post
        if (principal.kind != CredentialKind.TOKEN) {
            // Only the token makes the account; any other owner credential means it is already made,
            // or is a phone, which may not.
            call.respond(HttpStatusCode.Conflict, ErrorDto("this server's owner account is already made; sign in instead"))
            return@post
        }
        val req = call.receiveCappedJson<OwnerAccountCreateRequest>() ?: return@post
        val name = req.name.trim()
        if (!validName(name)) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("the name must be 1 to 64 letters, digits, spaces, dots, dashes or underscores"))
            return@post
        }
        val secret = decodeSeed(req.totpSecret)
        if (secret == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("the authenticator key could not be read; start again"))
            return@post
        }
        val recoveryKey = DeviceSignature.decodeCanonical(req.recoveryPublicKey, DeviceSignature.PUBLIC_KEY_BYTES)
        if (recoveryKey == null || !DeviceSignature.isUsablePublicKey(recoveryKey)) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("the recovery key could not be read; unlock the console again and start again"))
            return@post
        }
        val step = Totp.verifyStep(secret, req.totpCode.trim(), clock() / 1000)
        if (step == null) {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorDto("the six digits did not match the authenticator; nothing was made, so try the next six"),
            )
            return@post
        }
        val made = signIn.create(name, Base64.getUrlEncoder().withoutPadding().encodeToString(secret), step, req.recoveryPublicKey)
        if (made != OwnerSignIn.CreateStatus.OK) {
            call.respond(HttpStatusCode.Conflict, ErrorDto("this server's owner account is already made; sign in instead"))
            return@post
        }
        createBudget.reset(call.clientAddress())
        auditSafely {
            ownerAudit.append(
                signIn.ownerId, AuditActor.OWNER, AuditAction.OWNER_ACCOUNT_CREATED,
                meta = auditMeta(auditSourceIp, call, "credential" to CredentialKind.TOKEN.wire),
            )
        }
        startOwnerSession(call, signIn, name, sessionAbsoluteSeconds, cookieSecure)
    }

    post("/v1/owner/session") {
        if (!signInBudget.allow(call.clientAddress())) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorDto("too many attempts from here; wait a minute and try again"))
            return@post
        }
        val req = call.receiveCappedJson<OwnerSignInRequest>() ?: return@post
        val account = signIn.byName(req.name.trim())
        val now = clock()
        if (account == null) {
            // The same work and the same answer as a wrong code, so a name cannot be tested for.
            Totp.verifyStep(UNKNOWN_OWNER_SEED, req.code.trim(), now / 1000)
            call.respond(HttpStatusCode.Unauthorized, ErrorDto(OWNER_NO_MATCH))
            return@post
        }
        if (account.lockedUntil > now) {
            // No audit row: the lockout was recorded once, when it was armed.
            call.respond(HttpStatusCode.Unauthorized, ErrorDto(OWNER_NO_MATCH))
            return@post
        }
        val seed = decodeSeed(account.secretB64)
        val step = seed?.let { Totp.verifyStep(it, req.code.trim(), now / 1000) }
        if (step == null || !signIn.consumeStep(step)) {
            val locked = signIn.recordFailure(lockoutFails, lockoutSeconds * 1000)
            if (locked > now) {
                auditSafely {
                    ownerAudit.append(
                        signIn.ownerId, AuditActor.OWNER, AuditAction.LOCKOUT,
                        meta = auditMeta(auditSourceIp, call, "credential" to CredentialKind.ACCOUNT.wire),
                    )
                }
            }
            call.respond(HttpStatusCode.Unauthorized, ErrorDto(OWNER_NO_MATCH))
            return@post
        }
        signIn.recordSuccess()
        signInBudget.reset(call.clientAddress())
        auditSafely {
            ownerAudit.append(
                signIn.ownerId, AuditActor.OWNER, AuditAction.AUTH_SUCCESS,
                meta = auditMeta(auditSourceIp, call, "credential" to CredentialKind.ACCOUNT.wire),
            )
        }
        startOwnerSession(call, signIn, account.name, sessionAbsoluteSeconds, cookieSecure)
    }

    get("/v1/owner/session") {
        call.accountOnly(auth) ?: return@get
        val account = signIn.account()
        val csrf = call.request.cookies[OWNER_COOKIE]?.let(signIn::csrfOf)
        if (account == null || csrf == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto(UNAUTHORIZED_MESSAGE))
            return@get
        }
        call.respond(OwnerOverviewDto(account.name, csrf))
    }

    post("/v1/owner/session/logout") {
        call.accountOnly(auth) ?: return@post
        call.request.cookies[OWNER_COOKIE]?.let(signIn::revokeSession)
        clearOwnerCookie(call, cookieSecure)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/v1/owner/cli") {
        call.accountOnly(auth) ?: return@post
        val req = call.receiveCappedJson<CliMintRequest>() ?: return@post
        val label = req.label.trim()
        if (!validName(label)) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("the label must be 1 to 64 letters, digits, spaces, dots, dashes or underscores"))
            return@post
        }
        val minted = signIn.mintCli(label)
        auditSafely {
            ownerAudit.append(signIn.ownerId, AuditActor.OWNER, AuditAction.CLI_CREDENTIAL_ISSUED, objectRef = minted.credentialId, meta = auditMeta(auditSourceIp, call).ifEmpty { null })
        }
        call.respond(HttpStatusCode.Created, CliMintedDto(minted.credentialId, minted.token, minted.createdAt))
    }

    get("/v1/owner/cli") {
        call.accountOnly(auth) ?: return@get
        call.respond(CliCredentialList(signIn.listCli().map { CliCredentialDto(it.credentialId, it.label, it.createdAt, it.revokedAt) }))
    }

    post("/v1/owner/cli/{credentialId}/revoke") {
        call.accountOnly(auth) ?: return@post
        val id = call.parameters["credentialId"].orEmpty()
        if (!signIn.revokeCli(id)) {
            call.respond(HttpStatusCode.NotFound, ErrorDto("no such command-line credential"))
            return@post
        }
        auditSafely {
            ownerAudit.append(signIn.ownerId, AuditActor.OWNER, AuditAction.CLI_CREDENTIAL_REVOKED, objectRef = id, meta = auditMeta(auditSourceIp, call).ifEmpty { null })
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/** The owner behind an account-only route's request, or null once it has been answered (401, or 403 [ACCOUNT_ONLY_MESSAGE]). */
private suspend fun ApplicationCall.accountOnly(auth: OwnerAuth): OwnerPrincipal? {
    val principal = owner(auth) ?: return null
    if (principal.kind == CredentialKind.ACCOUNT) return principal
    respond(HttpStatusCode.Forbidden, ErrorDto(ACCOUNT_ONLY_MESSAGE))
    return null
}

internal suspend fun startOwnerSession(call: ApplicationCall, signIn: OwnerSignIn, name: String, absoluteSeconds: Long, cookieSecure: Boolean) {
    val session = signIn.createSession(absoluteSeconds)
    call.response.cookies.append(
        Cookie(
            name = OWNER_COOKIE,
            value = session.sessionId,
            encoding = CookieEncoding.RAW,
            httpOnly = true,
            secure = cookieSecure,
            path = "/",
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
    call.respond(HttpStatusCode.OK, OwnerSessionDto(name, session.csrfToken, session.absoluteExpiry))
}

private fun clearOwnerCookie(call: ApplicationCall, cookieSecure: Boolean) {
    call.response.cookies.append(
        Cookie(
            name = OWNER_COOKIE,
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

/**
 * Every refused owner sign-in says this, whatever the cause: an unknown name, a wrong or replayed
 * code, or a name paused after repeated wrong codes.
 */
internal const val OWNER_NO_MATCH = "that name and code do not match; after several wrong codes a name is paused for a few minutes"

/** Verified against when the name is unknown, so that path does the same work as a known one. */
private val UNKNOWN_OWNER_SEED = ByteArray(20)
