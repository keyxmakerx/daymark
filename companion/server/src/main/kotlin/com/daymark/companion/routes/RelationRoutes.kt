package com.daymark.companion.routes

import com.daymark.companion.clientAddress
import com.daymark.companion.auth.AttemptLimiter
import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.mail.MailMessage
import com.daymark.companion.mail.OwnerNotifier
import com.daymark.companion.storage.AuditActor
import com.daymark.companion.storage.AuditAction
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.Channel
import com.daymark.companion.storage.RelMeta
import com.daymark.companion.storage.RelationStore
import com.daymark.companion.storage.RelationStoreException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable data class RelMetaDto(val version: Long, val size: Long, val contentHash: String, val settingKey: String? = null, val createdAt: Long)
@Serializable data class RelVersionList(val lineage: String, val versions: List<RelMetaDto>)
@Serializable data class RelLineageList(val lineages: List<String>)
/**
 * How many versions the withdrawal marked.
 *
 * Note what is NOT here, and in RelMetaDto either: `expiry` and `revoked`. Both would be readable
 * by the therapist, because the version-list route is open to either counterparty — which would
 * hand the restricted party the exact distinction the 410 exists to withhold. The owner learns the
 * state from their own audit log.
 */
@Serializable data class RelRevokeResult(val lineage: String, val revokedVersions: Int)
@Serializable data class RelPutResult(val relRef: String, val channel: String, val lineage: String, val version: Long, val size: Long, val contentHash: String)

private fun RelMeta.toDto() = RelMetaDto(version, size, contentHash, settingKey, createdAt)

private val log = LoggerFactory.getLogger("com.daymark.companion.audit")

/**
 * Per-source request budget for SESSION-COOKIE callers on the relationship surface.
 *
 * Bearer callers were always metered: `AuthGuard.authorize` spends a token bucket on every owner
 * request. The cookie branch of [resolveRole] never reached AuthGuard, so a signed-in therapist met
 * no limit of any kind — and each assignments/gameplans PUT ends in a live SMTP round-trip to the
 * owner. One session could pour "you have something to review" mail into the owner's inbox for as
 * long as it stayed alive. For the owner of a mental-health journal that is not spam volume, it is
 * the therapist reaching them at will down a channel they opted into for occasional notices.
 *
 * 120/minute sits far above the portal's real cost — a therapist screen is a handful of requests and
 * a publish is two — and far below anything useful as a flood. Fixed-window overshoot (up to 2x
 * across a boundary) is the trade [AttemptLimiter] already documents.
 *
 * `internal` so the test can assert against the real production number instead of restating it.
 */
