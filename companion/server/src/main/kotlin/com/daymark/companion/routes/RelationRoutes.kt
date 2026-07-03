package com.daymark.companion.routes

import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.mail.MailMessage
import com.daymark.companion.mail.OwnerNotifier
import com.daymark.companion.storage.Channel
import com.daymark.companion.storage.RelMeta
import com.daymark.companion.storage.RelationStore
import com.daymark.companion.storage.RelationStoreException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable data class RelMetaDto(val version: Long, val size: Long, val contentHash: String, val settingKey: String? = null, val createdAt: Long)
@Serializable data class RelVersionList(val lineage: String, val versions: List<RelMetaDto>)
@Serializable data class RelLineageList(val lineages: List<String>)
@Serializable data class RelPutResult(val relRef: String, val channel: String, val lineage: String, val version: Long, val size: Long, val contentHash: String)

private fun RelMeta.toDto() = RelMetaDto(version, size, contentHash, settingKey, createdAt)

/** Who is presenting a request, resolved from the presented credential. */
enum class Role { OWNER, THERAPIST }

/**
 * The /v1/rel/{relRef}/{channel} zero-knowledge relationship-blob API. Every blob is opaque
 * ciphertext the server cannot read; routing is by an opaque per-relationship inbox token
 * (X-Rel-Token, BLAKE2b-hashed to relRef), NEVER a fingerprint.
 *
 * Direction enforcement is by authenticated ROLE (COMPANION_THERAPIST.md channel table):
 *   GRANT   owner-PUT   / therapist-GET
 *   SHARE   owner-PUT   / therapist-GET
 *   ASSIGN  therapist-PUT / owner-GET
 *   GAMEPLAN therapist-PUT / owner-GET
 *
 * Transitional-state note (see spec risks): OWNER role = a valid owner bearer token; THERAPIST
 * role = a valid therapist session cookie bound to this relRef.
 */
