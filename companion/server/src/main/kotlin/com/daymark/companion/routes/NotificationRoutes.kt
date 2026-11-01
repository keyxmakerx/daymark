package com.daymark.companion.routes

import com.daymark.companion.auth.OwnerAuth
import com.daymark.companion.mail.MailMessage
import com.daymark.companion.mail.OwnerAccountStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable data class NotificationSettingsDto(val email: String?, val events: List<String>)
@Serializable data class SetNotificationSettingsRequest(val email: String? = null, val events: List<String> = emptyList())

/**
 * The owner's notification email: the address notices go to, and which notices it wants
 * (COMPANION_SECURITY.md §6, "Owner notifications"). Owner-authenticated, like every other owner
 * write path. The address never gets anyone back in: the emailed access-token reset is gone, and
 * getting back in proves the owner's own key (OwnerRecoveryRoutes, #325).
 */
fun Route.notificationRoutes(
    accountStore: OwnerAccountStore,
    ownerGuard: OwnerAuth,
) {
    route("/v1") {
        get("/owner/notifications") {
            if (!call.ownerAuthorizedForNotifications(ownerGuard)) return@get
            val s = accountStore.getNotificationSettings()
            call.respond(NotificationSettingsDto(s.email, s.events.map { it.name }))
        }

        put("/owner/notifications") {
            if (!call.ownerAuthorizedForNotifications(ownerGuard)) return@put
            val req = call.receiveCappedJson<SetNotificationSettingsRequest>() ?: return@put
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
    }
}

/** The owner gate every owner route shares ([ownerAuthorized]). */
private suspend fun ApplicationCall.ownerAuthorizedForNotifications(guard: OwnerAuth): Boolean = ownerAuthorized(guard)

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
