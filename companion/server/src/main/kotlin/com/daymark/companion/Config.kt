package com.daymark.companion

import java.io.File

/**
 * Runtime configuration, read from DAYMARK_* environment variables. Names match
 * docs/COMPANION_DEPLOYMENT.md. Every secret supports a `*_FILE` indirection so it can
 * be delivered as a mounted file (docker secret) instead of an inline env var.
 *
 * Milestone 1 (this scaffold) wires only what the static-serving + health server needs.
 * The blob-store / auth / share knobs from the deployment doc are recognised as reserved
 * names but not yet consumed — they arrive with the sync milestone.
 */
data class Config(
    val bindAddr: String,
    val port: Int,
    val dataDir: String,
    val basePath: String,
    val webDir: String,
    val logLevel: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val basePathRaw = env["DAYMARK_BASE_PATH"]?.trim().orEmpty().ifEmpty { "/" }
            return Config(
                bindAddr = env["DAYMARK_BIND_ADDR"]?.trim().orEmpty().ifEmpty { "0.0.0.0" },
                port = env["DAYMARK_PORT"]?.trim()?.toIntOrNull() ?: 8080,
                dataDir = env["DAYMARK_DATA_DIR"]?.trim().orEmpty().ifEmpty { "/data" },
                basePath = normalizeBasePath(basePathRaw),
                // Where the built web/dist assets live inside the container/runtime.
                webDir = env["DAYMARK_WEB_DIR"]?.trim().orEmpty().ifEmpty { "web" },
                logLevel = env["DAYMARK_LOG_LEVEL"]?.trim().orEmpty().ifEmpty { "info" },
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
         * (trimmed). The file form wins. Reserved for secrets in later milestones.
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
