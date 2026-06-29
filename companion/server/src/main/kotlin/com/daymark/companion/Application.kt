package com.daymark.companion

import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("com.daymark.companion")

fun main() {
    val config = Config.fromEnv()
    log.info(
        "Daymark Companion starting on {}:{} basePath={} webDir={} dataDir={}",
        config.bindAddr, config.port, config.basePath, config.webDir, config.dataDir,
    )
    embeddedServer(Netty, port = config.port, host = config.bindAddr) {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: Config) {
    install(ContentNegotiation) { json(Json { explicitNulls = false }) }
    install(SecurityHeaders)

    val webRoot = File(config.webDir)
    if (!webRoot.isDirectory) {
        log.warn("Web directory '{}' not found — static assets will 404 until it is built/mounted.", webRoot.absolutePath)
    }

    routing {
        // Unauthenticated, content-free liveness probe. Always at the root, never under
        // the base path, so a healthcheck never needs to know the proxy prefix.
        get("/healthz") {
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
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
