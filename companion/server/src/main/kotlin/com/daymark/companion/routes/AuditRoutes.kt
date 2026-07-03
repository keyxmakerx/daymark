package com.daymark.companion.routes

import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.auth.Secrets
import com.daymark.companion.storage.AuditEvent
import com.daymark.companion.storage.AuditStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class AuditEventDto(
    val seq: Long,
    val ts: Long,
    val actor: String,
    val action: String,
    val objectRef: String? = null,
    val meta: Map<String, String>? = null,
    val entryHash: String,
)

@Serializable
data class AuditLogPage(val events: List<AuditEventDto>, val nextCursor: Long? = null)

private fun AuditEvent.toDto() = AuditEventDto(seq, ts, actor, action, objectRef, meta, entryHash)

/**
 * Owner-ONLY read path for the audit log (COMPANION_SECURITY.md §9). Therapists cannot read or
 * write this directly — every entry is appended server-side from the real access paths
 * (RelationRoutes, TherapistAuthRoutes), never client-supplied. Mirrors the same two-factor gate
 * as the /v1/rel/{relRef}/{channel} API: the caller must both hold the relationship's inbox
 * token (X-Rel-Token, hashed to relRef) AND present the owner bearer token.
 */
fun Route.auditRoutes(store: AuditStore, ownerGuard: AuthGuard) {
    route("/v1/rel/{relRef}/audit") {
        get {
            val pathRelRef = call.parameters["relRef"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            val inboxToken = call.request.headers["X-Rel-Token"]?.trim()?.ifBlank { null }
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorDto("missing relationship token"))
            if (!Secrets.constantTimeEquals(Secrets.relRefOf(inboxToken), pathRelRef)) {
                return@get call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
            }
            if (!call.ownerAuditAuthorized(ownerGuard)) return@get

            val before = call.request.queryParameters["before"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val cap = limit.coerceIn(1, AuditStore.MAX_PAGE_SIZE)
            val events = store.list(pathRelRef, before, cap)
            val nextCursor = if (events.size >= cap) events.lastOrNull()?.seq else null
            call.respond(AuditLogPage(events.map { it.toDto() }, nextCursor))
        }
    }
}

/** Owner-token gate, matching TherapistAuthRoutes.ownerAuthorized exactly. */
private suspend fun ApplicationCall.ownerAuditAuthorized(guard: AuthGuard): Boolean {
    val sourceId = request.origin.remoteAddress
    val presented = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    return when (guard.authorize(sourceId, presented)) {
        AuthGuard.Result.OK -> true
        AuthGuard.Result.RATE_LIMITED -> { respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited")); false }
        AuthGuard.Result.LOCKED -> { respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked")); false }
        AuthGuard.Result.BAD_TOKEN -> { respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); false }
    }
}
