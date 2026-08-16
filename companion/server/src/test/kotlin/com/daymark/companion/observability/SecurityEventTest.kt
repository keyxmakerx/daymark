package com.daymark.companion.observability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The security event log's two load-bearing properties, and the reason this file is long:
 *
 *  1. **Leakage is impossible, not merely discouraged.** A call site that tries to log a journal
 *     body, a TOTP secret, a bearer token or a raw relRef must not be able to succeed.
 *  2. **A record cannot be forged by its own input.** A CRLF or a quote inside a value must not be
 *     able to end the record and start a second one that a SIEM would then ingest as genuine.
 *
 * Both are "X is absent" properties, and an "X is absent" assertion is worthless until the
 * detector has been shown to see X when it *is* present. So every absence test here is preceded by
 * a test that feeds the same detector a deliberately bad line and asserts it fires. Those
 * `... detector sees ...` tests are not padding; they are what stops this file from being a green
 * check that asserts nothing.
 */
class SecurityEventTest {

    private val at: Instant = Instant.parse("2026-08-16T09:14:22.031Z")

    /** A fixed key so digests are reproducible within a test; production keys are random. */
    private fun keyed(seed: Int) = Correlator(ByteArray(32) { (it + seed).toByte() })

    private fun logTo(
        out: MutableList<String>,
        mode: SourceIpMode = SourceIpMode.PSEUDONYM,
        correlator: Correlator = keyed(1),
        maxLineBytes: Int = SecurityLog.MAX_LINE_BYTES,
        clock: () -> Instant = { at },
    ) = SecurityLog(
        sink = { out += it },
        sourceIpMode = mode,
        correlator = correlator,
        clock = clock,
        maxLineBytes = maxLineBytes,
    )

    private fun parse(line: String) = Json.parseToJsonElement(line).jsonObject

    private fun str(line: String, field: String) = parse(line)[field]!!.jsonPrimitive.content

