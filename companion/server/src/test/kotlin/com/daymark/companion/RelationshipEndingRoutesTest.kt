package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.auth.Totp
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.Channel
import com.daymark.companion.storage.RelationStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A CLINICIAN ENDING THEIR OWN ACCESS (issue #91): POST /v1/relations/{relRef}/ending, the sign-in
 * refusal it arms, the share refusal it arms, and what the owner can read back.
 *
 * The issue names one claim to attack before any of this is believed: self-leave cannot destroy
 * anything belonging to the owner. So the tests weighted heaviest here are not the happy path but
 * the two properties the whole feature rests on — that the ending CLOSES the way in, and that it
 * touches NOTHING of the owner's. The second is asserted against the stores afterwards rather than
 * against a status code: a route that answered 204 and had nevertheless withdrawn a share would
 * pass a status-only test and be precisely the bug.
 *
 * Every absence assertion below is paired with a positive control, because a check that cannot see
 * a planted example proves only that it is blind.
 */
class RelationshipEndingRoutesTest {

    private val ownerToken = "owner-token-abc"

    private val inboxA = "inbox-token-a"
    private val relRefA = Secrets.relRefOf(inboxA)
    private val relRefB = Secrets.relRefOf("inbox-token-b")

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, totpLockoutFails = 3, totpLockoutSeconds = 60L,
        inviteTtlSeconds = 3600L, cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("relationship-ending-test").toString()

    private class Fixture(val cfg: Config, val auth: AuthStore, val rel: RelationStore, val audit: AuditStore)

    private fun fixture(now: Long = 1_700_000_000_000L, clock: () -> Long = { now }): Fixture {
        val dir = tmpDir()
        val cfg = config(dir)
        return Fixture(
            cfg,
            AuthStore(dir, clock = clock),
            RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
            AuditStore(dir),
        )
    }

    private suspend fun HttpClient.leave(relRef: String, sessionId: String?, csrf: String?): HttpResponse =
        post("/v1/relations/$relRef/ending") {
            if (sessionId != null) header(HttpHeaders.Cookie, "daymark_session=$sessionId")
            if (csrf != null) header("X-CSRF-Token", csrf)
        }

    private suspend fun HttpClient.readEnding(relRef: String): HttpResponse =
        get("/v1/relations/$relRef/ending") { header(HttpHeaders.Authorization, "Bearer $ownerToken") }

    /*
     * A share deadline in the header shape the owner's console sends: URL-safe base64, no padding,
     * which is what libsodium's URLSAFE_NO_PADDING emits and the only thing the server's decoder
     * takes. The deadline is read against the ROUTE's clock, which is the wall clock here even when
     * the store's is pinned, so it is expressed relative to now rather than to the fixture.
     */
    private fun shareMeta(expiry: Long): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"shareId":"s","version":0,"expiry":$expiry}""".toByteArray())

    private fun aMonthFromNow(): Long = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000

    private suspend fun HttpClient.publishShare(relRef: String, inboxToken: String, version: Int, expiry: Long): HttpResponse =
        put("/v1/rel/$relRef/shares/share/$version") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            header("X-Rel-Token", inboxToken)
            header("X-Share-Meta", shareMeta(expiry))
            setBody("sealed-bytes")
        }

    private fun actions(audit: AuditStore, relRef: String) = audit.list(relRef).map { it.action }

    // ── (a) the clinician's own act ──────────────────────────────────────────────────────────

    @Test
    fun `a clinician ends their relationship, and the owner can read back that it ended`() = testApplication {
        val now = 1_700_000_000_000L
        val f = fixture(now)
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred-a", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        assertEquals(HttpStatusCode.NoContent, client.leave(relRefA, session.sessionId, session.csrfToken).status)

        val stored = f.auth.relationshipEnding(relRefA)
        assertNotNull(stored)
        assertEquals("cred-a", stored.credentialId)
        assertEquals(now, stored.endedAt)

        val read = client.readEnding(relRefA)
        assertEquals(HttpStatusCode.OK, read.status)
        assertTrue(read.bodyAsText().contains("\"endedAt\":$now"), read.bodyAsText())
        // The clinician's sign-in name is theirs, not the owner's to be handed. The owner needs the
        // date to act on and nothing else, and a route that echoes a credential is a route that can
        // be asked for one. Control below proves this search can see the id when it IS present.
        assertTrue(!read.bodyAsText().contains("cred-a"), read.bodyAsText())
        assertTrue("""{"endedAt":1,"credentialId":"cred-a"}""".contains("cred-a"))

        // The owner meets it in their access log too — this is where a person actually reads it.
        assertTrue(actions(f.audit, relRefA).contains("relationship.ended"), actions(f.audit, relRefA).toString())
    }

    @Test
    fun `a live relationship reads as no ending, which is an absence and not a failure`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        assertEquals(HttpStatusCode.NotFound, client.readEnding(relRefA).status)
        assertNull(f.auth.relationshipEnding(relRefA))
    }

    @Test
    fun `ending it twice does the same thing once, and writes one audit line`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val first = f.auth.createSession("cred-a", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        assertEquals(HttpStatusCode.NoContent, client.leave(relRefA, first.sessionId, first.csrfToken).status)
        val endedAt = f.auth.relationshipEnding(relRefA)!!.endedAt

        // A second call, on a fresh session, exactly as a retry or a second click would arrive. The
        // route cannot answer an error for an act that already succeeded, and there is nothing left
        // to retry.
        val second = f.auth.createSession("cred-a", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        assertEquals(HttpStatusCode.NoContent, client.leave(relRefA, second.sessionId, second.csrfToken).status)

        assertEquals(endedAt, f.auth.relationshipEnding(relRefA)!!.endedAt, "the row did not move")
        // One line per episode, on the ending, never per call — the lockout rule, applied here. A
        // log that grows a row per retry buries the row that matters.
        assertEquals(1, actions(f.audit, relRefA).count { it == "relationship.ended" })
    }

    @Test
    fun `every session that credential holds is cut, not only the one that asked`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val onTheClinicMachine = f.auth.createSession("cred-a", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        val asking = f.auth.createSession("cred-a", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        // The control: before leaving, the other session is genuinely live, so the assertion below
        // is measuring the cut rather than a session that never worked.
        assertEquals(
            AuthStore.SessionCheck.OK,
            f.auth.validateSession(onTheClinicMachine.sessionId, f.cfg.sessionIdleSeconds).check,
        )

        assertEquals(HttpStatusCode.NoContent, client.leave(relRefA, asking.sessionId, asking.csrfToken).status)

        assertEquals(
            AuthStore.SessionCheck.MISSING,
            f.auth.validateSession(onTheClinicMachine.sessionId, f.cfg.sessionIdleSeconds).check,
        )
    }

    // ── (b) the refusals it arms ─────────────────────────────────────────────────────────────

    /** An approved enrolment ticket for a relationship — the short way to one, as TherapistAuthTest does it. */
    private fun approvedTicket(f: Fixture, relRef: String, seed: Byte): String {
        val minted = f.auth.mintInvite(relRef, listOf("read.share"), 3600L)
        val ticket = Secrets.b64url(ByteArray(32) { seed })
        assertEquals(AuthStore.ApproveStatus.OK, f.auth.approveRedeem(minted.inviteId, ticket).status)
        return ticket
    }

    @Test
    fun `after leaving, a correct code no longer opens the door`() = testApplication {
        // A REAL clock for this one. The verify path deliberately asks the STORE what time it is, so
        // that a lockout the store wrote and a route deciding whether it is still in force cannot
        // disagree — which means a pinned store clock and a code generated off the wall clock would
        // never match, and this test would pass for the wrong reason.
        val f = fixture(clock = { System.currentTimeMillis() })
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }

        val leftSecret = ByteArray(20) { (it + 3).toByte() }
        val liveSecret = ByteArray(20) { (it + 11).toByte() }
        val tickets = listOf(
            Triple(approvedTicket(f, relRefA, 1), "cred-leaving", leftSecret),
            Triple(approvedTicket(f, relRefB, 2), "cred-live", liveSecret),
        )
        for ((ticket, cred, secret) in tickets) {
            assertEquals(
                HttpStatusCode.NoContent,
                client.post("/v1/totp/enroll") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"enrollTicket":"$ticket","credentialId":"$cred","secret":"${Secrets.b64url(secret)}"}""")
                }.status,
            )
        }

        val session = f.auth.createSession("cred-leaving", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        assertEquals(HttpStatusCode.NoContent, client.leave(relRefA, session.sessionId, session.csrfToken).status)

        val secondsNow = System.currentTimeMillis() / 1000
        // A fresh, correct, unspent code from the ended clinician's own authenticator — the exact
        // thing a saved copy of the key record plus the passphrase plus the phone would give
        // somebody. Refused, and 410 rather than 401 because re-authenticating cannot help.
        val refused = client.post("/v1/totp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"credentialId":"cred-leaving","code":"${Totp.code(leftSecret, secondsNow)}"}""")
        }
        assertEquals(HttpStatusCode.Gone, refused.status)
        assertNull(refused.headers[HttpHeaders.SetCookie], "no session may be issued on the way out")

        // THE CONTROL, on a second credential and a second relationship that nobody left: same
        // enrolment, same code generation, same route — and it signs in. Without it the refusal
        // above would be satisfied by a credential that never worked at all. It is a separate
        // credential rather than the same one signing in first because a code is single-use, and a
        // replayed one is refused as a wrong code — which would have hidden the very thing this is
        // measuring behind an identical-looking 401.
        val allowed = client.post("/v1/totp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"credentialId":"cred-live","code":"${Totp.code(liveSecret, secondsNow)}"}""")
        }
        assertEquals(HttpStatusCode.OK, allowed.status)
        assertNotNull(allowed.headers[HttpHeaders.SetCookie])

        // And the ended credential's row was never deleted or rewritten. That is what keeps the
        // unique rel_ref index able to refuse a second enrolment against this relationship.
        assertNotNull(f.auth.getTotp("cred-leaving"))
    }

    @Test
    fun `the ending is invisible to a wrong code, so nobody learns it by guessing a username`() = testApplication {
        val f = fixture(clock = { System.currentTimeMillis() })
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }

        val credentialId = "cred-quiet"
        val secretBytes = ByteArray(20) { (it + 5).toByte() }
        val ticket = approvedTicket(f, relRefA, 3)
        client.post("/v1/totp/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"enrollTicket":"$ticket","credentialId":"$credentialId","secret":"${Secrets.b64url(secretBytes)}"}""")
        }
        val session = f.auth.createSession(credentialId, relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        client.leave(relRefA, session.sessionId, session.csrfToken)

        // Somebody holding only the credential id — which is a therapist-typed username, not a
        // secret — must learn nothing. A wrong code answers exactly as it does for a live
        // relationship and for one that never existed.
        val wrongOnEnded = client.post("/v1/totp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"credentialId":"$credentialId","code":"000000"}""")
        }
        val neverExisted = client.post("/v1/totp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"credentialId":"no-such-credential","code":"000000"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongOnEnded.status)
        assertEquals(neverExisted.status, wrongOnEnded.status)
        assertEquals(neverExisted.bodyAsText(), wrongOnEnded.bodyAsText())
        // Control: the honest answer IS reachable, just only from behind a correct code — proved by
        // the test above. Without that pairing this assertion would be satisfied by a server that
        // never tells anyone anything, which is a different (and worse) design.
    }

    @Test
    fun `the owner cannot go on publishing shares to somebody who has left`() = testApplication {
        val now = 1_700_000_000_000L
        val f = fixture(now)
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val expiry = aMonthFromNow()

        // The control: publishing works before the ending, so the refusal below is the ending's
        // doing and not a broken request.
        assertEquals(HttpStatusCode.Created, client.publishShare(relRefA, inboxA, 1, expiry).status)

        val session = f.auth.createSession("cred-a", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        assertEquals(HttpStatusCode.NoContent, client.leave(relRefA, session.sessionId, session.csrfToken).status)

        val refused = client.publishShare(relRefA, inboxA, 2, expiry)
        assertEquals(HttpStatusCode.Gone, refused.status)
        // A REFUSAL IS NOT A TIDY-UP. The obvious over-reach here is for the refusal to also clear
        // the lineage — "nobody will read it, so remove it" — which would make an ended relationship
        // a way to delete the owner's own published material, the one thing this feature must never
        // become. The share the owner published while it was live is still there and still readable.
        assertEquals("sealed-bytes", String(f.rel.fetch(relRefA, Channel.SHARES, "share", 1)))
        // Nothing landed. The version the owner tried to write is not on the server, so the console
        // is never in a position to say a share was sent to a reader who is gone.
        assertEquals(listOf(1L), f.rel.listVersions(relRefA, Channel.SHARES, "share").map { it.version })
    }

    // ── (c) what it cannot reach ─────────────────────────────────────────────────────────────

    @Test
    fun `leaving destroys nothing of the owner's`() = testApplication {
        val now = 1_700_000_000_000L
        val f = fixture(now)
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val expiry = aMonthFromNow()

        // Everything the owner has on this relationship: a published share, a grant, and their own
        // public keys. If any of it moved, the issue's central claim would be false.
        assertEquals(HttpStatusCode.Created, client.publishShare(relRefA, inboxA, 1, expiry).status)
        assertEquals(
            HttpStatusCode.Created,
            client.put("/v1/rel/$relRefA/grants/grant/1") {
                header(HttpHeaders.Authorization, "Bearer $ownerToken")
                header("X-Rel-Token", inboxA)
                setBody("signed-grant-bytes")
            }.status,
        )
        f.auth.registerOwnerKeys(relRefA, "sign-pub", "box-pub")

        val session = f.auth.createSession("cred-a", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        assertEquals(HttpStatusCode.NoContent, client.leave(relRefA, session.sessionId, session.csrfToken).status)

        // The share is still there, still readable, still not marked withdrawn — leaving is not a
        // revocation and must never quietly become one.
        assertEquals(listOf(1L), f.rel.listVersions(relRefA, Channel.SHARES, "share").map { it.version })
        assertEquals("sealed-bytes", String(f.rel.fetch(relRefA, Channel.SHARES, "share", 1)))
        assertEquals("signed-grant-bytes", String(f.rel.fetch(relRefA, Channel.GRANTS, "grant", 1)))
        assertNotNull(f.auth.ownerKeys(relRefA))
        // And no withdrawal was recorded, which is the audit-side statement of the same fact.
        // Control: the search does fire on a log that contains one.
        assertTrue(!actions(f.audit, relRefA).contains("share.revoke"), actions(f.audit, relRefA).toString())
        assertTrue(listOf("share.revoke", "relationship.ended").contains("share.revoke"))
    }

    @Test
    fun `a session for one relationship cannot end another`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred-a", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        val res = client.leave(relRefB, session.sessionId, session.csrfToken)
        // 403, exactly as the therapist-keys route answers the same shape of request: the caller is
        // authenticated and simply does not reach B. It is not a 404, which would be a different
        // answer for a relationship that exists than for one that does not.
        assertEquals(HttpStatusCode.Forbidden, res.status)
        // Neither relationship moved. B is the one being probed; A is checked too, because quietly
        // acting on the session's own relRef is the other way this could have gone wrong.
        assertNull(f.auth.relationshipEnding(relRefB))
        assertNull(f.auth.relationshipEnding(relRefA))
    }

    @Test
    fun `a cookie without the anti-CSRF token cannot end anything`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred-a", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        assertEquals(HttpStatusCode.Unauthorized, client.leave(relRefA, session.sessionId, null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.leave(relRefA, session.sessionId, "wrong-token").status)
        assertEquals(HttpStatusCode.Unauthorized, client.leave(relRefA, null, session.csrfToken).status)
        assertNull(f.auth.relationshipEnding(relRefA))

        // Control: the same request WITH both does end it, so the three refusals above are the
        // gate working rather than the route being broken.
        assertEquals(HttpStatusCode.NoContent, client.leave(relRefA, session.sessionId, session.csrfToken).status)
        assertNotNull(f.auth.relationshipEnding(relRefA))
    }

    @Test
    fun `the owner's bearer token cannot end a relationship on the clinician's behalf`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }

        // The owner already has their own way to stop a share; ending the clinician's standing is
        // not theirs to do, and the route must not accept the token that opens everything else.
        val res = client.post("/v1/relations/$relRefA/ending") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertNull(f.auth.relationshipEnding(relRefA))
    }

    @Test
    fun `the ending is not readable without the owner's token`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred-a", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        client.leave(relRefA, session.sessionId, session.csrfToken)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/relations/$relRefA/ending").status)
        // Control: with the token it reads, so the refusal above is the gate and not an empty route.
        assertEquals(HttpStatusCode.OK, client.readEnding(relRefA).status)
    }

    @Test
    fun `the audit line names the event and carries nothing anyone wrote`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred-a", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        client.leave(relRefA, session.sessionId, session.csrfToken)

        val event = f.audit.list(relRefA).first { it.action == "relationship.ended" }
        assertEquals("therapist", event.actor)
        // The session's own opaque id and CSRF token are credentials. Neither may appear anywhere in
        // the row — the log records what happened, never what was in the request.
        val rendered = event.toString()
        assertTrue(!rendered.contains(session.sessionId), rendered)
        assertTrue(!rendered.contains(session.csrfToken), rendered)
        // Control: the same search finds them when they ARE present, so the two absences above are
        // measurements rather than blindness.
        assertTrue("audit row ${session.sessionId}".contains(session.sessionId))
        assertTrue("audit row ${session.csrfToken}".contains(session.csrfToken))
    }
}
