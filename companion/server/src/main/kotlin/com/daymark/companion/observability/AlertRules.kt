package com.daymark.companion.observability

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Decides **when an operator should be told something is wrong, and what they are told**.
 *
 * This file is a pure decision module: it opens no socket, touches no disk, knows nothing about
 * Ktor or SMTP, and — importantly — **never reads a clock**. Every entry point takes `now` as a
 * parameter, so a test can drive a week of traffic through it in a millisecond and the behaviour
 * under test is the real behaviour, not a sleep-shaped approximation of it. Delivery is somebody
 * else's job; see "WIRING" at the bottom of this KDoc.
 *
 * ## The operator is not the owner
 *
 * [com.daymark.companion.mail.OwnerNotifier] mails the *owner* about their own reviews. That is a
 * different person with a different job and a different threat model. Nothing in this server
 * currently tells the person *running* it that it is under attack, that its one writable path has
 * gone read-only, or that its alert channel is dead. That is what this decides.
 *
 * ## What an alert may contain — the cardinal rule, enforced structurally
 *
 * Daymark is a zero-knowledge relay for mental-health records. An alert that names a therapist, a
 * client, a relationship or a piece of content would hand the operator (and every downstream SIEM,
 * mail spool and paging service) exactly what the encryption exists to withhold. So the types here
 * make leaking hard rather than merely discouraged:
 *
 * - A credential identity enters as [PrincipalRef], which hashes it with a per-process [Salt] at
 *   construction and then **exposes no accessor at all**. The engine can count distinct principals;
 *   it cannot print one. [Alert] has no principal-shaped field, so there is nowhere to put one.
 * - Everything else that could carry text is a **closed enum** ([AuthSurface], [ConfigArea],
 *   [StorageArea]) rather than a `String`. A caller cannot smuggle a subject line, a path, a
 *   `relRef` or an exception message through an enum. This is the same trick the sealed
 *   [com.daymark.companion.mail.MailMessage] hierarchy uses to make a free-form mail body
 *   unrepresentable.
 * - No caller-supplied string is ever interpolated into [Alert.summary] or [Alert.check]. Those are
 *   built from fixed templates in this file plus integers plus enum names declared in this file.
 *   A test asserts it, and proves the detector first.
 *
 * The one caller-supplied string that survives is [SourceRef], and it lives in its own field
 * ([Alert.source]) rather than in the prose. See that type for why, and for the choice the
 * orchestrator has to make about it.
 *
 * ## No location features
 *
 * There is deliberately no geo-IP, no country lookup and no enrichment hook where one could be
 * added. An operator alert says "50 distinct sources", never "50 sources, mostly in X".
 *
 * ## Never a grade, never an all-clear
 *
 * There is no severity field, no risk number, no percentage and no "system healthy" message. Those
 * are the same green-shield failure this product refuses everywhere else: they invite an operator
 * to read reassurance into the absence of a signal. Silence here asserts nothing. The rule name and
 * the observed counts *are* the message.
 *
 * Alerts state what was OBSERVED and what an operator might CHECK. They do not say what happened.
 * Three failed logins from a colleague's new laptop and three from an attacker are the same
 * observation, and this module is not in a position to tell them apart.
 *
 * ## Alert fatigue is a security failure
 *
 * An operator who mutes the channel is worse off than one who was never alerted, so volume is
 * bounded three ways and all three are tested:
 *
 * 1. **Cooldown**, per rule *and per key* — a repeat for the same credential is silent for
 *    [Policy.cooldownMs], but a first-ever alert for a different credential is not.
 * 2. **Hysteresis via drain-on-fire** — when a rule fires it clears the counters that fired it, so
 *    the condition must re-accumulate a *full* threshold to fire again. Without this, one event
 *    over the line produces one alert per event forever after.
 * 3. **A hard cap** of [Policy.maxAlertsPerWindow] alerts per [Policy.capWindowMs], across all
 *    rules. Past the cap, alerts are dropped — but the *rule codes* that were dropped are
 *    accumulated and reported once in a single [AlertRule.ALERTS_SUPPRESSED] notice, so a flood of
 *    login failures can never bury the fact that the disk also stopped accepting writes. Rule codes
 *    are constants of this file; reporting them leaks nothing.
 *
 * ## WIRING (the orchestrator's job)
 *
 * - Construct one [AlertRules] per process. Construct one [Salt] per process
 *   ([Salt.random]) and keep it in memory only — never persist it, never log it. A salt that
 *   survives a restart, or that anyone can read, turns [PrincipalRef] back into a stable
 *   cross-restart identifier for a specific therapist.
 * - Call [observe] from the places that already know these things happened (the auth guard, the
 *   attempt limiter, the mailer, the blob/audit stores, startup validation) and route the returned
 *   alerts to delivery. Call [flush] from any timer you already have so a pending suppression
 *   notice does not wait for the next observation.
 * - **[Delivery.LOG_ONLY] must not be mailed.** See [AlertRule.MAIL_TRANSPORT_FAILING].
 */
class AlertRules(private val policy: Policy = Policy()) {

    // ---------------------------------------------------------------------------------------
    // Thresholds and windows. Every one of them is a judgement; the reasoning is next to it.
    // ---------------------------------------------------------------------------------------

