package com.daymark.companion.routes

import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.mail.MailMessage
import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.OwnerAccountStore
import com.daymark.companion.mail.ReissueConfirmOutcome
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant

@Serializable data class NotificationSettingsDto(val email: String?, val events: List<String>)
@Serializable data class SetNotificationSettingsRequest(val email: String? = null, val events: List<String> = emptyList())
@Serializable data class RecoveryRequestBody(val email: String)
@Serializable data class RecoveryConfirmBody(val confirmToken: String)
@Serializable data class RecoveryConfirmResponse(val newToken: String)

private val log = LoggerFactory.getLogger("com.daymark.companion.routes.RecoveryRoutes")

/**
 * Track T2 (email Option A, per the Companion coordination plan): owner notification-email
 * registration and the unauthenticated access-token recovery flow. This recovers *server access
 * only* — the server is zero-knowledge and can never reset the PIN or E2EE passphrase; see
 * COMPANION_SECURITY.md.
 *
 * `/v1/owner/notifications` is owner-bearer-token-authenticated, matching every other owner
 * write path. The `/v1/recovery` routes are deliberately unauthenticated (that is the point of a
 * recovery path) but heavily rate-limited and always-same-response, so they cannot be used to
 * probe which email address is registered. Sending the actual email is dispatched onto the
 * application's own background scope (never awaited inline) so response latency never differs
 * between a match and a non-match — see [buildRecoveryLink] and the request handler below.
 */
fun Route.recoveryRoutes(
    accountStore: OwnerAccountStore,
    ownerGuard: AuthGuard,
    mailer: Mailer,
    confirmTtlSeconds: Long,
    reissueMaxPerHour: Int,
    publicBaseUrl: String?,
) {
    route("/v1") {
        get("/owner/notifications") {
            if (!call.ownerAuthorizedForRecovery(ownerGuard)) return@get
            val s = accountStore.getNotificationSettings()
            call.respond(NotificationSettingsDto(s.email, s.events.map { it.name }))
        }

        put("/owner/notifications") {
            if (!call.ownerAuthorizedForRecovery(ownerGuard)) return@put
            val req = call.receive<SetNotificationSettingsRequest>()
            val email = req.email?.trim()?.ifBlank { null }
            if (email != null && !isPlausibleEmail(email)) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid email"))
                return@put
            }
            val events = req.events.mapNotNull { wire ->
                MailMessage.ReviewKind.entries.firstOrNull { it.name == wire }
            }.toSet()
            accountStore.setNotificationSettings(email, events)
            call.respond(HttpStatusCode.NoContent)
        }

        // Unauthenticated by design (this IS the recovery path). Always the same response
        // regardless of whether the email matches, whether it was rate-limited, or whether a
        // link was actually mintable — non-enumerating. The mail send (a real, network-bound
        // SMTP round-trip only on a match) is dispatched to the application's background scope
        // rather than awaited, so response TIMING cannot leak a match either — a status-code-only
        // non-enumeration guarantee is not enough when one branch does real I/O and the other
        // doesn't.
        post("/recovery/request") {
            call.response.header("Referrer-Policy", "no-referrer")
            val req = call.receive<RecoveryRequestBody>()
            val sourceId = call.request.origin.remoteAddress
            if (accountStore.allowReissueAttempt(sourceId, reissueMaxPerHour)) {
                val minted = accountStore.requestReissue(req.email.trim(), confirmTtlSeconds)
                if (minted != null) {
                    val link = buildRecoveryLink(publicBaseUrl, minted.confirmToken)
                    if (link != null) {
                        val app = call.application
                        app.launch(Dispatchers.IO) {
                            runCatching {
                                mailer.send(MailMessage.AccessRecovery(minted.email, URI(link), Instant.ofEpochMilli(minted.expiresAt)))
                            }.onFailure { log.warn("recovery email failed to send: {}", it.javaClass.simpleName) }
                        }
                    } else {
                        log.warn(
                            "access-token recovery was requested but no public base URL is configured " +
                                "(DAYMARK_PUBLIC_BASE_URL / DAYMARK_WEBAUTHN_ORIGINS) — refusing to build a " +
                                "recovery link from the request's Host header and skipping the send",
                        )
                    }
                }
            }
            call.respond(HttpStatusCode.Accepted)
        }

        post("/recovery/confirm") {
            call.response.header("Referrer-Policy", "no-referrer")
            val req = call.receive<RecoveryConfirmBody>()
            // The rotation is applied to the live guard from *inside* confirmReissue's own lock,
            // atomically with persisting it — see OwnerAccountStore.confirmReissue's kdoc for why
            // applying it out here (after the lock is released) would race two concurrent confirms.
            when (val result = accountStore.confirmReissue(req.confirmToken) { newToken -> ownerGuard.rotate(newToken) }) {
                is ReissueConfirmOutcome.Rotated -> {
                    // Best-effort receipt; dispatched in the background so a slow SMTP server
                    // never delays handing the owner their new token.
                    accountStore.registeredEmail()?.let { addr ->
                        val app = call.application
                        app.launch(Dispatchers.IO) {
                            runCatching { mailer.send(MailMessage.SecurityNotice(addr, MailMessage.SecurityEvent.TOKEN_REISSUED)) }
                                .onFailure { log.warn("token-reissued receipt failed to send: {}", it.javaClass.simpleName) }
                        }
                    }
                    call.respond(HttpStatusCode.OK, RecoveryConfirmResponse(result.newToken))
                }
                ReissueConfirmOutcome.Gone -> call.respond(HttpStatusCode.Gone, ErrorDto("recovery link unavailable"))
            }
        }
    }
}

/** Owner-token gate, matching the convention in TherapistAuthRoutes.kt / SyncRoutes.kt. */
private suspend fun ApplicationCall.ownerAuthorizedForRecovery(guard: AuthGuard): Boolean {
    val sourceId = request.origin.remoteAddress
    val presented = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    return when (guard.authorize(sourceId, presented)) {
        AuthGuard.Result.OK -> true
        AuthGuard.Result.RATE_LIMITED -> { respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited")); false }
        AuthGuard.Result.LOCKED -> { respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked")); false }
        AuthGuard.Result.BAD_TOKEN -> { respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); false }
    }
}

/**
 * Structural-only plausibility check (one `@`, something on both sides, no whitespace or control
 * characters) — not full RFC 5322. Control characters are rejected specifically because this
 * value is later used as a literal SMTP recipient address.
 */
private fun isPlausibleEmail(email: String): Boolean {
    val at = email.indexOf('@')
    if (at <= 0 || at >= email.length - 1 || email.indexOf('@', at + 1) != -1) return false
    return email.none { it.isWhitespace() || it.isISOControl() }
}

/**
 * Build the recovery confirmation link, using ONLY the configured [publicBaseUrl] — never the
 * request's `Host` header. Unlike the owner-authenticated invite/notification links elsewhere in
 * this package (see `resolveBaseUrl`), this endpoint is reachable by anyone with no credential at
 * all, so trusting a client-controllable header here would let an attacker point a real,
 * single-use recovery token at a domain they control. Returns null (skip sending) if unconfigured
 * rather than guess.
 */
private fun buildRecoveryLink(publicBaseUrl: String?, confirmToken: String): String? =
    publicBaseUrl?.trimEnd('/')?.let { "$it/recover#t=$confirmToken" }