internal const val THERAPIST_MAX_PER_WINDOW = 120
internal const val THERAPIST_WINDOW_MS = 60_000L

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
    auditStore: AuditStore,
    notifier: OwnerNotifier,
    publicBaseUrl: String?,
    auditSourceIp: Boolean = false,
    /** Injectable so tests can drive expiry without sleeping. */
    clock: () -> Long = { System.currentTimeMillis() },
    /**
     * The cookie-caller budget, built once here because `Application.module` has no knob for it —
     * `DAYMARK_RATE_LIMIT_RPS` sizes AuthGuard's bearer bucket, which is a different resource with
     * different traffic. Defaulted rather than required so this stays one file's change; wiring an
     * operator-visible knob through Config is a follow-up, not part of closing the bypass.
     */
    therapistLimiter: AttemptLimiter = AttemptLimiter(THERAPIST_MAX_PER_WINDOW, THERAPIST_WINDOW_MS),
) {
    route("/v1/rel/{relRef}/{channel}") {

        // List lineages in a channel (either counterparty may read).
        get {
            val ctx = resolve(store, ownerGuard, authStore, sessionIdleSeconds, auditStore, auditSourceIp, therapistLimiter) ?: return@get
            call.respond(RelLineageList(store.listLineages(ctx.relRef, ctx.channel)))
        }

        // List versions of a lineage (metadata only).
        get("/{lineage}") {
            val ctx = resolve(store, ownerGuard, authStore, sessionIdleSeconds, auditStore, auditSourceIp, therapistLimiter) ?: return@get
            val lineage = call.parameters["lineage"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            if (lineage == "current") return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            try {
                call.respond(RelVersionList(lineage, store.listVersions(ctx.relRef, ctx.channel, lineage).map { it.toDto() }))
            } catch (e: RelationStoreException) {
                call.failRel(e)
            }
        }

        /*
         * Withdraw a share lineage. Owner-only, and SHARES-only.
         *
         * SHARES-only is load-bearing, not tidiness. The obvious generalisation is "whoever writes
         * this channel may revoke it" — but `writerRole` makes the THERAPIST the writer of
         * assignments and gameplans, so that version of this route would hand the therapist an
         * irreversible kill switch over the owner's own assignments, with no un-revoke and no
         * remedy. This feature exists to constrain the therapist; it must not arm them.
         */
        post("/{lineage}/revoke") {
            val ctx = resolve(store, ownerGuard, authStore, sessionIdleSeconds, auditStore, auditSourceIp, therapistLimiter, requireCsrf = true) ?: return@post
            if (ctx.channel != Channel.SHARES) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not found"))
                return@post
            }
            if (ctx.role != Role.OWNER) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("only the owner may withdraw a share"))
                return@post
            }
            val lineage = call.parameters["lineage"] ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            try {
                val marked = store.revokeLineage(ctx.relRef, ctx.channel, lineage)
                auditSafely {
                    auditStore.append(
                        ctx.relRef,
                        AuditActor.OWNER,
                        AuditAction.SHARE_REVOKE,
                        objectRef = lineage,
                        meta = sourceIpMeta(auditSourceIp, call),
                    )
                }
                call.respond(HttpStatusCode.OK, RelRevokeResult(lineage, marked))
            } catch (e: RelationStoreException) {
                call.failRel(e)
            }
        }

        // Fetch the highest version of a lineage (the counterparty read path).
        get("/{lineage}/current") {
            val ctx = resolve(store, ownerGuard, authStore, sessionIdleSeconds, auditStore, auditSourceIp, therapistLimiter) ?: return@get
            val lineage = call.parameters["lineage"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            try {
                val (version, bytes) = store.fetchCurrent(ctx.relRef, ctx.channel, lineage)
                call.response.header("X-Content-Hash", RelationStore.sha256HexPublic(bytes))
                call.response.header("X-Version", version.toString())
                // Revocation is worthless if the browser can answer Refresh from its own cache with
                // the old 200 and the old bytes. An octet-stream 200 with no validators is
                // heuristically cacheable, so say no explicitly.
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondBytes(bytes, ContentType.Application.OctetStream)
                auditFetch(auditStore, ctx, lineage, version, auditSourceIp, call)
            } catch (e: RelationStoreException) {
                call.failRel(e)
            }
        }

        // Fetch one blob.
        get("/{lineage}/{version}") {
            val ctx = resolve(store, ownerGuard, authStore, sessionIdleSeconds, auditStore, auditSourceIp, therapistLimiter) ?: return@get
            val lineage = call.parameters["lineage"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            val version = call.parameters["version"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("version must be an integer"))
            try {
                val bytes = store.fetch(ctx.relRef, ctx.channel, lineage, version)
                call.response.header("X-Content-Hash", RelationStore.sha256HexPublic(bytes))
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondBytes(bytes, ContentType.Application.OctetStream)
                auditFetch(auditStore, ctx, lineage, version, auditSourceIp, call)
            } catch (e: RelationStoreException) {
                call.failRel(e)
            }
        }

        // Append a blob. Direction-enforced per channel. State-changing, so a THERAPIST (cookie)
        // writer MUST also present a matching X-CSRF-Token — a session cookie alone is not enough
        // (defends assignments/gameplans PUTs against cross-site forgery, matching /session/logout).
        put("/{lineage}/{version}") {
            val ctx = resolve(store, ownerGuard, authStore, sessionIdleSeconds, auditStore, auditSourceIp, therapistLimiter, requireCsrf = true) ?: return@put
            val requiredRole = writerRole(ctx.channel)
            if (ctx.role != requiredRole) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("wrong direction for this channel"))
                return@put
            }
            val lineage = call.parameters["lineage"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            val version = call.parameters["version"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorDto("version must be an integer"))
            /*
             * OPTIONAL non-secret routing tag. Read the next paragraph before citing the allowlist
             * as a guarantee anywhere.
             *
             * What `RelationStore.SETTING_ALLOWLIST` actually constrains is THIS HEADER: the
             * cleartext string the server stores in `rel_blobs.setting_key` and echoes back in the
             * version list. That is worth doing — it keeps an arbitrary therapist-chosen string out
             * of the server's own index and out of the owner's UI — but it is the whole of it.
             *
             * It does NOT constrain which setting the assignment changes. That key lives inside the
             * sealed body, which the server cannot read, and the header is not derived from the body
             * — it is a second, independent claim by the same author. A therapist who wanted to push
             * a `pin` assignment would send `X-Setting-Key: theme`, or (like the real client, which
             * has never sent this header at all — see companion/web/src/lib/therapist/assignClient.ts
             * `publishAssignment`) send nothing and skip the check outright. Making the header
             * MANDATORY on this channel would close the skip and change nothing else: the same party
             * still picks both halves, so no header rule can bind the ciphertext. The server has no
             * leverage here and should not be documented as if it does.
             *
             * The check that does bind is the OWNER's, on the plaintext, after decrypting:
             * companion/web/src/lib/assignments/inbox.ts calls `validateAssignment`, which enforces
             * the mirrored SETTING_ALLOWLIST in validate.ts. That one is run by the party being
             * protected, which is the property this header lacks and X-Share-Meta below has.
             *
             * Why the wording matters concretely: RelationStore's KDoc says this gate "guarantees no
             * PIN/lock/encryption/network/backup key can ever transit the setting channel". An owner
             * reading that would believe the server checks every setting assignment. It checks none
             * of the ones the shipping client sends. If anyone ever deleted the owner-side check as
             * "redundant with the server allowlist", a `pin` assignment would have sailed through
             * with nothing anywhere to stop it.
             */
            val settingKey = call.request.headers["X-Setting-Key"]?.trim()?.ifBlank { null }
            /*
             * The owner already sends the share deadline in X-Share-Meta; until now the server
             * dropped it on the floor and expiry was enforced only by the therapist's own browser.
             *
             * Trusting this header is defensible precisely because the sender is not the restricted
             * party: the OWNER writes it and the THERAPIST is bound by it. (Contrast X-Setting-Key,
             * where the sender IS the restricted party — see the block above for why no server-side
             * rule on that header can be worth anything.) The role check above has already
             * established this caller is the owner before the header is read at all. And it is not
             * load-bearing on its own: the authoritative expiry is bound into the signed AAD
             * transcript the client verifies, so a forged header can only make an honest server
             * refuse EARLIER, never serve later than the signed deadline.
             *
             * Only `expiry` is read. shareId/version/ownerSigningFp are deliberately ignored —
             * reading more of the envelope's metadata is a step toward reading the envelope.
             */
            val expiry: Long?
            if (ctx.channel == Channel.SHARES) {
                expiry = parseShareExpiry(call.request.headers["X-Share-Meta"], clock())
                if (expiry == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("missing, malformed, or past share expiry"))
                    return@put
                }
            } else {
                expiry = null
            }
            val body = call.readCappedRel(maxRequestBytes) ?: return@put
            try {
                val putMeta = store.put(ctx.relRef, ctx.channel, lineage, version, body, settingKey, expiry)
                call.response.header("X-Content-Hash", putMeta.contentHash)
                call.respond(
                    HttpStatusCode.Created,
                    RelPutResult(ctx.relRef, ctx.channel.wire, lineage, putMeta.version, putMeta.size, putMeta.contentHash),
                )
                auditPublish(auditStore, ctx, lineage, putMeta.version, auditSourceIp, call)
                // Best-effort "new item to review" notice — only for the therapist-writes-owner-reads
                // direction (writerRole already enforced this is THERAPIST for these two channels).
                //
                // On Dispatchers.IO because notify() bottoms out in a blocking SMTP round-trip to
                // whatever host the operator configured. That ran on the request coroutine's own
                // thread: not a delay to THIS caller (the 201 is already written above) but a thread
                // held out of the pool that serves everyone else, for as long as a slow or hanging
                // mail server felt like taking. Still awaited rather than launched — the send
                // completes before the handler returns, which is the ordering the notification tests
                // observe. Fully detaching it, the way RecoveryRoutes does, is the better end state
                // and a behaviour change, so it is called out in the report rather than smuggled in.
                reviewKindFor(ctx.channel)?.let { kind ->
                    val url = portalUrl(call, publicBaseUrl)
                    withContext(Dispatchers.IO) { notifier.notify(kind, url) }
                }
            } catch (e: RelationStoreException) {
                call.failRel(e)
            }
        }
    }
}

