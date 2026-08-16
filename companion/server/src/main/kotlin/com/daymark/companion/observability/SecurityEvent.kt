package com.daymark.companion.observability

/**
 * # The security-event vocabulary
 *
 * This file is the *whole* of what a Daymark Companion server is allowed to say about itself on
 * stdout in machine-readable form. Everything here is a closed enum, and that is the point: a
 * future contributor who needs a new event, a new refusal reason, or a new detail field has to
 * edit this file to get one, which is where a reviewer sees it. There is deliberately no
 * free-form string anywhere in the schema — see [DetailValue].
 *
 * ## Why this is so restrictive
 *
 * The Companion server is a zero-knowledge relay for a mental-health journal. Every blob it holds
 * is ciphertext it cannot read, and the entire value of operating one is that operating it grants
 * no access to anyone's records. Logging is where that guarantee usually dies — not through the
 * cipher, but through a debug line that seemed harmless. A log record reaches the operator, the
 * container runtime, journald, and every downstream SIEM and log shipper, forever, with none of
 * the access control the data itself has.
 *
 * So the rule this schema enforces structurally: **a security event may say that the system is
 * under load or under attack, and nothing about any individual's mental health.** Concretely,
 * none of these can be expressed in this schema at all:
 *
 *  - request or response bodies, blob bytes, journal or entry text;
 *  - mail subjects, bodies, or recipient addresses;
 *  - TOTP secrets or codes, session cookies, bearer or inbox tokens, wrapped keys, passphrases;
 *  - the audit log's actor / action / objectRef;
 *  - a raw relationship reference (`relRef`) or lineage id. Those are SENSITIVE LINKAGE: they
 *    identify one specific owner–clinician relationship. They may only appear as a per-process
 *    keyed digest — see [RefKind] and `Correlator`.
 *
 * There are also **no location fields of any kind** — no geo-IP, no country, no region, nothing.
 * That is an absolute product constraint, and the closed [DetailKey] set is what enforces it: you
 * cannot add `country` to a line without adding it here first.
 *
 * ## Events deliberately NOT in the vocabulary
 *
 * - **Per-request bearer-token success.** `AuthGuard.authorize` returns OK on every single sync
 *   request. Emitting a line for each would make the log a minute-by-minute record of when the
 *   owner opens their journal and writes in it — which is a behavioural health record, delivered
 *   to the operator, in a product whose premise is that the operator gets nothing.
 *   [SecurityEventType.AUTH_SUCCESS] is therefore only for *session-establishing* authentication.
 * - **Share withdrawal.** There is a real code path (`POST …/{lineage}/revoke`), but "this person
 *   just cut their clinician off" is a fact about a therapeutic relationship, not about the
 *   server's security posture. It already lives in the owner's own hash-chained audit log, which
 *   is owner-readable and not the operator's.
 *
 * ## What a SIEM should alert on
 *
 * See `SecurityLog` for the full field-by-field documentation and the recommended detections.
 */

/** How loud this record is. Independent of the event, so an operator can tune by either axis. */
enum class Severity(val wire: String) {
    INFO("info"),
    NOTICE("notice"),
    WARNING("warning"),
    CRITICAL("critical"),
}

/**
 * The coarse axis a SIEM groups on: `outcome=failure OR outcome=refused` is the single cheapest
 * useful alert, before anyone learns the event names.
 */
enum class Outcome(val wire: String) {
    /** The thing being attempted was permitted and happened. */
    SUCCESS("success"),

    /** A credential or authorization check said no. Someone tried and was not allowed. */
    FAILURE("failure"),

    /** The server declined to act at all: a rate limit, a size cap, a refused configuration. */
    REFUSED("refused"),

    /** Something the server was trying to do broke. Not an attacker signal on its own. */
    ERROR("error"),
}

/**
 * **A ROLE, never an identity.** There is no user id, no credential id, no email address, and no
 * relationship reference in this field, and there never can be — it is an enum.
 */
