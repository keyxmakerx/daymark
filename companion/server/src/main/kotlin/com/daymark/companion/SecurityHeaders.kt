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
 *
 * ## `connect-src 'self'` and the "Server URL" fields — do not relax this
 *
 * Four connection surfaces (SyncPanel, OwnerConsole, RecoverAccess, therapist LoginGate) offer an
 * operator-editable absolute Server URL. Point one at a DIFFERENT origin and the request dies, and
 * the callers report it as a plain network error — RecoverAccess.svelte says "Could not reach that
 * server. Check the server URL and try again", above a comment asserting that the only way to land
 * there is an unreachable host or a bad URL. So the operator re-types a URL that was correct, at a
 * server that was up. That misdiagnosis is real. Widening `connect-src` is still the wrong repair,
 * on two counts:
 *
 * 1. It would not make the field work. This server installs no CORS plugin and registers no OPTIONS
 *    handler, so a cross-origin call has no `Access-Control-Allow-Origin` to read, and every
 *    authenticated one (Authorization / X-Rel-Token are non-simple headers) is preflighted into a
 *    response carrying no CORS headers at all. The user gets the identical "failed to fetch" from
 *    CORS instead of from CSP. Same dead field, minus a control.
 * 2. The control it costs is the one that matters most here. `connect-src 'self'` is what stops
 *    script running in this page from POSTing anywhere else — and the page holds a person's
 *    decrypted journal, their reading passphrase, and unwrapped key material in memory. Trading
 *    the exfiltration boundary for a field that would remain broken is not a trade.
 *
 * Note the case that already works: typing this server's own absolute URL is same-origin, which
 * `'self'` permits. Only a genuinely different origin is blocked, and that is a configuration the
 * server does not support at all.
 *
 * So the CSP is not the defect — the UI is, and the fix belongs there: drop the absolute-URL field
 * (or bind it to same-origin), and say "this page can only talk to the server that served it"
 * instead of reporting a browser refusal as an unreachable host. Those files are outside this one;
 * this note exists so the next reader does not "fix" the CSP instead.
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
