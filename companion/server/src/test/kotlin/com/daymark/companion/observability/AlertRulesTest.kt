package com.daymark.companion.observability

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the operator alerting trips.
 *
 * ## House rule this file takes seriously
 *
 * For every "X is absent" assertion there is first a proof that the detector *sees* X when X is
 * present. A test that asserts a needle is missing, using a search that could never find it, is a
 * green check that asserts nothing — and this repo has shipped those before. So:
 *
 * - "does not fire below the threshold" is always followed by one more event and an assertion that
 *   it *does* fire, in the same test. Otherwise a rule that never fires at all would pass.
 * - "no identity in the alert" runs the same containment helper over a string that deliberately
 *   contains the identity, and asserts it is caught.
 * - "the cap holds under a flood" runs the identical flood against a policy with a huge cap and
 *   asserts it produces far more alerts, so the cap is demonstrably what bounded the first run.
 *
 * `now` is always an explicit parameter here; nothing sleeps and nothing reads a clock.
 */
class AlertRulesTest {

    /** A fixed salt — the production one is random per process, but a test needs determinism. */
    private val salt = Salt(ByteArray(32) { (it * 7 + 1).toByte() })

    private fun p(id: String) = PrincipalRef.of(id, salt)
    private fun src(addr: String) = SourceRef.clientAddress(addr)

    private val T0 = 1_700_000_000_000L
    private val MINUTE = 60_000L

    private fun authFail(
        source: String = "203.0.113.9",
        principal: String = "cred-a",
        surface: AuthSurface = AuthSurface.THERAPIST_TOTP,
    ) = Observation.AuthFailure(src(source), p(principal), surface)

    /** Feed [n] observations [stepMs] apart starting at [at], collecting every alert produced. */
    private fun AlertRules.feed(
        n: Int,
        at: Long,
        stepMs: Long = 1_000L,
        make: (Int) -> Observation,
    ): List<Alert> {
        val all = mutableListOf<Alert>()
        repeat(n) { i -> all += observe(make(i), at + i * stepMs) }
        return all
    }

    private fun List<Alert>.of(rule: AlertRule) = filter { it.rule == rule }

    // =======================================================================================
    // Rule 1 — one credential, many failures
    // =======================================================================================

