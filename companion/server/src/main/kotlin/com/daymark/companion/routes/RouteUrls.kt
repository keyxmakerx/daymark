package com.daymark.companion.routes

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * Resolve an absolute base URL for building a link to include in outbound email, for an
 * AUTHENTICATED caller (the invite mint route, therapist-enrolled/review notifications). Prefers
 * the configured [publicBaseUrl] (`DAYMARK_PUBLIC_BASE_URL` / `DAYMARK_WEBAUTHN_ORIGINS`);
 * otherwise falls back to the request's own scheme/`Host` as a best effort.
 *
 * This fallback is only acceptable because every caller of this function requires the caller to
 * already hold a valid owner bearer token or therapist session — an attacker who can spoof `Host`
 * here would need that credential already. The UNAUTHENTICATED access-token recovery flow
 * (`RecoveryRoutes.kt`) deliberately does NOT use this helper for exactly that reason: trusting a
 * client-controllable header to build a link mailed out by an anonymous request would let an
 * attacker point a real recovery token at a domain they control. See COMPANION_SECURITY.md.
 */
internal fun resolveBaseUrl(call: ApplicationCall, publicBaseUrl: String?): String {
    return publicBaseUrl?.trimEnd('/') ?: run {
        val scheme = call.request.origin.scheme
        val host = call.request.headers[HttpHeaders.Host] ?: "${call.request.origin.serverHost}:${call.request.origin.serverPort}"
        "$scheme://$host"
    }
}
