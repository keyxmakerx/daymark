package com.daymark.companion.routes

import com.daymark.companion.auth.AttemptLimiter
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.clientAddress
import com.daymark.companion.storage.ClinicianNotesStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable data class NotesWritten(val version: Long)
@Serializable data class NotesConflict(val error: String, val current: Long)

/**
 * THE CLINICIAN'S LOCKED NOTEBOOK (COMPANION_THERAPIST.md §9d, DECISIONS.md §D12, #548).
 *
 *   GET /v1/relations/{relRef}/notes   session cookie            → 200 bytes + X-Notes-Version, or 204 when none
 *   PUT /v1/relations/{relRef}/notes   session cookie + CSRF,
 *                                       X-Notes-Base: the version it was based on (0 for the first)
 *                                                                 → 200 {version} | 409 {current} | 413 | 507
 *
 * ONLY THE CLINICIAN. Both routes take the clinician's session and nothing else; an owner credential
 * is not consulted, so the person the notes are about cannot read them or learn that they exist. The
 * session must be bound to the relationship in the path, refused 403 otherwise, as the ending route
 * does. An ended relationship is refused too, although its sessions are already cut.
 *
 * NOTHING ABOUT A NOTEBOOK IS AUDITED OR LOGGED. The access log is the owner's, and a line there per
 * save would tell them how often their clinician writes about them, which is the clinician's affair.
 */
fun Route.clinicianNotesRoutes(
    store: ClinicianNotesStore,
    authStore: AuthStore,
    sessionIdleSeconds: Long,
    therapistLimiter: AttemptLimiter = AttemptLimiter(THERAPIST_MAX_PER_WINDOW, THERAPIST_WINDOW_MS),
) {
    /** The session's relationship, or null after answering. CSRF is required on writes. */
    suspend fun ApplicationCall.clinicianRelRef(requireCsrf: Boolean): String? {
        val sessionId = request.cookies["daymark_session"]
            ?: run { respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); return null }
        val csrf = if (requireCsrf) {
            request.headers["X-CSRF-Token"] ?: run { respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); return null }
        } else {
            null
        }
        val v = authStore.validateSession(sessionId, sessionIdleSeconds, requireCsrf = csrf)
        val session = v.record
        if (v.check != AuthStore.SessionCheck.OK || session == null) {
            respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); return null
        }
        // Metered after the session check, so only a real session can spend the budget.
        if (!therapistLimiter.allow(clientAddress())) {
            respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited")); return null
        }
        val pathRelRef = parameters["relRef"]
            ?: run { respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef")); return null }
        if (!Secrets.constantTimeEquals(session.relRef, pathRelRef)) {
            respond(HttpStatusCode.Forbidden, ErrorDto("session does not cover this relationship")); return null
        }
        if (authStore.relationshipEnding(session.relRef) != null) {
            respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); return null
        }
        return session.relRef
    }

    route("/v1/relations/{relRef}/notes") {
        get {
            val relRef = call.clinicianRelRef(requireCsrf = false) ?: return@get
            val stored = store.read(relRef)
            call.response.header("Cache-Control", "no-store")
            if (stored == null) {
                call.response.header("X-Notes-Version", "0")
                return@get call.respond(HttpStatusCode.NoContent)
            }
            call.response.header("X-Notes-Version", stored.version.toString())
            call.respondBytes(stored.bytes, ContentType.Application.OctetStream)
        }

        put {
            val relRef = call.clinicianRelRef(requireCsrf = true) ?: return@put
            val basedOn = call.request.headers["X-Notes-Base"]?.toLongOrNull()?.takeIf { it >= 0 }
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorDto("missing or invalid X-Notes-Base"))
            val bytes = call.readBodyCapped(store.maxBytes) ?: return@put
            if (bytes.isEmpty()) return@put call.respond(HttpStatusCode.BadRequest, ErrorDto("empty notebook"))
            when (val out = store.write(relRef, basedOn, bytes)) {
                is ClinicianNotesStore.Outcome.Written -> call.respond(HttpStatusCode.OK, NotesWritten(out.version))
                is ClinicianNotesStore.Outcome.Conflict ->
                    call.respond(HttpStatusCode.Conflict, NotesConflict("the notebook changed since it was read", out.current))
                ClinicianNotesStore.Outcome.TooLarge -> call.respond(HttpStatusCode.PayloadTooLarge, ErrorDto("notebook too large"))
                ClinicianNotesStore.Outcome.DiskFull -> call.respond(HttpStatusCode.InsufficientStorage, ErrorDto("could not store"))
            }
        }
    }
}