    data class Policy(
        /**
         * **Rule 1 — one credential under sustained attack.**
         *
         * The per-credential TOTP lockout defaults to 5 failures in 300 s, and the sync bearer
         * lockout to 8. Ten failures against one credential inside five minutes therefore means
         * the lockout has *already engaged and someone kept going* — and because
         * [com.daymark.companion.auth.AuthGuard]'s lockout is keyed on the source rather than the
         * credential, a caller spreading attempts across sources never trips it at all. That gap
         * is exactly what this rule watches.
         */
        val oneCredentialFailures: Int = 10,
        val oneCredentialWindowMs: Long = 5 * 60_000L,

        /**
         * **Rule 2 — credential stuffing.** The discriminator between an attack and a forgotten
         * password is not the number of failures, it is the number of *distinct principals*: a
         * person who cannot remember their passphrase generates many failures against exactly one
         * credential. Both floors must be crossed, and both are set high enough to survive the
         * common benign case — several people behind one NAT each mistyping once.
         */
        val stuffingDistinctPrincipals: Int = 8,
        val stuffingFailures: Int = 16,
        val stuffingWindowMs: Long = 15 * 60_000L,

        /**
         * **Rule 3 — the same volume spread across many sources.** Rules 1 and 2 both key on
         * something the attacker controls; a botnet defeats them by using each source once.
         * Fifty distinct sources failing authentication inside fifteen minutes is not a shape a
         * self-hosted server with a handful of therapists produces on its own. This is the
         * fleet-level sibling of the "sweep freed none" warning already in `AuthGuard`.
         */
        val wideSpreadDistinctSources: Int = 50,
        val wideSpreadWindowMs: Long = 15 * 60_000L,

        /**
         * **Rule 4 — a lockout re-arming over and over.** Deliberately a long window and a low
         * count: the signal is not volume (rules 1–3 own that) but *persistence past a control
         * that already said no*. Six lockouts in half an hour from one source is a client stuck in
         * a retry loop with a stale token, or someone who has decided to wait the lockout out.
         */
        val lockoutRearms: Int = 6,
        val lockoutWindowMs: Long = 30 * 60_000L,

        /** **Rule 6 — the alert channel itself.** Three consecutive-ish failures, not one: a
         *  single greylisting bounce is not an outage. */
        val mailFailures: Int = 3,
        val mailWindowMs: Long = 15 * 60_000L,

        /**
         * **Rule 7 — the content guard refused a message.** Threshold one, because a refusal means
         * something in this process tried to put text into an email that the guard did not
         * recognise as template. That is a bug or an attack, and it is precisely the failure mode
         * this product cannot afford to discover from a user.
         */
        val contentRefusals: Int = 1,
        val contentWindowMs: Long = 60 * 60_000L,

        /** **Rule 8 — storage writes failing.** `/readyz` already logs edges; this escalates. */
        val storageFailures: Int = 3,
        val storageWindowMs: Long = 10 * 60_000L,

        /** Default per-rule silence after firing. */
        val cooldownMs: Long = 30 * 60_000L,
        /** For rules whose condition changes slowly, or whose repeat says nothing new. */
        val longCooldownMs: Long = 60 * 60_000L,
        /** Configuration does not change without a restart, so repeating it inside a day is noise. */
        val configCooldownMs: Long = 24 * 60 * 60_000L,

        /** The hard cap: at most this many alerts per [capWindowMs], across every rule. */
        val maxAlertsPerWindow: Int = 6,
        val capWindowMs: Long = 60 * 60_000L,

        /**
         * How long the engine accumulates suppressed rule codes before emitting the single
         * [AlertRule.ALERTS_SUPPRESSED] notice. Without a delay the notice would fire on the first
         * suppression and name exactly one rule, which is the least useful moment to send it.
         */
        val suppressionNoticeDelayMs: Long = 5 * 60_000L,
        /** And its own cooldown, so a sustained flood cannot turn the notice into the flood. */
        val suppressionNoticeCooldownMs: Long = 15 * 60_000L,

        /**
         * Bounds on retained state. A distributed flood must not turn this module into the outage:
         * a per-source map with no cap is an attacker-controlled allocation. Dropping the
         * least-recently-seen key can only *undercount*, which is the safe direction for a
         * threshold — it delays an alert, it never invents one.
         */
        val maxTrackedKeys: Int = 4096,
        val maxEventsPerKey: Int = 256,

        /**
         * False when the operator has not configured SMTP at all. Every alert is then
         * [Delivery.LOG_ONLY] from the start, and the mail rules are inert — there is no channel
         * to report as failing.
         */
        val mailAvailable: Boolean = true,
    )

    // ---------------------------------------------------------------------------------------
    // State. All of it is bounded; see prune().
    // ---------------------------------------------------------------------------------------

    private class AuthEvent(val at: Long, val source: SourceRef, val principal: PrincipalRef, val surface: AuthSurface)

    /**
     * `LinkedHashMap` and not `HashMap`: entries are re-inserted on touch, so iteration order is
     * least-recently-seen first and both expiry pruning and over-cap eviction are a walk from the
     * head rather than a scan of everything.
     */
    private val authByPrincipal = LinkedHashMap<PrincipalRef, ArrayDeque<AuthEvent>>()
    private val authBySource = LinkedHashMap<SourceRef, ArrayDeque<AuthEvent>>()
    private val sourceLastFailure = LinkedHashMap<SourceRef, Long>()
    private val globalFailures = ArrayDeque<Long>()
    private val lockoutsBySource = LinkedHashMap<SourceRef, ArrayDeque<Long>>()
    private val storageByArea = LinkedHashMap<StorageArea, ArrayDeque<Long>>()
    private val mailTransportFailures = ArrayDeque<Long>()
    private val contentRefusals = ArrayDeque<Long>()

    private var mailDegraded = false
    private var mailFailuresWhileDegraded = 0L
    private var alertsNotMailed = 0L

    private val lastFired = LinkedHashMap<String, Long>()
    private val emitted = ArrayDeque<Long>()
    private val suppressedRules = LinkedHashSet<AlertRule>()
    private var suppressedCount = 0L
    private var suppressedSince = 0L

    private val authSourceWindowMs: Long
        get() = maxOf(policy.stuffingWindowMs, policy.wideSpreadWindowMs)

    // ---------------------------------------------------------------------------------------
    // Entry points
    // ---------------------------------------------------------------------------------------

