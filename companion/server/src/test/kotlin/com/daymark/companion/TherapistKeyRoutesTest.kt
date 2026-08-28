package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The therapist public-key relay: POST /v1/relations/{relRef}/therapist-keys (therapist session +
 * CSRF) and GET the same path (owner bearer token).
 *
 * Weighted towards the REFUSALS rather than the happy path, because that is where this route's
 * value is. Registration succeeding is one line of plumbing; what the owner is actually relying on
 * is that a registered key cannot be quietly replaced, that a session cannot reach across into
 * another relationship, that a cookie alone is not enough to write, and that a malformed key is
 * refused at the door instead of surfacing months later as a console that will not seal. Each of
 * those has a test that asserts the STORE afterwards, not just the status code — a 409 that had
 * nevertheless overwritten the row would pass a status-only assertion and be exactly the bug.
 */
class TherapistKeyRoutesTest {

    private val ownerToken = "owner-token-abc"

    /** Two unrelated relationships; the cross-relationship test needs a real second one. */
    private val relRefA = Secrets.relRefOf("inbox-token-a")
    private val relRefB = Secrets.relRefOf("inbox-token-b")

    /** A well-formed pair: exactly 32 bytes each, base64url without padding, as the client sends. */
    private val boxPub = Secrets.b64url(ByteArray(32) { (it + 1).toByte() })
    private val signPub = Secrets.b64url(ByteArray(32) { (200 - it).toByte() })

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, totpLockoutFails = 3, totpLockoutSeconds = 60L,
        inviteTtlSeconds = 3600L, cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("therapist-keys-test").toString()

    /** Every store the module needs, built against a fixed clock so `registeredAt` is assertable. */
    private class Fixture(val cfg: Config, val auth: AuthStore, val rel: RelationStore, val audit: AuditStore)

    private fun fixture(now: Long = 1_700_000_000_000L): Fixture {
        val dir = tmpDir()
        val cfg = config(dir)
        return Fixture(
            cfg,
            AuthStore(dir, clock = { now }),
            RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
            AuditStore(dir),
        )
    }

    private fun keysBody(box: String = "", sign: String = "") = """{"boxPubB64":"$box","signPubB64":"$sign"}"""

    private suspend fun HttpClient.register(
        relRef: String,
        sessionId: String?,
        csrf: String?,
        box: String = "",
        sign: String = "",
    ): HttpResponse = post("/v1/relations/$relRef/therapist-keys") {
        if (sessionId != null) header(HttpHeaders.Cookie, "daymark_session=$sessionId")
        if (csrf != null) header("X-CSRF-Token", csrf)
        contentType(ContentType.Application.Json)
        setBody(keysBody(box, sign))
    }

    private fun actions(audit: AuditStore, relRef: String) = audit.list(relRef).map { it.action }

    @Test
    fun `therapist registers and the owner reads back exactly what was registered`() = testApplication {
        val now = 1_700_000_000_000L
        val f = fixture(now)
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        val posted = client.register(relRefA, session.sessionId, session.csrfToken, boxPub, signPub)
        assertEquals(HttpStatusCode.NoContent, posted.status)

        val read = client.get("/v1/relations/$relRefA/therapist-keys") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, read.status)
        val body = read.bodyAsText()
        // Byte-for-byte, both keys. The owner's console derives fingerprints from these and pins
        // them, so a relay that "mostly" round-trips a key is a relay that breaks every seal.
        assertTrue(body.contains("\"boxPubB64\":\"$boxPub\""), body)
        assertTrue(body.contains("\"signPubB64\":\"$signPub\""), body)
        // Epoch MILLISECONDS, straight off AuthStore's clock — not the audit log's seconds.
        assertTrue(body.contains("\"registeredAt\":$now"), body)

