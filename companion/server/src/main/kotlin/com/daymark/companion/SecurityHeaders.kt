package com.daymark.companion

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.header

/**
 * Strict, vendored-asset Content-Security-Policy and hardening headers, applied to
 * EVERY response (including error pages) as real response headers — not a <meta> tag —
 * so they cannot be stripped by editing the document.
 *
 * The policy is `default-src 'self'` with exactly one relaxation: `wasm-unsafe-eval`
 * in script-src, reserved for the in-browser libsodium-WASM crypto the sync milestone
 * needs (NOT a blanket `unsafe-eval`). `img-src` additionally allows `blob:`/`data:` so
 * the browser can render images it decrypts in-memory. There is no `unsafe-inline`:
 * the UI ships one vendored stylesheet and uses no inline style attributes.
 *
 * CSP/SRI are defence-in-depth for the OWNER's browser; per COMPANION_SECURITY.md they
 * are explicitly NOT counted as a zero-knowledge guarantee against a malicious server.
 */
private const val CSP =
    "default-src 'self'; " +
        "base-uri 'none'; " + // the viewer never injects a <base> tag; 'none' per COMPANION_SECURITY.md
        "form-action 'self'; " +
        "frame-ancestors 'none'; " +
        "object-src 'none'; " +
        "script-src 'self' 'wasm-unsafe-eval'; " +
        "style-src 'self'; " +
        "img-src 'self' blob: data:; " +
        "font-src 'self'; " +
        "connect-src 'self'; " +
        "manifest-src 'self'"

private const val PERMISSIONS_POLICY =
    "geolocation=(), camera=(), microphone=(), payment=(), usb=(), magnetometer=(), accelerometer=()"

val SecurityHeaders = createApplicationPlugin(name = "SecurityHeaders") {
    onCall { call ->
        val h = call.response
        h.header("Content-Security-Policy", CSP)
        h.header("X-Content-Type-Options", "nosniff")
        h.header("X-Frame-Options", "DENY")
        h.header("Referrer-Policy", "no-referrer")
        h.header("Cross-Origin-Opener-Policy", "same-origin")
        h.header("Cross-Origin-Resource-Policy", "same-origin")
        h.header("Permissions-Policy", PERMISSIONS_POLICY)
        // HSTS is intentionally omitted here: TLS is terminated at the reverse proxy,
        // which is the correct place to set Strict-Transport-Security. See
        // docs/COMPANION_DEPLOYMENT.md.
    }
}