enum class Principal(val wire: String) {
    /** Authenticated with the owner bearer token. */
    OWNER("owner"),

    /** Authenticated with a therapist session (or attempting to). */
    THERAPIST("therapist"),

    /** No credential presented, or the credential was not recognised. */
    ANONYMOUS("anonymous"),

    /**
     * The server itself, with no request behind it — startup config refusal, an outbound mail
     * failure. Kept distinct from [ANONYMOUS] so an operator alerting on anonymous attack traffic
     * does not have to filter out boot-time noise.
     */
    SYSTEM("system"),
}

/**
 * The closed event vocabulary.
 *
 * Adding a value here is the review point for "should a server operator be able to see this?".
 * `SecurityEventTest` pins the exact wire set on purpose, so an addition cannot land without a
 * test change and therefore without someone reading it.
 */
enum class SecurityEventType(val wire: String, val defaultSeverity: Severity) {

    /**
     * A **session-establishing** authentication succeeded — a therapist passed TOTP verification.
     * Deliberately NOT emitted for per-request bearer-token checks; see this file's header for
     * why that would be a behavioural record of the owner's journalling.
     */
    AUTH_SUCCESS("auth.success", Severity.INFO),

    /** A presented credential was wrong: bad bearer token, bad or replayed TOTP code. */
    AUTH_FAILURE("auth.failure", Severity.NOTICE),

    /** Repeated failures crossed a threshold and a lockout is now in force. */
    LOCKOUT_ENGAGED("lockout.engaged", Severity.WARNING),

    /** A source or credential that had been locked out is clear again. */
    LOCKOUT_RELEASED("lockout.released", Severity.INFO),

    /**
     * A state-changing request arrived without the second factor the route requires — in practice
     * a missing `X-CSRF-Token` on a therapist write.
     */
    STEP_UP_REQUIRED("stepup.required", Severity.NOTICE),

    /** The second factor was presented and did not match. Materially worse than it being absent. */
    STEP_UP_FAILED("stepup.failed", Severity.WARNING),

    /** A per-source budget (token bucket or fixed window) refused a request. */
    RATE_LIMITED("rate.limited", Severity.NOTICE),

    /** A single-use invite was redeemed successfully. */
    INVITE_CONSUMED("invite.consumed", Severity.NOTICE),

    /** An invite redemption was refused: wrong secret, already consumed, expired, or locked. */
    INVITE_REJECTED("invite.rejected", Severity.NOTICE),

    /** The owner published to a share channel. Carries no content and no lineage in the clear. */
    SHARE_PUBLISHED("share.published", Severity.INFO),

    /** A share blob was served to the counterparty. */
    SHARE_FETCHED("share.fetched", Severity.INFO),

    /** A share read was refused because it had expired or been withdrawn. */
    SHARE_REFUSED("share.refused", Severity.NOTICE),

    /** The unauthenticated access-token recovery request endpoint was reached. */
    RECOVERY_REQUESTED("recovery.requested", Severity.NOTICE),

    /** A recovery request was over its per-source hourly budget. */
    RECOVERY_RATE_LIMITED("recovery.rateLimited", Severity.WARNING),

    /**
     * A recovery link was confirmed and **the owner bearer token was rotated**. The single
     * highest-value line in this log: it is the one event that changes who can reach the data.
     */
    RECOVERY_CONFIRMED("recovery.confirmed", Severity.CRITICAL),

    /** A request was rejected on size or shape before any handler logic ran. */
    REQUEST_REJECTED("request.rejected", Severity.INFO),

    /** A configuration value was refused. With `startup=true` the process is not coming up. */
    CONFIG_REFUSED("config.refused", Severity.CRITICAL),

    /** An outbound notification failed to send. The one deliberate egress path is broken. */
    MAIL_SEND_FAILED("mail.sendFailed", Severity.WARNING),