fun Route.relationRoutes(
    store: RelationStore,
    ownerGuard: AuthGuard,
    authStore: AuthStore,
    sessionIdleSeconds: Long,
    maxRequestBytes: Long,
    notifier: OwnerNotifier,
    publicBaseUrl: String?,
) {
    route("/v1/rel/{relRef}/{channel}") {

        // List lineages in a channel (either counterparty may read).
        get {
            val ctx = resolve(store, ownerGuard, authStore, sessionIdleSeconds) ?: return@get
            call.respond(RelLineageList(store.listLineages(ctx.relRef, ctx.channel)))
        }

        // List versions of a lineage (metadata only).
        get("/{lineage}") {
            val ctx = resolve(store, ownerGuard, authStore, sessionIdleSeconds) ?: return@get
            val lineage = call.parameters["lineage"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            if (lineage == "current") return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            try {
                call.respond(RelVersionList(lineage, store.listVersions(ctx.relRef, ctx.channel, lineage).map { it.toDto() }))
            } catch (e: RelationStoreException) {
                call.failRel(e)
            }
        }

        // Fetch the highest version of a lineage (the counterparty read path).
        get("/{lineage}/current") {
            val ctx = resolve(store, ownerGuard, authStore, sessionIdleSeconds) ?: return@get
            val lineage = call.parameters["lineage"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            try {
                val (version, bytes) = store.fetchCurrent(ctx.relRef, ctx.channel, lineage)
                call.response.header("X-Content-Hash", RelationStore.sha256HexPublic(bytes))
                call.response.header("X-Version", version.toString())
                call.respondBytes(bytes, ContentType.Application.OctetStream)
            } catch (e: RelationStoreException) {
                call.failRel(e)
            }
        }

        // Fetch one blob.
        get("/{lineage}/{version}") {
            val ctx = resolve(store, ownerGuard, authStore, sessionIdleSeconds) ?: return@get
            val lineage = call.parameters["lineage"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            val version = call.parameters["version"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("version must be an integer"))
            try {
                val bytes = store.fetch(ctx.relRef, ctx.channel, lineage, version)
                call.response.header("X-Content-Hash", RelationStore.sha256HexPublic(bytes))
                call.respondBytes(bytes, ContentType.Application.OctetStream)
            } catch (e: RelationStoreException) {
                call.failRel(e)
            }
        }

        // Append a blob. Direction-enforced per channel. State-changing, so a THERAPIST (cookie)
        // writer MUST also present a matching X-CSRF-Token — a session cookie alone is not enough
        // (defends assignments/gameplans PUTs against cross-site forgery, matching /session/logout).
        put("/{lineage}/{version}") {
            val ctx = resolve(store, ownerGuard, authStore, sessionIdleSeconds, requireCsrf = true) ?: return@put
            val requiredRole = writerRole(ctx.channel)
            if (ctx.role != requiredRole) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("wrong direction for this channel"))
                return@put
            }
            val lineage = call.parameters["lineage"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            val version = call.parameters["version"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorDto("version must be an integer"))
            // Non-secret routing tag; only meaningful for setting-type assignments. Validated
            // structurally by the store BEFORE the (opaque) body is read/stored.
            val settingKey = call.request.headers["X-Setting-Key"]?.trim()?.ifBlank { null }
            val body = call.readCappedRel(maxRequestBytes) ?: return@put
            try {
                val meta = store.put(ctx.relRef, ctx.channel, lineage, version, body, settingKey)
                call.response.header("X-Content-Hash", meta.contentHash)
                call.respond(
                    HttpStatusCode.Created,
                    RelPutResult(ctx.relRef, ctx.channel.wire, lineage, meta.version, meta.size, meta.contentHash),
                )
                // Best-effort "new item to review" notice — only for the therapist-writes-owner-reads
                // direction (writerRole already enforced this is THERAPIST for these two channels).
                reviewKindFor(ctx.channel)?.let { kind -> notifier.notify(kind, portalUrl(call, publicBaseUrl)) }
            } catch (e: RelationStoreException) {
                call.failRel(e)
            }
        }
    }
}

private fun writerRole(channel: Channel): Role = when (channel) {
    Channel.GRANTS, Channel.SHARES -> Role.OWNER
    Channel.ASSIGNMENTS, Channel.GAMEPLANS -> Role.THERAPIST
}

/** Which owner-facing notification (if any) a successful therapist PUT to this channel triggers. */
private fun reviewKindFor(channel: Channel): MailMessage.ReviewKind? = when (channel) {
    Channel.ASSIGNMENTS -> MailMessage.ReviewKind.NEW_ASSIGNMENT
    Channel.GAMEPLANS -> MailMessage.ReviewKind.NEW_GAMEPLAN
    Channel.GRANTS, Channel.SHARES -> null
}

/** Best-effort absolute URL to the owner console root, for "something to review" notifications. */
private fun portalUrl(call: ApplicationCall, publicBaseUrl: String?): java.net.URI {
    val base = publicBaseUrl?.trimEnd('/') ?: run {
        val scheme = call.request.origin.scheme
        val host = call.request.headers[HttpHeaders.Host] ?: "${call.request.origin.serverHost}:${call.request.origin.serverPort}"
        "$scheme://$host"
    }
    return java.net.URI("$base/")
}

private data class RelContext(val relRef: String, val channel: Channel, val role: Role)

/**
 * Resolve the channel, the relRef (from X-Rel-Token, hashed), and the caller's role. Responds
 * and returns null on any failure so the caller can `return@get` cleanly.
 */
private suspend fun io.ktor.server.routing.RoutingContext.resolve(
    store: RelationStore,
    ownerGuard: AuthGuard,
    authStore: AuthStore,
    sessionIdleSeconds: Long,
    requireCsrf: Boolean = false,
): RelContext? {
    val channelWire = call.parameters["channel"] ?: run {
        call.respond(HttpStatusCode.BadRequest, ErrorDto("missing channel")); return null
    }
    val channel = Channel.fromWire(channelWire) ?: run {
        call.respond(HttpStatusCode.NotFound, ErrorDto("unknown channel")); return null
    }

    val inboxToken = call.request.headers["X-Rel-Token"]?.trim()?.ifBlank { null } ?: run {
        call.respond(HttpStatusCode.Unauthorized, ErrorDto("missing relationship token")); return null
    }
    val pathRelRef = call.parameters["relRef"] ?: run {
        call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef")); return null
    }
    // The presented raw token must hash to the relRef in the path — otherwise the caller does
    // not hold the inbox token for this relationship.
    val computed = Secrets.relRefOf(inboxToken)
    if (!Secrets.constantTimeEquals(computed, pathRelRef)) {
        call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); return null
    }

    // Determine role. Prefer an owner bearer token; else a therapist session cookie bound here.
    // For state-changing therapist calls (requireCsrf), a missing/mismatched X-CSRF-Token is a
    // rejection, not a bypass — the cookie alone must not authorize a write.
    val role = resolveRole(call, ownerGuard, authStore, sessionIdleSeconds, pathRelRef, requireCsrf) ?: run {
        call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); return null
    }
    return RelContext(pathRelRef, channel, role)
}

