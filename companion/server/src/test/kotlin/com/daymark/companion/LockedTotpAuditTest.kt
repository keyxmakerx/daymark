package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.auth.Totp
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A locked TOTP credential may not be a pen the attacker writes with — the sibling of
 * [LockedInviteAuditTest], on the surface that outlives the invite.
 *
 * The verify route used to append a LOCKOUT audit row on EVERY request that bounced off an
 * already-locked credential. That is a worse primitive than the invite one, because the input is
 * a `credentialId` — a therapist-typed username that a current or former therapist knows and
 * that anyone observing their login traffic learns — and it stays valid for the life of the
 * relationship, where an invite dies in a day. The only meter on the path was the in-memory
 * source limiter, which a restart clears and source rotation sidesteps; the chain the rows land
 * in never forgets. So the same contract as the invite routes now holds here: the lockout is
 * recorded once, on the failure that ARMS it, and probes bouncing off it write nothing.
 *
 * These tests also lean on the route now asking the STORE for the time (AuthStore.nowMs) rather
 * than System.currentTimeMillis(): the lockout was armed off the store's clock, so the route
 * must consult the same clock — which is also what lets a test drive the locked path at all.
 */
class LockedTotpAuditTest {

    private val ownerToken = "owner-token-abc"
    private val relRef = Secrets.relRefOf("inbox-token-256-bit-example-xyz")

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, totpLockoutFails = 3, totpLockoutSeconds = 3_600L,
        inviteTtlSeconds = 86_400L, cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("locked-totp-audit").toString()

    private data class Stores(val rel: RelationStore, val auth: AuthStore, val audit: AuditStore)

    private fun stores(dir: String, cfg: Config, clock: () -> Long) = Stores(
        RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
        AuthStore(dir, clock),
        AuditStore(dir),
    )

    private fun lockoutRows(audit: AuditStore) =
        audit.list(relRef, limit = 50).count { it.action == "lockout" }

    private val secretBytes = ByteArray(20) { (it + 3).toByte() }

    /**
     * A code that is wrong AT THE GIVEN TIME, chosen against the verifier's own acceptance
     * window rather than hard-coded — a fixed "000000" would go stale the day the fake clock
     * in a test lands on a step whose real code happens to be all zeros.
     */
    private fun wrongCodeFor(nowMs: Long): String {
        val nowS = nowMs / 1000
        val plausible = (-1L..1L).map { Totp.code(secretBytes, nowS + it * 30) }.toSet()
        return generateSequence(0) { it + 1 }
            .map { it.toString().padStart(6, '0') }
            .first { it !in plausible }
    }

    /** Redeem a fresh invite over the wire and enroll [credentialId], the way a therapist does. */
    private suspend fun enroll(
        client: io.ktor.client.HttpClient,
        auth: AuthStore,
        credentialId: String,
    ) {
        // A ticket, through the store: the only route that mints one is the owner's pairing
        // approve, and this test is about what happens to a credential long after enrolment.
        val minted = auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val ticket = Secrets.b64url(ByteArray(32) { (it + 7).toByte() })
        assertEquals(AuthStore.ApproveStatus.OK, auth.approveRedeem(minted.inviteId, ticket).status)
        val enroll = client.post("/v1/totp/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"enrollTicket":"$ticket","credentialId":"$credentialId","secret":"${Secrets.b64url(secretBytes)}"}""")
        }
        assertEquals(HttpStatusCode.NoContent, enroll.status)
    }

    @Test
    fun `probes against a locked credential append one lockout row, not one per probe`() = testApplication {
        var now = 1_000_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        enroll(client, s.auth, "cred-1")

        // Three wrong codes arm the lockout; the third — the one on which it came into force —
        // writes the single LOCKOUT row.
        repeat(cfg.totpLockoutFails) {
            val r = client.post("/v1/totp/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"credentialId":"cred-1","code":"${wrongCodeFor(now)}"}""")
            }
            assertEquals(
                if (it == cfg.totpLockoutFails - 1) HttpStatusCode.TooManyRequests else HttpStatusCode.Unauthorized,
                r.status,
            )
        }
        assertEquals(1, lockoutRows(s.audit), "arming the lockout is the event, and it is recorded once")
        val rowsAfterArming = s.audit.list(relRef, limit = 50).size

        // Ten probes while the lockout is in force: every one bounces 429 off the locked path,
        // and not one may reach the owner's chain. credentialId is all this path takes, and a
        // credentialId is knowledge, not a secret — this is the free-of-charge write the fix
        // removed.
        repeat(10) {
            val r = client.post("/v1/totp/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"credentialId":"cred-1","code":"${wrongCodeFor(now)}"}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, r.status)
        }
        assertEquals(1, lockoutRows(s.audit), "ten probes later there is still exactly one lockout row")
        assertEquals(
            rowsAfterArming, s.audit.list(relRef, limit = 50).size,
            "the locked path appended NOTHING — a knock on a locked door is not a write into the owner's chain",
        )
    }

    @Test
    fun `each lockout episode still gets its own row, so the owner learns it kept happening`() = testApplication {
        // The same overcorrection guard as the invite side: "stop auditing locked probes" must
        // never decay into "stop auditing lockouts". Serving a lockout resets the fail counter
        // (recordTotpFailure), so a second episode takes a full threshold of fresh failures —
        // and the failure that crosses it writes row number two.
        var now = 1_000_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        enroll(client, s.auth, "cred-1")

        repeat(cfg.totpLockoutFails) {
            client.post("/v1/totp/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"credentialId":"cred-1","code":"${wrongCodeFor(now)}"}""")
            }
        }
        assertEquals(1, lockoutRows(s.audit))

        // Sit the whole lockout out. The route asks the store's clock, so advancing the
        // injected time really does expire it.
        now += cfg.totpLockoutSeconds * 1000 + 1_000
        repeat(cfg.totpLockoutFails) {
            val r = client.post("/v1/totp/verify") {
                contentType(ContentType.Application.Json)
                setBody("""{"credentialId":"cred-1","code":"${wrongCodeFor(now)}"}""")
            }
            assertEquals(
                if (it == cfg.totpLockoutFails - 1) HttpStatusCode.TooManyRequests else HttpStatusCode.Unauthorized,
                r.status,
            )
        }
        assertEquals(2, lockoutRows(s.audit), "a second episode is a second event, and the owner sees it")
    }
}