    // -----------------------------------------------------------------------------------------
    // The wire shape a SIEM ingests
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a record is one line of valid JSON with the seven fixed fields in a fixed order`() {
        val out = mutableListOf<String>()
        logTo(out).emit(
            SecurityEventType.AUTH_FAILURE,
            Outcome.FAILURE,
            principal = Principal.THERAPIST,
            sourceIp = "203.0.113.9",
            detail = listOf(
                DetailKey.REASON to DetailValue.Tag(Label.BAD_CREDENTIAL),
                DetailKey.ATTEMPTS to DetailValue.Count(3),
            ),
        )

        assertEquals(1, out.size, "one emit must produce exactly one record")
        val line = out.single()
        assertEquals(1, line.lines().size, "a record must occupy exactly one line")

        // The order is part of the contract: a regex-based parser (journald + a Wazuh decoder, the
        // cheapest possible setup) relies on it, and a JSON parser does not care either way.
        assertEquals(
            listOf("ts", "event", "outcome", "severity", "principal", "sourceIp", "detail"),
            parse(line).keys.toList(),
        )
    }

    @Test
    fun `ts is fixed-width ISO-8601 UTC with millisecond precision`() {
        val out = mutableListOf<String>()
        // A whole-second instant is the case that catches Instant.toString(), which drops the
        // fractional part entirely and yields a shorter timestamp than every other record.
        logTo(out, clock = { Instant.parse("2026-01-02T03:04:05Z") })
            .emit(SecurityEventType.AUTH_SUCCESS, Outcome.SUCCESS)

        assertEquals("2026-01-02T03:04:05.000Z", str(out.single(), "ts"))
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""").matches(str(out.single(), "ts")))
    }

    @Test
    fun `detail is always an object so its type never changes between records`() {
        val out = mutableListOf<String>()
        val log = logTo(out)
        log.emit(SecurityEventType.SESSION_EXPIRED, Outcome.FAILURE)
        log.emit(
            SecurityEventType.REQUEST_REJECTED,
            Outcome.REFUSED,
            detail = listOf(DetailKey.REASON to DetailValue.Tag(Label.BODY_TOO_LARGE)),
        )

        assertEquals(emptyMap(), parse(out[0])["detail"]!!.jsonObject.toMap())
        assertEquals("bodyTooLarge", parse(out[1])["detail"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `numbers and booleans render as JSON scalars, not as strings`() {
        val out = mutableListOf<String>()
        logTo(out).emit(
            SecurityEventType.CONFIG_REFUSED,
            Outcome.REFUSED,
            principal = Principal.SYSTEM,
            detail = listOf(
                DetailKey.LIMIT to DetailValue.Count(26_214_400),
                DetailKey.STARTUP to DetailValue.Flag(true),
            ),
        )
        val detail = parse(out.single())["detail"]!!.jsonObject
        assertEquals(26_214_400L, detail["limit"]!!.jsonPrimitive.longOrNull)
        assertEquals(true, detail["startup"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `severity defaults to the event type's own severity and can be raised at the call site`() {
        val out = mutableListOf<String>()
        val log = logTo(out)
        log.emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE)
        log.emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, severity = Severity.CRITICAL)

        assertEquals(SecurityEventType.AUTH_FAILURE.defaultSeverity.wire, str(out[0], "severity"))
        assertEquals("critical", str(out[1], "severity"))
    }

    @Test
    fun `the whole record renders byte-for-byte as documented`() {
        // A golden line. Pins field names, order, quoting, and number formatting all at once, so a
        // change to any of them has to be made deliberately and explained to a reviewer.
        val out = mutableListOf<String>()
        val correlator = keyed(7)
        val log = logTo(out, correlator = correlator)
        log.emit(
            SecurityEventType.SHARE_REFUSED,
            Outcome.REFUSED,
            principal = Principal.THERAPIST,
            sourceIp = "198.51.100.7",
            detail = listOf(
                DetailKey.REASON to DetailValue.Tag(Label.REVOKED),
                DetailKey.RELATIONSHIP to log.ref(RefKind.RELATIONSHIP, "rel-abc"),
            ),
        )
        // The two digests are computed rather than pasted — they depend on HMAC, which is not
        // something to hand-write into a test — but everything around them is literal.
        val src = correlator.digest(RefKind.SOURCE, "198.51.100.7")
        val rel = correlator.digest(RefKind.RELATIONSHIP, "rel-abc")
        assertEquals(
            """{"ts":"2026-08-16T09:14:22.031Z","event":"share.refused","outcome":"refused",""" +
                """"severity":"notice","principal":"therapist","sourceIp":"h:$src",""" +
                """"detail":{"reason":"revoked","relHash":"$rel"}}""",
            out.single(),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Log injection — a value must never be able to forge a second record
    // -----------------------------------------------------------------------------------------

    /** A value crafted to close the JSON object and open a fresh, attacker-authored record. */
    private val forging =
        "203.0.113.9\"}\r\n{\"ts\":\"2026-01-01T00:00:00.000Z\",\"event\":\"auth.success\",\"x\":\""

    @Test
    fun `the injection detector sees a forged record when one is really there`() {
        // Prove the detector before trusting it. This is what the emitter must NOT produce.
        val naive = """{"ts":"2026-08-16T09:14:22.031Z","sourceIp":"$forging"}"""

        assertEquals(2, naive.lines().size, "a naive concatenation must split into two lines")
        assertTrue(
            naive.lines()[1].startsWith("""{"ts":"""),
            "the second line must look like a genuine record — that is the attack",
        )
        assertTrue(
            naive.lines()[1].contains(""""event":"auth.success""""),
            "and it must carry the forged event a SIEM would then believe",
        )
    }

    @Test
    fun `a CRLF in a field cannot forge a second record`() {
        // Rendered through SecurityJson directly, so the emitter's source-address shape check is
        // out of the way and the ESCAPER alone is under test. Nothing else in the schema can carry
        // a caller-supplied string, so this is the whole of the attack surface.
        val line = SecurityJson.line(
            SecurityEvent(
                ts = at,
                type = SecurityEventType.AUTH_FAILURE,
                outcome = Outcome.FAILURE,
                severity = Severity.NOTICE,
                principal = Principal.ANONYMOUS,
                sourceIp = forging,
            ),
        )

        assertEquals(1, line.lines().size, "the record must still be one line")
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))

        // Stronger than "no newline": the value must round-trip exactly, which is only true if the
        // escaping is correct rather than merely destructive, and the record's own fields must be
        // the ones we wrote — not the forged ones.
        val obj = parse(line)
        assertEquals(forging, obj["sourceIp"]!!.jsonPrimitive.content)
        assertEquals("auth.failure", obj["event"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("ts", "event", "outcome", "severity", "principal", "sourceIp", "detail"),
            obj.keys.toList(),
            "the forged \"x\" field must not have been spliced in",
        )
    }

    @Test
    fun `escaping covers quotes, backslashes, C0 and C1 controls, DEL, and the JS line separators`() {
        val hostile = mapOf(
            "\"" to "\\\"",
            "\\" to "\\\\",
            "\n" to "\\n",
            "\r" to "\\r",
            "\t" to "\\t",
            "\u0000" to "\\u0000",
            "\u001B" to "\\u001b", // ESC — a value must not be able to repaint an operator's terminal
            "\u007F" to "\\u007f", // DEL
            "\u0085" to "\\u0085", // C1 NEL, a line terminator to several log shippers
            "\u2028" to "\\u2028", // JS line separator: splits a record in a browser-based viewer
            "\u2029" to "\\u2029",
        )
        for ((raw, expected) in hostile) {
            assertEquals("\"$expected\"", SecurityJson.escape(raw), "escaping of U+%04X".format(raw[0].code))
        }

        // And the combined string still round-trips through a real parser.
        val all = hostile.keys.joinToString("")
        val line = SecurityJson.line(
            SecurityEvent(at, SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, Severity.NOTICE, Principal.ANONYMOUS, all),
        )
        assertEquals(1, line.lines().size)
        assertEquals(all, parse(line)["sourceIp"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a forging client address is refused by the emitter before it ever reaches the escaper`() {
        val out = mutableListOf<String>()
        logTo(out, mode = SourceIpMode.FULL).emit(
            SecurityEventType.AUTH_FAILURE,
            Outcome.FAILURE,
            sourceIp = forging,
        )

        assertEquals(1, out.size)
        assertEquals(1, out.single().lines().size)
        assertEquals("invalid", str(out.single(), "sourceIp"))
        assertFalse(out.single().contains("auth.success"), "no trace of the forged record survives")
    }

    // -----------------------------------------------------------------------------------------
    // Leakage — the point of the whole exercise
    // -----------------------------------------------------------------------------------------

    /**
     * The things this server exists to not disclose. Every one of them is something a call site
     * might plausibly reach for while debugging.
     */
    private val secrets = listOf(
        "Some days I cannot get out of bed and I think about",  // journal text
        "PHQ-9 total 21, severe",                               // an assessment result
        "Subject: your session notes",                          // a mail subject
        "patient.name@example.org",                             // a recipient address
        "Bearer sk-live-ZmFrZS10b2tlbi12YWx1ZQ",                // the owner bearer token
        "JBSWY3DPEHPK3PXP",                                     // a TOTP secret
        "daymark_session=Zm9vYmFyc2Vzc2lvbmlk",                 // a session cookie
        "rel-9c1f0b7a5e2d4c3b8a6f",                             // a raw relRef: sensitive linkage
        // THE SHAPES THAT ACTUALLY GOT THROUGH. Every sentinel above contains a space, '@', '='
        // or a non-hex letter, so the address gate rejected all of them on punctuation alone —
        // which meant this fixture proved "strings with punctuation are refused" and not "the gate
        // is tight". The two shapes it could not express are the two that survived: hex-with-a-dot,
        // and anything riding in on a %zone suffix. Both are real payload carriers, and both are
        // red against the gate as it was written.
        "1.1%JBSWY3DPEHPK3PXP",                                 // a TOTP secret in a zone id
        "fe80::1%sk-live-ZmFrZStva2VuLXZhbHVl",                 // a token in a zone id
        "cafe.beef",                                            // hex-only, no zone, still not an address
    )

    private fun secretIn(text: String): String? = secrets.firstOrNull { text.contains(it) }

    @Test
    fun `the leak detector sees a secret when one is really there`() {
        // Prove the detector, exactly as MailContentGuardTest does for the mail sentinels.
        val naive = """{"ts":"2026-08-16T09:14:22.031Z","event":"auth.failure","note":"${secrets[0]}"}"""
        assertEquals(secrets[0], secretIn(naive))
        assertNull(secretIn("""{"ts":"2026-08-16T09:14:22.031Z","event":"auth.failure"}"""))
    }

    @Test
    fun `no secret can be routed into a record through any emit parameter`() {
        // There are exactly three places a call site could try to put one: the source address, and
        // the two detail kinds that accept a caller-supplied value at all. Try all of them, with
        // every secret, and assert nothing survives into the output.
        val out = mutableListOf<String>()
        val log = logTo(out)
        for (secret in secrets) {
            log.emit(
                SecurityEventType.AUTH_FAILURE,
                Outcome.FAILURE,
                principal = Principal.THERAPIST,
                sourceIp = secret,
                detail = listOf(
                    DetailKey.RELATIONSHIP to log.ref(RefKind.RELATIONSHIP, secret),
                    DetailKey.CREDENTIAL to log.ref(RefKind.CREDENTIAL, secret),
                    DetailKey.LINEAGE to log.ref(RefKind.LINEAGE, secret),
                ),
            )
        }
        assertEquals(secrets.size, out.size)
        for (line in out) {
            assertNull(secretIn(line), "a secret reached the log: $line")
            assertEquals(1, line.lines().size)
            parse(line) // still valid JSON
        }
    }

    @Test
    fun `the same call in FULL address mode still leaks nothing but the address itself`() {
        // FULL is the operator's deliberate opt-in for a bannable address. It must widen exactly
        // that one field and nothing else — in particular it must not start echoing raw refs.
        val out = mutableListOf<String>()
        val log = logTo(out, mode = SourceIpMode.FULL)
        for (secret in secrets) {
            log.emit(
                SecurityEventType.AUTH_FAILURE,
                Outcome.FAILURE,
                sourceIp = secret,
                detail = listOf(DetailKey.RELATIONSHIP to log.ref(RefKind.RELATIONSHIP, secret)),
            )
        }
        for (line in out) assertNull(secretIn(line), "a secret reached the log: $line")
    }

    /**
     * Does this class expose a way to build it from arbitrary text? The leak-shaped question.
     *
     * Synthetic constructors are skipped, and that is a documented fact rather than a loophole
     * being waved through. Kotlin emits a public **synthetic** `Ref(String, DefaultConstructorMarker)`
     * bridge because `Ref.of` lives on the companion — a separate JVM class — and has to reach the
     * private constructor somehow. The JVM flags it synthetic, the Kotlin compiler refuses to
     * resolve it, and this module is Kotlin-only, so no call site in this codebase can reach it.
     * The test below pins that exact shape so the bridge cannot quietly be replaced by a genuine
     * public constructor without a test failing.
     */
    private fun hasPublicStringConstructor(c: Class<*>): Boolean =
        c.declaredConstructors.any { ctor ->
            !ctor.isSynthetic &&
                java.lang.reflect.Modifier.isPublic(ctor.modifiers) &&
                ctor.parameterTypes.any { it == String::class.java }
        }

    /** A stand-in for the variant someone will eventually be tempted to add. */
    private class FreeText(val text: String)

    @Test
    fun `the free-text detector sees a variant that carries free text`() {
        assertTrue(
            hasPublicStringConstructor(FreeText::class.java),
            "the detector must flag a class that can be built from an arbitrary string",
        )
        assertFalse(hasPublicStringConstructor(DetailValue.Count::class.java))
    }

    @Test
    fun `every DetailValue variant is one of the reviewed four, and none carries free text`() {
        val nested = DetailValue::class.java.declaredClasses
            .filter { DetailValue::class.java.isAssignableFrom(it) }
            .map { it.simpleName }
            .toSet()
        // Deliberately brittle. Adding a variant means editing this line, which means a reviewer
        // reads the new variant and asks whether it can carry someone's journal.
        assertEquals(setOf("Count", "Flag", "Tag", "Ref"), nested)

        for (variant in listOf(
            DetailValue.Count::class.java,
            DetailValue.Flag::class.java,
            DetailValue.Tag::class.java,
            DetailValue.Ref::class.java,
        )) {
            assertFalse(
                hasPublicStringConstructor(variant),
                "${variant.simpleName} can be built from an arbitrary string — that is a leak path",
            )
        }
    }

    @Test
    fun `Ref's only real constructor is private, so a raw value cannot be dressed up as a digest`() {
        val real = DetailValue.Ref::class.java.declaredConstructors.filter { !it.isSynthetic }
        assertEquals(1, real.size, "Ref should declare exactly one real constructor")
        assertTrue(
            java.lang.reflect.Modifier.isPrivate(real.single().modifiers),
            "Ref must be reachable only through Ref.of, which always hashes its input",
        )

        // The one public constructor on the class is Kotlin's synthetic bridge to the private one
        // (see hasPublicStringConstructor). Pinned so that adding a genuine public constructor —
        // the change that would open a path for a raw relRef to arrive pre-formatted as a "digest"
        // — fails here instead of shipping.
        val publicCtors = DetailValue.Ref::class.java.constructors
        assertEquals(1, publicCtors.size)
        assertTrue(publicCtors.single().isSynthetic, "the only public constructor must be the synthetic bridge")
    }

    @Test
    fun `a ref is a digest of its input, never the input`() {
        val log = logTo(mutableListOf())
        val raw = "rel-9c1f0b7a5e2d4c3b8a6f"
        val ref = log.ref(RefKind.RELATIONSHIP, raw)

        assertNotEquals(raw, ref.digest)
        assertFalse(ref.digest.contains(raw))
        assertTrue(Regex("^[0-9a-f]{16}$").matches(ref.digest), "digest shape: ${ref.digest}")
        // toString is where a "helpful" debug print would leak it back out.
        assertFalse(ref.toString().contains(raw))
    }

    @Test
    fun `a digest correlates within one process and is worthless across two`() {
        val runA = keyed(1)
        val runB = keyed(2)
        val rel = "rel-9c1f0b7a5e2d4c3b8a6f"

        // Within a run: stable, so an operator can see that fifty refusals concern one relationship.
        assertEquals(runA.digest(RefKind.RELATIONSHIP, rel), runA.digest(RefKind.RELATIONSHIP, rel))
        // Different values stay distinguishable.
        assertNotEquals(runA.digest(RefKind.RELATIONSHIP, rel), runA.digest(RefKind.RELATIONSHIP, "rel-other"))
        // Across runs or deployments: unrelated, so a year of archived logs cannot be joined into a
        // profile of one person's therapy, and two operators cannot merge theirs.
        assertNotEquals(runA.digest(RefKind.RELATIONSHIP, rel), runB.digest(RefKind.RELATIONSHIP, rel))
        // Domain separation: the same string in two roles must not collapse into one identifier.
        assertNotEquals(runA.digest(RefKind.RELATIONSHIP, rel), runA.digest(RefKind.CREDENTIAL, rel))
    }

    @Test
    fun `two SecurityLog instances do not share a correlation key`() {
        // Each SecurityLog defaults to its own random key; nothing correlates across processes.
        val rel = "rel-9c1f0b7a5e2d4c3b8a6f"
        val a = SecurityLog(sink = {})
        val b = SecurityLog(sink = {})
        assertNotEquals(a.ref(RefKind.RELATIONSHIP, rel).digest, b.ref(RefKind.RELATIONSHIP, rel).digest)
    }

    // -----------------------------------------------------------------------------------------
    // The product constraint: no location, anywhere, ever
    // -----------------------------------------------------------------------------------------

    private val locationWords = listOf(
        "geo", "country", "city", "region", "latitude", "longitude",
        "coord", "postal", "timezone", "location", "asn",
    )

    private fun locationWordIn(names: Collection<String>): String? {
        val hay = names.joinToString(" ").lowercase()
        return locationWords.firstOrNull { hay.contains(it) }
    }

    @Test
    fun `the location detector sees a location field when one is really there`() {
        assertEquals("country", locationWordIn(listOf("ts", "event", "sourceCountry")))
        assertNull(locationWordIn(listOf("ts", "event", "sourceIp")))
    }

    @Test
    fun `no field or vocabulary word in the schema is a location`() {
        // Geo-enrichment "for security" is the single most likely thing to get added to a log like
        // this, and it is an absolute product prohibition. Named here so the addition trips a test
        // that says why rather than sailing through review as an obvious improvement.
        val everyName = buildList {
            addAll(listOf("ts", "event", "outcome", "severity", "principal", "sourceIp", "detail"))
            addAll(SecurityEventType.entries.map { it.wire })
            addAll(DetailKey.entries.map { it.wire })
            addAll(Label.entries.map { it.wire })
            addAll(Principal.entries.map { it.wire })
            addAll(Outcome.entries.map { it.wire })
            addAll(Severity.entries.map { it.wire })
            addAll(RefKind.entries.map { it.wire })
        }
        assertNull(locationWordIn(everyName), "the schema gained a location field")
    }

    // -----------------------------------------------------------------------------------------
    // Vocabulary stability — the SIEM contract
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the event vocabulary is exactly the reviewed set`() {
        // Brittle on purpose. An operator's alert rules are written against these strings, and a
        // new event is a decision about what a server operator gets to see about someone's care.
        assertEquals(
            setOf(
                "auth.success", "auth.failure",
                "lockout.engaged", "lockout.released",
                "stepup.required", "stepup.failed",
                "rate.limited",
                "invite.consumed", "invite.rejected",
                "share.published", "share.fetched", "share.refused",
                "recovery.requested", "recovery.rateLimited", "recovery.confirmed",
                "request.rejected",
                "config.refused",
                "mail.sendFailed",
                "proxy.trustMisconfigured",
                "session.expired",
                "log.emitFailed",
            ),
            SecurityEventType.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `principal can only ever be a role`() {
        assertEquals(
            setOf("owner", "therapist", "anonymous", "system"),
            Principal.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `wire names are unique within each vocabulary`() {
        fun assertUnique(name: String, wires: List<String>) =
            assertEquals(wires.size, wires.toSet().size, "duplicate wire name in $name: $wires")

        assertUnique("SecurityEventType", SecurityEventType.entries.map { it.wire })
        assertUnique("DetailKey", DetailKey.entries.map { it.wire })
        assertUnique("Label", Label.entries.map { it.wire })
        assertUnique("Outcome", Outcome.entries.map { it.wire })
        assertUnique("Severity", Severity.entries.map { it.wire })
        assertUnique("Principal", Principal.entries.map { it.wire })
        assertUnique("RefKind", RefKind.entries.map { it.wire })
    }

    @Test
    fun `every wire name is JSON-safe as written, so escaping is never load-bearing for the schema`() {
        val all = SecurityEventType.entries.map { it.wire } +
            DetailKey.entries.map { it.wire } +
            Label.entries.map { it.wire } +
            Outcome.entries.map { it.wire } +
            Severity.entries.map { it.wire } +
            Principal.entries.map { it.wire } +
            RefKind.entries.map { it.wire }
        val shape = Regex("^[a-z][A-Za-z0-9]*(\\.[a-z][A-Za-z0-9]*)*$")
        for (wire in all) {
            assertTrue(shape.matches(wire), "wire name '$wire' is not a plain dotted identifier")
            // Escaping it must be a no-op — if it ever isn't, someone put punctuation in a name.
            assertEquals("\"$wire\"", SecurityJson.escape(wire))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Rejection paths — a bad call site fails closed, and never fails the request
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a detail value of the wrong kind is rejected by the schema`() {
        val e = assertFailsWith<SecurityLogViolation> {
            SecurityEvent(
                ts = at,
                type = SecurityEventType.REQUEST_REJECTED,
                outcome = Outcome.REFUSED,
                severity = Severity.INFO,
                principal = Principal.ANONYMOUS,
                sourceIp = null,
                detail = mapOf(DetailKey.LIMIT to DetailValue.Tag(Label.EXPIRED)),
            )
        }
        // Named by key, never by value — the value is the part that might be sensitive.
        assertTrue(e.message!!.contains("limit"))
        assertFalse(e.message!!.contains("expired"))
    }

    @Test
    fun `too many detail entries are rejected`() {
        val tooMany = DetailKey.entries.take(SecurityEvent.MAX_DETAIL_ENTRIES + 1)
        assertTrue(tooMany.size > SecurityEvent.MAX_DETAIL_ENTRIES, "the vocabulary must be big enough to overflow the cap")
        assertFailsWith<SecurityLogViolation> {
            SecurityEvent(
                ts = at,
                type = SecurityEventType.REQUEST_REJECTED,
                outcome = Outcome.REFUSED,
                severity = Severity.INFO,
                principal = Principal.ANONYMOUS,
                sourceIp = null,
                detail = tooMany.associateWith { key ->
                    when (key.kind) {
                        DetailKind.COUNT -> DetailValue.Count(1)
                        DetailKind.FLAG -> DetailValue.Flag(true)
                        DetailKind.LABEL -> DetailValue.Tag(Label.UNKNOWN)
                        DetailKind.REF -> DetailValue.Ref.of(keyed(1), RefKind.RELATIONSHIP, "x")
                    }
                },
            )
        }
    }

    @Test
    fun `a call site that violates the schema gets log_emitFailed and prints nothing it was holding`() {
        val out = mutableListOf<String>()
        val log = logTo(out)
        // Duplicate keys — the shape of a real mistake, and the offending record carries a ref the
        // emitter must not fall back to printing.
        log.emit(
            SecurityEventType.SHARE_FETCHED,
            Outcome.SUCCESS,
            detail = listOf(
                DetailKey.RELATIONSHIP to log.ref(RefKind.RELATIONSHIP, "rel-abc"),
                DetailKey.RELATIONSHIP to log.ref(RefKind.RELATIONSHIP, "rel-def"),
            ),
        )

        assertEquals(1, out.size, "one line, not zero and not two")
        val line = out.single()
        assertEquals("log.emitFailed", str(line, "event"))
        assertEquals("error", str(line, "outcome"))
        assertEquals("system", str(line, "principal"))
        assertEquals(emptyMap(), parse(line)["detail"]!!.jsonObject.toMap())
        assertFalse(line.contains("share.fetched"), "the failed record must not be half-emitted")
    }

    @Test
    fun `emit never throws, even when the sink itself does`() {
        // A logging bug must never fail a real request — the same rule the route files' auditSafely
        // already follows. A full disk closing the appender must not turn into a 500.
        val log = SecurityLog(sink = { throw IllegalStateException("disk full") }, clock = { at })
        log.emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, sourceIp = "203.0.113.9")
    }

    @Test
    fun `emit never throws, even when the clock does`() {
        var calls = 0
        val out = mutableListOf<String>()
        val log = SecurityLog(
            sink = { out += it },
            clock = { if (calls++ == 0) throw IllegalStateException("clock broke") else at },
        )
        log.emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE)
        assertEquals(1, out.size)
        assertEquals("log.emitFailed", str(out.single(), "event"))
    }

    // -----------------------------------------------------------------------------------------
    // Source address handling — personal data, so the default must not export it
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the default mode exports a per-process digest, not an address`() {
        val out = mutableListOf<String>()
        val correlator = keyed(3)
        // No explicit mode: this asserts the DEFAULT, which is the part that matters. An IP is
        // personal data and this codebase already keeps it behind an opt-in in the audit log.
        SecurityLog(sink = { out += it }, correlator = correlator, clock = { at })
            .emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, sourceIp = "203.0.113.9")

        val rendered = str(out.single(), "sourceIp")
        assertFalse(rendered.contains("203.0.113.9"), "the raw address must not be exported by default")
        assertEquals("h:" + correlator.digest(RefKind.SOURCE, "203.0.113.9"), rendered)
        assertTrue(rendered.startsWith("h:"), "the prefix is what stops a parser reading a digest as an address")
    }

    @Test
    fun `pseudonymous addresses still group per source, which is what the detections need`() {
        val out = mutableListOf<String>()
        val log = logTo(out)
        log.emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, sourceIp = "203.0.113.9")
        log.emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, sourceIp = "203.0.113.9")
        log.emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, sourceIp = "198.51.100.7")

        assertEquals(str(out[0], "sourceIp"), str(out[1], "sourceIp"))
        assertNotEquals(str(out[0], "sourceIp"), str(out[2], "sourceIp"))
    }

    @Test
    fun `OFF omits the address entirely but keeps the field present`() {
        val out = mutableListOf<String>()
        logTo(out, mode = SourceIpMode.OFF)
            .emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, sourceIp = "203.0.113.9")

        assertTrue(parse(out.single()).containsKey("sourceIp"), "the field set is fixed even when the value is not there")
        assertEquals(null, parse(out.single())["sourceIp"]!!.jsonPrimitive.contentOrNullSafe())
        assertFalse(out.single().contains("203.0.113.9"))
    }

    @Test
    fun `FULL exports a literal address, and drops the zone id`() {
        /*
         * The zone goes, deliberately.
         *
         * `%eth0` names an interface on the machine that received the packet. It identifies nothing
         * about the client, means nothing to a SIEM correlating across hosts, and it was the one
         * part of an address that could carry arbitrary text: review demonstrated
         * `1.1%JBSWY3DPEHPK3PXP` — a TOTP secret — exported verbatim as a `sourceIp`, because the
         * gate matched a shape and the shape allowed thirty-two free characters after the `%`.
         *
         * Keeping a field that says nothing, in order to keep a channel that can say anything, is
         * the wrong side of that trade.
         */
        val out = mutableListOf<String>()
        val log = logTo(out, mode = SourceIpMode.FULL)
        for (addr in listOf("203.0.113.9", "2001:db8::1", "fe80::1%eth0")) {
            log.emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, sourceIp = addr)
        }
        assertEquals(listOf("203.0.113.9", "2001:db8::1", "fe80::1"), out.map { str(it, "sourceIp") })
    }

    @Test
    fun `FULL asks the parser, rather than matching a shape`() {
        /*
         * The regression this exists for. `cafe.beef` is hex and a dot, so it satisfied the old
         * shape gate and was exported as though it were an address; it is not one, and
         * InetAddress says so. Anything that is not an address must land on the fixed string
         * `invalid` rather than being passed through as data.
         */
        val out = mutableListOf<String>()
        val log = logTo(out, mode = SourceIpMode.FULL)
        for (junk in listOf("cafe.beef", "1.1%JBSWY3DPEHPK3PXP", "fe80::1%sk-live-ZmFrZStva2VuLXZhbHVl")) {
            log.emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, sourceIp = junk)
        }
        assertEquals(listOf("invalid", "invalid", "invalid"), out.map { str(it, "sourceIp") })

        // The control: a real address still gets through, so the three above are refusals by the
        // parser and not this gate rejecting everything.
        val ok = mutableListOf<String>()
        logTo(ok, mode = SourceIpMode.FULL)
            .emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, sourceIp = "198.51.100.7")
        assertEquals(listOf("198.51.100.7"), ok.map { str(it, "sourceIp") })
    }

    @Test
    fun `FULL refuses anything that is not address-shaped`() {
        val out = mutableListOf<String>()
        val log = logTo(out, mode = SourceIpMode.FULL)
        for (junk in listOf("not-an-address", "example.org", "203.0.113.9 OR 1=1", "")) {
            log.emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE, sourceIp = junk)
        }
        assertEquals(listOf("invalid", "invalid", "invalid", "invalid"), out.map { str(it, "sourceIp") })
    }

    @Test
    fun `an absent address is null in every mode`() {
        for (mode in SourceIpMode.entries) {
            val out = mutableListOf<String>()
            logTo(out, mode = mode).emit(SecurityEventType.CONFIG_REFUSED, Outcome.REFUSED, principal = Principal.SYSTEM)
            assertTrue(out.single().contains("\"sourceIp\":null"), "mode $mode")
        }
    }

    @Test
    fun `an unrecognised mode setting falls back to pseudonym, never to full`() {
        assertEquals(SourceIpMode.PSEUDONYM, SourceIpMode.parse(null))
        assertEquals(SourceIpMode.PSEUDONYM, SourceIpMode.parse(""))
        assertEquals(SourceIpMode.PSEUDONYM, SourceIpMode.parse("pseudonym"))
        // A typo must not be what starts exporting personal data.
        assertEquals(SourceIpMode.PSEUDONYM, SourceIpMode.parse("fulll"))
        assertEquals(SourceIpMode.PSEUDONYM, SourceIpMode.parse("yes"))
        assertEquals(SourceIpMode.FULL, SourceIpMode.parse("full"))
        assertEquals(SourceIpMode.FULL, SourceIpMode.parse("  FULL "))
        assertEquals(SourceIpMode.OFF, SourceIpMode.parse("off"))
    }

    // -----------------------------------------------------------------------------------------
    // The line cap
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an oversized record drops its detail rather than emitting broken JSON`() {
        val out = mutableListOf<String>()
        // A deliberately small cap. The real one is unreachable with the current schema, which is
        // the intent — but a defence that has never executed is not a defence.
        val log = logTo(out, maxLineBytes = 200)
        log.emit(
            SecurityEventType.AUTH_FAILURE,
            Outcome.FAILURE,
            sourceIp = "203.0.113.9",
            detail = listOf(
                DetailKey.RELATIONSHIP to log.ref(RefKind.RELATIONSHIP, "rel-abc"),
                DetailKey.CREDENTIAL to log.ref(RefKind.CREDENTIAL, "cred-abc"),
                DetailKey.LINEAGE to log.ref(RefKind.LINEAGE, "lineage-abc"),
                DetailKey.ATTEMPTS to DetailValue.Count(3),
            ),
        )

        val line = out.single()
        assertEquals(1, line.lines().size)
        parse(line) // must still parse: a truncated JSON object would not
        assertEquals(
            mapOf("reason" to "truncated"),
            parse(line)["detail"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
            "the record must say it dropped something rather than silently shortening",
        )
    }

    @Test
    fun `a fully populated record is comfortably inside the production cap`() {
        val out = mutableListOf<String>()
        val log = logTo(out, mode = SourceIpMode.FULL)
        log.emit(
            SecurityEventType.PROXY_TRUST_MISCONFIGURED,
            Outcome.REFUSED,
            principal = Principal.THERAPIST,
            sourceIp = "2001:0db8:85a3:0000:0000:8a2e:0370:7334",
            severity = Severity.CRITICAL,
            detail = DetailKey.entries.take(SecurityEvent.MAX_DETAIL_ENTRIES).map { key ->
                key to when (key.kind) {
                    DetailKind.COUNT -> DetailValue.Count(Long.MAX_VALUE)
                    DetailKind.FLAG -> DetailValue.Flag(true)
                    DetailKind.LABEL -> DetailValue.Tag(Label.SUBSYSTEM_THERAPIST_PORTAL)
                    DetailKind.REF -> log.ref(RefKind.RELATIONSHIP, "rel-abc")
                }
            },
        )
        val bytes = out.single().toByteArray(Charsets.UTF_8).size
        assertTrue(bytes < SecurityLog.MAX_LINE_BYTES / 2, "worst-case record was $bytes bytes")
        assertEquals(SecurityEvent.MAX_DETAIL_ENTRIES, parse(out.single())["detail"]!!.jsonObject.size)
    }

    // -----------------------------------------------------------------------------------------
    // Process-wide handle
    // -----------------------------------------------------------------------------------------

    @Test
    fun `current falls back before install, and switches to the installed emitter after`() {
        /*
         * This asserted `assertNotNull(SecurityLog.current())`, which cannot fail: `current()`
         * returns a non-null type, so the compiler already guarantees it. It was also
         * order-dependent — its "before anything has been installed" premise held only because
         * JUnit happened to order it ahead of the install test, and that order is a method-name
         * hash — and it emitted a real security record to the console through the default sink,
         * putting a fake auth.success in the CI output.
         *
         * What is actually worth pinning is the TRANSITION, which is observable and cannot be
         * satisfied by the type system: before install the fallback is returned, after install the
         * emitter is, and uninstall puts it back.
         */
        SecurityLog.uninstall()
        assertFalse(SecurityLog.isInstalled())
        val before = SecurityLog.current()
        assertSame(before, SecurityLog.current(), "the fallback must be stable, not rebuilt per call")

        val out = mutableListOf<String>()
        val mine = logTo(out)
        SecurityLog.install(mine)
        try {
            assertSame(mine, SecurityLog.current())
            assertNotSame(before, SecurityLog.current())
        } finally {
            SecurityLog.uninstall()
        }
        assertSame(before, SecurityLog.current(), "uninstall must restore the fallback")
    }

    @Test
    fun `install publishes the emitter the rest of the process will use`() {
        val out = mutableListOf<String>()
        val mine = logTo(out)
        SecurityLog.install(mine)
        try {
            SecurityLog.current().emit(SecurityEventType.AUTH_SUCCESS, Outcome.SUCCESS, principal = Principal.THERAPIST)
            assertEquals("auth.success", str(out.single(), "event"))
        } finally {
            // Actually restore it. Installing a do-nothing emitter here would leave a black hole in
            // a slot every later test in this JVM shares — an integration test that captured
            // emitted records would then capture none and pass for the wrong reason.
            SecurityLog.uninstall()
        }
        assertFalse(SecurityLog.isInstalled(), "the process-wide slot must be left as it was found")
    }

    // -----------------------------------------------------------------------------------------
    // Flood control
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a flood is bounded, and the loss is stated rather than silent`() {
        /*
         * Several of these events fire on unauthenticated paths an attacker chooses. Unbounded,
         * someone who cannot get in can still fill the operator's disk and spend their SIEM ingest
         * quota by failing to log in quickly. The per-source auth limiter does not bound this — it
         * is per source, and a distributed flood has many.
         */
        val out = mutableListOf<String>()
        val log = logTo(out)
        repeat(20_000) { log.emit(SecurityEventType.AUTH_FAILURE, Outcome.FAILURE) }

        assertTrue(out.size < 20_000, "the flood was not bounded at all")
        assertTrue(out.size >= 100, "the bucket cannot be so tight that ordinary traffic is lost")

        // The loss is VISIBLE. A gap an operator cannot see is worse than one they can, so the
        // next admitted record says how many were discarded.
        val dropped = out.mapNotNull { line ->
            parse(line)["detail"]!!.jsonObject["dropped"]?.jsonPrimitive?.longOrNull
        }
        assertTrue(dropped.isNotEmpty(), "records were discarded without ever saying so")
    }

    @Test
    fun `ordinary traffic is never dropped, and never carries a drop count`() {
        // The control for the test above: if the bucket were shedding normal load, this fails. A
        // self-hosted server for one owner and their clinician sees single-digit events a minute.
        val out = mutableListOf<String>()
        val log = logTo(out)
        repeat(100) { log.emit(SecurityEventType.AUTH_SUCCESS, Outcome.SUCCESS) }

        assertEquals(100, out.size)
        for (line in out) {
            assertNull(
                parse(line)["detail"]!!.jsonObject["dropped"],
                "a record under ordinary load must carry no drop count",
            )
        }
    }

}

/** `null` for a JSON null, the content otherwise. Kept local so the assertion above reads plainly. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content