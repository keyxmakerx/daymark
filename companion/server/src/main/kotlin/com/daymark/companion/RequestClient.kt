package com.daymark.companion

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import io.ktor.util.AttributeKey

/**
 * Call-site access to the trusted-proxy allowlist.
 *
 * Kept in an application attribute rather than threaded through every route signature: the
 * allowlist is process-wide configuration, and the alternative was adding a `Config` parameter to a
 * dozen route helpers that have no other reason to know about it.
 */
private val TrustedProxiesKey = AttributeKey<List<ClientAddress.Range>>("daymark.trustedProxies")

/** Publishes the configured allowlist for [clientAddress]. Called once from `Application.module`. */
fun Application.setTrustedProxies(trusted: List<ClientAddress.Range>) {
    attributes.put(TrustedProxiesKey, trusted)
}

private fun Application.trustedProxies(): List<ClientAddress.Range> =
    if (attributes.contains(TrustedProxiesKey)) attributes[TrustedProxiesKey] else emptyList()

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
