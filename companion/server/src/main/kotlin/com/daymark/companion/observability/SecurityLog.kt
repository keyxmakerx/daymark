package com.daymark.companion.observability

import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * # The SIEM-ingestible security event log
 *
 * One JSON object per line on stdout, with a stable schema, so that Wazuh / Splunk / Elastic /
 * Loki / plain journald can match on a field instead of grepping prose. The vocabulary that may
 * appear in those fields lives in `SecurityEvent.kt`, which is where the review happens; this
 * file is the machinery that renders and writes it.
 *
 * ## The record
 *
 * ```json
 * {"ts":"2026-08-16T09:14:22.031Z","event":"auth.failure","outcome":"failure",
 *  "severity":"notice","principal":"therapist","sourceIp":"h:9f2c1ab4d0e77351",
 *  "detail":{"reason":"badCredential","attempts":3}}
 * ```
 *
 * Seven fields, always all seven, always in this order:
 *
 * | field       | type          | notes |
 * |-------------|---------------|-------|
 * | `ts`        | string        | ISO-8601 UTC, fixed-width millisecond precision, always `Z`. Fixed width so a regex parser can rely on the offsets. |
 * | `event`     | string        | A wire name from [SecurityEventType]. Closed set. |
 * | `outcome`   | string        | `success` \| `failure` \| `refused` \| `error`. Closed set. |
 * | `severity`  | string        | `info` \| `notice` \| `warning` \| `critical`. Closed set. |
 * | `principal` | string        | A ROLE: `owner` \| `therapist` \| `anonymous` \| `system`. **Never an identity.** |
 * | `sourceIp`  | string\|null  | See [SourceIpMode]. `null` when off or when no request is behind the event; `h:` prefix means it is a per-process digest, not an address. |
 * | `detail`    | object        | Keys from [DetailKey], values numbers / booleans / [Label] words / keyed digests. Never free text. |
 *
 * `detail` is always an object (`{}` when empty) so a schema-typed ingest pipeline never sees the
 * field change type between records.
 *
 * ## What a SIEM should alert on
 *
 * Ordered by what actually matters on a self-hosted zero-knowledge relay:
 *
 * 1. `event="recovery.confirmed"` — **page a human.** This is the one event that changes who can
 *    reach the data: the owner bearer token has just been rotated by someone who followed an
 *    emailed link. Expected roughly never. One of these that the owner did not initiate is a full
 *    compromise of server access.
 * 2. `severity="critical"` — currently `recovery.confirmed` and `config.refused`. A
 *    `config.refused` with `detail.startup=true` means the process is *not coming up*; that is an
 *    outage, and on a personal journal an outage is silent until someone tries to write.
 * 3. `event="lockout.engaged"` grouped by `sourceIp`, and `event="rate.limited"` /
 *    `"recovery.rateLimited"` counted per window — the ordinary brute-force and abuse detections.
 *    Rising `auth.failure` with *many distinct* `sourceIp` values is credential stuffing; rising
 *    `auth.failure` from one is a single attacker.
 * 4. `event="stepup.failed"` — a CSRF token that was present and wrong. Materially different from
 *    `stepup.required` (absent), and a strong signal of an actual cross-site attempt.
 * 5. `event="proxy.trustMisconfigured"` — deployment is wrong in a way that makes every other
 *    per-source detection above useless, because all clients are sharing the proxy's address.
 *    Fires once per process; treat it as a config alarm, not a security alarm.
 * 6. `event="log.emitFailed"` — a code path is trying to log something this schema refuses to
 *    carry. That is a bug worth seeing, and possibly an attempted leak.
 * 7. `event="mail.sendFailed"` — the one deliberate egress path is broken, so the owner is no
 *    longer being told about invites, reviews, or token reissue.
 *
 * Do **not** build detections on `share.published` / `share.fetched` volume per relationship.
 * They are here so that a burst of fetches is visible, not so that one person's therapy cadence
 * can be charted; `relHash` is per-process precisely to make the second use worthless.
 *
 * ## Where the line goes
 *
 * Through SLF4J to the logger named [LOGGER_NAME] — `daymark.security`, deliberately **outside**
 * the `com.daymark.companion` hierarchy. `DAYMARK_LOG_LEVEL` is applied to that hierarchy at
 * startup (`Application.applyLogLevel`), so an operator setting `warn` to quieten the app would
 * otherwise silence the security log too. Being outside it means the security log's level is
 * pinned by logback config alone. See the logback snippet in the wiring notes.
 */
