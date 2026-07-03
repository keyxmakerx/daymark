package com.daymark.companion

import com.daymark.companion.mail.MailerConfig
import java.io.File

/**
 * Runtime configuration, read from DAYMARK_* environment variables. Names match
 * docs/COMPANION_DEPLOYMENT.md. Every secret supports a `*_FILE` indirection so it can
 * be delivered as a mounted file (docker secret) instead of an inline env var.
 */
data class Config(
    val bindAddr: String,
    val port: Int,
    val dataDir: String,
    val basePath: String,
    val webDir: String,
    val logLevel: String,
    // --- Sync blob store (Milestone 2) ---
    /** Bearer token clients present (Authorization: Bearer …). Null/blank => sync API disabled (fail-closed). */
    val authToken: String?,
    val maxBlobBytes: Long,
    val maxRequestBytes: Long,
    val maxVersions: Int,
    val perTokenQuotaBytes: Long,
    val authLockoutFails: Int,
    val authLockoutSeconds: Long,
    val rateLimitRps: Int,
    // --- Outbound SMTP (the ONE deliberate exception; OFF by default) ---
    /** SMTP config. Disabled unless DAYMARK_SMTP_HOST is set. See docs/COMPANION_SECURITY.md §6. */
    val mailer: MailerConfig = MailerConfig.fromEnv(emptyMap()),
    // --- Therapist portal (Milestone: server slice) ---
    /** Feature gate for the therapist auth + relationship-blob channels. Off unless DAYMARK_THERAPIST_AUTH=1. */
    val therapistAuthEnabled: Boolean = false,
    /** WebAuthn RP-ID / origins are config-pinned NOW even though verification is scaffold-only. */
    val webauthnRpId: String? = null,
    val webauthnOrigins: List<String> = emptyList(),
    /**
     * Explicit public origin for building absolute links in outbound email (invites,
     * review notifications, access-token recovery). Falls back to the first configured
     * WebAuthn origin if unset (many deployments already point that at the real external
     * origin). Never derived from a client-controllable `Host` header — see
     * COMPANION_SECURITY.md's trusted-proxy contract; the unauthenticated recovery route in
     * particular refuses to guess a base URL when this is unset rather than trusting the
     * request.
     */
    val publicBaseUrl: String? = null,
    /** Single-use invite TTL (default 72h). */
    val inviteTtlSeconds: Long = 259_200L,
    /** Idle / absolute session lifetimes (15 min / 8 h). */
    val sessionIdleSeconds: Long = 900L,
    val sessionAbsoluteSeconds: Long = 28_800L,
    /** TOTP verify lockout: fails before lockout, and lockout window. */
    val totpLockoutFails: Int = 5,
    val totpLockoutSeconds: Long = 300L,
    /** Per-relationship blob channel retention + quota. */
    val relMaxVersions: Int = 50,
    val relQuotaBytes: Long = 268_435_456L, // 256 MiB per relationship
    /**
     * Track T2 (email Option A): the unauthenticated access-token recovery request endpoint is
     * capped at this many attempts per source per hour (heavily rate-limited, per the mini-spec).
     */
    val reissueMaxPerHour: Int = 3,
    /** How long a minted recovery-confirmation link stays valid before it is GONE. */
    val reissueConfirmTtlSeconds: Long = 3600L,
    /**
     * Whether the therapist session cookie carries the `Secure` attribute. TRUE by default
     * (the portal requires a real TLS origin, per COMPANION_SECURITY.md open Q7). Only set
     * false for a plain-HTTP dev/test origin — the cookie would otherwise not be sent.
     */
    val cookieSecure: Boolean = true,
) {
    /** True when the sync API has a configured access token and may serve /v1. */
    val syncEnabled: Boolean get() = !authToken.isNullOrBlank()

    /** True only when the operator configured an outbound mail host. */
    val smtpEnabled: Boolean get() = mailer.enabled

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val basePathRaw = env["DAYMARK_BASE_PATH"]?.trim().orEmpty().ifEmpty { "/" }
            return Config(
                bindAddr = env["DAYMARK_BIND_ADDR"]?.trim().orEmpty().ifEmpty { "0.0.0.0" },
                port = env["DAYMARK_PORT"]?.trim()?.toIntOrNull() ?: 8080,
                dataDir = env["DAYMARK_DATA_DIR"]?.trim().orEmpty().ifEmpty { "/data" },
                basePath = normalizeBasePath(basePathRaw),
                webDir = env["DAYMARK_WEB_DIR"]?.trim().orEmpty().ifEmpty { "web" },
                logLevel = env["DAYMARK_LOG_LEVEL"]?.trim().orEmpty().ifEmpty { "info" },
                authToken = envOrFile("DAYMARK_AUTH_TOKEN", env)?.ifBlank { null },
                maxBlobBytes = env["DAYMARK_MAX_BLOB_BYTES"]?.trim()?.toLongOrNull() ?: 26_214_400L, // 25 MiB
                maxRequestBytes = env["DAYMARK_MAX_REQUEST_BYTES"]?.trim()?.toLongOrNull() ?: 27_262_976L,
                maxVersions = env["DAYMARK_MAX_VERSIONS"]?.trim()?.toIntOrNull() ?: 200,
                perTokenQuotaBytes = env["DAYMARK_PER_TOKEN_QUOTA_BYTES"]?.trim()?.toLongOrNull() ?: 5_368_709_120L, // 5 GiB
                authLockoutFails = env["DAYMARK_AUTH_LOCKOUT_FAILS"]?.trim()?.toIntOrNull() ?: 8,
                authLockoutSeconds = env["DAYMARK_AUTH_LOCKOUT_SECONDS"]?.trim()?.toLongOrNull() ?: 900L,
                rateLimitRps = env["DAYMARK_RATE_LIMIT_RPS"]?.trim()?.toIntOrNull() ?: 5,
                mailer = MailerConfig.fromEnv(env),
                therapistAuthEnabled = env["DAYMARK_THERAPIST_AUTH"]?.trim().let { it == "1" || it.equals("true", true) },
                webauthnRpId = env["DAYMARK_WEBAUTHN_RP_ID"]?.trim()?.ifBlank { null },
                webauthnOrigins = env["DAYMARK_WEBAUTHN_ORIGINS"]?.split(',')
                    ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                publicBaseUrl = env["DAYMARK_PUBLIC_BASE_URL"]?.trim()?.ifBlank { null }
                    ?: env["DAYMARK_WEBAUTHN_ORIGINS"]?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() },
                inviteTtlSeconds = env["DAYMARK_INVITE_TTL_SECONDS"]?.trim()?.toLongOrNull() ?: 259_200L,
                sessionIdleSeconds = env["DAYMARK_SESSION_IDLE_SECONDS"]?.trim()?.toLongOrNull() ?: 900L,
                sessionAbsoluteSeconds = env["DAYMARK_SESSION_ABSOLUTE_SECONDS"]?.trim()?.toLongOrNull() ?: 28_800L,
                totpLockoutFails = env["DAYMARK_TOTP_LOCKOUT_FAILS"]?.trim()?.toIntOrNull() ?: 5,
                totpLockoutSeconds = env["DAYMARK_TOTP_LOCKOUT_SECONDS"]?.trim()?.toLongOrNull() ?: 300L,
                relMaxVersions = env["DAYMARK_REL_MAX_VERSIONS"]?.trim()?.toIntOrNull() ?: 50,
                relQuotaBytes = env["DAYMARK_REL_QUOTA_BYTES"]?.trim()?.toLongOrNull() ?: 268_435_456L,
                cookieSecure = env["DAYMARK_COOKIE_INSECURE"]?.trim().let { !(it == "1" || it.equals("true", true)) },
                reissueMaxPerHour = env["DAYMARK_REISSUE_MAX_PER_HOUR"]?.trim()?.toIntOrNull() ?: 3,
                reissueConfirmTtlSeconds = env["DAYMARK_REISSUE_CONFIRM_TTL_SECONDS"]?.trim()?.toLongOrNull() ?: 3600L,
            )
        }

        /** Returns "/" or "/prefix" (leading slash, no trailing slash). */
        internal fun normalizeBasePath(raw: String): String {
            if (raw == "/" || raw.isBlank()) return "/"
            val trimmed = raw.trim().trim('/')
            return if (trimmed.isEmpty()) "/" else "/$trimmed"
        }

        /**
         * Read a value from `NAME` or, if `NAME_FILE` is set, from the file it points at
         * (trimmed). The file form wins.
         */
        fun envOrFile(name: String, env: Map<String, String> = System.getenv()): String? {
            env["${name}_FILE"]?.let { path ->
                val f = File(path)
                if (f.isFile) return f.readText().trim()
            }
            return env[name]?.trim()
        }
    }
}
