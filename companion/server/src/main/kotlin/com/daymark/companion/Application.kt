package com.daymark.companion

import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.OwnerAccountStore
import com.daymark.companion.mail.OwnerNotifier
import com.daymark.companion.routes.ErrorDto
import com.daymark.companion.routes.recoveryRoutes
import com.daymark.companion.routes.relationRoutes
import com.daymark.companion.routes.syncRoutes
import com.daymark.companion.routes.therapistAuthRoutes
import com.daymark.companion.storage.BlobStore
import com.daymark.companion.storage.RelationStore
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
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("com.daymark.companion")

/** The single non-secret capability flag the owner portal reads from /v1/config. */
@kotlinx.serialization.Serializable
data class ServerConfigDto(val smtpEnabled: Boolean)

fun main() {
    val config = Config.fromEnv()
    applyLogLevel(config.logLevel)
    log.info(
        "Daymark Companion starting on {}:{} basePath={} sync={} smtp={} dataDir={}",
        config.bindAddr, config.port, config.basePath, config.syncEnabled, config.smtpEnabled, config.dataDir,
    )
    if (!config.syncEnabled) {
        log.warn("DAYMARK_AUTH_TOKEN is not set — the /v1 sync API is DISABLED (fail-closed). Only the viewer + /healthz are served.")
    }
    if (config.smtpEnabled) {
        // Validate fail-closed at startup so a bad SMTP config (plaintext / no From) refuses
        // to boot instead of silently sending in the clear.
        config.mailer.validate()
        log.info("Outbound SMTP is ENABLED (the one deliberate egress exception): host={} port={} tls={}", config.mailer.host, config.mailer.port, config.mailer.tls)
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

fun Application.module(
    config: Config,
    blobStore: BlobStore? = null,
    mailer: Mailer? = null,
    relationStore: RelationStore? = null,
    authStore: AuthStore? = null,
    accountStore: OwnerAccountStore? = null,
) {
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

    // Track T2 (email Option A): the owner/bearer token now lives here, not just in config —
    // this is what makes it rotatable at runtime via the email-triggered recovery flow without a
    // restart. Bootstrapped from (and reconciled against, on every boot) DAYMARK_AUTH_TOKEN; see
    // OwnerAccountStore's kdoc for the reconciliation rule.
    val account = config.authToken?.let { token ->
        accountStore ?: OwnerAccountStore(config.dataDir, token)
    }
    val guard = account?.let {
        AuthGuard(it.currentToken(), config.authLockoutFails, config.authLockoutSeconds * 1000, config.rateLimitRps)
    }

    // Built once and DI'd to the invite/notification services. When SMTP is disabled this never
    // opens a socket. Tests can inject an InMemory-backed mailer.
    val mail = mailer ?: Mailer.forConfig(config.mailer)
    val notifier = account?.let { OwnerNotifier(it, mail) }

    // Therapist portal: relationship blob channels + auth. Gated on DAYMARK_THERAPIST_AUTH and
    // (for the owner-write direction / mint route) the owner bearer token being configured.
    val relStore = if (config.therapistAuthEnabled) {
        relationStore ?: RelationStore(config.dataDir, config.maxBlobBytes, config.relMaxVersions, config.relQuotaBytes)
    } else null
    val auth = if (config.therapistAuthEnabled) {
        authStore ?: AuthStore(config.dataDir)
    } else null

    routing {
        // Unauthenticated, content-free liveness probe — never under the base path.
        get("/healthz") {
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // Unauthenticated capability probe. Reveals ONLY whether the operator enabled outbound
        // SMTP, so the owner portal knows whether to offer the "send email invite" button. No
        // secrets, no config values — just the single boolean the UI needs.
        get("/v1/config") {
            call.respond(ServerConfigDto(smtpEnabled = config.smtpEnabled))
        }

        if (store != null && guard != null) {
            syncRoutes(store, guard, config.maxRequestBytes)
        } else {
            // Fail-closed: sync not configured. Cover the methods the API uses. Scope to the
            // exact sync paths so the therapist portal's /v1/rel + /v1/invite etc. can still be
            // registered independently below.
            get("/v1/snapshots/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
            put("/v1/snapshots/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
            get("/v1/keyparams") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
            put("/v1/keyparams") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
        }

        // Therapist portal. Fail-closed: 503 on every portal path when the feature is off, so a
        // probe cannot tell configured-but-empty from not-configured.
        if (relStore != null && auth != null && guard != null && notifier != null) {
            relationRoutes(
                relStore, guard, auth, config.sessionIdleSeconds, config.maxRequestBytes,
                notifier = notifier, publicBaseUrl = config.publicBaseUrl,
            )
            therapistAuthRoutes(
                authStore = auth,
                ownerGuard = guard,
                mailer = mail,
                inviteTtlSeconds = config.inviteTtlSeconds,
                sessionIdleSeconds = config.sessionIdleSeconds,
                sessionAbsoluteSeconds = config.sessionAbsoluteSeconds,
                totpLockoutFails = config.totpLockoutFails,
                totpLockoutSeconds = config.totpLockoutSeconds,
                publicBaseUrl = config.publicBaseUrl,
                notifier = notifier,
                cookieSecure = config.cookieSecure,
            )
        } else {
            get("/v1/rel/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("therapist portal not configured")) }
            put("/v1/rel/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("therapist portal not configured")) }
            post("/v1/invite") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("therapist portal not configured")) }
            post("/v1/invite/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("therapist portal not configured")) }
            post("/v1/totp/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("therapist portal not configured")) }
            post("/v1/session/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("therapist portal not configured")) }
            post("/v1/webauthn/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("therapist portal not configured")) }
        }

        // Track T2 (email Option A): owner notification-email registration + the unauthenticated
        // access-token recovery flow. Gated on the sync/owner bearer token being configured at
        // all (independent of the therapist portal — recovery covers plain /v1 sync access too),
        // fail-closed to 503 otherwise so a probe cannot tell configured-but-empty from absent.
        if (account != null && guard != null && notifier != null) {
            recoveryRoutes(
                accountStore = account,
                ownerGuard = guard,
                mailer = mail,
                confirmTtlSeconds = config.reissueConfirmTtlSeconds,
                reissueMaxPerHour = config.reissueMaxPerHour,
                publicBaseUrl = config.publicBaseUrl,
            )
        } else {
            get("/v1/owner/notifications") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("recovery not configured")) }
            put("/v1/owner/notifications") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("recovery not configured")) }
            post("/v1/recovery/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("recovery not configured")) }
        }

        // The therapist portal is a SEPARATE surface served at its own route. Map the clean path
        // "/therapist" to the second SPA entry (therapist.html), distinct from the owner viewer's
        // default (index.html). The bundled therapist.html is also reachable directly via static
        // serving; this route just gives it a clean URL. Same CSP headers apply.
        val therapistEntry = File(webRoot, "therapist.html")
        val serveTherapist: suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
            if (therapistEntry.isFile) call.respondFile(therapistEntry)
            else call.respond(HttpStatusCode.NotFound, ErrorDto("therapist portal not built"))
        }

        if (config.basePath == "/") {
            get("/therapist") { serveTherapist() }
            staticFiles("/", webRoot) { default("index.html") }
        } else {
            route(config.basePath) {
                get("/therapist") { serveTherapist() }
                staticFiles("/", webRoot) { default("index.html") }
            }
        }
    }
}
