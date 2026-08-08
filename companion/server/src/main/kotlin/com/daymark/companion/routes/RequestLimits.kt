package com.daymark.companion.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json

/**
 * Hard ceiling for JSON request bodies.
 *
 * Ktor 3.0.3 applies **no** default body limit (`RequestBodyLimit` only arrives in 3.2), and
 * `call.receive<T>()` buffers the whole body into the heap before any handler logic — including
 * before any rate limiter — runs. On the credential-free routes that is reachable by anyone: a
 * handful of slow-drip connections at the proxy's 28 MB cap each will exhaust the heap, and
 * `-XX:+ExitOnOutOfMemoryError` then halts the JVM at allocation time, where `StatusPages` never
 * sees it. `restart: unless-stopped` closes the loop into a crash cycle.
 *
 * 64 KiB is comfortably more than any of these payloads needs — the largest is an email address
 * plus a token — and small enough that concurrency cannot convert into memory pressure.
 */
const val JSON_BODY_MAX_BYTES: Long = 64L * 1024

/** Matches the `ContentNegotiation` config in `Application.module`. */
val bodyJson: Json = Json { explicitNulls = false; ignoreUnknownKeys = true }

/**
 * Reads the body with a hard cap, responding 413 and returning null if it is exceeded.
 *
 * Streams rather than buffering, so an oversized body is rejected as soon as it crosses the limit
 * instead of after the whole thing has been accepted.
 */
suspend fun ApplicationCall.readBodyCapped(max: Long = JSON_BODY_MAX_BYTES): ByteArray? {
    val stream = receiveStream()
    val buf = ByteArray(8 * 1024)
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
 * `call.receive<T>()` with a size cap and a 400 (not a 500) on malformed JSON.
 *
 * Returns null when it has already responded, so call sites read
 * `val req = call.receiveCappedJson<Foo>() ?: return@post`.
 */
suspend inline fun <reified T> ApplicationCall.receiveCappedJson(
    max: Long = JSON_BODY_MAX_BYTES,
): T? {
    val bytes = readBodyCapped(max) ?: return null
    return try {
        bodyJson.decodeFromString<T>(bytes.decodeToString())
    } catch (_: Exception) {
        // Deliberately generic: a parser message can echo body content back to the caller.
        respond(HttpStatusCode.BadRequest, ErrorDto("malformed request"))
        null
    }
}
