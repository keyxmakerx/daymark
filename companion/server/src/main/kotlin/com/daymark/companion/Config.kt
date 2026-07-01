package com.daymark.companion

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
) {
    /** True when the sync API has a configured access token and may serve /v1. */
    val syncEnabled: Boolean get() = !authToken.isNullOrBlank()

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