    /**
     * An `X-Forwarded-For` arrived while `DAYMARK_TRUSTED_PROXIES` is empty. Every per-client
     * lockout and rate limit is therefore keying on the proxy, so all clients share one bucket
     * and one attacker can lock out everybody. See `RequestClient.warnIfForwardedButUntrusted`.
     */
    PROXY_TRUST_MISCONFIGURED("proxy.trustMisconfigured", Severity.WARNING),

    /** A therapist session was presented after its idle or absolute lifetime had elapsed. */
    SESSION_EXPIRED("session.expired", Severity.INFO),

    /**
     * The emitter itself could not build a record — a call site passed something the schema
     * rejects. Emitted *instead of* the offending record, never alongside it, so a malformed call
     * site fails closed rather than printing whatever it was holding. Alert on this: it means a
     * code path is trying to log something the schema will not carry.
     */
    EMIT_FAILED("log.emitFailed", Severity.WARNING),
}

/** The four scalar shapes a detail value may take. Enforced per key by [DetailKey.kind]. */
enum class DetailKind { COUNT, FLAG, LABEL, REF }

/**
 * The closed set of detail field names.
 *
 * A call site cannot invent a key, so it cannot invent a field. Combined with [DetailValue] —
 * which has no variant that carries free text — this is what makes leakage structurally
 * impossible rather than merely discouraged.
 *
 * Note what is absent and must stay absent: anything holding a body, a subject, an address, a
 * credential, a raw `relRef`, a raw lineage id, or a location.
 */
enum class DetailKey(val wire: String, val kind: DetailKind) {
    /** Why the server did what it did. A word from the closed [Label] vocabulary. */
    REASON("reason", DetailKind.LABEL),

    /** Which part of the server produced this. A word from the closed [Label] vocabulary. */
    SUBSYSTEM("subsystem", DetailKind.LABEL),

    /** How many failed attempts have accumulated. */
    ATTEMPTS("attempts", DetailKind.COUNT),

    /**
     * The configured ceiling that was hit — the *limit*, never the observed value. Logging the
     * size of a rejected snapshot PUT would tell the operator how much someone had written.
     */
    LIMIT("limit", DetailKind.COUNT),

    /**
     * How many records the emitter discarded since the previous one it admitted.
     *
     * Present only when it is non-zero, and it is the honest half of flood control: the bucket
     * exists so an attacker cannot fill an operator's disk by failing to log in, but a gap nobody
     * can see is worse than one they can. A record carrying `dropped` is telling the operator the
     * server is shedding log lines, which is itself a signal worth alerting on.
     */
    DROPPED("dropped", DetailKind.COUNT),

    /** Duration of a lockout that was just engaged, in seconds. */
    LOCKOUT_SECONDS("lockoutSeconds", DetailKind.COUNT),

    /** How long until a rate-limited caller may retry, in seconds. */
    RETRY_AFTER_SECONDS("retryAfterSeconds", DetailKind.COUNT),

    /**
     * A **per-process keyed digest** of a relationship reference. Named `relHash` so nobody
     * reading a log line can mistake it for the real thing. Correlates lines within one run of
     * one process; it cannot be reversed, and it cannot be joined against another deployment or
     * another run, because the key is random per process. See `Correlator`.
     */
    RELATIONSHIP("relHash", DetailKind.REF),

    /** Per-process keyed digest of a credential id. Same rules as [RELATIONSHIP]. */
    CREDENTIAL("credHash", DetailKind.REF),

    /** Per-process keyed digest of a blob lineage id. Same rules as [RELATIONSHIP]. */
    LINEAGE("lineageHash", DetailKind.REF),

    /** True when the refusal happened during startup and the process is therefore not coming up. */
    STARTUP("startup", DetailKind.FLAG),
}

/**
 * The closed vocabulary of words a [DetailKind.LABEL] field may hold.
 *
 * Every value is a structural word about the server's own behaviour. None of them can be made to
 * carry content, because the field is an enum: the wire strings below are the complete set of
 * strings that can ever appear in a `reason` or `subsystem` field.
 */