private fun resolveRole(
    call: ApplicationCall,
    ownerGuard: AuthGuard,
    authStore: AuthStore,
    sessionIdleSeconds: Long,
    relRef: String,
    requireCsrf: Boolean,
): Role? {
    val sourceId = call.request.origin.remoteAddress
    val bearer = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    if (bearer != null && ownerGuard.authorize(sourceId, bearer) == AuthGuard.Result.OK) {
        return Role.OWNER
    }
    val sessionId = call.request.cookies["daymark_session"]
    if (sessionId != null) {
        // On a CSRF-required (write) path, the header MUST be present; a null header must never
        // validate against a null stored token, so absence is an immediate reject.
        val csrf = if (requireCsrf) {
            call.request.headers["X-CSRF-Token"] ?: return null
        } else {
            null
        }
        val v = authStore.validateSession(sessionId, sessionIdleSeconds, requireCsrf = csrf)
        if (v.check == AuthStore.SessionCheck.OK && v.record?.relRef == relRef) return Role.THERAPIST
    }
    return null
}

private suspend fun ApplicationCall.readCappedRel(max: Long): ByteArray? {
    val stream = receiveStream()
    val buf = ByteArray(64 * 1024)
    val out = java.io.ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        total += n
        if (total > max) {
            respond(HttpStatusCode.PayloadTooLarge, ErrorDto("request body too large"))
            return null
        }
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private suspend fun ApplicationCall.failRel(e: RelationStoreException) {
    val (status, message) = when (e.kind) {
        RelationStoreException.Kind.BAD_NAME -> HttpStatusCode.BadRequest to "invalid request"
        RelationStoreException.Kind.CONFLICT -> HttpStatusCode.Conflict to "version already exists"
        RelationStoreException.Kind.TOO_OLD -> HttpStatusCode.Conflict to "version below retention window"
        RelationStoreException.Kind.TOO_LARGE -> HttpStatusCode.PayloadTooLarge to "payload too large"
        RelationStoreException.Kind.QUOTA -> HttpStatusCode.InsufficientStorage to "insufficient storage"
        RelationStoreException.Kind.DISK_FULL -> HttpStatusCode.InsufficientStorage to "insufficient storage"
        RelationStoreException.Kind.NOT_FOUND -> HttpStatusCode.NotFound to "not found"
        RelationStoreException.Kind.SETTING_KEY_NOT_ALLOWED -> HttpStatusCode.UnprocessableEntity to "setting key not allowlisted"
    }
    respond(status, ErrorDto(message))
}