    /**
     * Feed one observation and get back whatever an operator should be told *right now* — usually
     * nothing. A single observation can trip more than one rule (the same failure can be both "one
     * credential under attack" and "many credentials from one source"), which is why this returns a
     * list rather than a nullable.
     *
     * Not thread-safe by itself; the orchestrator calls it under one lock or from one dispatcher.
     * Making it internally synchronized would be easy and would also hide the fact that alert
     * decisions are ordering-sensitive, so the choice is left visible.
     */
    fun observe(observation: Observation, now: Long): List<Alert> {
        val out = mutableListOf<Alert>()
        when (observation) {
            is Observation.AuthFailure -> onAuthFailure(observation, now, out)
            is Observation.LockoutEngaged -> onLockout(observation, now, out)
            is Observation.ConfigRefused -> onConfigRefused(observation, now, out)
            is Observation.StorageWriteFailed -> onStorageFailure(observation, now, out)
            Observation.MailTransportFailed -> onMailFailure(now, out)
            Observation.MailTransportSucceeded -> onMailSuccess(now, out)
            Observation.MailContentRefused -> onContentRefusal(now, out)
        }
        flushInto(out, now)
        prune(now)
        return out
    }

    /**
     * Emits anything that is due purely because time has passed — today that is the pending
     * suppression notice. Safe and cheap to call on any timer the process already has; returns an
     * empty list when there is nothing to say. It does **not** invent a heartbeat: an empty result
     * is not an assertion that anything is well.
     */
    fun flush(now: Long): List<Alert> {
        val out = mutableListOf<Alert>()
        flushInto(out, now)
        prune(now)
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Rule bodies
    // ---------------------------------------------------------------------------------------

    private fun onAuthFailure(e: Observation.AuthFailure, now: Long, out: MutableList<Alert>) {
        val event = AuthEvent(now, e.source, e.principal, e.surface)

        val byPrincipal = touchEvents(authByPrincipal, e.principal)
        byPrincipal.addLast(event)
        trimEvents(byPrincipal, now, policy.oneCredentialWindowMs)

        val bySource = touchEvents(authBySource, e.source)
        bySource.addLast(event)
        trimEvents(bySource, now, authSourceWindowMs)

        sourceLastFailure.remove(e.source)
        sourceLastFailure[e.source] = now
        globalFailures.addLast(now)
        // A wider cap than the per-key one: this is the count rule 3 reports, and truncating it at
        // 256 would make a fleet-wide flood report a number that looked like a quiet afternoon.
        trimTimes(globalFailures, now, policy.wideSpreadWindowMs, cap = policy.maxTrackedKeys)
        pruneSourceLastFailure(now)

        // Rule 1 — one credential, many failures.
        if (byPrincipal.size >= policy.oneCredentialFailures) {
            val distinctSources = byPrincipal.mapTo(HashSet()) { it.source }.size.toLong()
            maybeFire(
                out = out,
                rule = AlertRule.AUTH_FAILURES_ONE_PRINCIPAL,
                // A constant, not the credential: [maybeFire] mixes [keyRef] into the cooldown key
                // separately, so rule 1 stays independent per credential without the credential
                // becoming part of a string anything could print.
                key = "principal",
                keyRef = e.principal,
                now = now,
                windowMs = policy.oneCredentialWindowMs,
                facts = listOf(
                    Fact("failedAuthentications", byPrincipal.size.toLong()),
                    Fact("distinctSources", distinctSources),
                ),
                context = surfaceNames(byPrincipal),
                source = if (distinctSources == 1L) byPrincipal.first().source else null,
                summary = "${byPrincipal.size} failed authentications against a single credential " +
                    "in ${minutes(policy.oneCredentialWindowMs)} minutes, from $distinctSources distinct " +
                    (if (distinctSources == 1L) "source." else "sources."),
                check = "Check whether the lockout for this surface is engaging at all " +
                    "(DAYMARK_TOTP_LOCKOUT_FAILS, DAYMARK_AUTH_LOCKOUT_FAILS) — the sync lockout is keyed " +
                    "on the source, so attempts spread across sources do not trip it. Check the sources " +
                    "reaching this server against the ones you expect.",
                drain = { byPrincipal.clear() },
            )
        }

        // Rule 2 — one source, many credentials. Evaluated over the stuffing window only, which
        // may be shorter than the deque's retention.
        val inStuffing = bySource.filter { now - it.at < policy.stuffingWindowMs }
        val distinctPrincipals = inStuffing.mapTo(HashSet()) { it.principal }.size
        if (inStuffing.size >= policy.stuffingFailures && distinctPrincipals >= policy.stuffingDistinctPrincipals) {
            maybeFire(
                out = out,
                rule = AlertRule.AUTH_FAILURES_MANY_PRINCIPALS_ONE_SOURCE,
                key = e.source.value,
                keyRef = null,
                now = now,
                windowMs = policy.stuffingWindowMs,
                facts = listOf(
                    Fact("failedAuthentications", inStuffing.size.toLong()),
                    Fact("distinctCredentials", distinctPrincipals.toLong()),
                ),
                context = surfaceNames(inStuffing),
                source = e.source,
                summary = "${inStuffing.size} failed authentications from a single source in " +
                    "${minutes(policy.stuffingWindowMs)} minutes, across $distinctPrincipals distinct credentials.",
                check = "Check DAYMARK_TRUSTED_PROXIES. With no allowlist configured, every client behind " +
                    "a reverse proxy is counted as the proxy, so unrelated clients collapse into one source " +
                    "here and in every per-source control. Then check your proxy's own rate limiting.",
                drain = { bySource.clear() },
            )
        }

        // Rule 3 — many sources.
        if (sourceLastFailure.size >= policy.wideSpreadDistinctSources) {
            maybeFire(
                out = out,
                rule = AlertRule.AUTH_FAILURES_MANY_SOURCES,
                key = "",
                keyRef = null,
                now = now,
                windowMs = policy.wideSpreadWindowMs,
                facts = listOf(
                    Fact("distinctSources", sourceLastFailure.size.toLong()),
                    Fact("failedAuthentications", globalFailures.size.toLong()),
                ),
                context = emptyList(),
                source = null,
                summary = "Failed authentications from ${sourceLastFailure.size} distinct sources in " +
                    "${minutes(policy.wideSpreadWindowMs)} minutes (${globalFailures.size} observed).",
                check = "Check whether your reverse proxy is forwarding traffic you expect it to be " +
                    "dropping, and whether DAYMARK_RATE_LIMIT_RPS is set for this deployment. Per-source " +
                    "controls do not bind when each source is used once.",
                drain = { sourceLastFailure.clear(); globalFailures.clear() },
            )
        }
    }

    private fun onLockout(e: Observation.LockoutEngaged, now: Long, out: MutableList<Alert>) {
        val q = touchTimes(lockoutsBySource, e.source)
        q.addLast(now)
        trimTimes(q, now, policy.lockoutWindowMs)
        if (q.size < policy.lockoutRearms) return
        maybeFire(
            out = out,
            rule = AlertRule.LOCKOUTS_REARMING,
            key = e.source.value,
            keyRef = null,
            now = now,
            windowMs = policy.lockoutWindowMs,
            facts = listOf(Fact("lockoutsEngaged", q.size.toLong())),
            context = listOf(e.surface.name),
            source = e.source,
            summary = "A single source engaged the lockout ${q.size} times in " +
                "${minutes(policy.lockoutWindowMs)} minutes.",
            check = "Check whether a client is retrying with a credential that has been rotated or " +
                "revoked, and check the lockout window (DAYMARK_AUTH_LOCKOUT_SECONDS) against how fast " +
                "this is repeating.",
            drain = { q.clear() },
        )
    }

    private fun onConfigRefused(e: Observation.ConfigRefused, now: Long, out: MutableList<Alert>) {
        maybeFire(
            out = out,
            rule = AlertRule.CONFIG_REFUSED,
            key = e.area.name,
            keyRef = null,
            now = now,
            windowMs = 0L,
            facts = emptyList(),
            context = listOf(e.area.name),
            source = null,
            summary = "A configuration value was refused, in ${e.area.name}.",
            check = "Check the process output from startup for the refused setting and the reason. The " +
                "server refuses configurations it cannot honour safely rather than falling back to a " +
                "weaker one, so the setting is not in effect in some reduced form.",
            drain = {},
        )
    }

    private fun onStorageFailure(e: Observation.StorageWriteFailed, now: Long, out: MutableList<Alert>) {
        val q = touchTimes(storageByArea, e.area)
        q.addLast(now)
        trimTimes(q, now, policy.storageWindowMs)
        if (q.size < policy.storageFailures) return
        maybeFire(
            out = out,
            rule = AlertRule.STORAGE_WRITE_FAILING,
            key = e.area.name,
            keyRef = null,
            now = now,
            windowMs = policy.storageWindowMs,
            facts = listOf(Fact("failedWrites", q.size.toLong())),
            context = listOf(e.area.name),
            source = null,
            summary = plural(q.size.toLong(), "storage write failed", "storage writes failed") +
                " in ${minutes(policy.storageWindowMs)} minutes, in ${e.area.name}.",
            check = "Check free space on DAYMARK_DATA_DIR, its ownership against the container user, and " +
                "whether the volume is mounted read-only. /readyz probes the same path.",
            drain = { q.clear() },
        )
    }

    /**
     * Mail delivery failing is the awkward one, because **an alert about mail being down cannot be
     * delivered by mail.** Three things happen here, and the third is the one that matters:
     *
     * 1. The alert is marked [Delivery.LOG_ONLY]. The orchestrator must not attempt to mail it —
     *    that attempt would fail, which would feed this rule again, which is a loop.
     * 2. A latch is set. While it is set, **every** alert is [Delivery.LOG_ONLY], because none of
     *    them can be delivered either. Alerts do not queue: a security alert delivered an hour
     *    late, after the operator has already dealt with the incident, is noise, and a queue of
     *    them is an attacker-controlled buffer.
     * 3. The count of alerts raised while the channel was down is kept, and reported when the
     *    channel comes back ([AlertRule.MAIL_CHANNEL_RESTORED]) — so the operator learns that they
     *    missed N alerts and where to read them, instead of silently missing them.
     *
     * The residual gap is honest and worth stating plainly: if mail is down and nobody reads the
     * process log, nobody is told. A self-hosted server has no second channel this module could
     * reach without inventing egress that this product deliberately does not have. The process log
     * is the operator's floor — `docker logs`, journald, whatever they ship it to — and the
     * container HEALTHCHECK plus `/readyz` cover the storage case independently.
     */
    private fun onMailFailure(now: Long, out: MutableList<Alert>) {
        if (!policy.mailAvailable) return
        mailTransportFailures.addLast(now)
        trimTimes(mailTransportFailures, now, policy.mailWindowMs)
        if (mailDegraded) mailFailuresWhileDegraded++
        if (mailTransportFailures.size < policy.mailFailures) return

        val observed = mailTransportFailures.size.toLong()
        maybeFire(
            out = out,
            rule = AlertRule.MAIL_TRANSPORT_FAILING,
            key = "",
            keyRef = null,
            now = now,
            windowMs = policy.mailWindowMs,
            facts = listOf(Fact("failedDeliveries", observed)),
            context = emptyList(),
            source = null,
            summary = plural(observed, "outbound mail delivery failed", "outbound mail deliveries failed") +
                " in ${minutes(policy.mailWindowMs)} minutes. Mail is this server's alert channel, " +
                "so this was not mailed.",
            check = "Check SMTP host reachability, credentials (DAYMARK_SMTP_PASS_FILE) and TLS mode. " +
                "While delivery is failing, alerts are written to the process log only — read it there.",
            drain = { mailTransportFailures.clear() },
        )
        // The latch is set whether or not the alert itself was emitted. Cooldown and the cap govern
        // how often the operator is *told*; they must not govern what the module *believes*, or a
        // capped alert would leave later alerts marked mailable into a channel that is down.
        if (!mailDegraded) {
            mailDegraded = true
            mailFailuresWhileDegraded = observed
            alertsNotMailed = 0
        }
    }

    private fun onMailSuccess(now: Long, out: MutableList<Alert>) {
        mailTransportFailures.clear()
        if (!mailDegraded) return
        mailDegraded = false
        val failures = mailFailuresWhileDegraded
        val missed = alertsNotMailed
        mailFailuresWhileDegraded = 0
        alertsNotMailed = 0
        maybeFire(
            out = out,
            rule = AlertRule.MAIL_CHANNEL_RESTORED,
            key = "",
            keyRef = null,
            now = now,
            windowMs = 0L,
            facts = listOf(Fact("failedDeliveries", failures), Fact("alertsNotMailed", missed)),
            context = emptyList(),
            source = null,
            // Deliberately not phrased as a recovery or a reassurance. It reports two counts and
            // then says, in as many words, that it is not telling the operator anything about the
            // conditions those alerts described.
            summary = "Outbound mail delivery succeeded again after " +
                plural(failures, "failed delivery", "failed deliveries") + ". " +
                plural(missed, "alert was", "alerts were") +
                " raised while it was failing and went to the process log only.",
            check = "Read the process log for the " + plural(missed, "alert", "alerts") +
                " that were not mailed. This message describes the mail channel and nothing else; " +
                "it says nothing about whether the conditions those alerts reported have changed.",
            drain = {},
        )
    }

    private fun onContentRefusal(now: Long, out: MutableList<Alert>) {
        contentRefusals.addLast(now)
        trimTimes(contentRefusals, now, policy.contentWindowMs)
        if (contentRefusals.size < policy.contentRefusals) return
        maybeFire(
            out = out,
            rule = AlertRule.MAIL_CONTENT_REFUSED,
            key = "",
            keyRef = null,
            now = now,
            windowMs = policy.contentWindowMs,
            facts = listOf(Fact("refusedMessages", contentRefusals.size.toLong())),
            context = emptyList(),
            source = null,
            summary = "The outbound mail content guard refused " +
                plural(contentRefusals.size.toLong(), "message", "messages") +
                " in ${minutes(policy.contentWindowMs)} minutes. Nothing was delivered.",
            check = "The guard refuses a message before it leaves this process, so no message went out. " +
                "Check recent changes to the mail templates or to their callers.",
            drain = { contentRefusals.clear() },
        )
    }

    // ---------------------------------------------------------------------------------------
    // Firing: cooldown, cap, hysteresis
    // ---------------------------------------------------------------------------------------

    /**
     * @param key a cooldown discriminator. It must never be an identity: for a per-principal rule
     *   pass a constant and put the [PrincipalRef] in [keyRef], which is hashed into the cooldown
     *   key without ever being rendered. That way rule 1 stays independent per credential (a first
     *   alert about credential B is not silenced by a recent one about credential A) while the
     *   credential still cannot reach an [Alert].
     * @param drain the hysteresis step, run only when the alert is actually emitted.
     */
    @Suppress("LongParameterList")
    private fun maybeFire(
        out: MutableList<Alert>,
        rule: AlertRule,
        key: String,
        keyRef: PrincipalRef?,
        now: Long,
        windowMs: Long,
        facts: List<Fact>,
        context: List<String>,
        source: SourceRef?,
        summary: String,
        check: String,
        drain: () -> Unit,
    ): Boolean {
        val cooldownKey = "${rule.name}|$key|${keyRef?.cooldownDiscriminator() ?: ""}"
        val last = lastFired[cooldownKey]
        // Suppressed by cooldown: do NOT drain. The condition is ongoing, and draining here would
        // mean a sustained attack has to start counting from zero at every suppressed evaluation
        // and could never produce the "still happening" alert once the cooldown lapses.
        if (last != null && now - last < cooldownFor(rule)) return false

        trimTimes(emitted, now, policy.capWindowMs)
        if (emitted.size >= policy.maxAlertsPerWindow) {
            suppressedRules += rule
            suppressedCount++
            if (suppressedSince == 0L) suppressedSince = now
            return false
        }

        lastFired.remove(cooldownKey)
        lastFired[cooldownKey] = now
        emitted.addLast(now)
        drain()

        val delivery = if (policy.mailAvailable && !mailDegraded) Delivery.MAIL_OR_LOG else Delivery.LOG_ONLY
        if (mailDegraded && rule != AlertRule.MAIL_TRANSPORT_FAILING && rule != AlertRule.MAIL_CHANNEL_RESTORED) {
            alertsNotMailed++
        }
        out += Alert(
            rule = rule,
            at = now,
            windowMs = windowMs,
            facts = facts,
            context = context,
            source = source,
            summary = summary,
            check = check,
            // The mail-failing alert is LOG_ONLY unconditionally, including the very first one,
            // which is emitted in the same call that sets the latch.
            delivery = if (rule == AlertRule.MAIL_TRANSPORT_FAILING) Delivery.LOG_ONLY else delivery,
        )
        return true
    }

    /**
     * Emits the single notice that says "the cap bit, and here is what it ate".
     *
     * It is exempt from the cap — otherwise the cap would silence the notice that the cap is
     * active, which is the one thing that must never be silent — but it carries its own cooldown so
     * it cannot become the flood. The bound is therefore
     * `maxAlertsPerWindow + capWindowMs / suppressionNoticeCooldownMs` per window, not unbounded.
     */
    private fun flushInto(out: MutableList<Alert>, now: Long) {
        if (suppressedCount == 0L) return
        if (now - suppressedSince < policy.suppressionNoticeDelayMs) return
        val key = "${AlertRule.ALERTS_SUPPRESSED.name}||"
        val last = lastFired[key]
        if (last != null && now - last < policy.suppressionNoticeCooldownMs) return

        lastFired.remove(key)
        lastFired[key] = now
        val count = suppressedCount
        // Rule codes are constants declared in this file. Listing them tells the operator that a
        // disk alert was among the casualties without telling them anything about anyone.
        val rules = suppressedRules.map { it.name }.sorted()
        suppressedCount = 0
        suppressedSince = 0
        suppressedRules.clear()

        out += Alert(
            rule = AlertRule.ALERTS_SUPPRESSED,
            at = now,
            windowMs = policy.capWindowMs,
            facts = listOf(Fact("suppressedAlerts", count)),
            context = rules,
            source = null,
            summary = plural(count, "alert was", "alerts were") +
                " suppressed by the cap of ${policy.maxAlertsPerWindow} per " +
                "${minutes(policy.capWindowMs)} minutes.",
            check = "Check the process log for the rules listed here. The cap exists so that a flood " +
                "cannot make the channel unreadable; it drops alerts rather than delaying them.",
            delivery = if (policy.mailAvailable && !mailDegraded) Delivery.MAIL_OR_LOG else Delivery.LOG_ONLY,
        )
    }

    private fun cooldownFor(rule: AlertRule): Long = when (rule) {
        AlertRule.CONFIG_REFUSED -> policy.configCooldownMs
        AlertRule.AUTH_FAILURES_MANY_SOURCES,
        AlertRule.LOCKOUTS_REARMING,
        AlertRule.MAIL_TRANSPORT_FAILING,
        AlertRule.MAIL_CONTENT_REFUSED,
        -> policy.longCooldownMs
        AlertRule.MAIL_CHANNEL_RESTORED -> 0L
        AlertRule.ALERTS_SUPPRESSED -> policy.suppressionNoticeCooldownMs
        else -> policy.cooldownMs
    }

    // ---------------------------------------------------------------------------------------
    // Bounded-state housekeeping
    // ---------------------------------------------------------------------------------------

    /**
     * Fetch-or-create, re-inserting so the map's iteration order stays least-recently-seen first.
     * That ordering is what makes [pruneKeyed] a walk from the head instead of a full scan, which
     * matters precisely when the map is largest — i.e. under the flood.
     */
    private fun <K> touchEvents(map: LinkedHashMap<K, ArrayDeque<AuthEvent>>, key: K): ArrayDeque<AuthEvent> {
        val value = map.remove(key) ?: ArrayDeque()
        map[key] = value
        return value
    }

    private fun <K> touchTimes(map: LinkedHashMap<K, ArrayDeque<Long>>, key: K): ArrayDeque<Long> {
        val value = map.remove(key) ?: ArrayDeque()
        map[key] = value
        return value
    }

    private fun trimTimes(q: ArrayDeque<Long>, now: Long, windowMs: Long, cap: Int = policy.maxEventsPerKey) {
        while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
        while (q.size > cap) q.removeFirst()
    }

    private fun trimEvents(q: ArrayDeque<AuthEvent>, now: Long, windowMs: Long) {
        while (q.isNotEmpty() && now - q.first().at >= windowMs) q.removeFirst()
        while (q.size > policy.maxEventsPerKey) q.removeFirst()
    }

    private fun pruneSourceLastFailure(now: Long) {
        val it = sourceLastFailure.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value < policy.wideSpreadWindowMs) break // insertion order == recency order
            it.remove()
        }
        while (sourceLastFailure.size > policy.maxTrackedKeys) {
            val head = sourceLastFailure.keys.firstOrNull() ?: break
            sourceLastFailure.remove(head)
        }
    }

    private fun prune(now: Long) {
        pruneKeyed(authByPrincipal, now, policy.oneCredentialWindowMs) { it.lastOrNull()?.at }
        pruneKeyed(authBySource, now, authSourceWindowMs) { it.lastOrNull()?.at }
        pruneKeyed(lockoutsBySource, now, policy.lockoutWindowMs) { it.lastOrNull() }
        pruneKeyed(storageByArea, now, policy.storageWindowMs) { it.lastOrNull() }

        // Cooldown entries are the one map keyed by something that outlives its window, so it gets
        // the longest retention of any of them.
        if (lastFired.size > policy.maxTrackedKeys) {
            val it = lastFired.entries.iterator()
            while (it.hasNext() && lastFired.size > policy.maxTrackedKeys) {
                val e = it.next()
                if (now - e.value >= policy.configCooldownMs) it.remove() else break
            }
            while (lastFired.size > policy.maxTrackedKeys) {
                val head = lastFired.keys.firstOrNull() ?: break
                lastFired.remove(head)
            }
        }
    }

    private fun <K, V> pruneKeyed(map: LinkedHashMap<K, V>, now: Long, windowMs: Long, newest: (V) -> Long?) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val at = newest(e.value)
            if (at == null || now - at >= windowMs) it.remove() else break
        }
        while (map.size > policy.maxTrackedKeys) {
            val head = map.keys.firstOrNull() ?: break
            map.remove(head)
        }
    }

    private fun surfaceNames(events: Collection<AuthEvent>): List<String> =
        events.map { it.surface.name }.distinct().sorted()

    private fun minutes(ms: Long): Long = ms / 60_000L

    /**
     * "1 messages" reads like a machine wrote it, and an operator who thinks a machine wrote the
     * alert reads it less carefully. Only used where a count of exactly one is reachable.
     */
    private fun plural(n: Long, singular: String, pluralForm: String): String =
        "$n " + if (n == 1L) singular else pluralForm
}

