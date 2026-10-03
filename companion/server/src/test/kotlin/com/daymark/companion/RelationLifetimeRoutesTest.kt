package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 90-day rule as the clinician and the owner meet it over HTTP (#332).
 *
 * The relationship surface runs on an injected clock (`Application.module`'s `relationClock`): it
 * reads a share's end against it and the store dates and ends items by it, so a test moves 90 days
 * by changing a number. Sessions run on the real clock and stay valid throughout.
 */
class RelationLifetimeRoutesTest {

    private val ownerToken = "owner-token-lifetime"
    private val inboxToken = "inbox-token-lifetime-0123456789"
    private val relRef = Secrets.relRefOf(inboxToken)
    private val day = 24L * 60 * 60 * 1000
    private var now = 1_800_000_000_000L

    /** The one body every refusal carries, whatever the reason. */
    private val goneBody = """{"error":"no longer available"}"""

    private fun config(dir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 1_000_000L, maxRequestBytes = 1_001_024L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, cookieSecure = false,
    )

    private class Fixture(val cfg: Config, val rel: RelationStore, val auth: AuthStore, val audit: AuditStore)

    private fun fixture(): Fixture {
        val dir = Files.createTempDirectory("rel-lifetime-routes").toString()
        val cfg = config(dir)
        return Fixture(
            cfg,
            RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes, clock = { now }),
            AuthStore(dir),
            AuditStore(dir),
        )
    }

    private fun shareMeta(expiry: Long): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"shareId":"s","version":1,"expiry":$expiry,"ownerSigningFp":"fp"}""".toByteArray())

    private class Session(val cookie: String, val csrf: String)

    private fun session(f: Fixture): Session {
        val s = f.auth.createSession("cred-lifetime", relRef, f.cfg.sessionIdleSeconds, f.cfg.sessionAbsoluteSeconds)
        return Session(s.sessionId, s.csrfToken)
    }

    private suspend fun HttpClient.ownerPut(path: String, bytes: ByteArray, expiry: Long? = null): HttpResponse =
        put("/v1/rel/$relRef/$path") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            if (expiry != null) header("X-Share-Meta", shareMeta(expiry))
            setBody(bytes)
        }

    private suspend fun HttpClient.ownerGet(path: String): HttpResponse =
        get("/v1/rel/$relRef/$path") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }

    private suspend fun HttpClient.clinicianPut(s: Session, path: String, bytes: ByteArray): HttpResponse =
        put("/v1/rel/$relRef/$path") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${s.cookie}")
            header("X-CSRF-Token", s.csrf)
            setBody(bytes)
        }

    private suspend fun HttpClient.clinicianGet(s: Session, path: String): HttpResponse =
        get("/v1/rel/$relRef/$path") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${s.cookie}")
        }

    private suspend fun assertGone(response: HttpResponse, what: String) {
        assertEquals(HttpStatusCode.Gone, response.status, what)
        assertEquals(goneBody, response.bodyAsText(), "$what: the same words as every other refusal")
    }

    @Test
    fun `a share published with a 200-day end is served on day 89 and refused on day 91`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit, relationClock = { now }) }
        val s = session(f)
        val sealed = byteArrayOf(7, 7, 7)
        assertEquals(HttpStatusCode.Created, client.ownerPut("shares/share/1", sealed, expiry = now + 200 * day).status)

        now += 89 * day
        val served = client.clinicianGet(s, "shares/share/current")
        assertEquals(HttpStatusCode.OK, served.status)
        assertContentEquals(sealed, served.bodyAsBytes())

        now += 2 * day
        assertGone(client.clinicianGet(s, "shares/share/current"), "current on day 91")
        assertGone(client.clinicianGet(s, "shares/share/1"), "by version on day 91")
    }

    @Test
    fun `after version 2 is published, version 1 answers 410 and current is version 2, and it is not a withdrawal`() =
        testApplication {
            val f = fixture()
            application { module(f.cfg, null, null, f.rel, f.auth, f.audit, relationClock = { now }) }
            val s = session(f)
            val first = byteArrayOf(1)
            val second = byteArrayOf(2)
            assertEquals(HttpStatusCode.Created, client.ownerPut("shares/narrowed/1", first, expiry = now + 30 * day).status)
            // Control: version 1 is served while it is the newest.
            assertEquals(HttpStatusCode.OK, client.clinicianGet(s, "shares/narrowed/1").status)

            assertEquals(HttpStatusCode.Created, client.ownerPut("shares/narrowed/2", second, expiry = now + 30 * day).status)

            assertGone(client.clinicianGet(s, "shares/narrowed/1"), "the replaced version")
            val current = client.clinicianGet(s, "shares/narrowed/current")
            assertEquals(HttpStatusCode.OK, current.status)
            assertEquals("2", current.headers["X-Version"])
            assertContentEquals(second, current.bodyAsBytes())

            // Replacement writes no withdrawal into the owner's log...
            val actions = f.audit.list(relRef).map { it.action }
            assertTrue("share.revoke" !in actions, actions.toString())
            // ...and the search does see one when there is one: a real withdrawal, on another lineage.
            assertEquals(HttpStatusCode.Created, client.ownerPut("shares/taken-back/1", first, expiry = now + 30 * day).status)
            assertEquals(
                HttpStatusCode.OK,
                client.post("/v1/rel/$relRef/shares/taken-back/revoke") {
                    header("X-Rel-Token", inboxToken)
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                }.status,
            )
            assertTrue("share.revoke" in f.audit.list(relRef).map { it.action })
            // The withdrawal is refused in the same words as the replacement.
            assertGone(client.clinicianGet(s, "shares/taken-back/current"), "the withdrawn share")
        }

    @Test
    fun `an assignment and a game plan are served on day 89 and refused on day 91`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit, relationClock = { now }) }
        val s = session(f)
        assertEquals(HttpStatusCode.Created, client.clinicianPut(s, "assignments/task/1", byteArrayOf(3)).status)
        assertEquals(HttpStatusCode.Created, client.clinicianPut(s, "gameplans/plan/1", byteArrayOf(4)).status)

        now += 89 * day
        assertEquals(HttpStatusCode.OK, client.ownerGet("assignments/task/current").status)
        assertEquals(HttpStatusCode.OK, client.ownerGet("gameplans/plan/current").status)

        now += 2 * day
        assertGone(client.ownerGet("assignments/task/current"), "the assignment on day 91")
        assertGone(client.ownerGet("gameplans/plan/current"), "the game plan on day 91")
    }

    @Test
    fun `a grant is still served on day 400 (positive control)`() = testApplication {
        val f = fixture()
        application { module(f.cfg, null, null, f.rel, f.auth, f.audit, relationClock = { now }) }
        val s = session(f)
        val grant = byteArrayOf(9, 9)
        assertEquals(HttpStatusCode.Created, client.ownerPut("grants/grant/1", grant).status)
        now += 400 * day
        val read = client.clinicianGet(s, "grants/grant/current")
        assertEquals(HttpStatusCode.OK, read.status)
        assertContentEquals(grant, read.bodyAsBytes())
    }
}
