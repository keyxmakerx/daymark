package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.BODY_CHUNK_BYTES
import com.daymark.companion.auth.DeviceKeyStore
import com.daymark.companion.auth.DeviceSignature
import com.daymark.companion.auth.PairingCode
import com.daymark.companion.auth.Secrets
import com.daymark.companion.auth.SIGNED_BODY
import com.daymark.companion.auth.SignedBody
import com.daymark.companion.auth.bodyJoined
import com.daymark.companion.routes.JSON_BODY_MAX_BYTES
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
import java.lang.management.ManagementFactory
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
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
    fun `a forged signature on a relationship route is refused, and is not tried as the clinician's session it carries`() = testApplication {
        val server = DeviceServer(mode = SetupMode.PAIRED)
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val stranger = TestPhone() // a key nobody paired
        val inbox = "inbox-token-forged-signature-0123456789"
        val relRef = Secrets.relRefOf(inbox)
        // A clinician's session for this relationship, with its CSRF token: alone, enough to write an assignment.
        val session = AuthStore(server.dataDir.path).use { it.createSession("cred-forged", relRef, 900L, 28_800L) }
        val target = "/v1/rel/$relRef/assignments/lin/0"
        val body = byteArrayOf(1, 2, 3)
        suspend fun asTheClinician(signature: Map<String, String>) = client.put(target) {
            header("X-Rel-Token", inbox)
            header(HttpHeaders.Cookie, "daymark_session=${session.sessionId}")
            header("X-CSRF-Token", session.csrfToken)
            signedWith(signature)
            setBody(body)
        }
        suspend fun lineages() = client.get("/v1/rel/$relRef/assignments") {
            header("X-Rel-Token", inbox)
            header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
        }.bodyAsText()

        // The clinician's request, carrying a signature a stranger made over it, naming the phone's key.
        val forged = stranger.headers("PUT", target, body, server.seconds, signed = mapOf("X-Rel-Token" to inbox)) - "X-Rel-Token" +
            (DeviceSignature.KEY_HEADER to phone.keyId)
        assertEquals(unauthorized, asTheClinician(forged).answer())
        assertTrue("\"lineages\":[]" in lineages(), "its handler did not run: ${lineages()}")

        // Control: the clinician's session alone writes it, so the refusal above was the signature's.
        assertEquals(HttpStatusCode.Created, asTheClinician(emptyMap()).status)
        assertTrue("\"lineages\":[]" !in lineages(), "control: a write shows in the list")
    }

    @Test
    fun `a phone revoked after its key is checked and before its handler runs is refused, and nothing is stored`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val body = byteArrayOf(1, 2, 3)
        // Control: the phone's request is taken, so the refusal below is the revocation's.
        val first = "/v1/snapshots/devA/1"
        assertEquals(HttpStatusCode.Created, client.send(HttpMethod.Put, first, phone.headers("PUT", first, body, server.seconds), body).status)

        // The console's Revoke lands after the key was found live, once the body was in, and before the key
        // is read the last time: here, just after the reading taken as the request's nonce is.
        val target = "/v1/snapshots/devA/2"
        val landed = atNonce(server, before = false) {
            assertEquals(true, server.account.devices.revoke(server.account.devices.ownerId, phone.keyId), "the Revoke")
        }
        val res = client.send(HttpMethod.Put, target, phone.headers("PUT", target, body, server.seconds), body)
        server.aroundClock = null
        assertTrue(landed.get(), "the Revoke landed as the request's nonce was being taken")
        assertEquals(unauthorized, res.answer())
        val stored = client.get("/v1/snapshots/devA") { header(HttpHeaders.Authorization, "Bearer ${server.authToken}") }.bodyAsText()
        assertEquals(1, Regex("\"version\":").findAll(stored).count(), "nothing more was stored: $stored")
    }

    @Test
    fun `seen nonces are forgotten a minute after their time has passed, and forgetting them revokes nothing`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val old = (1..3).map { phone.headers("GET", "/v1/snapshots", timeSeconds = server.seconds) }
        old.forEach { assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/v1/snapshots", it).status) }
        assertEquals(3, nonceRows(server.dataDir), "control: the three are remembered")

        server.now += DeviceSignature.WINDOW_MS + DeviceKeyStore.NONCE_FORGET_MARGIN_MS + 1_000
        assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots").status, "the key still works")
        assertEquals(1, nonceRows(server.dataDir), "the three a minute past their time are gone; the new one is kept")
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

    /** Whether this thread is taking a nonce: inside the nonce store's rememberNonce. */
    private fun takingANonce(): Boolean = StackWalker.getInstance().walk { frames ->
        frames.anyMatch { it.className == DeviceKeyStore::class.java.name && it.methodName == "rememberNonce" }
    }

    /**
     * Arms [server]'s clock so that [act] runs once, on the thread that takes the first reading taken as
     * a nonce is being taken, just [before] that reading or just after it; no other reading is touched.
     * True, once asked, when [act] ran.
     */
    private fun atNonce(server: DeviceServer, before: Boolean, act: () -> Unit): AtomicBoolean {
        val ran = AtomicBoolean()
        server.aroundClock = { reading ->
            when {
                !takingANonce() || !ran.compareAndSet(false, true) -> reading()
                before -> { act(); reading() }
                else -> reading().also { act() }
            }
        }
        return ran
    }

    /**
     * [work] on a thread of its own, as a second request runs, waited for until it has finished or is
     * waiting for a lock this thread holds, as a second request must wait while this one holds it.
     */
    private fun alongside(work: () -> Unit) {
        val here = Thread.currentThread().threadId()
        val second = thread(name = "a second request") { work() }
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (second.isAlive) {
            val waiting = threads.getThreadInfo(second.threadId())
            if (waiting != null && waiting.threadState == Thread.State.BLOCKED && waiting.lockOwnerId == here) return
            check(System.nanoTime() < deadline) { "the second request neither finished nor waited for this one's lock" }
            Thread.sleep(1)
        }
    }

    @Test
    fun `a replay judged at the last millisecond of its window is refused, whatever another request forgets meanwhile`() {
        // Another request, a minute and a millisecond after the window closed, forgets every nonce whose
        // time passed more than a minute ago: the replay's first row among them. It lands just before the
        // replay reads the clock to take its nonce; just after that reading, from a thread of its own, as
        // a second request runs; and, the control, nowhere.
        val landings: Map<String, (DeviceServer, () -> Unit) -> AtomicBoolean?> = mapOf(
            "just before the replay reads the clock to take its nonce" to { server, forget -> atNonce(server, before = true, act = forget) },
            "just after that reading" to { server, forget -> atNonce(server, before = false) { alongside(forget) } },
            "nowhere" to { _, _ -> null },
        )
        for ((where, land) in landings) testApplication {
            val server = DeviceServer()
            server.start(this)
            val phone = TestPhone()
            server.pair(client, phone)
            val target = "/v1/snapshots/devA/1"
            val body = byteArrayOf(4, 5, 6)
            val sentAt = server.now
            val captured = phone.headers("PUT", target, body, server.seconds)
            assertEquals(HttpStatusCode.Created, client.send(HttpMethod.Put, target, captured, body).status, "$where: the phone's own request is taken")

            server.now = sentAt + DeviceSignature.WINDOW_MS
            val forgot = CompletableFuture<Boolean>()
            val landed = land(server) {
                server.now = sentAt + DeviceSignature.WINDOW_MS + DeviceKeyStore.NONCE_FORGET_MARGIN_MS + 1
                forgot.complete(server.account.devices.rememberNonce(phone.keyId, TestPhone.freshNonce(), keepUntil = server.now + DeviceSignature.WINDOW_MS))
            }
            val replay = client.send(HttpMethod.Put, target, captured, body).answer()
            server.aroundClock = null
            if (landed != null) assertTrue(landed.get(), "$where: a reading is taken as the replay's nonce is")
            assertEquals(unauthorized, replay, where)
            if (landed != null) {
                assertEquals(true, forgot.get(10, TimeUnit.SECONDS), "$where: the other request took its own nonce")
                assertEquals(emptyList(), keptUntil(server, captured.getValue(DeviceSignature.NONCE_HEADER)), "$where: it forgot the replay's first row")
            }
            val versions = server.signedGet(client, phone, "/v1/snapshots/devA").bodyAsText()
            assertEquals(1, Regex("\"version\":").findAll(versions).count(), "$where: nothing more was stored: $versions")
            if (landed == null) {
                // Control: at that same millisecond a request of the same time, never sent before, is taken,
                // so the replay was refused for its nonce and not for its time.
                val unsent = phone.headers("GET", "/v1/snapshots", timeSeconds = sentAt / 1000)
                assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/v1/snapshots", unsent).status, where)
            }
            // Control: a request the phone makes then is taken.
            assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots").status, where)
        }
    }

    @Test
    fun `a replay is refused though the server's clock is stepped back between its first use and the replay, since a nonce is kept a minute past its time`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val target = "/v1/snapshots/devA/1"
        val body = byteArrayOf(4, 5, 6)
        val sentAt = server.now
        val windowEnd = sentAt + DeviceSignature.WINDOW_MS
        val captured = phone.headers("PUT", target, body, server.seconds)
        val nonce = captured.getValue(DeviceSignature.NONCE_HEADER)
        assertEquals(HttpStatusCode.Created, client.send(HttpMethod.Put, target, captured, body).status, "the phone's own request is taken")

        // Control: with the clock inside the window and moved nowhere, the replay is refused and a fresh request is taken.
        assertEquals(unauthorized, client.send(HttpMethod.Put, target, captured, body).answer(), "control: the replay inside its window")
        assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots").status, "control: a fresh request")

        // Thirty seconds after the window closed, a request is taken, forgetting what lapsed more than a
        // minute before; then the clock is stepped back thirty seconds, to the window's last millisecond.
        server.now = windowEnd + 30_000
        assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots").status, "a request thirty seconds after the window")
        server.now -= 30_000
        assertEquals(unauthorized, client.send(HttpMethod.Put, target, captured, body).answer(), "the replay after the clock is stepped back")
        assertEquals(listOf(windowEnd), keptUntil(server, nonce), "its first row is still kept")
        val versions = server.signedGet(client, phone, "/v1/snapshots/devA").bodyAsText()
        assertEquals(1, Regex("\"version\":").findAll(versions).count(), "nothing more was stored: $versions")

        // The margin, to the millisecond: a request taken a minute after the window closed keeps the
        // nonce, and one taken a millisecond later forgets it.
        server.now = windowEnd + DeviceKeyStore.NONCE_FORGET_MARGIN_MS
        assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots").status)
        assertEquals(listOf(windowEnd), keptUntil(server, nonce), "kept a minute past its time")
        server.now = windowEnd + DeviceKeyStore.NONCE_FORGET_MARGIN_MS + 1
        assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots").status)
        assertEquals(emptyList(), keptUntil(server, nonce), "and no longer")
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
        assertEquals(listOf(SignedBody(line, kept = true, keptBytes = cap.toInt(), keptChunks = cap.toInt() / BODY_CHUNK_BYTES)), reads.distinct())
        assertContentEquals(body, server.signedGet(client, phone, target).bodyAsBytes())
    }

    @Test
    fun `a signed report of an invitation naming a key nobody paired keeps none of its body`() = testApplication {
        val server = DeviceServer(mode = SetupMode.PAIRED)
        server.start(this)
        val reads = java.util.Collections.synchronizedList(mutableListOf<SignedBody>())
        application {
            sendPipeline.intercept(ApplicationSendPipeline.Before) { call.attributes.getOrNull(SIGNED_BODY)?.let { reads += it } }
        }
        val phone = TestPhone()
        server.pair(client, phone)
        val stranger = TestPhone() // a key nobody paired
        val target = "/v1/invite/no-such-invite/report"
        // A report's JSON at the bound every route that takes no credential reads under.
        val body = ("{\"secret\":\"" + "a".repeat(JSON_BODY_MAX_BYTES.toInt() - 13) + "\"}").toByteArray()
        assertEquals(JSON_BODY_MAX_BYTES.toInt(), body.size)
        val line = DeviceSignature.bodyHash(body)

        val res = client.send(HttpMethod.Post, target, stranger.headers("POST", target, body, server.seconds), body)
        assertEquals(unauthorized, res.answer())
        assertEquals(listOf(SignedBody(line, kept = false, keptBytes = 0)), reads.distinct(), "read to its end and hashed, and none of it kept")

        // Control: the paired phone's report with the same body is kept, and reaches the owner's answer.
        reads.clear()
        val own = client.send(HttpMethod.Post, target, phone.headers("POST", target, body, server.seconds), body)
        assertEquals(HttpStatusCode.Gone to """{"error":"invite unavailable"}""", own.answer())
        assertEquals(listOf(SignedBody(line, kept = true, keptBytes = body.size, keptChunks = body.size / BODY_CHUNK_BYTES)), reads.distinct())
    }

    @Test
    fun `a live key's body is kept in the chunks it arrived in, and joined only when a handler reads it`() = testApplication {
        val cap = 256L * 1024
        val server = DeviceServer(maxRequestBytes = cap, maxBlobBytes = cap)
        server.start(this)
        // What each signed call held as it was answered: the reader's report, and whether its body was one array by then.
        val held = java.util.Collections.synchronizedList(mutableListOf<Pair<SignedBody, Boolean>>())
        application {
            sendPipeline.intercept(ApplicationSendPipeline.Before) { call.attributes.getOrNull(SIGNED_BODY)?.let { held += it to call.bodyJoined } }
        }
        val phone = TestPhone()
        server.pair(client, phone)
        val stranger = TestPhone() // a key nobody paired
        val target = "/v1/snapshots/devA/1"
        val body = ByteArray(cap.toInt() - 5) { (it * 17 + 3).toByte() }
        assertTrue(body.size % BODY_CHUNK_BYTES != 0, "the last chunk is part-filled")
        val kept = SignedBody(DeviceSignature.bodyHash(body), kept = true, keptBytes = body.size, keptChunks = (body.size + BODY_CHUNK_BYTES - 1) / BODY_CHUNK_BYTES)

        // Refused once its body is in: signed by a stranger, naming the live key. The body was kept, in
        // chunks, and no handler asked for it, so nothing joined it after its last byte.
        held.clear()
        val forged = stranger.headers("PUT", target, body, server.seconds) + (DeviceSignature.KEY_HEADER to phone.keyId)
        assertEquals(unauthorized, client.send(HttpMethod.Put, target, forged, body).answer())
        assertEquals(listOf(kept to false), held.distinct(), "kept in the chunks it arrived in, never joined")

        // Control: the phone's own request is taken. Its handler read the body, which was joined then.
        held.clear()
        val res = client.send(HttpMethod.Put, target, phone.headers("PUT", target, body, server.seconds), body)
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        assertEquals(listOf(kept to true), held.distinct(), "joined when its handler read it")
        assertContentEquals(body, server.signedGet(client, phone, target).bodyAsBytes(), "the chunks joined are the body as sent")
    }
}