// -------------------------------------------------------------------------------------------
// Inputs
// -------------------------------------------------------------------------------------------

/** Which authentication surface an attempt was made against. An enum, so no text can ride in. */
enum class AuthSurface { SYNC_BEARER, THERAPIST_TOTP, THERAPIST_INVITE, ACCESS_RECOVERY }

/**
 * Which area of configuration was refused. An enum rather than the setting name as a `String`,
 * because "just pass the env var name" becomes "just pass the value that was rejected" in one
 * careless edit, and some of those values are the bearer token and the SMTP password.
 */
enum class ConfigArea {
    MAIL_TLS, MAIL_FROM, MAIL_PORT, MAIL_CREDENTIALS,
    AUTH_TOKEN, DATA_DIR, TRUSTED_PROXIES, PUBLIC_BASE_URL, WEBAUTHN, OTHER,
}

/**
 * Which store failed to write. An enum rather than a path, because paths in this server contain
 * blob keys and relationship-scoped directories — sensitive linkage, not a filename.
 */
enum class StorageArea { BLOB_STORE, AUDIT_LOG, RELATION_STORE, DATA_DIR_PROBE, OTHER }

/** Everything the engine can be told. Note what is absent: bodies, keys, tokens, subjects, paths. */
sealed interface Observation {
    data class AuthFailure(
        val source: SourceRef,
        val principal: PrincipalRef,
        val surface: AuthSurface,
    ) : Observation

