package com.daymark.companion.routes

import com.daymark.companion.clientAddress
import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.storage.BlobStore
import com.daymark.companion.storage.BlobStoreException
import com.daymark.companion.storage.KeyDocumentException
import com.daymark.companion.storage.KeyDocumentStore
import com.daymark.companion.storage.SnapshotMeta
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable data class MetaDto(val version: Long, val size: Long, val contentHash: String, val createdAt: Long)
@Serializable data class VersionList(val lineage: String, val versions: List<MetaDto>)
@Serializable data class LineageList(val lineages: List<String>)
@Serializable data class PutResult(val lineage: String, val version: Long, val size: Long, val contentHash: String)
@Serializable data class ErrorDto(val error: String)

private fun SnapshotMeta.toDto() = MetaDto(version, size, contentHash, createdAt)

/** A wrapped-key version the server took (#258). */
@Serializable data class KeyDocumentWritten(val version: Long)

/** Which document `GET /v1/keydoc` answered with: [KEY_DOCUMENT_WRAPPED] or [KEY_DOCUMENT_KEYPARAMS] (#258). */
const val KEY_DOCUMENT_HEADER = "X-Key-Document"

/** The version of the wrapped key `GET /v1/keydoc` answered with; absent for the key parameters (#258). */
const val KEY_DOCUMENT_VERSION_HEADER = "X-Key-Document-Version"
const val KEY_DOCUMENT_WRAPPED = "wrapped"
const val KEY_DOCUMENT_KEYPARAMS = "keyparams"

/**
 * The /v1 sync API. The server is zero-knowledge: every blob is opaque ciphertext it
 * cannot read. All routes require a valid bearer token; identity for rate-limit/lockout
 * is [clientAddress]: the socket peer, unless a proxy on the operator's trusted-proxy list
 * vouches for another address (docs/COMPANION_DEPLOYMENT.md §4.0).
 */