class SecurityLog(
    /**
     * Where a rendered line goes. Injectable so the whole emitter is testable with no server and
     * no logging framework — the tests capture lines into a list.
     */
    private val sink: (String) -> Unit = DEFAULT_SINK,
    /** How much of a client address, if any, reaches the log. See [SourceIpMode]. */
    private val sourceIpMode: SourceIpMode = SourceIpMode.PSEUDONYM,
    /** The per-process digest key. One per [SecurityLog]; see [Correlator]. */
    private val correlator: Correlator = Correlator.perProcess(),
    private val clock: () -> Instant = Instant::now,
    private val maxLineBytes: Int = MAX_LINE_BYTES,
) {

    /**
     * Emit one security event.
     *
     * **This never throws and never blocks a request.** Building the record can legitimately fail
     * — a call site may pass a detail value the schema rejects — and the codebase's standing rule
     * is that a logging bug must never fail a real request (`auditSafely` in the route files says
     * the same thing). So the whole build is inside a guard, and a failure produces a
     * [SecurityEventType.EMIT_FAILED] line *instead of* the offending record. Instead, not
     * alongside: whatever the call site was holding when it got the schema wrong is exactly the
     * thing that must not be printed.
     *
     * @param sourceIp the resolved client address from `call.clientAddress()` — **never**
     *   `request.origin.remoteAddress`. Pass null for events with no request behind them. What
     *   actually reaches the log is decided by [sourceIpMode].
     */
    fun emit(
        event: SecurityEventType,
        outcome: Outcome,
        principal: Principal = Principal.ANONYMOUS,
        sourceIp: String? = null,
        severity: Severity = event.defaultSeverity,
        detail: List<Pair<DetailKey, DetailValue>> = emptyList(),
    ) {
        // Flood control, before anything is built.
        //
        // Several of these events fire on unauthenticated, attacker-chosen paths — auth failures,
        // recovery requests, rejected requests. Without a bound, someone who cannot get in can
        // still make the server write log lines as fast as it accepts connections, and the damage
        // lands on the operator: a filled disk, a spent SIEM ingest quota, and the real signal
        // buried under the noise that generated it. The existing per-source rate limiter does not
        // bound this, because it is per source and a distributed flood has many.
        //
        // Dropping beats blocking: a log write must never be able to stall a request path.
        if (!admits()) return
        val dropped = droppedSinceLastEmit.getAndSet(0)

        val line = try {
            val map = detail.toMap()
            if (map.size != detail.size) {
                throw SecurityLogViolation("duplicate detail keys in a single event")
            }
            SecurityJson.line(
                SecurityEvent(
                    ts = clock(),
                    type = event,
                    outcome = outcome,
                    severity = severity,
                    principal = principal,
                    sourceIp = renderSourceIp(sourceIp),
                    detail = if (dropped > 0) map + (DetailKey.DROPPED to DetailValue.Count(dropped)) else map,
                ),
                maxLineBytes,
            )
        } catch (_: Exception) {
            // Built from enum constants and a timestamp only, so it cannot itself fail. The
            // exception's message is deliberately DROPPED rather than logged: a message routinely
            // quotes the value that caused it, and the value is the thing that must not be printed.
            SecurityJson.emitFailedLine(runCatching(clock).getOrDefault(Instant.EPOCH))
        }
        try {
            sink(line)
        } catch (_: Exception) {
            // A broken sink (full disk, closed stream) must not turn into a failed request.
        }
    }

    /**
     * Mint a correlatable, non-reversible reference for a SENSITIVE LINKAGE value — a `relRef`, a
     * lineage id, a credential id. **Always pass the raw value here; never put it in a log line
     * yourself.** The result correlates lines within this process run and is worthless outside it.
     */
    fun ref(kind: RefKind, raw: String): DetailValue.Ref = DetailValue.Ref.of(correlator, kind, raw)

    /**
     * Decide what, if anything, of the client address is exported.
     *
     * An IP is personal data, and this codebase already treats it that way: the owner-readable
     * audit log only records one when the operator opts in with `DAYMARK_ACCESS_LOG_SOURCE_IP`.
     * Emitting raw addresses unconditionally here would widen where the IP goes — from an
     * opt-in, owner-readable field to every downstream log shipper — which is why the default is
     * [SourceIpMode.PSEUDONYM] and not [SourceIpMode.FULL].
     *
     * In [SourceIpMode.FULL] the value is checked against a literal-address shape and replaced
     * with `invalid` if it is anything else. That is not the injection defence — [SecurityJson]
     * escapes unconditionally and is tested separately — it is so that a nonsense value cannot
     * quietly become a "source" a SIEM then groups on.
     */
    private fun renderSourceIp(raw: String?): String? {
        if (raw == null) return null
        return when (sourceIpMode) {
            SourceIpMode.OFF -> null
            // "h:" so nobody, human or parser, mistakes a digest for an address.
            SourceIpMode.PSEUDONYM -> "h:" + correlator.digest(RefKind.SOURCE, raw)
            // The zone id is dropped, not just validated: it identifies a local interface, tells a
            // SIEM nothing, and was where a payload could ride in.
            SourceIpMode.FULL -> if (looksLikeAddress(raw)) raw.substringBefore('%') else "invalid"
        }
    }

    /**
     * A token bucket over the whole emitter: [MAX_LINES_PER_SECOND] sustained, [BURST] instantaneous.
     *
     * Deliberately global rather than per source. Per-source buckets are what the auth limiter
     * already has, and they are exactly what a distributed flood defeats — the resource being
     * protected here is the operator's disk and ingest quota, which is one shared thing however
     * many addresses the traffic arrives from.
     */
    private val tokens = AtomicLong(BURST)
    private val lastRefillNanos = AtomicLong(System.nanoTime())
    private val droppedSinceLastEmit = AtomicLong(0)

    private fun admits(): Boolean {
        val now = System.nanoTime()
        val previous = lastRefillNanos.getAndSet(now)
        val refill = (now - previous) / 1_000_000_000.0 * MAX_LINES_PER_SECOND
        if (refill >= 1) {
            tokens.updateAndGet { held -> minOf(BURST, held + refill.toLong()) }
        } else {
            // Do not lose sub-token time: put the clock back so the fraction accrues rather than
            // being reset by every call, which under load would refill nothing at all.
            lastRefillNanos.set(previous)
        }
        val taken = tokens.getAndUpdate { held -> if (held > 0) held - 1 else 0 }
        if (taken > 0) return true
        droppedSinceLastEmit.incrementAndGet()
        return false
    }

    companion object {
        /**
         * Outside `com.daymark.companion` on purpose — see the class KDoc. Fetched once; SLF4J's
         * single-argument `info(String)` does no `{}` substitution, which matters because every
         * line we hand it is full of braces.
         */
        const val LOGGER_NAME = "daymark.security"

        /**
         * A ceiling on one record. Unreachable with the schema as it stands (every field is an
         * enum, a number, a 16-hex digest or a 45-char address), which is the point: if it ever
         * *is* reached, something has been added that does not belong, and the detail is dropped
         * rather than printed. Tested with a reduced bound.
         */
        const val MAX_LINE_BYTES = 2048

        private val securityLogger by lazy { LoggerFactory.getLogger(LOGGER_NAME) }

        private val DEFAULT_SINK: (String) -> Unit = { line -> securityLogger.info(line) }

        /**
         * The sustained ceiling and the burst allowance.
         *
         * Far above anything a healthy deployment produces — this is a self-hosted server for one
         * owner and their clinician, where a busy minute is single-digit events — and far below
         * what fills a disk. An operator seeing `dropped` is being told the server is under a
         * flood, which is the signal worth having.
         */
        const val MAX_LINES_PER_SECOND = 200L
        const val BURST = 500L

        /**
         * A literal IPv4 / IPv6 address, optionally with a `%zone` suffix. Not a full validator —
         * `ClientAddress` already does the real parsing upstream — just a shape gate so only
         * address-looking things are exported as addresses.
         */
        /**
         * A literal address, re-parsed rather than shape-matched.
         *
         * This was a regex whose zone suffix was `(%[0-9A-Za-z._-]{1,32})?`, which admits
         * thirty-two characters of arbitrary alphanumerics after a `%`. Review demonstrated
         * `1.1%JBSWY3DPEHPK3PXP` — a TOTP secret — being exported verbatim as a `sourceIp`, along
         * with `fe80::1%sk-live-…` and a band label. The value is not reachable through
         * `ClientAddress.resolve` today, because `InetAddress.getByName` rejects an unknown zone
         * id, so nothing was leaking in practice. But this gate is the ONLY thing between a call
         * site and the log, and a gate whose stated job is that "a nonsense value cannot quietly
         * become a source" was passing nonsense.
         *
         * So it no longer matches a shape; it asks the parser. `InetAddress` is not consulted for
         * an unbracketed name — a hostname would be a DNS lookup on attacker-influenced input, and
         * `ClientAddress` refuses that for the same reason — so the pre-filter keeps the character
         * set to address characters and the parse decides.
         */
        private val ADDRESS_CHARS = Regex("^(?=.*[.:])[0-9A-Fa-f.:]{2,45}(%[0-9A-Za-z]{1,16})?$")

        internal fun looksLikeAddress(raw: String): Boolean {
            if (!ADDRESS_CHARS.matches(raw)) return false
            // Strip the zone before parsing: a zone id names a local interface, is meaningless to
            // any reader of these logs, and is the part that carried the payload.
            val bare = raw.substringBefore('%')
            if (bare.isEmpty()) return false
            val parsed = runCatching { InetAddress.getByName(bare) }.getOrNull() ?: return false
            /*
             * IPv4 must additionally survive the round trip; IPv6 must not be asked to.
             *
             * `InetAddress.getByName` is lenient about IPv4 in ways that matter here: it reads
             * "1.1" as 1.0.0.1, and several abbreviated and hex forms besides. Parsing alone would
             * have let `1.1%<secret>` through as the address "1.1" — the secret correctly stripped,
             * but a SIEM then grouping on a source that was never a real address and appears in
             * nobody's proxy logs. Requiring the text to equal its own canonical form closes that.
             *
             * The same check cannot be applied to IPv6, because Java canonicalises `2001:db8::1` to
             * its fully expanded `2001:db8:0:0:0:0:0:1` and the comparison would reject every
             * legitimate compressed address. It is not needed there: `ADDRESS_CHARS` has already
             * limited the input to hex and colons, and Java's IPv6 parser is strict about the rest.
             */
            if (bare.contains(':')) return true
            return parsed.hostAddress == bare
        }

        @Volatile
        private var installed: SecurityLog? = null

        private val fallback by lazy { SecurityLog() }

        /**
         * Publish the process-wide emitter. Called once from `Application.module`, before any
         * route is registered.
         *
         * A process-wide handle rather than a constructor parameter on every route: the
         * alternative was threading one more argument through a dozen route signatures that have
         * no other reason to know about logging, and the codebase already reaches for a
         * file-level logger the same way. The instance is fully injectable for tests, which
         * should build their own rather than installing one.
         */
        fun install(log: SecurityLog) {
            installed = log
        }

        /**
         * Puts the process-wide slot back to "nothing installed".
         *
         * For tests, and it exists because the obvious alternative is a trap. A test that finishes
         * by installing a do-nothing emitter has not restored anything — it has left a black hole
         * in a `@Volatile` that every later test in the same JVM shares, so any integration test
         * that captures emitted records afterwards captures none and passes for the wrong reason.
         * Gradle runs a module in one JVM and orders tests by a method-name hash, so which tests
         * that silently disables is not stable between runs.
         */
        internal fun uninstall() {
            installed = null
        }

        /** Whether anything has been installed. Distinguishes "not wired yet" from "wired". */
        internal fun isInstalled(): Boolean = installed != null

        /**
         * The process-wide emitter, or a default one (pseudonymous source addresses, SLF4J sink)
         * if nothing has been installed. Never null, so a call site added before the wiring lands
         * still logs rather than crashing.
         */
        fun current(): SecurityLog = installed ?: fallback
    }
}

