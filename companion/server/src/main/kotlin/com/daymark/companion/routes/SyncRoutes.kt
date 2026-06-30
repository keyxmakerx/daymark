package com.daymark.companion.routes

import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.storage.BlobStore
import com.daymark.companion.storage.BlobStoreException
import com.daymark.companion.storage.SnapshotMeta
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable data class MetaDto(val version: Long, val size: Long, val contentHash: String, val createdAt: Long)
@Serializable data class VersionList(val lineage: String, val versions: List<MetaDto>)
@Serializable data class LineageList(val lineages: List<String>)
@Serializable data class PutResult(val lineage: String, val version: Long, val size: Long, val contentHash: String)
@Serializable data class ErrorDto(val error: String)

private fun SnapshotMeta.toDto() = MetaDto(version, size, contentHash, createdAt)

/**
 * The /v1 sync API. The server is zero-knowledge: every blob is opaque ciphertext it
 * cannot read. All routes require a valid bearer token; identity for rate-limit/lockout
 * is the socket peer (no forwarded headers trusted).
 */
fun Route.syncRoutes(store: BlobStore, guard: AuthGuard, maxRequestBytes: Long) {
    route("/v1") {
        // Non-secret KDF parameters (salt etc.) shared by all of an owner's clients.
        put("/keyparams") {
            if (!call.authorized(guard)) return@put
            val body = call.readCapped(maxRequestBytes) ?: return@put
            try {
                store.putKeyparams(body)
                call.respond(HttpStatusCode.NoContent)
            } catch (e: BlobStoreException) {
                call.failBlob(e)
            }
        }
        get("/keyparams") {
            if (!call.authorized(guard)) return@get
            val kp = store.getKeyparams()
            if (kp == null) call.respond(HttpStatusCode.NotFound, ErrorDto("no keyparams"))
            else call.respondBytes(kp, ContentType.Application.Json)
        }

        // List all snapshot lineages.
        get("/snapshots") {
            if (!call.authorized(guard)) return@get
            call.respond(LineageList(store.listLineages()))
        }

        // List versions of one lineage (metadata only).
        get("/snapshots/{lineage}") {
            if (!call.authorized(guard)) return@get
            val lineage = call.parameters["lineage"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            try {
                call.respond(VersionList(lineage, store.listVersions(lineage).map { it.toDto() }))
            } catch (e: BlobStoreException) {
                call.failBlob(e)
            }
        }

        // Store an append-only ciphertext blob.
        put("/snapshots/{lineage}/{version}") {
            if (!call.authorized(guard)) return@put
            val lineage = call.parameters["lineage"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            val version = call.parameters["version"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorDto("version must be an integer"))
            val body = call.readCapped(maxRequestBytes) ?: return@put
            try {
                val meta = store.put(lineage, version, body)
                call.response.header("X-Content-Hash", meta.contentHash)
                call.respond(HttpStatusCode.Created, PutResult(meta.lineage, meta.version, meta.size, meta.contentHash))
            } catch (e: BlobStoreException) {
                call.failBlob(e)
            }
        }

        // Fetch one ciphertext blob.
        get("/snapshots/{lineage}/{version}") {
            if (!call.authorized(guard)) return@get
            val lineage = call.parameters["lineage"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing lineage"))
            val version = call.parameters["version"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("version must be an integer"))
            try {
                val bytes = store.fetch(lineage, version)
                call.response.header("X-Content-Hash", BlobStore.sha256Hex(bytes))
                call.respondBytes(bytes, ContentType.Application.OctetStream)
            } catch (e: BlobStoreException) {
                call.failBlob(e)
            }
        }
    }
}

/** Verify the bearer token; respond + return false on any failure. Generic, non-enumerating errors. */
private suspend fun ApplicationCall.authorized(guard: AuthGuard): Boolean {
    val sourceId = request.origin.remoteAddress
    val presented = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    return when (guard.authorize(sourceId, presented)) {
        AuthGuard.Result.OK -> true
        AuthGuard.Result.RATE_LIMITED -> { respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited")); false }
        AuthGuard.Result.LOCKED -> { respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked")); false }
        AuthGuard.Result.BAD_TOKEN -> { respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); false }
    }
}

/** Read the request body with a hard cap; respond 413 and return null if exceeded. */
private suspend fun ApplicationCall.readCapped(max: Long): ByteArray? {
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

private suspend fun ApplicationCall.failBlob(e: BlobStoreException) {
    val status = when (e.kind) {
        BlobStoreException.Kind.BAD_NAME -> HttpStatusCode.BadRequest
        BlobStoreException.Kind.CONFLICT -> HttpStatusCode.Conflict
        BlobStoreException.Kind.TOO_LARGE -> HttpStatusCode.PayloadTooLarge
        BlobStoreException.Kind.QUOTA -> HttpStatusCode.InsufficientStorage
        BlobStoreException.Kind.DISK_FULL -> HttpStatusCode.InsufficientStorage
        BlobStoreException.Kind.NOT_FOUND -> HttpStatusCode.NotFound
    }
    respond(status, ErrorDto(e.message ?: "error"))
}