    data class LockoutEngaged(val source: SourceRef, val surface: AuthSurface) : Observation

    data class ConfigRefused(val area: ConfigArea) : Observation

    data class StorageWriteFailed(val area: StorageArea) : Observation

    /** A send attempt failed at the transport. No recipient, no kind, no exception text. */
    data object MailTransportFailed : Observation

    /** A send attempt succeeded. This is what clears the mail-degraded latch. */
    data object MailTransportSucceeded : Observation

    /** `MailContentGuard` refused a rendered message. Nothing left the process. */
    data object MailContentRefused : Observation
}

/**
 * A per-process, in-memory salt for [PrincipalRef] (and for [SourceRef.salted], if the operator
 * wants source addresses hashed too).
 *
 * It must be generated fresh on every start and must never be persisted or logged. A salt that
 * survives a restart makes the resulting digest a stable identifier for a specific therapist across
 * the server's whole lifetime — which is the exact linkage this hashing exists to destroy. Per
 * process, the digest is stable enough to count distinct principals inside a fifteen-minute window,
 * and worthless to anyone reading a log tomorrow.
 */
class Salt(internal val bytes: ByteArray) {
    /** Never print the salt, not even a prefix of it. */
    override fun toString(): String = "Salt(redacted)"

    companion object {
        fun random(): Salt = Salt(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}

/**
 * A credential identity, hashed on the way in and **unreadable thereafter** — there is deliberately
 * no accessor, no `value`, and a `toString` that redacts. The engine needs to answer "how many
 * distinct credentials?", which equality and hashing answer; it never needs to answer "which one?",
 * so the type refuses to be able to.
 *
 * Pass a `credentialId`, an owner id, an inbox-token-derived routing id — whatever the calling site
 * already has. Never pass a `relRef` or a lineage id here expecting them to appear in an alert;
 * they will not, which is the point.
 *
 * SHA-256 with a per-process salt, not Argon2: this is on a hot path that an attacker can drive,
 * and the property needed is unlinkability outside the process, not resistance to an offline attack
 * on a stolen digest — there is no digest to steal, because none is ever written anywhere.
 */
class PrincipalRef private constructor(private val digest: String) {
    internal fun cooldownDiscriminator(): String = digest

    override fun equals(other: Any?): Boolean = other is PrincipalRef && other.digest == digest
    override fun hashCode(): Int = digest.hashCode()
    override fun toString(): String = "PrincipalRef(redacted)"

    companion object {
        fun of(credentialId: String, salt: Salt): PrincipalRef = PrincipalRef(digestOf(credentialId, salt))
    }
}

/**
 * The source an observation came from — the only caller-supplied string that can reach an [Alert],
 * and it reaches it in its own field ([Alert.source]) rather than inside [Alert.summary].
 *
 * ## The judgement, stated plainly
 *
 * An IP address is personal data, and this codebase already treats it that way (the owner-readable
 * audit log records it only when `DAYMARK_ACCESS_LOG_SOURCE_IP` is on, default off). But an alert
 * the operator cannot act on is not an alert: "17 failed authentications across 6 distinct
 * credentials" with no source is a fact about weather. The address is the thing that goes into a
 * firewall rule.
 *
 * So the module takes a position and makes the orchestrator take one too:
 *
 * - [clientAddress] passes the resolved client address through verbatim. Use
 *   `call.clientAddress()` — never `request.origin.remoteAddress` — so the trusted-proxy contract
 *   in [com.daymark.companion.ClientAddress] is honoured and the value is the real client rather
 *   than the proxy.
 * - [salted] hashes it instead, for a deployment whose posture says operator alerts must not carry
 *   addresses at all. Correlation across alerts still works; the address does not leave.
 *
 * Because it is a separate field, an operator alert can be rendered with or without it from the
 * same [Alert] — see [Alert.render].
 *
 * **Never construct this from a `relRef`, a lineage id, a session token or an email address.** Those
 * are sensitive linkage or content and this field is rendered.
 */
class SourceRef private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is SourceRef && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        /**
         * An address that has been re-parsed, or the fixed string `invalid`.
         *
         * This used to wrap whatever it was handed. Nothing upstream can produce a hostile value
         * today — `call.clientAddress()` returns a parsed address — but this is a public
         * constructor on a value that is rendered into operator mail, and "no current caller
         * misuses it" is not a property, it is a coincidence.
         */
        fun clientAddress(address: String): SourceRef =
            SourceRef(if (SecurityLog.looksLikeAddress(address)) address.substringBefore('%') else "invalid")

        /** 16 hex chars is ample to keep two sources apart inside one window, and is not an address. */
        fun salted(address: String, salt: Salt): SourceRef = SourceRef(digestOf(address, salt).take(16))
    }
}

