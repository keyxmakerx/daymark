package com.daymark.companion

import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.routes.ErrorDto
import com.daymark.companion.routes.syncRoutes
import com.daymark.companion.storage.BlobStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("com.daymark.companion")

fun main() {
    val config = Config.fromEnv()
    applyLogLevel(config.logLevel)
    log.info(
        "Daymark Companion starting on {}:{} basePath={} sync={} dataDir={}",
        config.bindAddr, config.port, config.basePath, config.syncEnabled, config.dataDir,
    )
    if (!config.syncEnabled) {
        log.warn("DAYMARK_AUTH_TOKEN is not set — the /v1 sync API is DISABLED (fail-closed). Only the viewer + /healthz are served.")
    }
    embeddedServer(Netty, port = config.port, host = config.bindAddr) {
        module(config)
    }.start(wait = true)
}

/** Apply DAYMARK_LOG_LEVEL to the app's logger at startup (logback). */
private fun applyLogLevel(level: String) {
    val logback = LoggerFactory.getLogger("com.daymark.companion") as? ch.qos.logback.classic.Logger
    logback?.level = ch.qos.logback.classic.Level.toLevel(level, ch.qos.logback.classic.Level.INFO)
}

fun Application.module(config: Config, blobStore: BlobStore? = null) {
    install(ContentNegotiation) { json(Json { explicitNulls = false }) }
    install(SecurityHeaders)
    install(StatusPages) {
        // Generic error bodies, no stack traces (COMPANION_SECURITY.md §6).
        exception<Throwable> { call, cause ->
            log.error("unhandled error on {}", call.request.local.uri, cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal error"))
        }
    }

    val webRoot = File(config.webDir)
    if (!webRoot.isDirectory) {
        log.warn("Web directory '{}' not found — static assets will 404 until it is built/mounted.", webRoot.absolutePath)
    }

    val store = if (config.syncEnabled) {
        blobStore ?: BlobStore(config.dataDir, config.maxBlobBytes, config.maxVersions, config.perTokenQuotaBytes)
    } else null
    val guard = config.authToken?.let {
        AuthGuard(it, config.authLockoutFails, config.authLockoutSeconds * 1000, config.rateLimitRps)
    }

    routing {
        // Unauthenticated, content-free liveness probe — never under the base path.
        get("/healthz") {
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        if (store != null && guard != null) {
            syncRoutes(store, guard, config.maxRequestBytes)
        } else {
            // Fail-closed: sync not configured. Cover the methods the API uses.
            get("/v1/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
            put("/v1/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
        }

        if (config.basePath == "/") {
            staticFiles("/", webRoot) { default("index.html") }
        } else {
            route(config.basePath) {
                staticFiles("/", webRoot) { default("index.html") }
            }
        }
    }
}