fun Route.syncRoutes(store: BlobStore, keys: KeyDocumentStore, guard: AuthGuard, maxRequestBytes: Long) {
    route("/v1") {
        // Non-secret KDF parameters (salt etc.) shared by all of an owner's clients, until a wrapped
        // key supersedes them (#258). From then on both methods answer 410 Gone: the parameters were
        // here and are withdrawn for good. Not 404, which says none were ever published and which a
        // writer answers by publishing a fresh salt; not 409, which invites a retry that can never
        // succeed. The file stays on the volume (KeyDocumentStore).
        put("/keyparams") {
            if (!call.authorized(guard)) return@put
            val body = call.readCapped(maxRequestBytes) ?: return@put
            try {
                keys.putKeyparams(body)
                call.respond(HttpStatusCode.NoContent)
            } catch (e: KeyDocumentException) {
                call.failKeyDocument(e)
            }
        }
        get("/keyparams") {
            if (!call.authorized(guard)) return@get
            try {
                val kp = keys.getKeyparams()
                if (kp == null) call.respond(HttpStatusCode.NotFound, ErrorDto("no keyparams"))
                else call.respondBytes(kp, ContentType.Application.Json)
            } catch (e: KeyDocumentException) {
                call.failKeyDocument(e)
            }
        }

        // The owner's key document (#258): the newest wrapped key if one exists, the key parameters
        // otherwise, 404 when neither does. X-Key-Document says which of the two the body is, and
        // X-Key-Document-Version the wrapped key's version; the reader still checks the body as that
        // kind, since the server vouches for neither. Only with the owner's token, because the
        // passphrase slot can be guessed at offline by whoever holds the document. no-store, because
        // a changed passphrase retires the old slot only if nothing answers from a kept copy.
        get("/keydoc") {
            if (!call.authorized(guard)) return@get
            try {
                when (val current = keys.current()) {
                    is KeyDocumentStore.Current.Wrapped -> {
                        call.response.header(KEY_DOCUMENT_HEADER, KEY_DOCUMENT_WRAPPED)
                        call.response.header(KEY_DOCUMENT_VERSION_HEADER, current.version.toString())
                        call.response.header(HttpHeaders.CacheControl, "no-store")
                        call.respondBytes(current.document, ContentType.Application.Json)
                    }
                    is KeyDocumentStore.Current.Keyparams -> {
                        call.response.header(KEY_DOCUMENT_HEADER, KEY_DOCUMENT_KEYPARAMS)
                        call.response.header(HttpHeaders.CacheControl, "no-store")
                        call.respondBytes(current.document, ContentType.Application.Json)
                    }
                    KeyDocumentStore.Current.None -> call.respond(HttpStatusCode.NotFound, ErrorDto("no key document"))
                }
            } catch (e: KeyDocumentException) {
                call.failKeyDocument(e)
            }
        }

        // Create-only: version 1 of the wrapped key, whether or not key parameters exist (with them,
        // this is enrolment). 409 once any version exists, so of two devices creating at once one is
        // refused, and they never both mint a master.
        post("/keydoc") {
            if (!call.authorized(guard)) return@post
            val body = call.readBodyCapped(KeyDocumentStore.WRAPPED_KEY_MAX_BYTES.toLong()) ?: return@post
            try {
                call.respond(HttpStatusCode.Created, KeyDocumentWritten(keys.create(body)))
            } catch (e: KeyDocumentException) {
                call.failKeyDocument(e)
            }
        }

        // A new version: a changed passphrase or recovery code. {version} is the one this write
        // makes, so the version it changed is {version} - 1, and it must be the newest: 409 when
        // another device wrote first. Insert-only, like every version.
        put("/keydoc/{version}") {
            if (!call.authorized(guard)) return@put
            val version = call.parameters["version"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorDto("version must be an integer"))
            val body = call.readBodyCapped(KeyDocumentStore.WRAPPED_KEY_MAX_BYTES.toLong()) ?: return@put
            try {
                call.respond(HttpStatusCode.Created, KeyDocumentWritten(keys.append(version, body)))
            } catch (e: KeyDocumentException) {
                call.failKeyDocument(e)
            }
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
    val sourceId = clientAddress()
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

private val routeLog = org.slf4j.LoggerFactory.getLogger("com.daymark.companion.routes")

private suspend fun ApplicationCall.failBlob(e: BlobStoreException) {
    // Fixed, non-enumerating client messages (no filesystem paths / internals); the real
    // detail is logged server-side only. See COMPANION_SECURITY.md §6.
    val (status, message) = when (e.kind) {
        BlobStoreException.Kind.BAD_NAME -> HttpStatusCode.BadRequest to "invalid request"
        BlobStoreException.Kind.CONFLICT -> HttpStatusCode.Conflict to "version already exists"
        BlobStoreException.Kind.TOO_OLD -> HttpStatusCode.Conflict to "version below retention window"
        BlobStoreException.Kind.TOO_LARGE -> HttpStatusCode.PayloadTooLarge to "payload too large"
        BlobStoreException.Kind.QUOTA -> HttpStatusCode.InsufficientStorage to "insufficient storage"
        BlobStoreException.Kind.DISK_FULL -> HttpStatusCode.InsufficientStorage to "insufficient storage"
        BlobStoreException.Kind.NOT_FOUND -> HttpStatusCode.NotFound to "not found"
    }
    if (e.kind == BlobStoreException.Kind.DISK_FULL) routeLog.warn("blob store I/O error: {}", e.message)
    respond(status, ErrorDto(message))
}

/**
 * One fixed answer per refusal of the key-document store (#258). The volume's failure is the one
 * logged, and only by the failure's class: no content, no path, no size, no version.
 */
private suspend fun ApplicationCall.failKeyDocument(e: KeyDocumentException) {
    val (status, message) = when (e.kind) {
        KeyDocumentException.Kind.NOT_A_JSON_OBJECT -> HttpStatusCode.BadRequest to "the key document must be a JSON object"
        KeyDocumentException.Kind.TOO_DEEP -> HttpStatusCode.BadRequest to "the key document is nested too deeply"
        KeyDocumentException.Kind.BAD_VERSION -> HttpStatusCode.BadRequest to "a new version is 2 or more"
        KeyDocumentException.Kind.TOO_LARGE -> HttpStatusCode.PayloadTooLarge to "payload too large"
        KeyDocumentException.Kind.EXISTS -> HttpStatusCode.Conflict to "a wrapped key already exists"
        KeyDocumentException.Kind.NOT_NEXT -> HttpStatusCode.Conflict to "not the next version"
        KeyDocumentException.Kind.SUPERSEDED -> HttpStatusCode.Gone to "superseded by the wrapped key"
        KeyDocumentException.Kind.FULL -> HttpStatusCode.InsufficientStorage to "insufficient storage"
        KeyDocumentException.Kind.IO -> HttpStatusCode.InsufficientStorage to "insufficient storage"
    }
    if (e.kind == KeyDocumentException.Kind.IO) routeLog.warn("key document store I/O error: {}", e.cause?.javaClass?.simpleName)
    respond(status, ErrorDto(message))
}
