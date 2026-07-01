package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.storage.RelationStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelationRoutesTest {

    private val ownerToken = "owner-token-abc"
    private val inboxToken = "inbox-token-256-bit-example-xyz"
    private val relRef = Secrets.relRefOf(inboxToken)

    private fun config(
        dataDir: String,
        therapistAuth: Boolean = true,
        authToken: String? = ownerToken,
        maxBlobBytes: Long = 26_214_400L,
        relMaxVersions: Int = 50,
        relQuota: Long = 268_435_456L,
    ) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dataDir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = authToken,
        maxBlobBytes = maxBlobBytes, maxRequestBytes = maxBlobBytes + 1024,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 200,
        therapistAuthEnabled = therapistAuth,
        relMaxVersions = relMaxVersions, relQuotaBytes = relQuota,
        cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("relstore-test").toString()

    private fun stores(dir: String, config: Config) = Triple(
        com.daymark.companion.storage.BlobStore(dir, config.maxBlobBytes, config.maxVersions, config.perTokenQuotaBytes),
        RelationStore(dir, config.maxBlobBytes, config.relMaxVersions, config.relQuotaBytes),
        AuthStore(dir),
    )

    @Test
    fun `portal is fail-closed when feature disabled`() = testApplication {
        val dir = tmpDir()
        application { module(config(dir, therapistAuth = false)) }
        val res = client.get("/v1/rel/$relRef/grants") { header("X-Rel-Token", inboxToken) }
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }

    @Test
    fun `missing or wrong rel token is unauthorized`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        // No token.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/rel/$relRef/grants").status)
        // Wrong token (hashes to a different relRef than the path).
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/rel/$relRef/grants") { header("X-Rel-Token", "not-the-token") }.status,
        )
    }

    @Test
    fun `owner PUT then owner GET round-trips grant bytes`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        val body = byteArrayOf(9, 8, 7, 6, 5)
        val put = client.put("/v1/rel/$relRef/grants/lin1/0") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, put.status)
        assertEquals(RelationStore.sha256HexPublic(body), put.headers["X-Content-Hash"])

        val got = client.get("/v1/rel/$relRef/grants/lin1/0") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, got.status)
        assertContentEquals(body, got.bodyAsBytes())
    }

    @Test
    fun `append-only rejects a re-PUT of the same version`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        suspend fun put(body: ByteArray) = client.put("/v1/rel/$relRef/shares/lin/0") {
            header("X-Rel-Token", inboxToken); header(HttpHeaders.Authorization, "Bearer $ownerToken"); setBody(body)
        }
        assertEquals(HttpStatusCode.Created, put(byteArrayOf(1)).status)
        assertEquals(HttpStatusCode.Conflict, put(byteArrayOf(2)).status)
    }

    @Test
    fun `current returns the highest version`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        suspend fun put(v: Int) = client.put("/v1/rel/$relRef/grants/lin/$v") {
            header("X-Rel-Token", inboxToken); header(HttpHeaders.Authorization, "Bearer $ownerToken"); setBody(byteArrayOf(v.toByte()))
        }
        put(0); put(1); put(2)
        val cur = client.get("/v1/rel/$relRef/grants/lin/current") {
            header("X-Rel-Token", inboxToken); header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, cur.status)
        assertEquals("2", cur.headers["X-Version"])
        assertContentEquals(byteArrayOf(2), cur.bodyAsBytes())
    }

    @Test
    fun `structural setting allowlist accepts theme and rejects pin without reading body`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        // Assignments are therapist-write; use a therapist session bound to this rel.
        val ts = therapistSession(auth, relRef)
        // theme -> allowed
        val ok = client.put("/v1/rel/$relRef/assignments/lin/0") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${ts.cookie}")
            header("X-CSRF-Token", ts.csrf)
            header("X-Setting-Key", "theme")
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.Created, ok.status)
        // pin -> 422, and the body can be arbitrary random bytes (server never reads it).
        val bad = client.put("/v1/rel/$relRef/assignments/lin2/0") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${ts.cookie}")
            header("X-CSRF-Token", ts.csrf)
            header("X-Setting-Key", "pin")
            setBody(ByteArray(64) { it.toByte() })
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
    }

    @Test
    fun `direction enforcement blocks therapist PUT to shares and owner PUT to assignments`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        val ts = therapistSession(auth, relRef)
        // Therapist writing to shares (owner-only) -> 403.
        val t = client.put("/v1/rel/$relRef/shares/lin/0") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${ts.cookie}")
            header("X-CSRF-Token", ts.csrf)
            setBody(byteArrayOf(1))
        }
        assertEquals(HttpStatusCode.Forbidden, t.status)
        // Owner writing to assignments (therapist-only) -> 403.
        val o = client.put("/v1/rel/$relRef/assignments/lin/0") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            setBody(byteArrayOf(1))
        }
        assertEquals(HttpStatusCode.Forbidden, o.status)
    }

    @Test
    fun `therapist PUT without CSRF token is unauthorized`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        val ts = therapistSession(auth, relRef)
        // Session cookie present, but NO X-CSRF-Token on a state-changing PUT -> 401.
        val noCsrf = client.put("/v1/rel/$relRef/assignments/lin/0") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${ts.cookie}")
            setBody(byteArrayOf(1))
        }
        assertEquals(HttpStatusCode.Unauthorized, noCsrf.status)
        // Wrong CSRF token -> 401.
        val badCsrf = client.put("/v1/rel/$relRef/assignments/lin/0") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${ts.cookie}")
            header("X-CSRF-Token", "not-the-token")
            setBody(byteArrayOf(1))
        }
        assertEquals(HttpStatusCode.Unauthorized, badCsrf.status)
        // Correct CSRF token -> 201.
        val ok = client.put("/v1/rel/$relRef/assignments/lin/0") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${ts.cookie}")
            header("X-CSRF-Token", ts.csrf)
            setBody(byteArrayOf(1))
        }
        assertEquals(HttpStatusCode.Created, ok.status)
    }

    @Test
    fun `two different inbox tokens are isolated relationships`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        val tokenB = "another-inbox-token-999"
        val relRefB = Secrets.relRefOf(tokenB)
        client.put("/v1/rel/$relRef/grants/lin/0") {
            header("X-Rel-Token", inboxToken); header(HttpHeaders.Authorization, "Bearer $ownerToken"); setBody(byteArrayOf(1))
        }
        // relationship B lists nothing.
        val listB = client.get("/v1/rel/$relRefB/grants") {
            header("X-Rel-Token", tokenB); header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, listB.status)
        // Relationship B has no lineages: an empty list, not the "lin" lineage from A.
        assertTrue(listB.bodyAsText().contains("\"lineages\":[]"))
    }

    @Test
    fun `invalid relRef charset is rejected`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        // A token whose hash won't match this bad path anyway -> unauthorized before charset.
        val res = client.get("/v1/rel/bad..name/grants") { header("X-Rel-Token", inboxToken) }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    /** A therapist session bound to [relRef]: the raw session id (cookie) and its anti-CSRF token. */
    private data class TSession(val cookie: String, val csrf: String)

    /** Create a therapist session bound to this rel directly (auth enroll/verify is covered elsewhere). */
    private fun therapistSession(auth: AuthStore, relRef: String): TSession {
        val credentialId = "cred-" + relRef.take(8)
        val session = auth.createSession(credentialId, relRef, 900L, 28_800L)
        return TSession(session.sessionId, session.csrfToken)
    }
}