// -------------------------------------------------------------------------------------------
// Outputs
// -------------------------------------------------------------------------------------------

enum class AlertRule {
    /** One credential, many failures — a targeted attempt that has outlived the lockout. */
    AUTH_FAILURES_ONE_PRINCIPAL,

    /** One source, many distinct credentials — the shape of stuffing rather than of forgetting. */
    AUTH_FAILURES_MANY_PRINCIPALS_ONE_SOURCE,

    /** The same volume spread thin, where no per-source control binds. */
    AUTH_FAILURES_MANY_SOURCES,

    /** A lockout engaging over and over from one source. */
    LOCKOUTS_REARMING,

    /**
     * A configuration value was refused.
     *
     * Note what this can and cannot cover. A refusal raised *during* startup validation — SMTP
     * `TLS=none`, a missing `From`, an unreadable `*_FILE` secret — happens before there is a mailer
     * to send anything with, and the process does not continue. That case is visible only in the
     * process output, and no alerting module can change that. This rule is for refusals detected
     * once the server is up: a reload, a rotated secret that no longer parses, a setting rejected
     * on a later code path.
     */
    CONFIG_REFUSED,

    /** The alert channel is failing. Always [Delivery.LOG_ONLY]. */
    MAIL_TRANSPORT_FAILING,

