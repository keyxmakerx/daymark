package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.routes.PAIR_MAX_PER_WINDOW
import com.daymark.companion.routes.PAIR_WINDOW_MS
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pairing budget's durability, proven at the only level where the wiring can fail: over HTTP,
 * across a real restart of the application.
 *
 * `PersistentAttemptLimiterTest` pins the durable implementation, and `InviteReportRoutesTest`
 * pins that the redeem route has SOME per-source budget — but neither binds the route to the
 * durable implementation. That gap was found the direct way: swapping the route's
 * `pairSourceLimiter` from `PersistentAttemptLimiter` back to the in-memory `AttemptLimiter` left
 * the whole suite green. The property the durable limiter exists for — an attacker cannot buy a
 * fresh guessing budget by provoking, or waiting out, a deploy — was true while nothing kept it
 * true, which in this repository is the recurring shape of bug: a correct component behind wiring
 * no test observes. The two limiters were made deliberately interchangeable at the call site (a
 * one-word change), so the call site is exactly where a well-meant edit can undo the guarantee
 * without any store-level test noticing.
 *
 * Hence the shape of this test, which is the store-level restart test lifted to the wire:
 *
 *  1. spend the whole per-source budget through the HTTP route, using a fresh invite per guess so
 *     the per-invite backoff cannot be the thing answering 429 — the per-SOURCE budget must be;
 *  2. CLOSE the auth store and build a second, fresh ktor application over the SAME data
 *     directory — a real restart, in which everything the first process held in memory is gone
 *     and only the database is left;
 *  3. require the next attempt to still be refused, even though it carries the CORRECT secret.
 *     The order of checks in the route is part of what is being pinned here: the source budget is
 *     spent before the secret is read, so being right does not buy an over-budget source a way in;
 *  4. then advance the injected clock past the window and require the correct secret to succeed,
 *     because durability that never ages out is not a rate limit, it is a permanent ban on an
 *     address — and addresses are shared by whole households and clinics.
 *
 * The budget and window come from the production constants rather than being restated, so this
 * test follows the real numbers instead of silently testing stale ones.
 */
class PairingLimiterRestartTest {

    private val ownerToken = "owner-token-abc"
    private val relRef = Secrets.relRefOf("inbox-token-256-bit-example-xyz")

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, totpLockoutFails = 3, totpLockoutSeconds = 60L,
        inviteTtlSeconds = 86_400L, cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("pairing-limiter-restart").toString()

    private data class Stores(val rel: RelationStore, val auth: AuthStore, val audit: AuditStore)

    private fun stores(dir: String, cfg: Config, clock: () -> Long) = Stores(
        RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
        AuthStore(dir, clock),
        AuditStore(dir),
    )

    @Test
    fun `a spent pairing budget survives a restart and ages out rather than becoming a ban`() {
        // One mutable instant, captured by the clock closure BOTH lives of the store read, so the
        // test controls time across the restart the same way the store-level restart tests do.
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)

        // ---- First life of the process -------------------------------------------------------
        val s1 = stores(dir, cfg) { now }
        // The invitation the attacker is actually after. Minted first and never guessed at, so
        // its own per-invite counter stays at zero: when the correct secret is refused after the
        // restart, the refusal can only be coming from the per-SOURCE budget under test.
        val victim = s1.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        testApplication {
            application { module(cfg, null, null, s1.rel, s1.auth, s1.audit) }

            // Spend the entire budget with wrong secrets, each against a FRESHLY minted invite.
            // The fresh invite per guess matters: the per-invite backoff arms at totpLockoutFails
            // and also answers 429, so guessing at one invite repeatedly would let that counter
            // mask the one this test exists to observe. Spreading the guesses is also exactly the
            // attack the per-source budget was added for.
            repeat(PAIR_MAX_PER_WINDOW) { i ->
                val decoy = s1.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
                val r = client.post("/v1/invite/${decoy.inviteId}/pairing/fetch") {
                    contentType(ContentType.Application.Json); setBody("""{"secret":"wrong-$i"}""")
                }
                assertEquals(
                    HttpStatusCode.Unauthorized, r.status,
                    "guess ${i + 1} of $PAIR_MAX_PER_WINDOW is within budget, so it is a plain " +
                        "wrong-secret refusal — anything else means some other control fired first",
                )
            }

            // The budget is now exactly spent; the next attempt from this source is refused
            // before any secret is read. Establishing the 429 BEFORE the restart is what makes
            // the post-restart assertion meaningful: same source, same refusal, new process.
            val decoy = s1.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
            val overBudget = client.post("/v1/invite/${decoy.inviteId}/pairing/fetch") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"still-wrong"}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, overBudget.status, "the per-source budget is spent")
        }

        // ---- The restart ---------------------------------------------------------------------
        // Close everything the first life held. This is the step that separates a durable limiter
        // from an in-memory one: after these closes, the only place the spent window can possibly
        // live is the database in `dir`. A limiter whose state was in a HashMap has just lost it.
        s1.auth.close()
        s1.rel.close()
        s1.audit.close()

        // The deploy takes a minute — enough for an in-memory window to look plausible if it were
        // being re-created, and deliberately well inside PAIR_WINDOW_MS so the durable window is
        // still in force when the process comes back.
        now += 60_000L

        val s2 = stores(dir, cfg) { now }
        testApplication {
            application { module(cfg, null, null, s2.rel, s2.auth, s2.audit) }

            // The attempt that decides the finding: the source is over budget, the process has
            // restarted, and the request carries the CORRECT secret. If a restart handed the
            // attacker a fresh budget this would be a 200 and the invite would be redeemed — the
            // exact outcome "one online guess per attempt" promises cannot happen. The route
            // checks the source budget before it reads the body, so being right changes nothing.
            val afterRestart = client.post("/v1/invite/${victim.inviteId}/pairing/fetch") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"${victim.secret}"}""")
            }
            assertEquals(
                HttpStatusCode.TooManyRequests, afterRestart.status,
                "a restart must not hand the source a fresh pairing budget — this is the finding",
            )
            assertEquals(
                "PENDING", s2.auth.inviteStatusFor(victim.inviteId),
                "and the refused attempt spent nothing: the invite is untouched",
            )

            // ---- The window ages out ---------------------------------------------------------
            // Durability must not curdle into a permanent lockout. The stored window is dated
            // from the first guess, so advancing past PAIR_WINDOW_MS from THAT instant (the +60s
            // above already happened, hence the full window again is more than enough) puts this
            // source back in budget, and the honest holder of the link gets in.
            now += PAIR_WINDOW_MS
            val healed = client.post("/v1/invite/${victim.inviteId}/pairing/fetch") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"${victim.secret}"}""")
            }
            assertEquals(
                HttpStatusCode.Gone, healed.status,
                "once the window has aged out the correct secret is accepted again (410 = nothing " +
                    "waiting, where a rate-limited or wrong one would be 429 or 401) — the budget " +
                    "heals, it does not ban",
            )
        }
        s2.auth.close()
        s2.rel.close()
        s2.audit.close()
    }
}
