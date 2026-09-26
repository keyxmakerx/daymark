package com.daymark.companion.routes

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * The absolute base of a link handed to an AUTHENTICATED caller: the invite mint route and the
 * therapist-enrolled / review notifications. In a running server this is always the configured
 * [publicBaseUrl] (`DAYMARK_PUBLIC_BASE_URL`, else the first `DAYMARK_WEBAUTHN_ORIGINS` entry):
 * `Config.fromEnv` refuses to start the clinician portal or outbound email without one (#180).
 *
 * The request's scheme and `Host` are read only when [publicBaseUrl] is null here, and only a
 * `Config` built by hand gets here with none — the tests build theirs that way. A deployment never
 * does: a visitor controls `Host`, and an invitation link carries its secret. The UNAUTHENTICATED
 * access-token recovery flow (`RecoveryRoutes.kt`) does not use this helper at all; with no
 * configured address it sends nothing. See COMPANION_SECURITY.md §5.5.
 */
internal fun resolveBaseUrl(call: ApplicationCall, publicBaseUrl: String?): String {
    return publicBaseUrl?.trimEnd('/') ?: run {
        val scheme = call.request.origin.scheme
        val host = call.request.headers[HttpHeaders.Host] ?: "${call.request.origin.serverHost}:${call.request.origin.serverPort}"
        "$scheme://$host"
    }
}
