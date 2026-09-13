package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.routes.PAIR_MAX_PER_WINDOW
import com.daymark.companion.routes.REPORT_MAX_PER_WINDOW
import com.daymark.companion.routes.REPORT_WINDOW_MS
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
 *
 * And, since the pairing surface's budgets were split (2026-09-12), a third property that is about
 * what this route REFUSES TO SAY. Every anonymous call gets the same 204 — right secret, wrong
 * secret, no secret, an invitation that is dead or was never there — so the one door this product
 * most needs to leave open to a stranger cannot be turned round and used to ask questions.
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
            val r = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
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
        val ok = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        // "Still gets in" now means the secret is ACCEPTED and there is simply no pairing run
        // waiting — 410, the shelf-is-empty answer — where a wrong secret is still 401. That
        // difference is the assertion: the guesses did not burn the invitation.
        assertEquals(HttpStatusCode.Gone, ok.status, "the real therapist's secret still passes — that is what was being protected")

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

        val afterwards = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
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
        //
        // What CHANGED in 2026-09-12 is only what the refusal says. It used to be a 401, which
        // told an anonymous caller "there is something at this id worth authenticating to"; it is
        // now the same flat 204 every other anonymous report gets. The property this test exists
        // for is untouched and asserted below the answer: the invitation is still PENDING, and the
        // real holder's secret still works.
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        val bare = client.post("/v1/invite/${minted.inviteId}/report")
        assertEquals(HttpStatusCode.NoContent, bare.status)

        val guessed = client.post("/v1/invite/${minted.inviteId}/report") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"not-the-secret"}""")
        }
        assertEquals(HttpStatusCode.NoContent, guessed.status)

        assertEquals("PENDING", s.auth.inviteStatusFor(minted.inviteId), "the invitation is untouched")
        assertEquals(
            0, s.audit.list(relRef, limit = 50).count { it.action == "invite.reported" },
            "and nothing that failed to prove anything was recorded as a report",
        )
        val ok = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        // Accepted (410 = nothing waiting), not refused (401 = wrong secret). See above.
        assertEquals(HttpStatusCode.Gone, ok.status)
    }

    @Test
    fun `every anonymous report reads the same from the outside`() = testApplication {
        /*
         * The oracle test. A report answers a caller who has proved nothing, so every difference
         * between one answer and another is a question an attacker gets to ask for free: does this
         * invitation exist, is it still live, is it locked, was that the right secret. The answer
         * to all of them is one 204.
         *
         * The positive control matters more than usual here, because "everything is 204" is the
         * shape of assertion that also passes when the harness has stopped looking: the same client
         * is sent at the RELAY, which is specified to distinguish a wrong secret (401) from a dead
         * invitation (410). If those two come back different, this client can tell answers apart,
         * and the flatness above is the route's doing rather than the test's blindness.
         */
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }

        val live = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val dead = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        s.auth.reportInviteByOwner(dead.inviteId)
        val burned = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        suspend fun report(inviteId: String, body: String?): HttpResponse =
            client.post("/v1/invite/$inviteId/report") {
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }

        val answers = listOf(
            "an id that never existed" to report("no-such-invite", """{"secret":"${live.secret}"}"""),
            "a live invitation, wrong secret" to report(live.inviteId, """{"secret":"wrong"}"""),
            "a live invitation, no secret at all" to report(live.inviteId, null),
            "an invitation a person already reported" to report(dead.inviteId, """{"secret":"${dead.secret}"}"""),
            // The real one, last, so the burn cannot colour the answers above.
            "a live invitation, right secret" to report(burned.inviteId, """{"secret":"${burned.secret}"}"""),
            "the same invitation again, right secret" to report(burned.inviteId, """{"secret":"${burned.secret}"}"""),
        )
        for ((what, res) in answers) {
            assertEquals(HttpStatusCode.NoContent, res.status, "$what must read like every other report")
            assertEquals("", res.bodyAsText(), "$what must carry no body to read either")
        }

        // Only the one that proved the secret changed anything.
        assertEquals("PENDING", s.auth.inviteStatusFor(live.inviteId))
        assertEquals("REPORTED", s.auth.inviteStatusFor(burned.inviteId))

        // The positive control: this client CAN see a difference where the design puts one.
        val wrongOnRelay = client.post("/v1/invite/${live.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"wrong"}""")
        }
        val deadOnRelay = client.post("/v1/invite/${dead.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${dead.secret}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongOnRelay.status)
        assertEquals(HttpStatusCode.Gone, deadOnRelay.status)
    }

    @Test
    fun `a correct report is honoured while the invitation is locked out`() = testApplication {
        /*
         * The case this is all for. Somebody is guessing at an invitation — which is the strongest
         * reason its real holder could have to close it — and the lockout those guesses armed used
         * to answer the holder's report before the secret was even looked at. The guesser's volume
         * decided that the invitation stayed open.
         *
         * The lockout keeps everything it was for: it still shuts the doors that hand something
         * back. It does not shut this one, because this one hands nothing back.
         */
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        repeat(cfg.totpLockoutFails) { i ->
            client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"wrong-$i"}""")
            }
        }
        // The lock is real and in force — asserted with the CORRECT secret, so this is the
        // positive control for the assertion that follows rather than a restatement of it.
        val lockedOut = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, lockedOut.status, "the invitation is locked")

        val reported = client.post("/v1/invite/${minted.inviteId}/report") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.NoContent, reported.status)
        assertEquals(
            "REPORTED", s.auth.inviteStatusFor(minted.inviteId),
            "a lockout must not be able to keep an invitation alive against the person it was sent to",
        )
        assertEquals(
            1, s.audit.list(relRef, limit = 50).count { it.action == "invite.reported" },
            "and the owner is told it happened",
        )
    }

    @Test
    fun `the burn is idempotent - a second report on the same invitation writes nothing`() = testApplication {
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)
        val second = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        repeat(3) {
            val r = client.post("/v1/invite/${minted.inviteId}/report") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
            }
            assertEquals(HttpStatusCode.NoContent, r.status, "every one of them is answered the same way")
        }
        assertEquals(
            1, s.audit.list(relRef, limit = 50).count { it.action == "invite.reported" },
            "three clicks on one invitation is one thing that happened, not three",
        )
        // Positive control: the count CAN go up — so the assertion above is about the second
        // report writing nothing, not about the audit log being unable to grow.
        client.post("/v1/invite/${second.inviteId}/report") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${second.secret}"}""")
        }
        assertEquals(2, s.audit.list(relRef, limit = 50).count { it.action == "invite.reported" })
    }

    @Test
    fun `a wrong report leaves no guess row, though the same guess on the relay does`() = testApplication {
        // A report is not an attempt to get in, and this is the one anonymous surface with no
        // attempt budget in front of it — so guess rows here would be volume an attacker chooses,
        // written into a chain that never forgets. The relay is where a wrong secret is somebody
        // working on the invitation, and that still reaches the owner; the same request shape at
        // both doors is what makes this a control rather than a hope.
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        client.post("/v1/invite/${minted.inviteId}/report") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"wrong-at-the-report"}""")
        }
        assertEquals(
            0, s.audit.list(relRef, limit = 50).count { it.action == "pair.guess_failed" },
            "a wrong report is not a guess row",
        )

        client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"wrong-at-the-relay"}""")
        }
        assertEquals(
            1, s.audit.list(relRef, limit = 50).count { it.action == "pair.guess_failed" },
            "the planted control: the same wrong secret at the relay IS on the record",
        )
    }

    @Test
    fun `a spent pairing budget does not reach the report route`() = testApplication {
        /*
         * The finding behind the split, as a test. The pairing budget is per address, and an
         * honest ceremony — or a second clinician on the same clinic connection — can spend it.
         * What must never follow from that is a person being unable to close a link they did not
         * expect: report is not in that budget at all.
         */
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        // Spend the whole per-address window on the relay. Unknown ids, so this costs the budget
        // and nothing else — no invitation's counter is touched.
        repeat(PAIR_MAX_PER_WINDOW) { i ->
            client.post("/v1/invite/no-such-invite-$i/pairing/fetch") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"whatever"}""")
            }
        }
        val throttled = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, throttled.status, "the pairing budget is spent")
        assertNotNull(throttled.headers["Retry-After"], "and it says when it heals, so a screen can say so")

        val reported = client.post("/v1/invite/${minted.inviteId}/report") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.NoContent, reported.status)
        assertEquals("REPORTED", s.auth.inviteStatusFor(minted.inviteId), "the report went through anyway")
    }

    @Test
    fun `the report route keeps a raw flood guard, and it heals`() = testApplication {
        // Not a guessing budget: a ceiling on scripts. It has to exist because the route hashes an
        // Argon2 verification per call by construction, and it has to be far out of a person's
        // reach — a report is one click, and a whole clinic behind one address is a few a day.
        var now = 1_000_000L
        val dir = tmpDir()
        val cfg = config(dir)
        val s = stores(dir, cfg) { now }
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }
        val minted = s.auth.mintInvite(relRef, listOf("read.share"), 86_400L)

        repeat(REPORT_MAX_PER_WINDOW) {
            val r = client.post("/v1/invite/no-such-invite/report") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"whatever"}""")
            }
            assertEquals(HttpStatusCode.NoContent, r.status, "a human never reaches this")
        }
        val flooded = client.post("/v1/invite/no-such-invite/report") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"whatever"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, flooded.status)

        // And it is a guard, not a ban: the window rolls and the next honest report is taken.
        now += REPORT_WINDOW_MS + 1_000
        val afterwards = client.post("/v1/invite/${minted.inviteId}/report") {
            contentType(ContentType.Application.Json); setBody("""{"secret":"${minted.secret}"}""")
        }
        assertEquals(HttpStatusCode.NoContent, afterwards.status)
        assertEquals("REPORTED", s.auth.inviteStatusFor(minted.inviteId))
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
            val r = client.post("/v1/invite/${minted.inviteId}/pairing/fetch") {
                contentType(ContentType.Application.Json); setBody("""{"secret":"wrong"}""")
            }
            if (r.status == HttpStatusCode.TooManyRequests) rateLimited = true
        }
        assertTrue(rateLimited, "one source cannot spend an unlimited number of pairing attempts")
    }
}