/**
 * How much of a client address reaches the security log.
 *
 * The default is [PSEUDONYM] because it keeps every per-source detection working — "40 failures
 * from one source in a minute" needs a stable key, not a real address — while not exporting
 * personal data to every downstream log shipper by default. [FULL] exists because an operator
 * running fail2ban-style active response genuinely needs a bannable address, and that is their
 * call to make about their own deployment.
 */
enum class SourceIpMode {
    /** `sourceIp` is always null. The most conservative setting; per-source detections stop working. */
    OFF,

    /** `sourceIp` is `h:<16 hex>` — a per-process keyed digest. Correlates within a run only. */
    PSEUDONYM,

    /** `sourceIp` is the resolved client address verbatim. Personal data; opt in deliberately. */
    FULL,

    ;

    companion object {
        /**
         * Parse an operator-supplied setting. Anything unrecognised (including a typo) falls back
         * to [PSEUDONYM] rather than to [FULL] — a misconfiguration must not be what starts
         * exporting addresses.
         */
        fun parse(raw: String?): SourceIpMode = when (raw?.trim()?.lowercase()) {
            "off", "none", "0", "false" -> OFF
            "full", "raw", "ip" -> FULL
            else -> PSEUDONYM
        }
    }
}

/**
 * Turns a sensitive linkage value into something that correlates but does not identify.
 *
 * ## Why keyed, and why per process
 *
 * A plain hash of a `relRef` would be useless as a defence: relRefs come from a known, enumerable
 * space, so anyone holding the log and the ciphertext store could hash every relRef they can see
 * and undo it in a moment. A **keyed** hash (HMAC-SHA-256) with a key nobody has cannot be
 * inverted that way.
 *
 * The key is random per process and never persisted. That is the design, not a limitation:
 *
 *  - within one run, the same relRef always produces the same digest, so an operator can see that
 *    fifty refused reads all concern one relationship;
 *  - across restarts or across deployments, the digests are unrelated, so nobody can build a
 *    longitudinal profile of one person's therapy out of a year of archived logs, and two
 *    operators cannot join their logs together.
 *
 * A restart therefore breaks correlation. That is the intended trade: incident correlation lasts
 * as long as an incident, not as long as an archive.
 *
 * Digests are truncated to 64 bits (16 hex chars). Collisions are the only risk of truncation and
 * 64 bits is far past sufficient for the handful of distinct values one process run will ever see,
 * while keeping the line short and visibly *not* a raw value.
 */