        // Both routes leave a trace, and neither trace carries key material. The audit log is
        // metadata-only by contract, and a public key is still content that was in the request.
        val events = f.audit.list(relRefA)
        assertTrue(events.any { it.action == "therapist_key.registered" }, events.toString())
        assertTrue(events.any { it.action == "therapist_key.fetched" }, events.toString())
        assertTrue(
            events.none { e ->
                (e.objectRef ?: "").contains(boxPub) || (e.meta?.values?.any { it.contains(boxPub) } ?: false)
            },
            events.toString(),
        )
    }

    @Test
    fun `a second registration is refused and does NOT overwrite the first`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        assertEquals(
            HttpStatusCode.NoContent,
            client.register(relRefA, session.sessionId, session.csrfToken, boxPub, signPub).status,
        )

        // A different, entirely valid pair — this is the substitution the insert-only rule exists
        // to stop, so it must be refused on its merits and not because it looked malformed.
        val otherBox = Secrets.b64url(ByteArray(32) { 7 })
        val otherSign = Secrets.b64url(ByteArray(32) { 9 })
        val second = client.register(relRefA, session.sessionId, session.csrfToken, otherBox, otherSign)
        assertEquals(HttpStatusCode.Conflict, second.status)

        // The status code is the smaller half of this test. What matters is that the row did not
        // move: if it had, the owner's next share would be sealed to the attacker's key while the
        // console still reported it as sealed to the pinned one.
        val stored = f.auth.therapistKeys(relRefA)
        assertNotNull(stored)
        assertEquals(boxPub, stored.boxPubB64)
        assertEquals(signPub, stored.signPubB64)

        val read = client.get("/v1/relations/$relRefA/therapist-keys") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertTrue(read.bodyAsText().contains(boxPub))
        assertTrue(!read.bodyAsText().contains(otherBox))

        // The refusal is itself an event the owner is entitled to see — it is the only sign that
        // something tried to replace a key they may already have pinned.
        assertTrue(actions(f.audit, relRefA).contains("therapist_key.refused"), actions(f.audit, relRefA).toString())
    }

    @Test
    fun `a session for one relationship cannot register keys for another`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        // A real, valid session — bound to A.
        val session = f.auth.createSession("cred", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        val res = client.register(relRefB, session.sessionId, session.csrfToken, boxPub, signPub)
        // 403, not 401 and not a silent redirect to the session's own relationship: the caller is
        // authenticated and simply does not reach B, and re-authenticating cannot change that.
        assertEquals(HttpStatusCode.Forbidden, res.status)

        // Nothing written to B, which is the relationship being defended...
        assertNull(f.auth.therapistKeys(relRefB))
        // ...and nothing quietly written to A either. A handler that "helpfully" ignored the path
        // and used the session's relRef would pass the first assertion and fail this one.
        assertNull(f.auth.therapistKeys(relRefA))
    }

    @Test
    fun `a missing or wrong CSRF header is refused`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        // Cookie alone. A browser attaches this to a cross-site POST on its own, so if the cookie
        // were sufficient any page the therapist visited could publish keys as them.
        val missing = client.register(relRefA, session.sessionId, csrf = null, box = boxPub, sign = signPub)
        assertEquals(HttpStatusCode.Unauthorized, missing.status)

        val wrong = client.register(relRefA, session.sessionId, csrf = "not-the-token", box = boxPub, sign = signPub)
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        // And no cookie at all.
        val anonymous = client.register(relRefA, sessionId = null, csrf = session.csrfToken, box = boxPub, sign = signPub)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        assertNull(f.auth.therapistKeys(relRefA))
    }

    @Test
    fun `a key that is not 32 bytes is refused`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        val short31 = Secrets.b64url(ByteArray(31))
        val long33 = Secrets.b64url(ByteArray(33))
        val rejected = listOf(
            short31 to signPub,       // one byte short
            long33 to signPub,        // one byte long
            boxPub to short31,        // the SECOND key is the wrong length — both are checked
            "" to signPub,            // empty
            "not-base64url-!!" to signPub,
            // Long enough to be refused before anything is allocated to decode it.
            "A".repeat(500) to signPub,
        )
        for ((box, sign) in rejected) {
            val res = client.register(relRefA, session.sessionId, session.csrfToken, box, sign)
            assertEquals(HttpStatusCode.BadRequest, res.status, "expected 400 for box=$box")
            // One generic message for every way a key can be wrong, and it must not echo the input
            // back — a 400 that quoted the offending value would put caller-controlled bytes into
            // the owner's console and anything else that renders an error.
            assertTrue(res.bodyAsText().contains("invalid key"), res.bodyAsText())
        }
        // Not one of them landed. A wrong-length key stored now is a mystery to debug later.
        assertNull(f.auth.therapistKeys(relRefA))

        // The valid pair still registers afterwards: the refusals rejected the input, not the
        // relationship, and did not consume the one registration this relationship gets.
        assertEquals(
            HttpStatusCode.NoContent,
            client.register(relRefA, session.sessionId, session.csrfToken, boxPub, signPub).status,
        )
    }

    @Test
    fun `nothing is readable without the owner token`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        assertEquals(
            HttpStatusCode.NoContent,
            client.register(relRefA, session.sessionId, session.csrfToken, boxPub, signPub).status,
        )

        val anonymous = client.get("/v1/relations/$relRefA/therapist-keys")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertTrue(!anonymous.bodyAsText().contains(boxPub))

        val wrongToken = client.get("/v1/relations/$relRefA/therapist-keys") {
            header(HttpHeaders.Authorization, "Bearer not-the-owner-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongToken.status)
        assertTrue(!wrongToken.bodyAsText().contains(boxPub))

        // The therapist's own session does not open the read side. It is a deliberate asymmetry:
        // the writer of these keys has no business enumerating what the server holds for the
        // relationship, and a session that leaked would otherwise become a read capability too.
        val cookieOnly = client.get("/v1/relations/$relRefA/therapist-keys") {
            header(HttpHeaders.Cookie, "daymark_session=${session.sessionId}")
            header("X-CSRF-Token", session.csrfToken)
        }
        assertEquals(HttpStatusCode.Unauthorized, cookieOnly.status)
        assertTrue(!cookieOnly.bodyAsText().contains(boxPub))
    }

    @Test
    fun `the owner gets 404 before the therapist has registered anything`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val res = client.get("/v1/relations/$relRefA/therapist-keys") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        // 404 is the honest answer — the therapist has not published yet, so there is nothing to
        // pin and nothing to seal to. The console's job here is to wait.
        assertEquals(HttpStatusCode.NotFound, res.status)
        // A miss writes no audit entry: relRef is a raw path parameter on this route, so auditing
        // misses would let an owner-token holder seed the log with rows for relationships that
        // never existed.
        assertTrue(f.audit.list(relRefA).isEmpty(), f.audit.list(relRefA).toString())
    }

    @Test
    fun `both halves are fail-closed when the therapist portal is off`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir).copy(therapistAuthEnabled = false)
        application { module(cfg) }
        // 503 rather than 404, so a probe cannot tell "configured, nobody registered yet" from
        // "this deployment has no therapist portal at all".
        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            client.get("/v1/relations/$relRefA/therapist-keys") {
                header(HttpHeaders.Authorization, "Bearer $ownerToken")
            }.status,
        )
        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            client.register(relRefA, "whatever", "whatever", boxPub, signPub).status,
        )
    }
}