    /** The alert channel works again, and here is how many alerts it missed. Not an all-clear. */
    MAIL_CHANNEL_RESTORED,

    /** The content guard refused an outbound message. A bug or an attack; nothing was delivered. */
    MAIL_CONTENT_REFUSED,

    /** Writes to a store are failing. */
    STORAGE_WRITE_FAILING,

    /** The per-window cap dropped alerts, and these are the rules it dropped. */
    ALERTS_SUPPRESSED,
}

/**
 * Whether this alert may go out over the mail path.
 *
 * [LOG_ONLY] is not advisory. Mailing a [LOG_ONLY] alert either fails (because mail is what is
 * broken) or feeds the rule that produced it, and both outcomes are worse than the log line.
 */
enum class Delivery { MAIL_OR_LOG, LOG_ONLY }

/** A single named count. Labels are constants of this file, safe for a metric sink. */
data class Fact(val label: String, val value: Long)

/**
 * What the operator is told.
 *
 * There is no severity, no number out of ten and no colour. [rule] names the observation and
 * [facts] quantifies it; anything more would be this module diagnosing, which it is not equipped
 * to do.
 *
 * [summary] and [check] are built from fixed templates in this file, integers, and enum names
 * declared in this file. No caller-supplied string is ever interpolated into either. [source] is
 * the one caller-supplied value, deliberately kept out of the prose so that the delivery layer can
 * choose whether to render it — see [SourceRef].
 */