class Correlator internal constructor(private val key: ByteArray) {

    /**
     * `HMAC-SHA-256(key, kind ‖ 0x00 ‖ raw)`, truncated to 16 lowercase hex chars.
     *
     * The `0x00` separator makes the domain prefix unambiguous, so `RefKind.REL` + `"ab"` cannot
     * collide with a hypothetical `RefKind.RELA` + `"b"`.
     *
     * `internal` rather than public: the only thing outside this package needs is
     * [DetailValue.Ref.of], and a public function handing back a hex string invites someone to
     * put it somewhere by hand.
     */
    internal fun digest(kind: RefKind, raw: String): String {
        // Mac is not thread-safe, so a fresh instance per call. Security events are low volume by
        // construction (see SecurityEvent.kt on why per-request success is not logged), so this is
        // not on any hot path.
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        mac.update(kind.wire.toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(raw.toByteArray(Charsets.UTF_8))
        val out = mac.doFinal()
        val sb = StringBuilder(DIGEST_HEX_CHARS)
        for (i in 0 until DIGEST_HEX_CHARS / 2) {
            val b = out[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4])
            sb.append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        private const val HMAC = "HmacSHA256"
        private const val DIGEST_HEX_CHARS = 16
        private val HEX = "0123456789abcdef".toCharArray()

        /** A fresh random key from the platform CSPRNG. One per [SecurityLog]. */
        fun perProcess(): Correlator {
            val key = ByteArray(32)
            SecureRandom().nextBytes(key)
            return Correlator(key)
        }
    }
}

/**
 * The JSON writer.
 *
 * Hand-written rather than delegated to a serializer, for one reason: **a log line must not be
 * forgeable by its own input.** A value carrying a `"` or a CRLF must not be able to close the
 * object early and start a second record, because a SIEM ingesting one-record-per-line would then
 * accept an attacker-authored record as genuine. Owning the escaping means the property is
 * testable directly, and `SecurityEventTest` tests it directly — including proving first that the
 * test's own detector catches a naive concatenation.
 *
 * With the closed schema, the only string that reaches [escape] from outside the process is
 * `sourceIp`, and that has already been shape-checked. The escaping is unconditional anyway:
 * a defence that only works because of an upstream check is one refactor from not working.
 */
internal object SecurityJson {

