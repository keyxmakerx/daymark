package com.daymark.companion

import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.PairingStore
import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.OwnerAccountStore
import com.daymark.companion.mail.OwnerNotifier
import com.daymark.companion.org.OrgStore
import com.daymark.companion.routes.ErrorDto
import com.daymark.companion.routes.auditChainRoutes
import com.daymark.companion.routes.auditRoutes
import com.daymark.companion.routes.orgRoutes
import com.daymark.companion.routes.pairingRelayRoutes
import com.daymark.companion.routes.recoveryRoutes
import com.daymark.companion.routes.relationRoutes
import com.daymark.companion.routes.relationshipEndingRoutes
import com.daymark.companion.routes.syncRoutes
import com.daymark.companion.routes.therapistAuthRoutes
import com.daymark.companion.routes.ownerKeyRoutes
import com.daymark.companion.routes.therapistKeyRoutes
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.BlobStore
import com.daymark.companion.storage.KeyDocumentStore
import com.daymark.companion.storage.RelationStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("com.daymark.companion")

/** The non-secret facts the consoles read from /v1/config. */
@kotlinx.serialization.Serializable
data class ServerConfigDto(
    val smtpEnabled: Boolean,
    /**
     * The shape the operator chose with `DAYMARK_SETUP_MODE` — `solo`, `paired` or `practice`, the
     * field and ids the first-run screen reads (`companion/web/src/lib/setup/shape.ts`) — so the
     * screen stops asking (#330). Absent when none was chosen: the screen reads a published shape as
     * the configuration having answered, and a shape the server assumed is not that. The body is then
     * the one it was before the setting existed.
     */
    val setupMode: String? = null,
)

/** The exit status of a [StartupRefusal]: `EX_CONFIG` in sysexits.h, a configuration error. */
internal const val EXIT_CONFIG = 78

