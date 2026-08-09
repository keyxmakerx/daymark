package com.daymark.companion

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerFactory.getLogger("com.daymark.companion.proxy")

/**
 * Call-site access to the trusted-proxy allowlist.
 *
 * Kept in an application attribute rather than threaded through every route signature: the
 * allowlist is process-wide configuration, and the alternative was adding a `Config` parameter to a
 * dozen route helpers that have no other reason to know about it.
 */
private val TrustedProxiesKey = AttributeKey<List<ClientAddress.Range>>("daymark.trustedProxies")

/**
 * One-shot latch for [warnIfForwardedButUntrusted]. Per-application rather than a top-level
 * `AtomicBoolean` so tests in the same JVM do not silence each other.
 */
private val MisconfigWarningKey = AttributeKey<AtomicBoolean>("daymark.proxyMisconfigWarning")

/** Publishes the configured allowlist for [clientAddress]. Called once from `Application.module`. */
fun Application.setTrustedProxies(trusted: List<ClientAddress.Range>) {
    attributes.put(TrustedProxiesKey, trusted)
    attributes.put(MisconfigWarningKey, AtomicBoolean(trusted.isEmpty()))
}

private fun Application.trustedProxies(): List<ClientAddress.Range> =
    if (attributes.contains(TrustedProxiesKey)) attributes[TrustedProxiesKey] else emptyList()

/**
 * Turn the one misconfiguration that fails *quiet* into one that fails *loud*.
 *
 * `DAYMARK_TRUSTED_PROXIES` unset is a legitimate configuration — it is what a directly-reachable
 * deployment wants — so the startup warning in `Application.module` cannot distinguish "correct"
 * from "operator forgot", and a warning you always see is a warning nobody reads. An inbound
 * `X-Forwarded-For` while the allowlist is empty is different: something IS proxying, and the app
 * is ignoring what it says. Every per-client control is then keying on the proxy, so all clients
 * share one lockout bucket. Nothing else in the system reports this, because "ignore untrusted
 * headers" is also the correct response to a *spoofed* header.
 *
 * Logged once per process. Repeating it per request would be a log-flood amplifier reachable by
 * anyone who can send a header.
 */
internal fun ApplicationCall.warnIfForwardedButUntrusted() {
    val latch = application.attributes.getOrNull(MisconfigWarningKey) ?: return
    if (!latch.get()) return
    if (!request.headers.contains(HttpHeaders.XForwardedFor)) return
    if (!latch.compareAndSet(true, false)) return
    log.warn(
        "Received X-Forwarded-For from {} but DAYMARK_TRUSTED_PROXIES is EMPTY, so the header is " +
            "being ignored and every per-client lockout and rate limit is keying on the proxy's " +
            "address instead of the client's. If you run a reverse proxy, set " +
            "DAYMARK_TRUSTED_PROXIES to that address (as seen here: {}). If you do not, something " +
            "upstream is injecting the header and you should find out what. Logged once per start.",
        request.origin.remoteAddress,
        request.origin.remoteAddress,
    )
}

/**
 * Runs [warnIfForwardedButUntrusted] on every request, not only the ones that resolve a client
 * address. A deployment that is misconfigured this way should say so on the first request it ever
 * serves — waiting until someone hits an authenticated route means the warning surfaces during an
 * incident instead of during setup. Costs one already-false boolean read per call once it has
 * fired, or when the allowlist is configured.
 */
val ProxyMisconfigWarning = createApplicationPlugin("ProxyMisconfigWarning") {
    onCall { call -> call.warnIfForwardedButUntrusted() }
}

/**
 * **The address every per-client security control must key on** — lockouts, rate limits, and audit
 * `sourceIp`. Use this instead of `request.origin.remoteAddress`, which behind a reverse proxy is
 * the proxy's address for every request on the internet and therefore lumps all clients into one
 * bucket. See [ClientAddress] for the full reasoning and the trust rule.
 *
 * With no `DAYMARK_TRUSTED_PROXIES` configured this returns exactly `origin.remoteAddress`, so the
 * default behaviour is unchanged.
 */
fun ApplicationCall.clientAddress(): String = ClientAddress.resolve(
    peer = request.origin.remoteAddress,
    forwardedFor = request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty(),
    trusted = application.trustedProxies(),
)

/** [clientAddress] for helpers that hold only the request. */
fun ApplicationRequest.clientAddress(): String = call.clientAddress()