    /**
     * Fixed-width UTC, millisecond precision. `Instant.toString()` drops trailing zero
     * milliseconds, which gives variable-width timestamps and quietly breaks offset-based parsers;
     * an explicit pattern does not.
     */
    private val TS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Render one record as a single line, with no trailing newline (the sink adds that). */
    fun line(event: SecurityEvent, maxBytes: Int = SecurityLog.MAX_LINE_BYTES): String {
        val full = build(event, event.detail)
        if (full.toByteArray(Charsets.UTF_8).size <= maxBytes) return full

        // Over the cap: drop the detail rather than truncate it. A truncated JSON object is not
        // JSON, and a silently shortened one is a lie; a `reason:truncated` marker is honest and
        // still parses.
        val trimmed = build(event, mapOf(DetailKey.REASON to DetailValue.Tag(Label.TRUNCATED)))
        if (trimmed.toByteArray(Charsets.UTF_8).size <= maxBytes) return trimmed

        // Only reachable if the source address itself is pathological. Drop that too.
        return build(event.copy(sourceIp = null), mapOf(DetailKey.REASON to DetailValue.Tag(Label.TRUNCATED)))
    }

    /**
     * The record emitted when building a real one failed. Constructed from compile-time constants
     * and a timestamp, so it cannot itself throw and cannot carry anything the caller was holding.
     */
    fun emitFailedLine(ts: Instant): String = build(
        SecurityEvent(
            ts = ts,
            type = SecurityEventType.EMIT_FAILED,
            outcome = Outcome.ERROR,
            severity = SecurityEventType.EMIT_FAILED.defaultSeverity,
            principal = Principal.SYSTEM,
            sourceIp = null,
        ),
        emptyMap(),
    )

