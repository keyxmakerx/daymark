package com.daymark.companion

import com.daymark.companion.mail.MailerConfig
import java.io.File
import java.net.URI

/**
 * A configuration the server will not start with. `main` logs [message] as one line and exits
 * with [EXIT_CONFIG], so an operator reads the setting to change rather than a stack trace, and
 * reads it again on every restart until it is changed (#180).
 *
 * The message names settings and says what to set. It never repeats a value as the operator wrote
 * it: logs carry no content or identifiers, and a value can be a hostname, a path or a secret. What
 * it may say back is which of a setting's fixed choices the server read, in the server's own
 * spelling — `DAYMARK_SETUP_MODE is paired` — since that is none of those (#330).
 */
class StartupRefusal(override val message: String) : Exception(message) {
    init {
        require('\n' !in message && '\r' !in message) { "a startup refusal is one line" }
    }
}

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
    /**
     * `DAYMARK_THERAPIST_AUTH`: on for `1` or `true`, off for anything else. The switch the setup mode
     * replaces (#330): it decides [shape] only when no [setupMode] is chosen, and with one chosen
     * [fromEnv] refuses a value that disagrees with it.
     */
    val therapistAuthEnabled: Boolean = false,
    /** WebAuthn RP-ID / origins are config-pinned NOW even though verification is scaffold-only. */
    val webauthnRpId: String? = null,
    val webauthnOrigins: List<String> = emptyList(),
    /**
     * The server's public address: the base of every link it hands out — invitations, review
     * notifications, access-token recovery. `DAYMARK_PUBLIC_BASE_URL`, else the first
     * `DAYMARK_WEBAUTHN_ORIGINS` entry; never a request's `Host` header, which a visitor controls
     * (COMPANION_SECURITY.md §5.5).
     *
     * [fromEnv] refuses to start without one, or with one that is not a usable absolute http(s)
     * address, whenever [buildsLinks] (#180), so a server that `main` started always has it there.
     * A sync-only server needs none. It is null alongside [buildsLinks] only in a [Config] built by
     * hand, as the tests build theirs.
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
    val relQuotaBytes: Long = 268_435_456L, // 256 MiB per relationship; a clinician may write a quarter (RelationStore)
    /** Owner-readable audit log (COMPANION_SECURITY.md §9): retention window, IP off by default. */
    val auditRetentionDays: Long = 90L,
    val auditSourceIpEnabled: Boolean = false,
    /**
     * The owner's access-token recovery: the unauthenticated request endpoint is capped at this
     * many attempts per source per hour (COMPANION_SECURITY.md §6, "Owner notifications and
     * server-access recovery").
     */
    val reissueMaxPerHour: Int = 3,
    /** How long a minted recovery-confirmation link stays valid before it is GONE. */
    val reissueConfirmTtlSeconds: Long = 3600L,
    /**
     * Whether the therapist session cookie carries the `Secure` attribute. TRUE by default
     * (the portal requires a real TLS origin, per COMPANION_SECURITY.md §5.4; what that means for
     * a LAN deployment is still open, in #205). Only set false (`DAYMARK_COOKIE_INSECURE`) for a
     * plain-HTTP dev/test origin — the cookie would otherwise not be sent. [fromEnv] refuses the
     * switch alongside an `https` public address (#181).
     */
    val cookieSecure: Boolean = true,
    /**
     * The trusted-proxy allowlist (`docs/COMPANION_DEPLOYMENT.md` §4.0) — comma-separated IPs and
     * CIDR blocks, e.g. `"10.89.0.2/32"`.
     *
     * **Default is trust-nothing**, and that default is load-bearing. Empty means forwarded headers
     * are ignored entirely and every per-source control keys on the direct peer — exactly what this
     * server did before this setting existed. There is deliberately no broad default like
     * `172.16.0.0/12`: that would let any co-resident container forge `X-Forwarded-For` and walk
     * straight past the auth lockout.
     *
     * Set this and per-client controls start distinguishing clients instead of lumping every
     * request behind the proxy into one bucket. Leave it unset behind a proxy and one attacker can
     * lock out every user. See [ClientAddress].
     */
    val trustedProxies: List<ClientAddress.Range> = emptyList(),
    /**
     * The shape the operator chose with `DAYMARK_SETUP_MODE`, or null when they chose none (#330).
     * Null is not a fourth shape: [shape] resolves it. Only a chosen shape is published on
     * `/v1/config`, because the first-run screen reads a published one as the configuration having
     * answered its question (`companion/web/src/lib/setup/shape.ts`).
     */
    val setupMode: SetupMode? = null,
) {
    /** True when the sync API has a configured access token and may serve /v1. */
    val syncEnabled: Boolean get() = !authToken.isNullOrBlank()

    /** True only when the operator configured an outbound mail host. */
    val smtpEnabled: Boolean get() = mailer.enabled

    /**
     * Whether the server's public address, [publicBaseUrl], is an `https` one: the one question the
     * session cookie's switch (#181) and pairing a phone (#189) both ask of it. Without it, a pairing
     * code is never minted, since the ceremony's two screens would both be on a path anyone on the
     * network can rewrite.
     */
    val publicAddressIsHttps: Boolean get() = publicBaseUrl?.startsWith("https://", ignoreCase = true) == true

    /**
     * The shape this server serves (#330): the one the operator chose or, with none chosen, the one
     * `DAYMARK_THERAPIST_AUTH` already meant. On, it switches the clinician and practice routes on
     * together and every page is served, which is [SetupMode.PRACTICE] exactly. Off, every clinician,
     * pairing and practice route answers 503, which is [SetupMode.SOLO]; a solo server also stops
     * serving the clinician and practice pages, whose every call answered 503 on it. `module` logs
     * which shape it assumed.
     */
    val shape: SetupMode get() = setupMode ?: if (therapistAuthEnabled) SetupMode.PRACTICE else SetupMode.SOLO

    /**
     * True when links to this server leave it: the clinician portal hands the owner invitation
     * links, and outbound email carries notification and recovery links. Either one makes
     * [publicBaseUrl] required at start (#180), whichever setting switched the portal on (#330).
     */
    val buildsLinks: Boolean get() = shape.clinicianGroup || smtpEnabled

    /**
     * A data class's generated `toString()` prints every property — including [authToken], the
     * bearer token that gates the entire sync API.
     *
     * Nothing logs a `Config` today. But "nothing logs it today" is one debug statement, one
     * exception message, or one `log.info("starting with {}", config)` away from a live credential
     * sitting in a log file that the operator then pastes into a bug report. `MailerConfig` already
     * redacts for exactly this reason; this is the same guard, eight files over.
     */
    override fun toString(): String =
        "Config(bindAddr=$bindAddr, port=$port, dataDir=$dataDir, basePath=$basePath, " +
            "webDir=$webDir, logLevel=$logLevel, syncEnabled=$syncEnabled, smtpEnabled=$smtpEnabled, " +
            "therapistAuthEnabled=$therapistAuthEnabled, setupMode=${setupMode?.wire ?: "unset"}, " +
            "shape=${shape.wire}, trustedProxies=${trustedProxies.size} entries, " +
            "authToken=${if (authToken.isNullOrBlank()) "unset" else "REDACTED"})"

    companion object {
        /** The address every refusal about the public address offers as its example. */
        internal const val EXAMPLE_PUBLIC_BASE_URL = "https://daymark.example.com"

        /**
         * The configuration `main` runs with, or a [StartupRefusal] naming the setting to change.
         *
         * The refusals live here, where `main` reads the environment, and not in
         * `Application.module`: a test that builds its own [Config] without a public address still
         * starts, and a deployment never does.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val therapistAuthRaw = env["DAYMARK_THERAPIST_AUTH"]?.trim().orEmpty()
            val therapistAuthOn = therapistAuthRaw == "1" || therapistAuthRaw.equals("true", true)
            // First, because every other check depends on the shape: a refusal about the public
            // address means nothing for a mode the server cannot read.
            val setupMode = readSetupMode(
                env["DAYMARK_SETUP_MODE"],
                therapistAuthSet = therapistAuthRaw.isNotEmpty(),
                therapistAuthOn = therapistAuthOn,
            )
            val basePathRaw = env["DAYMARK_BASE_PATH"]?.trim().orEmpty().ifEmpty { "/" }
            val webauthnOrigins = env["DAYMARK_WEBAUTHN_ORIGINS"]?.split(',')
                ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val explicitBaseUrl = env["DAYMARK_PUBLIC_BASE_URL"]?.trim()?.ifBlank { null }
            val config = Config(
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
                therapistAuthEnabled = therapistAuthOn,
                setupMode = setupMode,
                webauthnRpId = env["DAYMARK_WEBAUTHN_RP_ID"]?.trim()?.ifBlank { null },
                webauthnOrigins = webauthnOrigins,
                publicBaseUrl = explicitBaseUrl ?: webauthnOrigins.firstOrNull(),
                inviteTtlSeconds = env["DAYMARK_INVITE_TTL_SECONDS"]?.trim()?.toLongOrNull() ?: 259_200L,
                sessionIdleSeconds = env["DAYMARK_SESSION_IDLE_SECONDS"]?.trim()?.toLongOrNull() ?: 900L,
                sessionAbsoluteSeconds = env["DAYMARK_SESSION_ABSOLUTE_SECONDS"]?.trim()?.toLongOrNull() ?: 28_800L,
                totpLockoutFails = env["DAYMARK_TOTP_LOCKOUT_FAILS"]?.trim()?.toIntOrNull() ?: 5,
                totpLockoutSeconds = env["DAYMARK_TOTP_LOCKOUT_SECONDS"]?.trim()?.toLongOrNull() ?: 300L,
                relMaxVersions = env["DAYMARK_REL_MAX_VERSIONS"]?.trim()?.toIntOrNull() ?: 50,
                relQuotaBytes = env["DAYMARK_REL_QUOTA_BYTES"]?.trim()?.toLongOrNull() ?: 268_435_456L,
                cookieSecure = env["DAYMARK_COOKIE_INSECURE"]?.trim().let { !(it == "1" || it.equals("true", true)) },
                auditRetentionDays = env["DAYMARK_ACCESS_LOG_RETENTION_DAYS"]?.trim()?.toLongOrNull() ?: 90L,
                auditSourceIpEnabled = env["DAYMARK_ACCESS_LOG_SOURCE_IP"]?.trim()
                    .let { it == "1" || it.equals("true", true) },
                reissueMaxPerHour = env["DAYMARK_REISSUE_MAX_PER_HOUR"]?.trim()?.toIntOrNull() ?: 3,
                reissueConfirmTtlSeconds = env["DAYMARK_REISSUE_CONFIRM_TTL_SECONDS"]?.trim()?.toLongOrNull() ?: 3600L,
                trustedProxies = ClientAddress.parseTrusted(env["DAYMARK_TRUSTED_PROXIES"]),
            )
            refuseUnsafe(
                config,
                addressSetting = if (explicitBaseUrl != null) {
                    "DAYMARK_PUBLIC_BASE_URL"
                } else {
                    "the first entry of DAYMARK_WEBAUTHN_ORIGINS, standing in for the unset DAYMARK_PUBLIC_BASE_URL,"
                },
            )
            return config
        }

        /**
         * The shape `DAYMARK_SETUP_MODE` names, or null when it is unset or blank (#330). Blank is
         * unset because compose passes every setting it names, empty when `.env` leaves it out.
         *
         * Refused, each in one line naming the settings:
         * - A value that names none of the three. It is never read as a shape — least of all as
         *   "everything on" — and never repeated back, since a mistyped value can be anything.
         * - A shape `DAYMARK_THERAPIST_AUTH` contradicts. The mode replaces that switch: with a mode
         *   chosen the switch may be left out, and when it is set it must agree, on for paired and
         *   practice and off for solo. A value other than `1` or `true` has always meant off.
         */
        private fun readSetupMode(raw: String?, therapistAuthSet: Boolean, therapistAuthOn: Boolean): SetupMode? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            val mode = SetupMode.parse(value) ?: throw StartupRefusal(
                "Refusing to start: DAYMARK_SETUP_MODE is not one of solo, paired or practice. " +
                    "Set it to the one this server is for.",
            )
            if (!mode.clinicianGroup && therapistAuthOn) {
                throw StartupRefusal(
                    "Refusing to start: DAYMARK_SETUP_MODE is ${mode.wire} while DAYMARK_THERAPIST_AUTH is on, " +
                        "and a ${mode.wire} server has no clinician portal. Remove DAYMARK_THERAPIST_AUTH, which " +
                        "the setup mode replaces, or set DAYMARK_SETUP_MODE to paired or practice.",
                )
            }
            if (mode.clinicianGroup && therapistAuthSet && !therapistAuthOn) {
                throw StartupRefusal(
                    "Refusing to start: DAYMARK_SETUP_MODE is ${mode.wire}, which turns the clinician portal on, " +
                        "while DAYMARK_THERAPIST_AUTH is set to turn it off (anything but 1 or true is off). " +
                        "Remove DAYMARK_THERAPIST_AUTH, which the setup mode replaces, or set " +
                        "DAYMARK_SETUP_MODE to solo.",
                )
            }
            return mode
        }

        /**
         * Throws a [StartupRefusal] for a configuration the server must not run with.
         * [addressSetting] names where [publicBaseUrl] was read from, so a refusal points at the
         * setting the operator actually wrote.
         */
        private fun refuseUnsafe(config: Config, addressSetting: String) {
            refuseLinksWithoutAddress(config, addressSetting)
            refuseInsecureCookie(config, addressSetting)
        }

        /**
         * While [buildsLinks], the public address must be present and usable (#180). A link that
         * cannot take the configured address would have to take its host from the request, which a
         * visitor controls — and an invitation link carries the invitation's secret, so a link on a
         * host an attacker names hands the secret to them.
         */
        private fun refuseLinksWithoutAddress(config: Config, addressSetting: String) {
            if (!config.buildsLinks) return
            val address = config.publicBaseUrl
            val use = "Set DAYMARK_PUBLIC_BASE_URL to the address people type, for example $EXAMPLE_PUBLIC_BASE_URL"
            if (address == null) {
                // The setting that switched the portal on: the setup mode when one is chosen, else
                // the switch it replaces (#330).
                val portal = when {
                    !config.shape.clinicianGroup -> null
                    config.setupMode != null -> "DAYMARK_SETUP_MODE is ${config.setupMode.wire}"
                    else -> "DAYMARK_THERAPIST_AUTH is on"
                }
                val on = listOfNotNull(portal, "DAYMARK_SMTP_HOST is set".takeIf { config.smtpEnabled })
                    .joinToString(" and ")
                throw StartupRefusal(
                    "Refusing to start: DAYMARK_PUBLIC_BASE_URL is not set. $on, so this server sends " +
                        "links to itself, and it will not take its own address from a request. $use",
                )
            }
            if (!isUsableBaseUrl(address)) {
                throw StartupRefusal(
                    "Refusing to start: $addressSetting is not a usable address: it must begin with " +
                        "http:// or https://, name a host, and carry no user name, query or fragment. $use",
                )
            }
        }

        /**
         * `DAYMARK_COOKIE_INSECURE` is refused alongside an `https` public address (#181). The switch
         * drops `Secure` from the clinician's session cookie so a plain-http test origin can carry it;
         * a server people reach over https has no use for that, and with it the cookie is one
         * plain-http request away from crossing the network in the clear.
         */
        private fun refuseInsecureCookie(config: Config, addressSetting: String) {
            if (!config.cookieSecure && config.publicAddressIsHttps) {
                throw StartupRefusal(
                    "Refusing to start: DAYMARK_COOKIE_INSECURE is on while $addressSetting is an https " +
                        "address. The switch lets the clinician session cookie travel over plain http and " +
                        "is for local testing only. Remove DAYMARK_COOKIE_INSECURE.",
                )
            }
        }

        /**
         * Whether [raw] can be the base of a link: an absolute `http` or `https` address with a host
         * name, and nothing a path cannot be appended to — no user name, query or fragment, and no
         * empty path segment (`https://https://host` is a host named `https` with an empty
         * segment). The host is an IP literal or ASCII letters, digits, dots and hyphens, as
         * `java.net.URI` reads one: an underscore is refused, and an internationalised name is
         * written in its `xn--` form.
         */
        internal fun isUsableBaseUrl(raw: String): Boolean {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase()
            return (scheme == "http" || scheme == "https") &&
                !uri.host.isNullOrEmpty() &&
                (uri.port == -1 || uri.port in 1..65_535) &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                "//" !in uri.rawPath.orEmpty()
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
                // isFile() is a stat, not a readability check. A docker secret arrives with the
                // ownership of the file on the host, and Compose silently ignores the secrets
                // uid/gid/mode keys outside Swarm — so root:root 0600 is the common case while the
                // process runs as 65532. Calling readText() on that threw AccessDeniedException out
                // of the first statement of main(), before a single log line, which reads like a
                // crash rather than a permissions problem.
                if (f.isFile && !f.canRead()) {
                    val user = System.getProperty("user.name") ?: "unknown"
                    error(
                        "${name}_FILE is set to '$path' and that file exists, but this process " +
                            "(running as '$user') cannot read it. Docker secrets keep the HOST " +
                            "file's owner and mode — Compose ignores the secrets uid/gid/mode keys " +
                            "outside Swarm — so make it readable by the container user on the host: " +
                            "chown 65532:65532 <file> && chmod 400 <file>.",
                    )
                }
                if (f.isFile) {
                    return runCatching { f.readText().trim() }.getOrElse { cause ->
                        error("${name}_FILE is set to '$path' but reading it failed: ${cause.message}")
                    }
                }
            }
            return env[name]?.trim()
        }
    }
}
