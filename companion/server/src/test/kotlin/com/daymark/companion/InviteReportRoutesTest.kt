package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.routes.PAIR_MAX_PER_WINDOW
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The burn rule as it is actually reachable: over the wire.
 *
 * `InviteBurnRuleTest` pins the store's behaviour; this pins the wiring, because a correct store
 * behind a route that burns on a 401 would be exactly as broken as a burning store. The two
 * properties under test are the ones an attacker would go after from opposite directions:
 *
 *  - No number of wrong secrets sent to the public routes may destroy an invitation. With a
 *    password-authenticated exchange a typo and a hostile guess are indistinguishable, so anything
 *    that kills the invite on failure hands whoever holds the link a permanent veto over every
 *    invitation the owner ever mints.
 *  - An explicit report, from either side, kills it immediately — and no third party who has
 *    neither the owner's token nor the invite secret can do the same.
 */
class InviteReportRoutesTest {

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

    private fun tmpDir() = Files.createTempDirectory("invite-report-test").toString()

    private data class Stores(val rel: RelationStore, val auth: AuthStore, val audit: AuditStore)

    private fun stores(dir: String, cfg: Config, clock: () -> Long) = Stores(
        RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
        AuthStore(dir, clock),
        AuditStore(dir),
    )

    @Test
    fun `no number of wrong secrets over the wire consumes the invite`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        // Nine guesses, spaced far enough apart to sit out each armed lockout, so this is a
        // sustained campaign rather than one burst that the backoff would have swallowed anyway.
        repeat(9) { i ->
            val r = client.post("/v1/invite/${minted.inviteId}/redeem") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"wrong-$i"}""")
            }
            assertTrue(
                r.status == HttpStatusCode.Unauthorized || r.status == HttpStatusCode.TooManyRequests,
                "a guess is refused or throttled, never fatal — got ${r.status}",
            )
            assertTrue(r.status != HttpStatusCode.Gone, "410 would mean a guess had destroyed the invite")
            now += 120_000
        }

        assertEquals("PENDING", s.auth.inviteStatusFor(minted.inviteId))
        val ok = client.post("/v1/invite/${minted.inviteId}/redeem") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status, "the real therapist still gets in — that is what was being protected")

        // Each guess is recorded. The automatic response is restrained on purpose, so the log is
        // the only thing that tells the owner somebody was working on their invite.
        val actions = s.audit.list(relRef, limit = 50).map { it.action }
        assertTrue(actions.contains("pair.guess_failed"), "a failed pairing attempt that left no trace would be an attack that left no trace")
    }

    @Test
    fun `an owner report kills the invite at once`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        val reported = client.post("/v1/invite/${minted.inviteId}/report") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.NoContent, reported.status, "an owner needs no body to say this wasn't me")
        assertEquals("REPORTED", s.auth.inviteStatusFor(minted.inviteId))

        val afterwards = client.post("/v1/invite/${minted.inviteId}/redeem") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.Gone, afterwards.status, "even the correct secret is refused once a person has reported it")

        val report = assertNotNull(
            s.audit.list(relRef, limit = 50).firstOrNull { it.action == "invite.reported" },
            "the report is on the record",
        )
        assertEquals("owner", report.actor, "and which side reported is part of what it says")
    }

    @Test
    fun `the invited party can report with the secret they were sent`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        val res = client.post("/v1/invite/${minted.inviteId}/report") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.NoContent, res.status)
        assertEquals("REPORTED", s.auth.inviteStatusFor(minted.inviteId))
        assertEquals(
            "therapist",
            s.audit.list(relRef, limit = 50).first { it.action == "invite.reported" }.actor,
        )
    }

    @Test
    fun `a report without the secret or the owner token cannot kill anything`() = testApplication {
        // Otherwise the fix would have reintroduced the bug it exists to remove: a bare POST to a
        // guessed invite id would destroy invitations for free.
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        val bare = client.post("/v1/invite/${minted.inviteId}/report")
        assertEquals(HttpStatusCode.Unauthorized, bare.status)

        val guessed = client.post("/v1/invite/${minted.inviteId}/report") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"not-the-secret"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, guessed.status)

        assertEquals("PENDING", s.auth.inviteStatusFor(minted.inviteId), "the invitation is untouched")
        val ok = client.post("/v1/invite/${minted.inviteId}/redeem") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test
    fun `an unknown invite id is refused the same way a terminal one is`() = testApplication {
        // Non-enumerating: the report route must not become a way to discover which invitations
        // exist by comparing responses.
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        s.auth.reportInviteByOwner(minted.inviteId)

        val terminal = client.post("/v1/invite/${minted.inviteId}/report") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        val unknown = client.post("/v1/invite/no-such-invite/report") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.Gone, terminal.status)
        assertEquals(terminal.status, unknown.status)
    }

    @Test
    fun `the pairing surface has a per-source budget of its own`() = testApplication {
        // The per-invite backoff is only half the control the design asks for; without the
        // per-source half an attacker simply spreads their guessing across freshly minted invites.
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }

        var rateLimited = false
        repeat(PAIR_MAX_PER_WINDOW + 1) {
            // A fresh invite each time, so nothing here is stopped by the per-invite counter.
            val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
            val r = client.post("/v1/invite/${minted.inviteId}/redeem") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"wrong"}""")
            }
            if (r.status == HttpStatusCode.TooManyRequests) rateLimited = true
        }
        assertTrue(rateLimited, "one source cannot spend an unlimited number of pairing attempts")
    }
}