enum class Label(val wire: String) {
    // --- why a credential check failed -------------------------------------------------------
    BAD_CREDENTIAL("badCredential"),
    UNKNOWN_CREDENTIAL("unknownCredential"),
    REPLAYED_CODE("replayedCode"),
    LOCKED_OUT("lockedOut"),

    // --- why an object was refused -----------------------------------------------------------
    EXPIRED("expired"),
    REVOKED("revoked"),
    CONSUMED("consumed"),
    NOT_FOUND("notFound"),
    WRONG_DIRECTION("wrongDirection"),

    // --- second factor -----------------------------------------------------------------------
    MISSING_CSRF("missingCsrf"),
    CSRF_MISMATCH("csrfMismatch"),

    // --- session -----------------------------------------------------------------------------
    SESSION_IDLE_EXPIRED("sessionIdleExpired"),
    SESSION_ABSOLUTE_EXPIRED("sessionAbsoluteExpired"),

    // --- why a request was rejected ----------------------------------------------------------
    BODY_TOO_LARGE("bodyTooLarge"),
    MALFORMED_BODY("malformedBody"),
    MISSING_FIELD("missingField"),
    UNKNOWN_CHANNEL("unknownChannel"),

    // --- which budget refused ----------------------------------------------------------------
    TOKEN_BUCKET("tokenBucket"),
    SOURCE_BUDGET("sourceBudget"),

    // --- configuration refusals --------------------------------------------------------------
    TLS_REQUIRED("tlsRequired"),
    MISSING_FROM("missingFrom"),
    PORT_OUT_OF_RANGE("portOutOfRange"),
    UNREADABLE_SECRET_FILE("unreadableSecretFile"),

    // --- outbound mail -----------------------------------------------------------------------
    SMTP_TRANSPORT("smtpTransport"),
    CONTENT_GUARD("contentGuard"),

    // --- which subsystem ---------------------------------------------------------------------
    SUBSYSTEM_SMTP("smtp"),
    SUBSYSTEM_SYNC("sync"),
    SUBSYSTEM_THERAPIST_PORTAL("therapistPortal"),
    SUBSYSTEM_RECOVERY("recovery"),
    SUBSYSTEM_STORAGE("storage"),
    SUBSYSTEM_PROXY_TRUST("proxyTrust"),

    // --- emitter self-reporting --------------------------------------------------------------
    /** The record exceeded the line cap and its detail was dropped rather than truncated. */
    TRUNCATED("truncated"),

    /** Deliberately vague. Preferred over reaching for an exception message, which is free text. */
    UNKNOWN("unknown"),
}

/**
 * Domain separator for a keyed digest, so the same string used in two roles does not produce the
 * same digest. Without this, a credential id that happened to equal a relRef would silently join
 * two different kinds of line together.
 */
enum class RefKind(val wire: String) {
    RELATIONSHIP("rel"),
    CREDENTIAL("cred"),
    LINEAGE("lineage"),
    SOURCE("src"),
}

/**
 * **The reason a journal body cannot end up in this log.**
 *
 * A detail value is one of exactly four things, and not one of them is a caller-supplied string:
 *
 *  - [Count] — a number;
 *  - [Flag] — a boolean;
 *  - [Tag] — a word from the closed [Label] enum;
 *  - [Ref] — a keyed digest, which has **no public constructor at all**. The only way to obtain
 *    one is [Ref.of], which hashes its input unconditionally. There is no path by which a raw
 *    value reaches a `Ref`, not even by accident, and no path by which a pre-computed string can
 *    be passed off as a digest.
 *
 * So the usual leak — `detail["body"] = requestBody` — does not fail a review, it fails to
 * compile. `SecurityEventTest` additionally pins the variant list, so adding a `Text(String)`
 * variant breaks a test that says why.
 */