    @Test
    fun `one credential crossing the failure threshold fires`() {
        val r = AlertRules()
        val alerts = r.feed(10, T0) { authFail() }
        val fired = alerts.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL)
        assertEquals(1, fired.size, "exactly one alert, not one per event past the line")
        val a = fired.single()
        assertEquals(10L, a.facts.single { it.label == "failedAuthentications" }.value)
        assertEquals(1L, a.facts.single { it.label == "distinctSources" }.value)
        assertEquals(Delivery.MAIL_OR_LOG, a.delivery)
    }

    @Test
    fun `one credential just below the threshold does not fire — and the tenth proves the detector`() {
        val r = AlertRules()
        val nine = r.feed(9, T0) { authFail() }
        assertTrue(nine.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).isEmpty(), "nine must be silent")

        // Detector proof: the rule is wired and does fire — so the silence above was the threshold,
        // not a rule that can never trip.
        val tenth = r.observe(authFail(), T0 + 9_000)
        assertEquals(1, tenth.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).size)
    }

    @Test
    fun `failures that fall out of the window stop counting`() {
        val r = AlertRules()
        r.feed(9, T0) { authFail() }
        // Well past the 5-minute window: the nine are gone, so this one is alone.
        val late = r.observe(authFail(), T0 + 10 * MINUTE)
        assertTrue(late.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).isEmpty())

        // Detector proof: nine more inside the window with that one makes ten, and it fires.
        val more = r.feed(9, T0 + 10 * MINUTE + 1_000) { authFail() }
        assertEquals(1, more.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).size)
    }

    @Test
    fun `failures against one credential from many sources are still one credential`() {
        val r = AlertRules()
        // The gap rule 1 exists for: the sync lockout keys on the source, so ten attempts from ten
        // sources never trip it. Rule 1 keys on the credential and does.
        val alerts = r.feed(10, T0) { i -> authFail(source = "198.51.100.$i") }
        val a = alerts.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).single()
        assertEquals(10L, a.facts.single { it.label == "distinctSources" }.value)
        assertEquals(null, a.source, "there is no single source to name, so none is named")
    }

    // =======================================================================================
    // Rule 2 — one source, many distinct credentials (stuffing)
    // =======================================================================================

    @Test
    fun `many distinct credentials from one source fires the stuffing rule`() {
        val r = AlertRules()
        // 8 credentials x 2 attempts = 16 failures. No single credential reaches rule 1's ten.
        val alerts = r.feed(16, T0) { i -> authFail(principal = "cred-${i % 8}") }
        val a = alerts.of(AlertRule.AUTH_FAILURES_MANY_PRINCIPALS_ONE_SOURCE).single()
        assertEquals(16L, a.facts.single { it.label == "failedAuthentications" }.value)
        assertEquals(8L, a.facts.single { it.label == "distinctCredentials" }.value)
        assertEquals("203.0.113.9", a.source?.value)
        assertTrue(
            alerts.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).isEmpty(),
            "two attempts per credential is nobody's brute force",
        )
    }

    @Test
    fun `a forgotten passphrase is not stuffing — one credential hammered does not fire rule 2`() {
        val r = AlertRules()
        val alerts = r.feed(20, T0) { authFail() }
        assertTrue(
            alerts.of(AlertRule.AUTH_FAILURES_MANY_PRINCIPALS_ONE_SOURCE).isEmpty(),
            "20 failures against ONE credential is volume, not spread — the discriminator is " +
                "distinct principals",
        )

        // Detector proof: the same source, same volume, but spread across credentials does fire.
        val spread = r.feed(16, T0 + 30_000) { i -> authFail(principal = "other-${i % 8}") }
        assertEquals(1, spread.of(AlertRule.AUTH_FAILURES_MANY_PRINCIPALS_ONE_SOURCE).size)
    }

    @Test
    fun `seven distinct credentials is below the spread floor — the eighth proves the detector`() {
        val r = AlertRules()
        // 7 credentials x 3 = 21 failures: past the volume floor, short of the distinctness floor.
        val below = r.feed(21, T0) { i -> authFail(principal = "cred-${i % 7}") }
        assertTrue(below.of(AlertRule.AUTH_FAILURES_MANY_PRINCIPALS_ONE_SOURCE).isEmpty())

        val eighth = r.feed(3, T0 + 25_000) { authFail(principal = "cred-7") }
        assertEquals(1, eighth.of(AlertRule.AUTH_FAILURES_MANY_PRINCIPALS_ONE_SOURCE).size)
    }

    // =======================================================================================
    // Rule 3 — the same volume spread across many sources
    // =======================================================================================

    @Test
    fun `forty-nine distinct sources is silent — the fiftieth proves the detector`() {
        val r = AlertRules()
        val below = r.feed(49, T0) { i -> authFail(source = "198.51.100.$i", principal = "cred-$i") }
        assertTrue(below.of(AlertRule.AUTH_FAILURES_MANY_SOURCES).isEmpty())

        val fiftieth = r.observe(authFail(source = "198.51.100.49", principal = "cred-49"), T0 + 49_000)
        val a = fiftieth.of(AlertRule.AUTH_FAILURES_MANY_SOURCES).single()
        assertEquals(50L, a.facts.single { it.label == "distinctSources" }.value)
        assertEquals(null, a.source, "there is no one source to blame, and the alert does not invent one")
    }

    // =======================================================================================
    // Rule 4 — lockouts re-arming
    // =======================================================================================

    @Test
    fun `a source re-arming the lockout six times fires — five does not`() {
        val r = AlertRules()
        val five = r.feed(5, T0, stepMs = MINUTE) {
            Observation.LockoutEngaged(src("203.0.113.9"), AuthSurface.SYNC_BEARER)
        }
        assertTrue(five.of(AlertRule.LOCKOUTS_REARMING).isEmpty())

        val sixth = r.observe(
            Observation.LockoutEngaged(src("203.0.113.9"), AuthSurface.SYNC_BEARER),
            T0 + 5 * MINUTE,
        )
        val a = sixth.of(AlertRule.LOCKOUTS_REARMING).single()
        assertEquals(6L, a.facts.single { it.label == "lockoutsEngaged" }.value)
        assertContains(a.context, AuthSurface.SYNC_BEARER.name)
    }

    @Test
    fun `lockouts are counted per source, so two clients do not add up`() {
        val r = AlertRules()
        val mixed = r.feed(10, T0, stepMs = MINUTE) { i ->
            Observation.LockoutEngaged(src("198.51.100.${i % 2}"), AuthSurface.SYNC_BEARER)
        }
        assertTrue(mixed.of(AlertRule.LOCKOUTS_REARMING).isEmpty(), "five each is below the floor")

        // Detector proof: push one of them to six and it fires.
        val push = r.feed(1, T0 + 11 * MINUTE) {
            Observation.LockoutEngaged(src("198.51.100.0"), AuthSurface.SYNC_BEARER)
        }
        assertEquals(1, push.of(AlertRule.LOCKOUTS_REARMING).size)
    }

    // =======================================================================================
    // Rule 5 — a refused configuration
    // =======================================================================================

    @Test
    fun `a refused configuration fires once and then rests, per area`() {
        val r = AlertRules()
        val first = r.observe(Observation.ConfigRefused(ConfigArea.MAIL_TLS), T0)
        assertEquals(1, first.of(AlertRule.CONFIG_REFUSED).size)
        assertContains(first.single().context, ConfigArea.MAIL_TLS.name)

        val repeat = r.observe(Observation.ConfigRefused(ConfigArea.MAIL_TLS), T0 + MINUTE)
        assertTrue(repeat.isEmpty(), "configuration does not change without a restart")

        // A different area is a different thing to check, so it is not silenced by the first.
        val other = r.observe(Observation.ConfigRefused(ConfigArea.TRUSTED_PROXIES), T0 + 2 * MINUTE)
        assertEquals(1, other.of(AlertRule.CONFIG_REFUSED).size)

        // Detector proof for the cooldown: past a day, the same area speaks again.
        val nextDay = r.observe(Observation.ConfigRefused(ConfigArea.MAIL_TLS), T0 + 25 * 60 * MINUTE)
        assertEquals(1, nextDay.of(AlertRule.CONFIG_REFUSED).size)
    }

    // =======================================================================================
    // Rule 6/7 — the alert channel itself
    // =======================================================================================

    @Test
    fun `mail failing is never mailed, degrades every later alert, and reports the misses on recovery`() {
        val r = AlertRules()

        val two = r.feed(2, T0) { Observation.MailTransportFailed }
        assertTrue(two.of(AlertRule.MAIL_TRANSPORT_FAILING).isEmpty())

        val third = r.observe(Observation.MailTransportFailed, T0 + 2_000)
        val mailAlert = third.of(AlertRule.MAIL_TRANSPORT_FAILING).single()
        assertEquals(
            Delivery.LOG_ONLY,
            mailAlert.delivery,
            "an alert about mail being down cannot be delivered by mail",
        )

        // While the channel is down, everything else is undeliverable too — it must not be queued
        // or optimistically marked mailable.
        val auth = r.feed(10, T0 + 10_000) { authFail() }
        val authAlert = auth.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).single()
        assertEquals(Delivery.LOG_ONLY, authAlert.delivery)

        // Recovery reports what was missed, and says in as many words that it is not an all-clear.
        val back = r.observe(Observation.MailTransportSucceeded, T0 + 20_000)
        val restored = back.of(AlertRule.MAIL_CHANNEL_RESTORED).single()
        assertEquals(1L, restored.facts.single { it.label == "alertsNotMailed" }.value)
        assertEquals(3L, restored.facts.single { it.label == "failedDeliveries" }.value)
        assertEquals(Delivery.MAIL_OR_LOG, restored.delivery)

        // Detector proof that the degraded latch actually cleared.
        val afterRecovery = r.feed(10, T0 + 30_000) { authFail(principal = "cred-b") }
        assertEquals(
            Delivery.MAIL_OR_LOG,
            afterRecovery.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).single().delivery,
        )
    }

    @Test
    fun `with no SMTP configured at all every alert is log-only`() {
        val r = AlertRules(AlertRules.Policy(mailAvailable = false))
        val alerts = r.feed(10, T0) { authFail() }
        assertEquals(Delivery.LOG_ONLY, alerts.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).single().delivery)
    }

    @Test
    fun `a single content-guard refusal fires — it means content nearly left the process`() {
        val r = AlertRules()
        val a = r.observe(Observation.MailContentRefused, T0).of(AlertRule.MAIL_CONTENT_REFUSED).single()
        assertEquals(1L, a.facts.single { it.label == "refusedMessages" }.value)
        assertEquals(Delivery.MAIL_OR_LOG, a.delivery, "the transport is fine; only this message was refused")
    }

    // =======================================================================================
    // Rule 8 — storage
    // =======================================================================================

    @Test
    fun `storage write failures are counted per area`() {
        val r = AlertRules()
        val mixed = r.feed(4, T0) { i ->
            Observation.StorageWriteFailed(if (i % 2 == 0) StorageArea.BLOB_STORE else StorageArea.AUDIT_LOG)
        }
        assertTrue(mixed.of(AlertRule.STORAGE_WRITE_FAILING).isEmpty(), "two each is below the floor")

        val third = r.observe(Observation.StorageWriteFailed(StorageArea.AUDIT_LOG), T0 + 5_000)
        val a = third.of(AlertRule.STORAGE_WRITE_FAILING).single()
        assertContains(a.context, StorageArea.AUDIT_LOG.name)
        assertEquals(3L, a.facts.single { it.label == "failedWrites" }.value)
    }

    // =======================================================================================
    // Fatigue controls: cooldown, hysteresis, cap
    // =======================================================================================

    @Test
    fun `a cooldown suppresses a repeat, and the repeat returns once it lapses`() {
        val first = AlertRules()
        assertEquals(1, first.feed(10, T0) { authFail() }.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).size)

        // A full fresh threshold, inside the 30-minute cooldown: silent.
        val inside = first.feed(10, T0 + 10 * MINUTE) { authFail() }
        assertTrue(inside.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).isEmpty())

        // Detector proof: past the cooldown, the same condition speaks again.
        val after = first.feed(10, T0 + 40 * MINUTE) { authFail() }
        assertEquals(1, after.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).size)
    }

    @Test
    fun `hysteresis — after firing, one more failure does not fire again`() {
        val r = AlertRules(AlertRules.Policy(cooldownMs = 0L))
        assertEquals(1, r.feed(10, T0) { authFail() }.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).size)

        // With the cooldown removed entirely, only drain-on-fire stands between the operator and
        // one alert per event. The eleventh failure must be silent.
        val eleventh = r.observe(authFail(), T0 + 10_000)
        assertTrue(eleventh.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).isEmpty())

        // Detector proof: a full fresh threshold does fire, so the silence was hysteresis and not
        // a rule that stopped working.
        val fresh = r.feed(9, T0 + 11_000) { authFail() }
        assertEquals(1, fresh.of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).size)
    }

    @Test
    fun `a flood is capped, and the cap is what capped it`() {
        // 60 credentials x 10 failures from one source, spread over four minutes so every one of
        // them sits inside rule 1's five-minute window. Left uncapped this is 60+ alerts.
        fun flood(rules: AlertRules): List<Alert> =
            rules.feed(600, T0, stepMs = 400L) { i -> authFail(principal = "cred-${i % 60}") }

        val capped = AlertRules()
        val produced = flood(capped)

        val real = produced.filter { it.rule != AlertRule.ALERTS_SUPPRESSED }
        assertEquals(
            6,
            real.size,
            "the cap is 6 per hour across all rules; a flood must not be able to exceed it",
        )
        assertTrue(
            produced.of(AlertRule.ALERTS_SUPPRESSED).size <= 1,
            "the notice about suppression must not itself become the flood",
        )

        // The suppression notice is due five minutes after the cap first bit; the flood only ran
        // for four, so a timer tick is what delivers it. It names the rules it ate.
        val notice = capped.flush(T0 + 10 * MINUTE).of(AlertRule.ALERTS_SUPPRESSED).single()
        assertTrue(notice.facts.single { it.label == "suppressedAlerts" }.value > 6L)
        assertContains(notice.context, AlertRule.AUTH_FAILURES_ONE_PRINCIPAL.name)

        // Detector proof: the identical flood with a huge cap produces far more than six, so the
        // number above is the cap doing its job rather than the rules failing to fire.
        val uncapped = AlertRules(AlertRules.Policy(maxAlertsPerWindow = 10_000))
        val unbounded = flood(uncapped).filter { it.rule != AlertRule.ALERTS_SUPPRESSED }
        assertTrue(
            unbounded.size > 30,
            "expected the same traffic to be alert-noisy without a cap, got ${unbounded.size}",
        )
    }

    @Test
    fun `the suppression notice does not consume the cap itself`() {
        val r = AlertRules(AlertRules.Policy(maxAlertsPerWindow = 2, suppressionNoticeDelayMs = 0L))
        val produced = r.feed(200, T0, stepMs = 200L) { i -> authFail(principal = "cred-${i % 20}") }
        assertEquals(2, produced.count { it.rule != AlertRule.ALERTS_SUPPRESSED })
        assertTrue(produced.any { it.rule == AlertRule.ALERTS_SUPPRESSED }, "the drop must be reported")
    }

    @Test
    fun `flush on a quiet engine says nothing — silence is not an all-clear message`() {
        val r = AlertRules()
        assertTrue(r.flush(T0).isEmpty())
        assertTrue(r.flush(T0 + 24 * 60 * MINUTE).isEmpty())
    }

    // =======================================================================================
    // The cardinal rule: nothing about a person reaches an alert
    // =======================================================================================

    @Test
    fun `no credential identity reaches the alert, in any field, hashed or raw`() {
        val rawId = "therapist.jane@clinic.example"
        val digest = MessageDigest.getInstance("SHA-256").let { md ->
            md.update(salt.bytes)
            md.update(rawId.toByteArray(Charsets.UTF_8))
            md.digest().joinToString("") { b -> "%02x".format(b) }
        }

        val r = AlertRules()
        val alert = r.feed(10, T0) { authFail(principal = rawId) }
            .of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).single()

        val everything = listOf(
            alert.summary,
            alert.check,
            alert.render(includeSource = true),
            alert.toString(),
            alert.context.joinToString("|"),
            alert.facts.joinToString("|") { "${it.label}=${it.value}" },
        )

        // Detector proof FIRST: the helper does find these needles when they are actually there.
        assertTrue(leaks("principal=$rawId", rawId), "the containment check must be able to fail")
        assertTrue(leaks("ref=$digest", digest), "the digest check must be able to fail")

        for (text in everything) {
            assertFalse(leaks(text, rawId), "raw credential id reached an alert: $text")
            assertFalse(leaks(text, digest), "the salted digest reached an alert: $text")
        }
        // And the type itself refuses to be printed, so a stray log statement cannot do it either.
        assertEquals("PrincipalRef(redacted)", p(rawId).toString())
        assertEquals("Salt(redacted)", salt.toString())
    }

    @Test
    fun `the source is a separate field, so an alert can be rendered without it`() {
        val r = AlertRules()
        val alert = r.feed(16, T0) { i -> authFail(principal = "cred-${i % 8}") }
            .of(AlertRule.AUTH_FAILURES_MANY_PRINCIPALS_ONE_SOURCE).single()

        // Detector proof: the address IS present when the caller asks for it.
        assertTrue(leaks(alert.render(includeSource = true), "203.0.113.9"))
        // ...and absent from the prose itself, which is what makes the choice possible at all.
        assertFalse(leaks(alert.render(includeSource = false), "203.0.113.9"))
        assertFalse(leaks(alert.summary + alert.check, "203.0.113.9"))

        // A deployment that wants no addresses in operator alerts salts them instead.
        val hashed = SourceRef.salted("203.0.113.9", salt)
        assertFalse(leaks(hashed.value, "203.0.113.9"))
        assertEquals(hashed.value, SourceRef.salted("203.0.113.9", salt).value, "stable within a process")
    }

    @Test
    fun `every rule's copy is free of the mail content guard's record-like tokens`() {
        // Mirrors MailContentGuard.RECORD_SENTINELS, which is private. If that list grows and this
        // one does not, the wiring that sends these alerts over SMTP starts throwing
        // MailContentViolation in production instead of here.
        val sentinels = listOf(
            "score", "phq", "gad", "mood", "journal", "assessment", "self-harm",
            "diagnos", "symptom", "note:", "band", "check-in", "sleep",
        )

        // Detector proof: this scan does catch a sentinel when one is present.
        assertEquals("mood", firstSentinel("your mood is recorded", sentinels))
        assertEquals(null, firstSentinel("nothing to see", sentinels))

        val alerts = oneAlertPerRule()
        assertEquals(
            AlertRule.entries.toSet(),
            alerts.map { it.rule }.toSet(),
            "every rule must be exercised here, or a new rule ships its copy unchecked",
        )
        for (a in alerts) {
            val copy = a.summary + " " + a.check + " " + a.context.joinToString(" ") +
                " " + a.facts.joinToString(" ") { it.label }
            assertEquals(null, firstSentinel(copy, sentinels), "sentinel in ${a.rule}: $copy")
        }
    }

    @Test
    fun `no rule's copy grades, scores, or declares things fine`() {
        // Words that would turn an operator alert into the green shield this product refuses.
        val banned = listOf(
            "all clear", "all-clear", "all good", "no issues", "no problems", "nothing to worry",
            "healthy", "nominal", "everything is", "%", "out of 10", "grade", "rating", "risk level",
            "severity", "you are safe", "resolved",
        )

        // Detector proof, in both directions.
        assertEquals("healthy", firstSentinel("the system is healthy", banned))
        assertEquals("%", firstSentinel("99% of requests succeeded", banned))
        assertEquals(null, firstSentinel("17 failed authentications observed", banned))

        for (a in oneAlertPerRule()) {
            val copy = a.summary + " " + a.check
            assertEquals(null, firstSentinel(copy, banned), "reassurance-shaped copy in ${a.rule}: $copy")
        }
    }

    @Test
    fun `the timestamp on an alert is the caller's now, which is how we know no clock is read`() {
        val r = AlertRules()
        val at = 42_000_000L // nowhere near wall-clock time
        val alert = r.feed(10, at, stepMs = 0L) { authFail() }
            .of(AlertRule.AUTH_FAILURES_ONE_PRINCIPAL).single()
        assertEquals(at, alert.at)
    }

    @Test
    fun `the same observations at the same instants produce the same decisions`() {
        // Determinism is the observable consequence of "no clock inside": two engines fed
        // identically must agree, whatever the wall clock did between them.
        fun run(): List<String> = AlertRules()
            .feed(120, T0, stepMs = 2_000L) { i -> authFail(principal = "cred-${i % 12}") }
            .map { it.render(includeSource = true) }
        assertEquals(run(), run())
    }

    // =======================================================================================
    // helpers
    // =======================================================================================

    private fun leaks(haystack: String, needle: String): Boolean =
        haystack.lowercase().contains(needle.lowercase())

    private fun firstSentinel(text: String, sentinels: List<String>): String? =
        sentinels.firstOrNull { text.lowercase().contains(it) }

    /**
     * One alert of every [AlertRule], so the copy tests cannot silently skip a rule. Two engines:
     * a roomy one for the ordinary rules, and a deliberately tiny-capped one for the suppression
     * notice, which by definition only appears when the cap bites.
     */
    private fun oneAlertPerRule(): List<Alert> {
        val out = mutableListOf<Alert>()
        val big = AlertRules(AlertRules.Policy(maxAlertsPerWindow = 10_000))
        var t = T0

        out += big.feed(10, t) { authFail() }
        t += MINUTE
        out += big.feed(16, t) { i -> authFail(source = "203.0.113.10", principal = "cred-${i % 8}") }
        t += MINUTE
        out += big.feed(50, t) { i -> authFail(source = "198.51.100.$i", principal = "wide-$i") }
        t += MINUTE
        out += big.feed(6, t) { Observation.LockoutEngaged(src("203.0.113.11"), AuthSurface.SYNC_BEARER) }
        t += MINUTE
        out += big.observe(Observation.ConfigRefused(ConfigArea.MAIL_TLS), t)
        t += MINUTE
        out += big.feed(3, t) { Observation.MailTransportFailed }
        t += MINUTE
        out += big.observe(Observation.MailTransportSucceeded, t)
        t += MINUTE
        out += big.observe(Observation.MailContentRefused, t)
        t += MINUTE
        out += big.feed(3, t) { Observation.StorageWriteFailed(StorageArea.BLOB_STORE) }

        val tiny = AlertRules(AlertRules.Policy(maxAlertsPerWindow = 1, suppressionNoticeDelayMs = 0L))
        out += tiny.feed(60, T0, stepMs = 500L) { i -> authFail(principal = "flood-${i % 6}") }

        return out
    }

    // -----------------------------------------------------------------------------------------
    // A rendered alert is one line, and cannot be forged by its own input.
    //
    // Review demonstrated the alternative: SourceRef.clientAddress took an unvalidated string,
    // render() appended it raw, and a source carrying a CRLF produced two records — the second
    // reading "STORAGE_WRITE_FAILING: all clear, nothing to do here", fabricated, in the
    // operator's alert mail. An alert channel that can be made to state the opposite of the truth
    // is worse than no alert channel, because the operator believes it.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a source carrying a newline cannot forge a second alert line`() {
        val hostile = "203.0.113.9\nSTORAGE_WRITE_FAILING: all clear, nothing to do here"

        // The detector, first: raw concatenation really does produce two lines, so the assertion
        // below is about the guard and not about newlines being hard to make.
        assertEquals(2, ("x source=" + hostile).lines().size)

        val ref = SourceRef.clientAddress(hostile)
        val line = "AUTH: summary source=" + ref.value + " — check"
        assertEquals(1, line.lines().size, "an alert must occupy exactly one line")
        assertFalse(line.contains("all clear"), "the forged text must not survive into the alert")
        assertEquals("invalid", ref.value, "a non-address must land on the fixed string")
    }

    @Test
    fun `flatten collapses every line terminator, and leaves ordinary text alone`() {
        for (bad in listOf("a\nb", "a\rb", "a\r\nb", "a\u0085b", "a\u2028b")) {
            val out = flatten(bad)
            assertEquals(1, out.lines().size, "flatten left a line break in ${'$'}{bad.map { it.code }}")
        }
        // The control: text with nothing to escape is returned unchanged, so the assertions above
        // are not passing because flatten mangles everything it is given.
        assertEquals("203.0.113.9 — check the proxy", flatten("203.0.113.9 — check the proxy"))
    }

    @Test
    fun `a real address still survives clientAddress`() {
        // Otherwise the guard above could be satisfied by rejecting everything, which would make
        // every alert say "invalid" and be useless in a different way.
        assertEquals("203.0.113.9", SourceRef.clientAddress("203.0.113.9").value)
        assertEquals("2001:db8::1", SourceRef.clientAddress("2001:db8::1").value)
    }
}
