package com.daymark.companion

import com.daymark.companion.auth.DeviceSignature
import com.daymark.companion.auth.PairingCode
import com.daymark.companion.auth.SIGNED_BODY
import com.daymark.companion.auth.SignedBody
import com.daymark.companion.routes.PAIRING_CODE_CREDENTIAL
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A phone's signed request (#186): it carries no token; a captured one cannot be replayed, altered,
 * or turned into a request for a path it did not name; its time must be within five minutes of the
 * server's; the nonces it used are remembered across a restart and forgotten once they could no
 * longer be used anyway; and a failed signature spends the same lockout budget a bad token does.
 */
class SignedRequestTest {

    private val unauthorized = HttpStatusCode.Unauthorized to """{"error":"unauthorized"}"""

    private suspend fun HttpResponse.answer() = status to bodyAsText()

    /** Send [headers] as signed, to [target] with [method] and [body], whatever they were signed over. */
    private suspend fun HttpClient.send(method: HttpMethod, target: String, headers: Map<String, String>, body: ByteArray? = null): HttpResponse =
        request(target) {
            this.method = method
            signedWith(headers)
            if (body != null) setBody(body)
        }

    private fun nonceRows(dataDir: File): Int =
        DriverManager.getConnection("jdbc:sqlite:${File(dataDir, "owner-account.db").path}").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM seen_nonces").use { rs -> rs.next(); rs.getInt(1) } }
        }

    /** When each row kept for [nonce] lapses: none for a nonce no request took. */
    private fun keptUntil(server: DeviceServer, nonce: String): List<Long> =
        DriverManager.getConnection("jdbc:sqlite:${File(server.dataDir, "owner-account.db").path}").use { c ->
            c.prepareStatement("SELECT keep_until FROM seen_nonces WHERE nonce=?").use { ps ->
                ps.setString(1, nonce)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }
        }

    @Test
    fun `a registered phone's signed request reaches the owner's routes with no token`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)

        val blob = byteArrayOf(1, 2, 3, 4, 5)
        val put = client.send(HttpMethod.Put, "/v1/snapshots/devA/1", phone.headers("PUT", "/v1/snapshots/devA/1", blob, server.seconds), blob)
        assertEquals(HttpStatusCode.Created, put.status, put.bodyAsText())
        val got = server.signedGet(client, phone, "/v1/snapshots/devA/1")
        assertEquals(HttpStatusCode.OK, got.status)
        assertContentEquals(blob, got.bodyAsBytes(), "the handler stored the very bytes the signature covered")
    }

    @Test
    fun `a replayed request is refused`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val captured = phone.headers("GET", "/v1/snapshots", timeSeconds = server.seconds)
        assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/v1/snapshots", captured).status, "control: the request itself is good")
        assertEquals(unauthorized, client.send(HttpMethod.Get, "/v1/snapshots", captured).answer(), "the same request again")
    }

    @Test
    fun `an altered body is refused`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val signedBody = byteArrayOf(10, 20, 30)
        val altered = signedBody.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertTrue(!signedBody.contentEquals(altered), "the alteration changes the body")

        val headers = phone.headers("PUT", "/v1/snapshots/devA/1", signedBody, server.seconds)
        assertEquals(unauthorized, client.send(HttpMethod.Put, "/v1/snapshots/devA/1", headers, altered).answer())
        val list = server.signedGet(client, phone, "/v1/snapshots/devA")
        assertTrue("\"versions\":[]" in list.bodyAsText(), "nothing was stored: ${list.bodyAsText()}")
        // Control: the altered body, signed as itself, is taken.
        val fresh = phone.headers("PUT", "/v1/snapshots/devA/1", altered, server.seconds)
        assertEquals(HttpStatusCode.Created, client.send(HttpMethod.Put, "/v1/snapshots/devA/1", fresh, altered).status)
    }

    @Test
    fun `an altered path, query or method is refused`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        for (v in 1..2) {
            val blob = byteArrayOf(v.toByte())
            assertEquals(HttpStatusCode.Created, client.send(HttpMethod.Put, "/v1/snapshots/devA/$v", phone.headers("PUT", "/v1/snapshots/devA/$v", blob, server.seconds), blob).status)
        }
        val signedPath = "/v1/snapshots/devA/1"
        val alteredPath = signedPath.replace("/1", "/2")
        assertNotEquals(signedPath, alteredPath)
        val headers = phone.headers("GET", signedPath, timeSeconds = server.seconds)
        assertEquals(unauthorized, client.send(HttpMethod.Get, alteredPath, headers).answer(), "the path")

        // The method: a read of the key parameters, sent as a write of them.
        val readHeaders = phone.headers("GET", "/v1/keyparams", timeSeconds = server.seconds)
        assertEquals(unauthorized, client.send(HttpMethod.Put, "/v1/keyparams", readHeaders, ByteArray(0)).answer(), "the method")

        val signedQuery = "/v1/owner/audit?limit=1"
        val alteredQuery = "/v1/owner/audit?limit=2"
        val queryHeaders = phone.headers("GET", signedQuery, timeSeconds = server.seconds)
        assertEquals(unauthorized, client.send(HttpMethod.Get, alteredQuery, queryHeaders).answer(), "the query")
        // Controls: each, signed afresh and sent as signed, is taken.
        assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, signedPath, phone.headers("GET", signedPath, timeSeconds = server.seconds)).status)
        assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, signedQuery, phone.headers("GET", signedQuery, timeSeconds = server.seconds)).status)
        // A request its signature refused took no nonce: the headers tried against the altered query are
        // good once for the query they name, and then spent.
        assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, signedQuery, queryHeaders).status, "not spent by the refusal")
        assertEquals(unauthorized, client.send(HttpMethod.Get, signedQuery, queryHeaders).answer(), "spent by their one use")
    }

    @Test
    fun `a time more than five minutes from the server's is refused, either way`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val window = DeviceSignature.WINDOW_SECONDS
        for (offset in listOf(-(window + 1), window + 1)) {
            val headers = phone.headers("GET", "/v1/snapshots", timeSeconds = server.seconds + offset)
            assertEquals(unauthorized, client.send(HttpMethod.Get, "/v1/snapshots", headers).answer(), "$offset s")
        }
        // Controls: just inside the window, either way, is taken.
        for (offset in listOf(-(window - 1), window - 1)) {
            val headers = phone.headers("GET", "/v1/snapshots", timeSeconds = server.seconds + offset)
            assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/v1/snapshots", headers).status, "$offset s")
        }
    }

    @Test
    fun `a nonce seen before a restart is still refused after it`() {
        val dataDir = kotlin.io.path.createTempDirectory("device-restart").toFile()
        val phone = TestPhone()
        val before = DeviceServer(dataDir = dataDir)
        lateinit var captured: Map<String, String>
        testApplication {
            before.start(this)
            before.pair(client, phone)
            captured = phone.headers("GET", "/v1/snapshots", timeSeconds = before.seconds)
            assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/v1/snapshots", captured).status, "control: taken before the restart")
        }
        before.account.close()

        val after = DeviceServer(dataDir = dataDir, now = before.now + 1_000)
        testApplication {
            after.start(this)
            assertEquals(unauthorized, client.send(HttpMethod.Get, "/v1/snapshots", captured).answer(), "replayed after the restart")
            // Control: the key survived the restart, so the refusal above is the nonce's.
            assertEquals(HttpStatusCode.OK, after.signedGet(client, phone, "/v1/snapshots").status)
        }
    }

    @Test
    fun `someone holding a full capture cannot make a request for a path they did not see`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)

        // Everything the phone sent, as a person on the path would have recorded it.
        data class Captured(val method: HttpMethod, val target: String, val body: ByteArray?, val headers: Map<String, String>)
        val blob = byteArrayOf(7, 7, 7)
        val capture = listOf(
            Captured(HttpMethod.Get, "/v1/snapshots", null, phone.headers("GET", "/v1/snapshots", timeSeconds = server.seconds)),
            Captured(HttpMethod.Get, "/v1/snapshots/devA", null, phone.headers("GET", "/v1/snapshots/devA", timeSeconds = server.seconds)),
            Captured(HttpMethod.Put, "/v1/snapshots/devA/1", blob, phone.headers("PUT", "/v1/snapshots/devA/1", blob, server.seconds)),
            Captured(HttpMethod.Get, "/v1/keydoc", null, phone.headers("GET", "/v1/keydoc", timeSeconds = server.seconds)),
        )
        for (c in capture) {
            val status = client.send(c.method, c.target, c.headers, c.body).status
            assertTrue(status != HttpStatusCode.Unauthorized, "control: ${c.method.value} ${c.target} was taken ($status)")
        }
        // And requests the person on the path held back, so their nonces were never used.
        // The first use of each of these meets a fresh nonce, so only the signature can refuse it.
        val withheld = listOf(
            Captured(HttpMethod.Get, "/v1/snapshots/devA", null, phone.headers("GET", "/v1/snapshots/devA", timeSeconds = server.seconds)),
            Captured(HttpMethod.Put, "/v1/snapshots/devA/5", blob, phone.headers("PUT", "/v1/snapshots/devA/5", blob, server.seconds)),
        )
        val untouched = Captured(HttpMethod.Put, "/v1/snapshots/devA/6", blob, phone.headers("PUT", "/v1/snapshots/devA/6", blob, server.seconds))
        val everything = capture + withheld

        // Paths the capture never named, each tried with every captured set of headers and body.
        val unseen = listOf(
            HttpMethod.Get to "/v1/snapshots/devA/1",
            HttpMethod.Put to "/v1/snapshots/devA/2",
            HttpMethod.Get to "/v1/devices/registration",
            HttpMethod.Get to "/v1/owner/audit",
        )
        for ((method, target) in unseen) {
            assertTrue(everything.none { it.method == method && it.target == target }, "$target is unseen")
            for (c in everything) {
                assertEquals(unauthorized, client.send(method, target, c.headers, c.body).answer(), "${method.value} $target with the headers of ${c.method.value} ${c.target}")
            }
        }
        val versions = server.signedGet(client, phone, "/v1/snapshots/devA").bodyAsText()
        assertTrue("\"version\":2" !in versions, "no version 2 was written: $versions")
        // Control: a held-back request nobody turned elsewhere is still good for exactly what it names.
        assertEquals(HttpStatusCode.Created, client.send(untouched.method, untouched.target, untouched.headers, untouched.body).status)
        // Control: the phone itself can make each of them.
        assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots/devA/1").status)
    }

    @Test
    fun `a failed signature spends the same lockout budget a bad token does, and arming it writes one row`() = testApplication {
        val server = DeviceServer(lockoutFails = 3)
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        // Control: before any failure, the phone's signature is taken, so the 429 below is the lockout's.
        assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots").status)

        // Three failures of three kinds, from one address: a bad token, a bad signature, a wrong code.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/snapshots") { header(HttpHeaders.Authorization, "Bearer not-the-token") }.status)
        val wrongPath = phone.headers("GET", "/v1/snapshots/devA", timeSeconds = server.seconds)
        assertEquals(unauthorized, client.send(HttpMethod.Get, "/v1/snapshots", wrongPath).answer())
        val wrongCode = client.post("/v1/devices/redeem") {
            contentType(ContentType.Application.Json)
            setBody(phone.redeemBody(PairingCode.generate()))
        }
        assertEquals(HttpStatusCode.NotFound, wrongCode.status)

        // Locked: a good signature and the good token alike, from that address.
        val locked = HttpStatusCode.TooManyRequests to """{"error":"temporarily locked"}"""
        assertEquals(locked, server.signedGet(client, phone, "/v1/snapshots").answer())
        assertEquals(locked, client.get("/v1/snapshots") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN") }.answer())
        repeat(3) { client.send(HttpMethod.Get, "/v1/snapshots", wrongPath) }

        assertEquals(listOf("device.registered", "lockout"), server.ownerLog(), "one row when the lockout was armed, none per probe")
        val row = server.ownerAudit.list(server.account.devices.ownerId, limit = 1).single()
        assertEquals(mapOf("credential" to PAIRING_CODE_CREDENTIAL), row.meta, "it names what armed it, and no value")
    }

    @Test
    fun `a request carrying a signature is judged by the signature alone`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val badSignature = phone.headers("GET", "/v1/keydoc", timeSeconds = server.seconds)
        val withToken = client.get("/v1/snapshots") {
            header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN")
            signedWith(badSignature)
        }
        assertEquals(unauthorized, withToken.answer(), "a good token does not rescue a bad signature")
        // Control: the token alone is taken.
        assertEquals(HttpStatusCode.OK, client.get("/v1/snapshots") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN") }.status)
    }

    @Test
    fun `seen nonces are forgotten once their time has passed, and forgetting them revokes nothing`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val old = (1..3).map { phone.headers("GET", "/v1/snapshots", timeSeconds = server.seconds) }
        old.forEach { assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/v1/snapshots", it).status) }
        assertEquals(3, nonceRows(server.dataDir), "control: the three are remembered")

        server.now += DeviceSignature.WINDOW_MS + 1_000
        assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots").status, "the key still works")
        assertEquals(1, nonceRows(server.dataDir), "the three past their time are gone; the new one is kept")
        old.forEach { assertEquals(unauthorized, client.send(HttpMethod.Get, "/v1/snapshots", it).answer(), "and a replay of one is refused by its time") }
    }

    @Test
    fun `a time far outside the window, or a forged signature, leaves no nonce behind`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val stranger = TestPhone() // a key nobody paired
        val tenYears = 10L * 365 * 24 * 60 * 60
        val refused = mapOf(
            "signed by the phone, ten years ahead" to phone.headers("GET", "/v1/snapshots", timeSeconds = server.seconds + tenYears),
            "signed by the phone, ten years behind" to phone.headers("GET", "/v1/snapshots", timeSeconds = server.seconds - tenYears),
            "forged, naming the phone's key" to stranger.headers("GET", "/v1/snapshots", timeSeconds = server.seconds) + (DeviceSignature.KEY_HEADER to phone.keyId),
        )
        for ((what, headers) in refused) {
            assertEquals(unauthorized, client.send(HttpMethod.Get, "/v1/snapshots", headers).answer(), what)
            assertEquals(emptyList(), keptUntil(server, headers.getValue(DeviceSignature.NONCE_HEADER)), "$what: no nonce row")
        }
        // Control: the phone's own request, timed now, is taken, and its nonce kept to the end of its window.
        val good = phone.headers("GET", "/v1/snapshots", timeSeconds = server.seconds)
        assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/v1/snapshots", good).status)
        assertEquals(listOf(server.seconds * 1000 + DeviceSignature.WINDOW_MS), keptUntil(server, good.getValue(DeviceSignature.NONCE_HEADER)))
    }

    @Test
    fun `a replay at the very end of its window is refused, and a request timed ahead of the server is kept to the end of its own`() {
        testApplication {
            val server = DeviceServer()
            server.start(this)
            val phone = TestPhone()
            server.pair(client, phone)
            val sentAt = server.now
            val sent = phone.headers("GET", "/v1/snapshots", timeSeconds = server.seconds)
            assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/v1/snapshots", sent).status)
            server.now = sentAt + DeviceSignature.WINDOW_MS
            assertEquals(unauthorized, client.send(HttpMethod.Get, "/v1/snapshots", sent).answer(), "at +300 000 ms its nonce is still kept")
            server.now = sentAt + DeviceSignature.WINDOW_MS + 1
            assertEquals(unauthorized, client.send(HttpMethod.Get, "/v1/snapshots", sent).answer(), "at +300 001 ms its time is past")
            // Control: a request made then is taken.
            assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots").status)
        }
        testApplication {
            // A phone whose clock runs 299 s ahead of the server's.
            val server = DeviceServer()
            server.start(this)
            val phone = TestPhone()
            server.pair(client, phone)
            val ahead = server.seconds + 299
            val sent = phone.headers("GET", "/v1/snapshots", timeSeconds = ahead)
            assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/v1/snapshots", sent).status, "within the window")
            // Its nonce is kept to the end of the request's own window, not the end of one begun when it arrived.
            server.now = ahead * 1000 + DeviceSignature.WINDOW_MS
            assertEquals(unauthorized, client.send(HttpMethod.Get, "/v1/snapshots", sent).answer(), "at the end of its own window")
            assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots").status, "control: a request made then is taken")
        }
    }

    @Test
    fun `a body sent for a key that is not live is read to its end and hashed, and none of it is kept`() = testApplication {
        val cap = 256L * 1024
        val server = DeviceServer(maxRequestBytes = cap, maxBlobBytes = cap)
        server.start(this)
        // What the check's reader left on each call, taken as the call is answered.
        val reads = java.util.Collections.synchronizedList(mutableListOf<SignedBody>())
        application {
            sendPipeline.intercept(ApplicationSendPipeline.Before) { call.attributes.getOrNull(SIGNED_BODY)?.let { reads += it } }
        }
        val phone = TestPhone()
        server.pair(client, phone)
        val revoked = TestPhone()
        server.pair(client, revoked)
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/devices/${revoked.keyId}/revoke") { header(HttpHeaders.Authorization, "Bearer ${server.authToken}") }.status)
        val stranger = TestPhone() // a key nobody paired
        val target = "/v1/snapshots/devA/1"
        val body = ByteArray(cap.toInt()) { (it * 31 + 7).toByte() } // a whole body, at the cap
        val line = DeviceSignature.bodyHash(body)

        for ((what, signer) in mapOf("a key nobody paired" to stranger, "a revoked key" to revoked)) {
            reads.clear()
            val res = client.send(HttpMethod.Put, target, signer.headers("PUT", target, body, server.seconds), body)
            assertEquals(unauthorized, res.answer(), what)
            assertEquals(listOf(SignedBody(line, kept = false, keptBytes = 0)), reads.distinct(), "$what: read to its end and hashed, and none of it kept")
        }

        // Control: the same body from the live key is kept, taken, and stored as it was sent.
        reads.clear()
        val res = client.send(HttpMethod.Put, target, phone.headers("PUT", target, body, server.seconds), body)
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        assertEquals(listOf(SignedBody(line, kept = true, keptBytes = cap.toInt())), reads.distinct())
        assertContentEquals(body, server.signedGet(client, phone, target).bodyAsBytes())
    }
}