/** Log a therapist-share-read or owner-gameplan-read. No-op for uninteresting (channel, role) pairs. */
private fun auditFetch(auditStore: AuditStore, ctx: RelContext, lineage: String, version: Long, sourceIp: Boolean, call: ApplicationCall) {
    val (actor, action) = when {
        ctx.channel == Channel.SHARES && ctx.role == Role.THERAPIST -> AuditActor.THERAPIST to AuditAction.SHARE_OPEN
        ctx.channel == Channel.GAMEPLANS && ctx.role == Role.OWNER -> AuditActor.OWNER to AuditAction.GAMEPLAN_OPEN
        else -> return
    }
    auditSafely {
        auditStore.append(ctx.relRef, actor, action, objectRef = "$lineage:$version", meta = sourceIpMeta(sourceIp, call))
    }
}

/** Log a therapist publishing an assignment or game plan. No-op for the other (owner-write) channels. */
private fun auditPublish(auditStore: AuditStore, ctx: RelContext, lineage: String, version: Long, sourceIp: Boolean, call: ApplicationCall) {
    val action = when (ctx.channel) {
        Channel.ASSIGNMENTS -> AuditAction.ASSIGNMENT_PUBLISH
        Channel.GAMEPLANS -> AuditAction.GAMEPLAN_PUBLISH
        Channel.GRANTS, Channel.SHARES -> return
    }
    auditSafely {
        auditStore.append(ctx.relRef, AuditActor.THERAPIST, action, objectRef = "$lineage:$version", meta = sourceIpMeta(sourceIp, call))
    }
}

