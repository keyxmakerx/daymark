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
import kotlin.test.assertTrue

/**
 * The owner's public keys, on their way to the clinician.
 *
 * The mirror of [TherapistKeyRoutesTest], and the reason both exist is the same: each side of the
 * relationship needs the other's public halves and, until these two routes, neither had a way to
 * get them. The visible cost of THIS gap was two of the nine fields on the sign-in form — a
 * clinician pasting the owner's signing and encryption keys, from an email, on every visit.
 *
 * The auth is REVERSED relative to the therapist route, and most of what is worth testing here
 * follows from that: the owner writes with a bearer token, the clinician reads with a session, and
 * neither may do the other's half. A clinician who could write this record could repoint the key
 * their own shares are verified against, which is the substitution the whole pinning design exists
 * to catch.
 */
class OwnerKeyRoutesTest {

    private val ownerToken = "owner-token-abc"
    private val relRefA = Secrets.relRefOf("inbox-token-a")
    private val relRefB = Secrets.relRefOf("inbox-token-b")

    /** Exactly 32 bytes each, base64url unpadded, as a real client emits. */
    private val signPub = Secrets.b64url(ByteArray(32) { (it + 3).toByte() })
    private val boxPub = Secrets.b64url(ByteArray(32) { (150 - it).toByte() })

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, totpLockoutFails = 3, totpLockoutSeconds = 60L,
        inviteTtlSeconds = 3600L, cookieSecure = false,
    )

    private class Fixture(val cfg: Config, val auth: AuthStore, val rel: RelationStore, val audit: AuditStore)

    private fun fixture(now: Long = 1_700_000_000_000L): Fixture {
        val dir = Files.createTempDirectory("owner-keys-test").toString()
        val cfg = config(dir)
        return Fixture(cfg, AuthStore(dir, clock = { now }), RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes), AuditStore(dir))
    }

    private fun body(sign: String = "", box: String = "") = """{"signPubB64":"$sign","boxPubB64":"$box"}"""

    private suspend fun HttpClient.publish(relRef: String, token: String?, sign: String = signPub, box: String = boxPub): HttpResponse =
        post("/v1/relations/$relRef/owner-keys") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body(sign, box))
        }

    @Test
    fun `the owner publishes and the clinician reads back exactly what was published`() = testApplication {
        val now = 1_700_000_000_000L
        val f = fixture(now)
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }

        assertEquals(HttpStatusCode.NoContent, client.publish(relRefA, ownerToken).status)

        val session = f.auth.createSession("cred", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        val read = client.get("/v1/relations/$relRefA/owner-keys") {
            header(HttpHeaders.Cookie, "daymark_session=${session.sessionId}")
        }
        assertEquals(HttpStatusCode.OK, read.status)
        val text = read.bodyAsText()
        // Byte-for-byte. The clinician pins these and refuses anything not signed by them, so a
        // relay that "mostly" round-trips a key is a relay that rejects every genuine share.
        assertTrue(text.contains("\"signPubB64\":\"$signPub\""), text)
        assertTrue(text.contains("\"boxPubB64\":\"$boxPub\""), text)
        assertTrue(text.contains("\"registeredAt\":$now"), text)

        val events = f.audit.list(relRefA)
        assertTrue(events.any { it.action == "owner_key.registered" }, events.toString())
        assertTrue(events.any { it.action == "owner_key.fetched" }, events.toString())
        // The audit log is metadata-only by contract; a public key is still content that arrived in
        // a request body, so it must not appear anywhere in the trace.
        assertTrue(
            events.none { e -> (e.objectRef ?: "").contains(signPub) || (e.meta?.values?.any { it.contains(signPub) } ?: false) },
            events.toString(),
        )
    }

    @Test
    fun `a clinician session cannot publish the owner's keys`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        // No bearer token — a session cookie is presented instead, which is what a compromised
        // clinician browser would have. Writing here would let it repoint the very key its own
        // shares are checked against, so the answer must be a refusal and not a partial success.
        val res = client.post("/v1/relations/$relRefA/owner-keys") {
            header(HttpHeaders.Cookie, "daymark_session=${session.sessionId}")
            contentType(ContentType.Application.Json)
            setBody(body(signPub, boxPub))
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals(null, f.auth.ownerKeys(relRefA), "nothing may be stored by a refused write")
    }

    @Test
    fun `a second publish is refused and does NOT overwrite the first`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }

        assertEquals(HttpStatusCode.NoContent, client.publish(relRefA, ownerToken).status)

        val other = Secrets.b64url(ByteArray(32) { 9 })
        val again = client.publish(relRefA, ownerToken, sign = other, box = other)
        assertEquals(HttpStatusCode.Conflict, again.status)

        // The stored row is untouched. A silent overwrite is the failure that matters here: it
        // repoints what every future share is verified against, which is the substitution pinning
        // exists to catch, and it would look exactly like success from both ends.
        val stored = f.auth.ownerKeys(relRefA)
        assertEquals(signPub, stored?.signPubB64)
        assertEquals(boxPub, stored?.boxPubB64)
        assertTrue(f.audit.list(relRefA).any { it.action == "owner_key.refused" })
    }

    @Test
    fun `a session for one relationship cannot read another's owner keys`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        assertEquals(HttpStatusCode.NoContent, client.publish(relRefB, ownerToken).status)

        val session = f.auth.createSession("cred", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        val res = client.get("/v1/relations/$relRefB/owner-keys") {
            header(HttpHeaders.Cookie, "daymark_session=${session.sessionId}")
        }
        // 403 rather than 401: the caller is authenticated and re-authenticating cannot help. Their
        // session is real and simply does not reach this relationship.
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun `an unauthenticated caller reads nothing`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        assertEquals(HttpStatusCode.NoContent, client.publish(relRefA, ownerToken).status)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/relations/$relRefA/owner-keys").status)
    }

    @Test
    fun `a key that is not 32 bytes is refused`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }

        val short = Secrets.b64url(ByteArray(31) { 1 })
        assertEquals(HttpStatusCode.BadRequest, client.publish(relRefA, ownerToken, sign = short).status)
        assertEquals(HttpStatusCode.BadRequest, client.publish(relRefA, ownerToken, box = short).status)
        assertEquals(HttpStatusCode.BadRequest, client.publish(relRefA, ownerToken, sign = "not base64!!").status)
        assertEquals(null, f.auth.ownerKeys(relRefA), "no malformed key may reach the store")
    }

    @Test
    fun `reading before anything is published is a 404, not an empty record`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit) }
        val session = f.auth.createSession("cred", relRefA, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)

        // An empty or zeroed record would be worse than a 404: the client would pin it, and every
        // genuine share would then fail verification against a key nobody holds.
        val res = client.get("/v1/relations/$relRefA/owner-keys") {
            header(HttpHeaders.Cookie, "daymark_session=${session.sessionId}")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }
}
