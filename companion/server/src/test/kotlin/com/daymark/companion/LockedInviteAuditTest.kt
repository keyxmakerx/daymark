package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
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
 * A locked invite may not be a pen the attacker writes with.
 *
 * The redeem route used to append a LOCKOUT audit row on EVERY request that arrived while the
 * invite was locked. That path is the cheapest one in the whole pairing surface — the lockout
 * check comes before the secret is even hashed, so it takes no secret and no crypto work to reach
 * — which means anyone who had merely seen the invite link could append to the owner's audit
 * chain at a pace of their choosing. The audit chain is the one signal the owner has about what
 * their access control did, and it never forgets a row; letting a link-holder pump attacker-paced
 * noise into it buries the one row that matters under volume the attacker controls, and converts
 * every probe into permanent growth of the owner's log into the bargain.
 *
 * The fix records each lockout ONCE, on the wrong-secret request that arms it (a request that did
 * pay for an Argon2 verification), and records nothing for requests that bounce off a lockout
 * already in force. These tests pin both halves of that shape, and the third test pins the part
 * that is easy to lose in the fix: the owner must still learn about EVERY lockout episode, so a
 * second episode writes a second row — "once per lockout" must never decay into "once ever".
 */
class LockedInviteAuditTest {

    private val ownerToken = "owner-token-abc"
    private val relRef = Secrets.relRefOf("inbox-token-256-bit-example-xyz")

    /**
     * Lockout base of 3600 s, deliberately much longer than [PAIR_WINDOW_MS]: the second test
     * rolls the per-source window while the invite lockout is still in force, so the probes it
     * then sends are refused by the LOCKED path itself rather than by the source budget — which
     * is exactly the unmetered, source-rotating attacker the fix is aimed at.
     */
    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, totpLockoutFails = 3, totpLockoutSeconds = 3_600L,
        inviteTtlSeconds = 86_400L, cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("locked-invite-audit").toString()

    private data class Stores(val rel: RelationStore, val auth: AuthStore, val audit: AuditStore)

    private fun stores(dir: String, cfg: Config, clock: () -> Long) = Stores(
        RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
        AuthStore(dir, clock),
        AuditStore(dir),
    )

    private fun lockoutRows(audit: AuditStore) =
        audit.list(relRef, limit = 50).count { it.action == "lockout" }

    @Test
    fun `probes against a locked invite append one lockout row, not one per probe`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        // Three wrong secrets arm the lockout. Each of these DID cost the attacker an Argon2
        // verification, so each is on the record as a guess — and the third also writes the one
        // LOCKOUT row, because it is the request on which the lockout came into force.
        repeat(cfg.totpLockoutFails) { i ->
            val r = client.post("/v1/invite/${minted.inviteId}/redeem") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"wrong-$i"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        assertEquals(1, lockoutRows(s.audit), "arming the lockout is the event, and it is recorded once")
        val rowsAfterArming = s.audit.list(relRef, limit = 50).size

        // Eight probes inside the same source window: every one answers 429 off the LOCKED path,
        // and not one of them may reach the owner's chain — this is the free-of-charge path the
        // finding is about.
        repeat(8) {
            val r = client.post("/v1/invite/${minted.inviteId}/redeem") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"whatever"}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, r.status)
        }

        // Roll past the per-source window while the invite lockout (an hour) is still in force.
        // This models the attacker the per-source budget cannot stop: one who rotates sources or
        // simply paces below the budget. Twelve more probes, all refused by the invite's own
        // lockout, and still nothing lands in the chain.
        now += PAIR_WINDOW_MS + 1_000
        repeat(12) {
            val r = client.post("/v1/invite/${minted.inviteId}/redeem") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"whatever"}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, r.status)
        }

        assertEquals(1, lockoutRows(s.audit), "twenty probes later there is still exactly one lockout row")
        assertEquals(
            rowsAfterArming, s.audit.list(relRef, limit = 50).size,
            "the locked path appended NOTHING — an attacker-paced request must not be a write into the owner's chain",
        )
    }

    @Test
    fun `each lockout episode still gets its own row, so the owner learns it kept happening`() = testApplication {
        // The overcorrection this guards against: "stop auditing locked probes" quietly becoming
        // "stop auditing lockouts". One row per EPISODE is the contract — the owner learns that a
        // lockout happened, every time one happens, just not one row per knock on it.
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        repeat(cfg.totpLockoutFails) { i ->
            client.post("/v1/invite/${minted.inviteId}/redeem") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"wrong-$i"}""")
            }
        }
        assertEquals(1, lockoutRows(s.audit))

        // Sit the whole lockout out (the base is an hour, which is also the backoff cap), then
        // arm a second one. The served lockout reset the counter, so it takes a full threshold of
        // fresh failures again — and the failure that crosses it writes row number two.
        now += 3_600_000L + 1_000
        repeat(cfg.totpLockoutFails) { i ->
            val r = client.post("/v1/invite/${minted.inviteId}/redeem") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"still-wrong-$i"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        assertEquals(2, lockoutRows(s.audit), "a second episode is a second event, and the owner sees it")
    }

    @Test
    fun `a lockout armed through the report route is on the record too`() = testApplication {
        // Redeem and report spend ONE shared fail counter, so the guess that crosses the
        // threshold can land on either surface. If only the redeem route wrote the arming row, an
        // attacker could do their guessing through report and the lockout would leave no trace.
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        repeat(cfg.totpLockoutFails) { i ->
            val r = client.post("/v1/invite/${minted.inviteId}/report") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"wrong-$i"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        assertEquals(1, lockoutRows(s.audit), "the arming row is written on whichever surface armed it")

        // And probes at the locked report route stay silent the same way redeem's do.
        val rowsAfterArming = s.audit.list(relRef, limit = 50).size
        repeat(4) {
            val r = client.post("/v1/invite/${minted.inviteId}/report") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"whatever"}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, r.status)
        }
        assertEquals(1, lockoutRows(s.audit))
        assertEquals(rowsAfterArming, s.audit.list(relRef, limit = 50).size)
    }
}
