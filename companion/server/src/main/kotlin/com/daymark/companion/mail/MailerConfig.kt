package com.daymark.companion.mail

import com.daymark.companion.Config

/**
 * Configuration for the ONE deliberate outbound exception: owner-configured SMTP.
 *
 * OFF by default. It is only [enabled] when [host] is non-blank (i.e. the operator set
 * `DAYMARK_SMTP_HOST`). When enabled it requires TLS (STARTTLS or implicit); a `TLS=none`
 * setting is rejected at startup — plaintext SMTP is never a mode. Credentials come via
 * `DAYMARK_SMTP_PASS(_FILE)` so they can be a mounted docker secret.
 *
 * See docs/COMPANION_SECURITY.md §6 (egress lockdown) and docs/COMPANION_DEPLOYMENT.md §8.
 */
data class MailerConfig(
    val host: String?,
    val port: Int,
    val user: String?,
    val pass: String?,
    val from: String?,
    val tls: TlsMode,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 15_000,
    /**
     * Dev-only escape hatch: allow http:// links in message bodies. Off in production so
     * a link can never be delivered over an unauthenticated scheme.
     */
    val allowInsecureLinks: Boolean = false,
) {
    /** True only when an SMTP host is configured. No socket is ever opened otherwise. */
    val enabled: Boolean get() = !host.isNullOrBlank()

    enum class TlsMode { STARTTLS, IMPLICIT }

    /**
     * Fail-closed validation, run at startup when [enabled]. Throws
     * [MailerConfigException] on any misconfiguration so the server refuses to boot with a
     * mailer that would send in the clear or with no envelope From.
     */
    fun validate() {
        if (!enabled) return
        if (from.isNullOrBlank()) {
            throw MailerConfigException("DAYMARK_SMTP_FROM is required when SMTP is enabled")
        }
        if (port !in 1..65535) {
            throw MailerConfigException("DAYMARK_SMTP_PORT out of range: $port")
        }
        // tls is a non-null enum here; a config value of 'none'/unknown is turned into an
        // exception in fromEnv rather than a silent fallback.
    }

    /** Never print the password. */
    override fun toString(): String =
        "MailerConfig(host=$host, port=$port, user=$user, pass=${if (pass.isNullOrEmpty()) "<none>" else "<redacted>"}, " +
            "from=$from, tls=$tls, connectTimeoutMs=$connectTimeoutMs, readTimeoutMs=$readTimeoutMs, " +
            "allowInsecureLinks=$allowInsecureLinks, enabled=$enabled)"

    companion object {
        const val DEFAULT_PORT = 587

        fun fromEnv(env: Map<String, String> = System.getenv()): MailerConfig {
            val host = env["DAYMARK_SMTP_HOST"]?.trim()?.ifBlank { null }
            val port = env["DAYMARK_SMTP_PORT"]?.trim()?.toIntOrNull() ?: DEFAULT_PORT
            val user = Config.envOrFile("DAYMARK_SMTP_USER", env)?.ifBlank { null }
            val pass = Config.envOrFile("DAYMARK_SMTP_PASS", env)?.ifBlank { null }
            val from = env["DAYMARK_SMTP_FROM"]?.trim()?.ifBlank { null }
            val allowInsecure = env["DAYMARK_SMTP_ALLOW_INSECURE_LINKS"]?.trim().equals("1", true) ||
                env["DAYMARK_SMTP_ALLOW_INSECURE_LINKS"]?.trim().equals("true", true)

            val enabled = !host.isNullOrBlank()
            val tls = parseTls(env["DAYMARK_SMTP_TLS"]?.trim(), enabled)

            return MailerConfig(
                host = host,
                port = port,
                user = user,
                pass = pass,
                from = from,
                tls = tls,
                allowInsecureLinks = allowInsecure,
            )
        }

        private fun parseTls(raw: String?, enabled: Boolean): TlsMode {
            val value = raw?.lowercase()?.ifBlank { null } ?: "starttls"
            return when (value) {
                "starttls" -> TlsMode.STARTTLS
                "implicit", "smtps" -> TlsMode.IMPLICIT
                "none", "plain", "plaintext" ->
                    if (enabled) {
                        throw MailerConfigException(
                            "DAYMARK_SMTP_TLS=none is not permitted — plaintext SMTP is refused; use starttls or implicit",
                        )
                    } else {
                        // Not enabled anyway; default so construction of a disabled config never throws.
                        TlsMode.STARTTLS
                    }
                else -> throw MailerConfigException("DAYMARK_SMTP_TLS unknown value: $raw (use starttls or implicit)")
            }
        }
    }
}

class MailerConfigException(message: String) : Exception(message)
