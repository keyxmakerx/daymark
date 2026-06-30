package com.daymark.companion

import com.daymark.companion.storage.BlobStore
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

class SyncRoutesTest {

    private val token = "test-token-123"

    private fun config(
        dataDir: String,
        authToken: String? = token,
        maxBlobBytes: Long = 26_214_400L,
        maxVersions: Int = 200,
        quota: Long = 5_368_709_120L,
        lockoutFails: Int = 8,
        rps: Int = 100,
    ) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dataDir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = authToken,
        maxBlobBytes = maxBlobBytes, maxRequestBytes = maxBlobBytes + 1024,
        maxVersions = maxVersions, perTokenQuotaBytes = quota,
        authLockoutFails = lockoutFails, authLockoutSeconds = 900L, rateLimitRps = rps,
    )

    private fun tmpDir() = Files.createTempDirectory("blobstore-test").toString()

    @Test
    fun `sync API is fail-closed when no token is configured`() = testApplication {
        application { module(config(tmpDir(), authToken = null)) }
        val res = client.get("/v1/snapshots")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
    }

    @Test
    fun `requests without a valid token are rejected`() = testApplication {
        val dir = tmpDir()
        val store = BlobStore(dir, 26_214_400L, 200, 5_368_709_120L)
        application { module(config(dir), store) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/snapshots").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/v1/snapshots") { header(HttpHeaders.Authorization, "Bearer wrong") }.status,
        )
    }

    @Test
    fun `put then fetch round-trips the exact bytes`() = testApplication {
        val dir = tmpDir()
        val store = BlobStore(dir, 26_214_400L, 200, 5_368_709_120L)
        application { module(config(dir), store) }
        val blob = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)

        val put = client.put("/v1/snapshots/devA/0") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(blob)
        }
        assertEquals(HttpStatusCode.Created, put.status)
        assertTrue(put.headers["X-Content-Hash"] == BlobStore.sha256Hex(blob))

        val got = client.get("/v1/snapshots/devA/0") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, got.status)
        assertContentEquals(blob, got.bodyAsBytes())
    }

    @Test
    fun `versions are append-only`() = testApplication {
        val dir = tmpDir()
        val store = BlobStore(dir, 26_214_400L, 200, 5_368_709_120L)
        application { module(config(dir), store) }
        suspend fun put(v: Int, body: ByteArray) = client.put("/v1/snapshots/devA/$v") {
            header(HttpHeaders.Authorization, "Bearer $token"); setBody(body)
        }
        assertEquals(HttpStatusCode.Created, put(0, byteArrayOf(1)).status)
        assertEquals(HttpStatusCode.Conflict, put(0, byteArrayOf(2)).status) // overwrite rejected
    }

    @Test
    fun `lists lineages and versions`() = testApplication {
        val dir = tmpDir()
        val store = BlobStore(dir, 26_214_400L, 200, 5_368_709_120L)
        application { module(config(dir), store) }
        suspend fun put(lin: String, v: Int) = client.put("/v1/snapshots/$lin/$v") {
            header(HttpHeaders.Authorization, "Bearer $token"); setBody(byteArrayOf(v.toByte()))
        }
        put("devA", 0); put("devA", 1); put("devB", 0)
        val lineages = client.get("/v1/snapshots") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText()
        assertTrue(lineages.contains("devA") && lineages.contains("devB"))
        val versions = client.get("/v1/snapshots/devA") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText()
        assertTrue(versions.contains("\"version\":0") && versions.contains("\"version\":1"))
    }

    @Test
    fun `invalid lineage name is rejected`() = testApplication {
        val dir = tmpDir()
        val store = BlobStore(dir, 26_214_400L, 200, 5_368_709_120L)
        application { module(config(dir), store) }
        val res = client.put("/v1/snapshots/bad..name/0") {
            header(HttpHeaders.Authorization, "Bearer $token"); setBody(byteArrayOf(1))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `blob over the size cap is rejected`() = testApplication {
        val dir = tmpDir()
        val store = BlobStore(dir, 8L, 200, 5_368_709_120L)
        application { module(config(dir, maxBlobBytes = 8L), store) }
        val res = client.put("/v1/snapshots/devA/0") {
            header(HttpHeaders.Authorization, "Bearer $token"); setBody(ByteArray(64))
        }
        assertTrue(res.status == HttpStatusCode.PayloadTooLarge)
    }

    @Test
    fun `keyparams round-trip`() = testApplication {
        val dir = tmpDir()
        val store = BlobStore(dir, 26_214_400L, 200, 5_368_709_120L)
        application { module(config(dir), store) }
        val kp = """{"alg":"xchacha20poly1305","kdf":"argon2id","saltB64":"AAAA"}""".toByteArray()
        val put = client.put("/v1/keyparams") {
            header(HttpHeaders.Authorization, "Bearer $token"); setBody(kp)
        }
        assertEquals(HttpStatusCode.NoContent, put.status)
        val got = client.get("/v1/keyparams") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, got.status)
        assertContentEquals(kp, got.bodyAsBytes())
    }

    @Test
    fun `repeated bad tokens trigger lockout`() = testApplication {
        val dir = tmpDir()
        val store = BlobStore(dir, 26_214_400L, 200, 5_368_709_120L)
        application { module(config(dir, lockoutFails = 3, rps = 100), store) }
        // 3 bad attempts -> 401, then locked -> 429
        repeat(3) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/v1/snapshots") { header(HttpHeaders.Authorization, "Bearer nope") }.status,
            )
        }
        val locked = client.get("/v1/snapshots") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.TooManyRequests, locked.status) // locked out even with the right token
    }
}