sealed interface DetailValue {
    val kind: DetailKind

    /** A number. Emitted as a JSON number. */
    data class Count(val value: Long) : DetailValue {
        override val kind: DetailKind get() = DetailKind.COUNT
    }

    /** A boolean. Emitted as a JSON boolean. */
    data class Flag(val value: Boolean) : DetailValue {
        override val kind: DetailKind get() = DetailKind.FLAG
    }

    /** A word from the closed [Label] vocabulary. */
    data class Tag(val value: Label) : DetailValue {
        override val kind: DetailKind get() = DetailKind.LABEL
    }

    /**
     * A per-process keyed digest of a sensitive linkage value (a relRef, a lineage id, a
     * credential id).
     *
     * Not a data class: no `copy()`, so there is no generated back door that takes a String.
     * The constructor is private and [of] is the only way in; [of] always hashes.
     */
    class Ref private constructor(val digest: String) : DetailValue {
        override val kind: DetailKind get() = DetailKind.REF

        /** Prints the digest, because that is all it holds — the raw value was never stored. */
        override fun toString(): String = "Ref($digest)"

        override fun equals(other: Any?): Boolean = other is Ref && other.digest == digest
        override fun hashCode(): Int = digest.hashCode()

        companion object {
            /**
             * The ONLY way to build a [Ref]. [raw] is hashed with [correlator]'s per-process key
             * and discarded; the caller cannot supply a pre-computed digest, so a raw relRef
             * cannot be smuggled through by dressing it up as one.
             */
            fun of(correlator: Correlator, kind: RefKind, raw: String): Ref =
                Ref(correlator.digest(kind, raw))
        }
    }
}

/** Raised when a call site hands the schema something it will not carry. See [SecurityEvent]. */
class SecurityLogViolation(message: String) : IllegalArgumentException(message)

/**
 * One security record, fully resolved and ready to render.
 *
 * The constructor validates and **throws** [SecurityLogViolation] on a schema violation. That is
 * deliberate and it is safe, because `SecurityLog.emit` constructs this inside its own guard: a
 * bad call site produces a [SecurityEventType.EMIT_FAILED] line, never an exception escaping into
 * a request handler. Tests construct it directly to assert the rejection.
 *
 * @param ts when the event happened. Rendered ISO-8601 UTC, millisecond precision.
 * @param type the closed event vocabulary; renders as `event`.
 * @param outcome the coarse success/failure/refused/error axis.
 * @param severity how loud; defaults come from [SecurityEventType.defaultSeverity].
 * @param principal the acting ROLE — never an identity.
 * @param sourceIp already resolved and already sanitised by the emitter. Null when the operator
 *   has source addresses switched off, or when there is no request behind the event.
 * @param detail at most [MAX_DETAIL_ENTRIES] entries, each value matching its key's
 *   [DetailKey.kind].
 */
data class SecurityEvent(
    val ts: java.time.Instant,
    val type: SecurityEventType,
    val outcome: Outcome,
    val severity: Severity,
    val principal: Principal,
    val sourceIp: String?,
    val detail: Map<DetailKey, DetailValue> = emptyMap(),
) {
    init {
        if (detail.size > MAX_DETAIL_ENTRIES) {
            throw SecurityLogViolation(
                "security event carries ${detail.size} detail entries, max is $MAX_DETAIL_ENTRIES",
            )
        }
        for ((key, value) in detail) {
            if (value.kind != key.kind) {
                // Named by key, never by value: the value is the thing that might be sensitive.
                throw SecurityLogViolation(
                    "detail key '${key.wire}' expects ${key.kind}, got ${value.kind}",
                )
            }
        }
    }

    companion object {
        /**
         * A hard cap on how much a single record can carry. Not primarily a size control — the
         * closed key set already bounds that — but a bound on how much a future contributor can
         * pile into one line before someone notices.
         */
        const val MAX_DETAIL_ENTRIES = 8
    }
}