private fun sourceIpMeta(enabled: Boolean, call: ApplicationCall): Map<String, String>? =
    if (enabled) mapOf("sourceIp" to call.clientAddress()) else null

/** The audit log is additive, never load-bearing: a logging bug must never fail a real request. */
private fun auditSafely(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        log.warn("audit log append failed", e)
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
    return java.net.URI("${resolveBaseUrl(call, publicBaseUrl)}/")
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
    auditStore: AuditStore,
    auditSourceIp: Boolean,
    therapistLimiter: AttemptLimiter,
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

    /*
     * Meter cookie callers. See THERAPIST_MAX_PER_WINDOW for what went unbounded without this.
     *
     * Charged AFTER the inbox-token check, not before, and that ordering is the point: only a caller
     * who already holds the relationship's inbox token can spend the budget. Metering earlier would
     * let anyone who can reach the server attach a made-up cookie and burn a real therapist's
     * allowance from the same address — turning a rate limit into a way to lock a clinician out of
     * their own caseload. The work in front of the token check is one BLAKE2b hash; the expensive
     * part (session lookup, blob write, SMTP) is all behind it.
     *
     * Keyed on clientAddress() to match every other per-source control here (AuthGuard, the TOTP
     * limiter, the recovery limiter) and to inherit the same trusted-proxy contract.
     */
    if (call.request.cookies["daymark_session"] != null && !therapistLimiter.allow(call.clientAddress())) {
        call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited")); return null
    }

    // Determine role. Prefer an owner bearer token; else a therapist session cookie bound here.
    // For state-changing therapist calls (requireCsrf), a missing/mismatched X-CSRF-Token is a
    // rejection, not a bypass — the cookie alone must not authorize a write.
    val role = resolveRole(call, ownerGuard, authStore, sessionIdleSeconds, pathRelRef, requireCsrf, auditStore, auditSourceIp) ?: run {
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
    auditStore: AuditStore,
    auditSourceIp: Boolean,
): Role? {
    val sourceId = call.clientAddress()
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
        if (v.check == AuthStore.SessionCheck.EXPIRED) {
            auditSafely {
                auditStore.append(relRef, AuditActor.THERAPIST, AuditAction.SESSION_EXPIRED, meta = sourceIpMeta(auditSourceIp, call))
            }
        }
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

/**
 * The share deadline out of `X-Share-Meta`, or null if there isn't a usable one.
 *
 * Returns null — meaning "reject this publish" on the shares channel — for absent, oversized,
 * un-decodable, non-JSON, missing-field, non-integer, non-positive, and already-past values. Failing
 * closed here is what keeps the grandfather rule in `gateLocked` bounded: after this change a share
 * row with no expiry can only be one an older build wrote, never one written today.
 *
 * A past expiry is rejected rather than stored because the alternative is silent: the owner sees
 * "published" and the therapist sees 410 forever, with nothing anywhere saying why.
 *
 * URL-safe base64 without padding, because that is what the client's `toBase64` (libsodium
 * URLSAFE_NO_PADDING) emits. The standard decoder would reject it.
 */
internal fun parseShareExpiry(header: String?, now: Long, maxAheadMs: Long = 366L * 24 * 60 * 60 * 1000): Long? {
    val raw = header?.trim() ?: return null
    if (raw.isEmpty() || raw.length > 4096) return null // don't base64-decode an attacker-sized header
    val json = runCatching { String(java.util.Base64.getUrlDecoder().decode(raw), Charsets.UTF_8) }.getOrNull() ?: return null
    // Only `expiry` is read. Deliberately not shareId/version/ownerSigningFp: the server needs one
    // number to know when to stop moving bytes, and reading more of the envelope's metadata is a
    // step toward reading the envelope. (The header's `version` also disagrees with the sealed
    // envelope's own from the second publish onward, so cross-checking it would be wrong.)
    val expiry = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(json)
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("expiry")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.takeIf { !it.isString }
            ?.content
            ?.toLongOrNull()
    }.getOrNull() ?: return null
    if (expiry <= 0 || expiry <= now) return null
    // Clamp rather than reject: only a modified client can exceed the UI's 365-day ceiling, and
    // clamping keeps "effectively no expiry" from being reachable by writing a huge number.
    return minOf(expiry, now + maxAheadMs)
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
        /*
         * 410 Gone, not 404 and not 403.
         *
         * 404 would be a lie with teeth: the therapist client maps 404 to null and the UI renders
         * "No share has been published for you yet", so a withdrawn share would tell the therapist
         * the owner never shared anything.
         *
         * 403 means "your identity is not permitted", which is wrong — no identity may fetch this,
         * and re-authenticating cannot help. It is also already spoken for on this surface ("wrong
         * direction for this channel").
         *
         * 410 means "it was here, it is deliberately gone." True of both an elapsed deadline and a
         * withdrawal, and already this codebase's word for it (a consumed invite returns Gone).
         * Expired and revoked share one message on purpose — see RelationStoreException.Kind.GONE.
         */
        RelationStoreException.Kind.GONE -> HttpStatusCode.Gone to "no longer available"
    }
    respond(status, ErrorDto(message))
}