fun main() {
    val config = try {
        Config.fromEnv()
    } catch (refusal: StartupRefusal) {
        // One line naming the setting, and out. It is logged before DAYMARK_LOG_LEVEL is applied,
        // so no level can hide it, and without the exception, so no stack trace buries it (#180).
        log.error(refusal.message)
        exitProcess(EXIT_CONFIG)
    }
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
    // A request-read timeout. This used to be the bundled reverse proxy's job — `read_header 10s`,
    // `read_body 120s` — and it left with the proxy, so nothing in the deployment bounded how
    // slowly a client could dribble a request. Netty being non-blocking makes connection-count
    // slowloris weak, not the slow-request kind, and Ktor's own default here is
    // `requestReadTimeoutSeconds = 0` — infinite (verified against the Ktor 3.0.3 source, not
    // assumed). The operator's proxy is asked for the same thing in
    // COMPANION_DEPLOYMENT.md §3.1 requirement 9, but the app must not depend on a proxy
    // it cannot see for its floor.
    //
    // 120 s, not 10: a 25 MiB snapshot over a slow mobile uplink is a legitimate long request, and
    // cutting those off would be a worse bug than the one being closed. `responseWriteTimeoutSeconds`
    // is deliberately left at Ktor's 10 s default — it is shipped behaviour I have not investigated,
    // and widening it "while I'm here" would be changing a limit I have no finding about.
    //
    // NOTE the shape: Ktor 3.0.3 has NO embeddedServer overload taking port/host AND configure
    // together (the compiler will list the three that exist if you get this wrong). Connectors are
    // set inside `configure` instead, via the `connector` extension on ApplicationEngine.Configuration.
    try {
        embeddedServer(
            Netty,
            configure = {
                connector {
                    host = config.bindAddr
                    port = config.port
                }
                requestReadTimeoutSeconds = 120
            },
        ) {
            module(config)
        }.start(wait = true)
    } catch (e: Throwable) {
        // A database this release must not open, or could not change, refuses the start as a setting
        // does: one line naming the database and its versions, and out with 78 (#193). The stores
        // raise it as the module opens them, before a port is bound or a request is taken. Logged at
        // a level DAYMARK_LOG_LEVEL cannot hide, as the settings refusal above is.
        val refusal = generateSequence(e) { it.cause }.filterIsInstance<StartupRefusal>().firstOrNull() ?: throw e
        applyLogLevel("error")
        log.error(refusal.message)
        exitProcess(EXIT_CONFIG)
    }
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
    auditStore: AuditStore? = null,
    accountStore: OwnerAccountStore? = null,
    // Appended rather than slotted in beside the stores they sit next to, because every existing
    // caller passes the ones above POSITIONALLY. A new parameter in the middle compiles for none of
    // them; a new parameter at the end compiles for all of them and changes nothing.
    orgStore: OrgStore? = null,
    orgAuditStore: AuditStore? = null,
    pairingStore: PairingStore? = null,
    /**
     * The relationship surface's clock: it reads a share's end against it, and the relationship
     * store built here dates each item by it and ends it by it (#332). Injectable so a test can move
     * 90 days without sleeping; a caller that passes its own [relationStore] gives it the same clock.
     */
    relationClock: () -> Long = { System.currentTimeMillis() },
    /**
     * The scheduler for the server's chores ([Housekeeping]). Injectable so a test can drive its
     * ticker; this module registers the jobs on it, starts it, and stops it as the application stops.
     */
    housekeeping: Housekeeping? = null,
) {
    // Publish the trusted-proxy allowlist before any route runs: every per-client lockout and rate
    // limit reads it via ApplicationCall.clientAddress(). Empty (the default) means forwarded
    // headers are ignored and controls key on the direct peer, exactly as before.
    setTrustedProxies(config.trustedProxies)
    if (config.trustedProxies.isEmpty()) {
        log.warn(
            "DAYMARK_TRUSTED_PROXIES is unset: per-client lockouts and rate limits will key on the " +
                "direct peer address. Behind a reverse proxy that is the PROXY for every request, so " +
                "all clients share one bucket and a single attacker can lock out everyone. Set it to " +
                "your proxy's address on the internal network. See docs/COMPANION_DEPLOYMENT.md 4.0.",
        )
    }
    // The line above is a hint; unset is also correct for a directly-reachable deployment, so it
    // cannot tell "fine" from "forgotten". This turns it into evidence: it fires only when a
    // forwarded header actually arrives while the allowlist is empty.
    install(ProxyMisconfigWarning)
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

    // Answers "can this server still take a write?", which /healthz never could. See Readiness.
    val readiness = Readiness(config.dataDir)

    val store = if (config.syncEnabled) {
        blobStore ?: BlobStore(config.dataDir, config.maxBlobBytes, config.maxVersions, config.perTokenQuotaBytes)
    } else null
    // The owner's key documents (#258): the key parameters and the wrapped key that supersedes them.
    // Part of the sync API, so opened with it and on the same volume.
    val keyDocuments = if (config.syncEnabled) KeyDocumentStore(config.dataDir) else null

    // The owner's email, for notifications and access-token recovery (COMPANION_SECURITY.md §6,
    // "Owner notifications and server-access recovery"): the owner/bearer token now lives here, not
    // just in config — this is what makes it rotatable at runtime via the email-triggered recovery
    // flow without a restart. Bootstrapped from (and reconciled against, on every boot)
    // DAYMARK_AUTH_TOKEN; see OwnerAccountStore's kdoc for the reconciliation rule.
    val account = config.authToken?.let { token ->
        accountStore ?: OwnerAccountStore(config.dataDir, token)
    }
    val guard = account?.let {
        AuthGuard(it.currentTokenHash(), config.authLockoutFails, config.authLockoutSeconds * 1000, config.rateLimitRps)
    }

    // Built once and DI'd to the invite/notification services. When SMTP is disabled this never
    // opens a socket. Tests can inject an InMemory-backed mailer.
    val mail = mailer ?: Mailer.forConfig(config.mailer)
    val notifier = account?.let { OwnerNotifier(it, mail) }

    // The shape (#330): which route groups and pages this server serves, and so which stores it
    // opens. A group's stores are opened only in a shape that serves the group, so a solo server
    // writes no relationship, sign-in, audit or pairing file to the volume, and a paired one no
    // practice file. One line per start says which shape, and whether it was chosen or assumed.
    val shape = config.shape
    log.info(shapeLogLine(config))

    // The clinician group's stores: relationship blob channels and sign-in. Its routes also need the
    // owner bearer token (the owner-write direction, the mint route); without one they answer 503.
    val relStore = if (shape.clinicianGroup) {
        relationStore ?: RelationStore(config.dataDir, config.maxBlobBytes, config.relMaxVersions, config.relQuotaBytes, relationClock)
    } else null
    val auth = if (shape.clinicianGroup) {
        authStore ?: AuthStore(config.dataDir)
    } else null
    // Owner-readable audit log (COMPANION_SECURITY.md §9). Same group as the rest of the clinician
    // portal — it only makes sense once relationships/sessions exist.
    val audit = if (shape.clinicianGroup) {
        auditStore ?: AuditStore(config.dataDir, config.auditRetentionDays * 86_400L)
    } else null
    // Store-and-forward state for the CPace pairing exchange (COMPANION_PAIRING.md §4). Same
    // group: an exchange belongs to an invite, and invites only exist when the portal is on.
    val pairing = if (shape.clinicianGroup) {
        pairingStore ?: PairingStore(config.dataDir)
    } else null
    // The practice group's stores, in the practice shape only. The control plane authorises on
    // clinician portal sessions, so it has no meaning without the clinician group, which every shape
    // with the practice group also has.
    val orgs = if (shape.practiceGroup) {
        orgStore ?: OrgStore(config.dataDir)
    } else null
    // A SECOND audit chain, in its own database file. Same class, same hash chain, same
    // metadata-only contract — and a separate file so that a practice's membership history and a
    // patient's access history can never be keyed into the same table. See AuditStore's `dbName`.
    val orgAudit = if (shape.practiceGroup) {
        orgAuditStore ?: AuditStore(config.dataDir, config.auditRetentionDays * 86_400L, dbName = "org-audit.db")
    } else null

    // The server's scheduled chores (Housekeeping): each runs now, before a request is taken, and
    // then on its interval. Only the relationship store is swept; the sync API's snapshots are the
    // owner's own backups and have no end (#338). Ending a relationship deletes nothing by itself —
    // its items follow the same clock as everyone's.
    val chores = housekeeping ?: Housekeeping()
    if (relStore != null) {
        chores.every("relationship sweep", RELATION_SWEEP_INTERVAL_MS) { sweepRelationships(relStore) }
    }
    // Stopped as the application stops, before anything a chore uses can be closed under it.
    monitor.subscribe(ApplicationStopping) { chores.close() }
    chores.start()

    routing {
        // Unauthenticated, content-free LIVENESS probe — never under the base path.
        // "The process is up and routing." Deliberately says nothing about whether the server can
        // still accept a write: a full disk leaves every read working, and a proxy that pulls this
        // backend out of rotation over it would take away the operator's last way to read their own
        // data through the proxy. Point a load balancer here; point monitoring at /readyz.
        get("/healthz") {
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        }

        // Unauthenticated READINESS probe — "and it can still write." 503 when it cannot.
        // The body stays content-free for the same reason /healthz does: this endpoint is reachable
        // by anyone who can reach the app, and "which" failure it is belongs in the operator's log,
        // not in a response to an anonymous caller. Result is cached (see Readiness) so this cannot
        // be used to force one disk write per request.
        get("/readyz") {
            val result = readiness.check()
            if (result.ready) {
                call.respondText("""{"ok":true}""", ContentType.Application.Json)
            } else {
                call.respondText(
                    """{"ok":false}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
        }

        // Unauthenticated capability probe. Reveals whether the operator enabled outbound SMTP, so
        // the owner portal knows whether to offer the "send email invite" button, and the shape the
        // operator chose, so the first-run screen does not ask (#330). No secrets and no value the
        // operator typed: the shape is one of three fixed words, and anyone who can reach the server
        // can already read it from the routes.
        get("/v1/config") {
            call.respond(ServerConfigDto(smtpEnabled = config.smtpEnabled, setupMode = config.setupMode?.wire))
        }

        if (store != null && keyDocuments != null && guard != null) {
            syncRoutes(store, keyDocuments, guard, config.maxRequestBytes)
        } else {
            // Fail-closed: sync not configured. Cover the methods the API uses. Scope to the
            // exact sync paths so the therapist portal's /v1/rel + /v1/invite etc. can still be
            // registered independently below.
            get("/v1/snapshots/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
            put("/v1/snapshots/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
            get("/v1/keyparams") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
            put("/v1/keyparams") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
            get("/v1/keydoc") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
            post("/v1/keydoc") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
            put("/v1/keydoc/{...}") { call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto("sync API not configured")) }
        }

        // The clinician group (#330): on in paired and practice. Fail-closed: when it is off, every
        // path it answers answers 503 instead (clinicianGroupOff), so a probe cannot tell
        // configured-but-empty from not-configured. The group is all or nothing: a store missing
        // here would leave its routes answered by nothing at all rather than by the 503.
        if (relStore != null && auth != null && guard != null && audit != null && notifier != null &&
            pairing != null
        ) {
            relationRoutes(
                relStore, guard, auth, config.sessionIdleSeconds, config.maxRequestBytes,
                auditStore = audit, auditSourceIp = config.auditSourceIpEnabled,
                notifier = notifier, publicBaseUrl = config.publicBaseUrl,
                clock = relationClock,
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
                auditStore = audit,
                auditSourceIp = config.auditSourceIpEnabled,
            )
            // The therapist's public keys, on their way to the owner. Registered under the same
            // feature gate as the rest of the portal because it has no meaning without one: the
            // keys belong to a relationship, and relationships only exist when the portal is on.
            therapistKeyRoutes(
                authStore = auth,
                ownerGuard = guard,
                sessionIdleSeconds = config.sessionIdleSeconds,
                auditStore = audit,
                auditSourceIp = config.auditSourceIpEnabled,
            )

            ownerKeyRoutes(
                authStore = auth,
                ownerGuard = guard,
                sessionIdleSeconds = config.sessionIdleSeconds,
                auditStore = audit,
                auditSourceIp = config.auditSourceIpEnabled,
            )

            // A clinician ending their own access, and the owner reading back that it ended. Same
            // feature gate as the rest of the portal: an ending is a fact about a relationship, and
            // relationships only exist when the portal is on. See routes/RelationshipEndingRoutes.kt.
            relationshipEndingRoutes(
                authStore = auth,
                ownerGuard = guard,
                sessionIdleSeconds = config.sessionIdleSeconds,
                auditStore = audit,
                auditSourceIp = config.auditSourceIpEnabled,
            )
            // The CPace relay: opaque pairing blobs between the owner and a holder of the invite
            // link. The server never has the code and never parses a message — see the routes file.
            pairingRelayRoutes(
                authStore = auth,
                pairingStore = pairing,
                ownerGuard = guard,
                auditStore = audit,
                totpLockoutFails = config.totpLockoutFails,
                totpLockoutSeconds = config.totpLockoutSeconds,
                auditSourceIp = config.auditSourceIpEnabled,
            )
            auditRoutes(audit, guard)
            // The chain's own check: recompute the stored audit chain for one relationship and
            // report its head. Owner bearer token, same gate as the therapist-keys read — a head
            // plus a count per relRef is exactly the relationship metadata this server does not
            // hand to anonymous callers. See routes/AuditChainRoutes.kt for the whole argument.
            auditChainRoutes(audit, guard)
        } else {
            clinicianGroupOff()
        }

        // The practice group (#330): on in practice only, and fail-closed the same way. The org /
        // practice control plane: membership and roles only — it holds no key, serves no
        // ciphertext, and cannot mint a grant. See routes/OrgRoutes.kt for the whole argument.
        if (orgs != null && orgAudit != null && auth != null && guard != null) {
            orgRoutes(
                orgStore = orgs,
                authStore = auth,
                ownerGuard = guard,
                sessionIdleSeconds = config.sessionIdleSeconds,
                orgAudit = orgAudit,
                auditSourceIp = config.auditSourceIpEnabled,
            )
        } else {
            practiceGroupOff()
        }

        // The owner's email (COMPANION_SECURITY.md §6, "Owner notifications and server-access
        // recovery"): notification-email registration + the unauthenticated access-token recovery
        // flow. Gated on the sync/owner bearer token being configured at all (independent of the
        // therapist portal — recovery covers plain /v1 sync access too), fail-closed to 503
        // otherwise so a probe cannot tell configured-but-empty from absent.
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
        // serving; this route just gives it a clean URL. Same CSP headers apply. Both are served only
        // in a shape with the clinician group (pageRoutes).
        val therapistEntry = File(webRoot, Pages.CLINICIAN)
        val serveTherapist: suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
            if (therapistEntry.isFile) call.respondFile(therapistEntry)
            else call.respond(HttpStatusCode.NotFound, ErrorDto("therapist portal not built"))
        }

        /*
         * The invitation link's own path, and the reason this route exists at all.
         *
         * `buildInviteLink` has always addressed invitations to `{base}/portal/invite#id=...&s=...`,
         * and until now NOTHING served that path — the only occurrence of it in the repository was
         * the line constructing it. Every invitation this server has ever sent was a 404, which is
         * why the therapist half of the product could not be used and why the sign-in form ended up
         * asking a clinician to paste nine values by hand.
         *
         * It serves the therapist entry, not the owner's: `staticFiles`' `default` answers every
         * path that names no file with index.html, so without this route the link would land on the
         * owner's viewer, the wrong surface entirely. The fragment carrying the invitation is never
         * sent to the server (deliberately — see buildInviteLink), so this route sees only the path
         * and hands back the page that knows how to read the rest client-side.
         */
        val invitePaths = listOf("/portal/invite", "/portal/invite/")

        /*
         * IT REDIRECTS RATHER THAN SERVING, and that distinction is the entire bug.
         *
         * Serving therapist.html here returned 200 with the right markup and a completely BLANK
         * page. The bundle is built with `base: './'` (vite.config.ts), so every asset reference is
         * relative — and a browser resolves those against the DIRECTORY of the current URL. From
         * `/therapist` that is `/`, so `./assets/x.js` is `/assets/x.js` and everything loads. From
         * `/portal/invite` it is `/portal/`, so the browser asks for `/portal/assets/x.js`, the
         * static handler answers with index.html, and Chromium refuses every script and stylesheet
         * for having a `text/html` MIME type. Nothing renders. No error page — white.
         *
         * Worth recording how far this got: the route existed, the response was 200, the body
         * contained the therapist entry's own marker, a server test asserted exactly that and
         * passed, and both suites were green. The page still could not load a single byte of its
         * own JavaScript. It took rendering it in a real browser to see, which is the argument for
         * doing that at all.
         *
         * A 302 to `/therapist` fixes it because the browser CARRIES THE FRAGMENT across a redirect
         * whose target has none of its own — so `#id=...&s=...` survives, arrives at a URL whose
         * relative assets resolve, and therapist.ts reads it there exactly as before. The secret
         * still never reaches the server: a fragment is not sent with the request, and it is not in
         * the Location header either, because the browser reattaches it client-side.
         */
        val therapistPath = if (config.basePath == "/") "/therapist" else "${config.basePath.trimEnd('/')}/therapist"
        val redirectToTherapist: suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
            call.respondRedirect(therapistPath, permanent = false)
        }

        if (config.basePath == "/") {
            pageRoutes(shape, webRoot, invitePaths, serveTherapist, redirectToTherapist)
        } else {
            route(config.basePath) {
                pageRoutes(shape, webRoot, invitePaths, serveTherapist, redirectToTherapist)
            }
        }
    }
}

/**
 * The one line a start logs about its shape (#330), at info. It names the shape and the settings that
 * decided it, never a value the operator typed, and it is logged once per start, never per request.
 */
internal fun shapeLogLine(config: Config): String {
    val shape = config.shape
    return if (config.setupMode != null) {
        "Serving the ${shape.wire} shape, as DAYMARK_SETUP_MODE says: ${shape.serves}."
    } else {
        "Serving the ${shape.wire} shape, assumed because DAYMARK_SETUP_MODE is not set and " +
            "DAYMARK_THERAPIST_AUTH is ${if (config.therapistAuthEnabled) "on" else "off"}: ${shape.serves}. " +
            "Set DAYMARK_SETUP_MODE to solo, paired or practice to choose; see docs/COMPANION_DEPLOYMENT.md §0."
    }
}

/**
 * The one answer every route of a group that is off gives (#330): exactly what every clinician,
 * pairing and practice route answered with `DAYMARK_THERAPIST_AUTH` off, before the groups were split,
 * whichever group is off and in whichever shape. So a probe learns nothing from a group being off
 * that the old switch did not already tell it, and the body says "therapist portal" for the practice
 * group too, for that reason.
 */
private const val GROUP_OFF_ERROR = "therapist portal not configured"

private val groupOff: suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
    call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto(GROUP_OFF_ERROR))
}

/**
 * The clinician group, off: every method and path the group answers when it is on — relationship
 * channels and their revocation, the owner's access log, invitations, pairing, sign-in, the passkey
 * scaffold, keys and endings — answers 503 instead. A probe cannot tell "configured but nobody has
 * registered yet" (404) or "not signed in" (401) from "this shape has no clinicians" (503).
 * ShapeRoutingTest walks the routes of a server with the group on and asks every one of them here.
 */
private fun Route.clinicianGroupOff() {
    get("/v1/rel/{...}", groupOff)
    put("/v1/rel/{...}", groupOff)
    post("/v1/rel/{...}", groupOff)
    post("/v1/invite", groupOff)
    post("/v1/invite/{...}", groupOff)
    post("/v1/totp/{...}", groupOff)
    post("/v1/session/{...}", groupOff)
    get("/v1/webauthn/{...}", groupOff)
    post("/v1/webauthn/{...}", groupOff)
    get("/v1/relations/{...}", groupOff)
    post("/v1/relations/{...}", groupOff)
}

/**
 * The practice group, off: every method the org control plane answers, so a probe cannot tell "this
 * practice has no such member" (404) from "this shape has no practices" (503).
 */
private fun Route.practiceGroupOff() {
    get("/v1/orgs", groupOff)
    post("/v1/orgs", groupOff)
    get("/v1/orgs/{...}", groupOff)
    post("/v1/orgs/{...}", groupOff)
    delete("/v1/orgs/{...}", groupOff)
}

/**
 * The web build's pages, and the shapes that serve each (#330). The owner's page and the server
 * console are served in every shape; the clinician's page with the clinician group; the practice page
 * with the practice group. A page of the build that is not named here is served in every shape by the
 * static handler, which is why ShapeRoutingTest fails on a page it has not classified.
 */
internal object Pages {
    const val OWNER = "index.html"
    const val SERVER_CONSOLE = "admin.html"
    const val CLINICIAN = "therapist.html"
    const val PRACTICE = "practice.html"

    /** The page files [shape] does not serve. */
    fun notServed(shape: SetupMode): List<String> = listOfNotNull(
        CLINICIAN.takeUnless { shape.clinicianGroup },
        PRACTICE.takeUnless { shape.practiceGroup },
    )
}

/**
 * The pages, under the base path (#330).
 *
 * A page the shape does not serve is REFUSED AT THE FILE, not at a path. The static handler resolves
 * `/therapist.html/`, `/./therapist.html`, `/x/../therapist.html` and `/%2Ftherapist.html` to the same
 * file as `/therapist.html`, so a route on that one spelling would leave the others serving the page.
 * `exclude` sees the file the handler resolved, whatever the spelling, and the handler answers it 403
 * with no body. The clean paths that lead to the clinician's page — `/therapist` and the invitation
 * link's `/portal/invite` — answer the same 403, so "not served in this shape" has one answer, apart
 * from "not in this build", which `/therapist` answers 404.
 */
private fun Route.pageRoutes(
    shape: SetupMode,
    webRoot: File,
    invitePaths: List<String>,
    serveTherapist: suspend io.ktor.server.routing.RoutingContext.() -> Unit,
    redirectToTherapist: suspend io.ktor.server.routing.RoutingContext.() -> Unit,
) {
    if (shape.clinicianGroup) {
        get("/therapist") { serveTherapist() }
        invitePaths.forEach { p -> get(p) { redirectToTherapist() } }
    } else {
        val refused: suspend io.ktor.server.routing.RoutingContext.() -> Unit = { call.respond(HttpStatusCode.Forbidden) }
        get("/therapist", refused)
        invitePaths.forEach { p -> get(p, refused) }
    }
    val notServed = Pages.notServed(shape).map { File(webRoot, it) }
    staticFiles("/", webRoot) {
        default(Pages.OWNER)
        exclude { requested -> notServed.any { page -> isSamePage(requested, page) } }
    }
}

/**
 * Whether [requested] is the file [page], as the filesystem sees it: by identity rather than by name,
 * so another spelling of the same file — another case on a case-insensitive disk, a link — is the
 * same page. Two files that cannot be compared count as the same, so a failure refuses rather than
 * serves. Nothing is there to serve, or to refuse, when either is not a file.
 */
private fun isSamePage(requested: File, page: File): Boolean {
    if (!requested.isFile || !page.isFile) return false
    return runCatching { Files.isSameFile(requested.toPath(), page.toPath()) }.getOrDefault(true)
}
