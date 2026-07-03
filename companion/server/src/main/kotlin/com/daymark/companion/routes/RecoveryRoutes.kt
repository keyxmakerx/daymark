package com.daymark.companion.routes

import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.mail.MailMessage
import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.OwnerAccountStore
import com.daymark.companion.mail.ReissueConfirmOutcome
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Instant

@Serializable data class NotificationSettingsDto(val email: String?, val events: List<String>)
@Serializable data class SetNotificationSettingsRequest(val email: String? = null, val events: List<String> = emptyList())
@Serializable data class RecoveryRequestBody(val email: String)
@Serializable data class RecoveryConfirmBody(val confirmToken: String)
@Serializable data class RecoveryConfirmResponse(val newToken: String)

/**
 * Track T2 (COMPANION_PLAN.md, email Option A): owner notification-email registration and the
 * unauthenticated access-token recovery flow. This recovers *server access only* — the server is
 * zero-knowledge and can never reset the PIN or E2EE passphrase; see COMPANION_SECURITY.md.
 *
 * `/v1/owner/notifications` is owner-bearer-token-authenticated, matching every other owner
 * write path. The `/v1/recovery` routes are deliberately unauthenticated (that is the point of a
 * recovery path) but heavily rate-limited and always-same-response, so they cannot be used to
 * probe which email address is registered.
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
        // regardless of whether the email matches — non-enumerating.
        post("/recovery/request") {
            call.response.header("Referrer-Policy", "no-referrer")
            val req = call.receive<RecoveryRequestBody>()
            val sourceId = call.request.origin.remoteAddress
            if (accountStore.allowReissueAttempt(sourceId, reissueMaxPerHour)) {
                val minted = accountStore.requestReissue(req.email.trim(), confirmTtlSeconds)
                if (minted != null) {
                    val link = buildRecoveryLink(call, publicBaseUrl, minted.confirmToken)
                    runCatching {
                        mailer.send(MailMessage.AccessRecovery(minted.email, URI(link), Instant.ofEpochMilli(minted.expiresAt)))
                    }
                }
            }
            call.respond(HttpStatusCode.Accepted)
        }

        post("/recovery/confirm") {
            call.response.header("Referrer-Policy", "no-referrer")
            val req = call.receive<RecoveryConfirmBody>()
            when (val result = accountStore.confirmReissue(req.confirmToken)) {
                is ReissueConfirmOutcome.Rotated -> {
                    ownerGuard.rotate(result.newToken)
                    // Best-effort receipt; never gates the response — the rotation already happened.
                    accountStore.getNotificationSettings().email?.let { addr ->
                        runCatching { mailer.send(MailMessage.SecurityNotice(addr, MailMessage.SecurityEvent.TOKEN_REISSUED)) }
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

/** Structural-only plausibility check (one '@', something on both sides) — not full RFC 5322. */
private fun isPlausibleEmail(email: String): Boolean {
    val at = email.indexOf('@')
    return at > 0 && at < email.length - 1 && email.indexOf('@', at + 1) == -1 && !email.contains(' ')
}

private fun buildRecoveryLink(call: ApplicationCall, publicBaseUrl: String?, confirmToken: String): String {
    val base = publicBaseUrl?.trimEnd('/') ?: run {
        val scheme = call.request.origin.scheme
        val host = call.request.headers[HttpHeaders.Host] ?: "${call.request.origin.serverHost}:${call.request.origin.serverPort}"
        "$scheme://$host"
    }
    return "$base/recover#t=$confirmToken"
}