    private fun build(event: SecurityEvent, detail: Map<DetailKey, DetailValue>): String {
        val sb = StringBuilder(192)
        sb.append('{')
        field(sb, "ts", TS.format(event.ts)); sb.append(',')
        field(sb, "event", event.type.wire); sb.append(',')
        field(sb, "outcome", event.outcome.wire); sb.append(',')
        field(sb, "severity", event.severity.wire); sb.append(',')
        field(sb, "principal", event.principal.wire); sb.append(',')

        // Always present, even when null: a fixed field set means an ingest pipeline never has to
        // cope with a field appearing and disappearing between records.
        sb.append("\"sourceIp\":")
        if (event.sourceIp == null) sb.append("null") else escape(sb, event.sourceIp)
        sb.append(',')

        sb.append("\"detail\":{")
        // Sorted by declaration order so two records with the same detail render byte-identically,
        // which is what makes a golden-line test meaningful.
        var first = true
        for (key in DetailKey.entries) {
            val value = detail[key] ?: continue
            if (!first) sb.append(',')
            first = false
            escape(sb, key.wire)
            sb.append(':')
            when (value) {
                is DetailValue.Count -> sb.append(value.value.toString())
                is DetailValue.Flag -> sb.append(if (value.value) "true" else "false")
                is DetailValue.Tag -> escape(sb, value.value.wire)
                is DetailValue.Ref -> escape(sb, value.digest)
            }
        }
        sb.append("}}")
        return sb.toString()
    }

    private fun field(sb: StringBuilder, name: String, value: String) {
        escape(sb, name)
        sb.append(':')
        escape(sb, value)
    }

    /** Convenience overload for tests and for reading the escaping in isolation. */
    fun escape(s: String): String = StringBuilder(s.length + 2).also { escape(it, s) }.toString()

    /**
     * Append `s` as a JSON string literal.
     *
     * Escapes more than RFC 8259 strictly requires, and each extra is a specific defence:
     *
     *  - **C0 controls** (including CR, LF and ESC) — CR/LF are the log-injection vector: one of
     *    them inside a value would end the record and let the rest of the value be read as a
     *    second, attacker-authored record. ESC would let a value repaint an operator's terminal.
     *  - **DEL and the C1 range** — same terminal concern, and several log shippers treat C1 as
     *    a delimiter.
     *  - **U+2028 / U+2029** — legal inside a JSON string, but *line terminators* in ECMAScript.
     *    A log viewer that splits on JS line terminators before `JSON.parse` would see two
     *    records where the file has one. This is the subtle version of the same injection.
     */
    private fun escape(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch < ' ' || ch == '\u007F' -> unicodeEscape(sb, ch)
                ch in '\u0080'..'\u009F' -> unicodeEscape(sb, ch)
                ch == '\u2028' || ch == '\u2029' -> unicodeEscape(sb, ch)
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    private fun unicodeEscape(sb: StringBuilder, ch: Char) {
        sb.append("\\u")
        val hex = Integer.toHexString(ch.code)
        for (i in hex.length until 4) sb.append('0')
        sb.append(hex)
    }
}