data class Alert(
    val rule: AlertRule,
    val at: Long,
    /** The window the counts were measured over. Zero for point-in-time rules. */
    val windowMs: Long,
    val facts: List<Fact>,
    /** Closed-set enum names ([AuthSurface], [ConfigArea], [StorageArea], [AlertRule]) only. */
    val context: List<String>,
    val source: SourceRef?,
    val summary: String,
    val check: String,
    val delivery: Delivery,
) {
    /**
     * One-line rendering for a log or a mail body.
     *
     * @param includeSource whether to append [source]. Pass the operator's choice; false renders an
     *   alert that provably contains nothing supplied by any caller.
     */
    /**
     * One line, and provably one line.
     *
     * Every rendered alert is flattened before it is returned. Review demonstrated the alternative:
     * `SourceRef.clientAddress` took an unvalidated string, `render` appended it raw, and a source
     * carrying a CRLF produced two records — the second reading
     * `STORAGE_WRITE_FAILING: all clear, nothing to do here`, fabricated, in the operator's alert
     * mail. An alert channel that can be made to state the opposite of the truth is worse than no
     * alert channel, because the operator believes it.
     *
     * `SecurityLog` had both a shape gate and an escaper and this file had neither. It uses them
     * now rather than growing a second copy: one implementation of "a value cannot forge a record"
     * is the only number of implementations worth having.
     */
    fun render(includeSource: Boolean = true): String = flatten(
        buildString {
            append(rule.name).append(": ").append(summary)
            if (context.isNotEmpty()) append(" [").append(context.joinToString(", ")).append("]")
            if (includeSource && source != null) append(" source=").append(source.value)
            append(" — ").append(check)
        },
    )
}

/**
 * Collapses anything that could end a line into a visible escape, so one alert is one record.
 *
 * Not a JSON escape — an alert is prose for a person, not a parsed record — but the same property:
 * no input can terminate the line it is inside. Control characters become their `\uXXXX` form so
 * an operator can see that something odd arrived rather than having it silently deleted.
 */
internal fun flatten(s: String): String = buildString(s.length) {
    for (ch in s) {
        when {
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch.isISOControl() -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
    }
}

/** SHA-256 over salt || value, hex. Shared by [PrincipalRef] and [SourceRef.salted]. */
private fun digestOf(value: String, salt: Salt): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(salt.bytes)
    md.update(value.toByteArray(Charsets.UTF_8))
    return md.digest().joinToString("") { b -> "%02x".format(b) }
}
