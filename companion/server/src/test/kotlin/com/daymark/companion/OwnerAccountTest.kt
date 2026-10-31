package com.daymark.companion

import com.daymark.companion.auth.Totp
import com.daymark.companion.routes.ACCOUNT_ONLY_MESSAGE
import com.daymark.companion.routes.CLI_REFUSED_MESSAGE
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
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
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The owner's own account replaces the shared token (#324).
 *
 * Every test makes the account the way an upgraded install does: by presenting the old token once.
 * Then it checks what the issue asks: the token is refused on every owner route, existing data and a
 * paired phone carry on under the same owner id, the account's session and the CLI's credential each
 * reach what they should and nothing more, and no administrator or clinician session stands in.
 */
class OwnerAccountTest {

    private val seed = ByteArray(20) { (it * 11 + 5).toByte() }
    private val seedB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(seed)
    private fun code(server: DeviceServer, offsetSteps: Long = 0) = Totp.code(seed, server.seconds + offsetSteps * 30)

    /** Every owner route a credential can reach, by method and a concrete path, with a body where one is read. */
    private val ownerRoutes: List<Triple<String, String, String?>> = listOf(
        Triple("GET", "/v1/snapshots", null),
        Triple("GET", "/v1/snapshots/main", null),
        Triple("GET", "/v1/snapshots/main/1", null),
        Triple("PUT", "/v1/snapshots/main/2", "ciphertext"),
        Triple("GET", "/v1/keyparams", null),
        Triple("GET", "/v1/keydoc", null),
        Triple("GET", "/v1/devices", null),
        Triple("POST", "/v1/devices/pairing", null),
        Triple("GET", "/v1/owner/audit", null),
        Triple("GET", "/v1/owner/notifications", null),
        Triple("PUT", "/v1/owner/notifications", """{"email":null,"events":[]}"""),
    )

    private suspend fun HttpClient.call(method: String, path: String, body: String?, auth: HttpRequestBuilder.() -> Unit): HttpResponse {
        val block: HttpRequestBuilder.() -> Unit = {
            auth()
            if (body != null) {
                contentType(if (body.startsWith("{")) ContentType.Application.Json else ContentType.Application.OctetStream)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> get(path, block)
            "PUT" -> put(path, block)
            else -> post(path, block)
        }
    }

    private fun bearer(token: String): HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer $token") }

    /** A signed-in console: its cookie, and its anti-CSRF token on every request. */
    private data class Session(val cookie: String, val csrf: String) {
        val auth: HttpRequestBuilder.() -> Unit get() = {
            header(HttpHeaders.Cookie, cookie)
            header("X-CSRF-Token", csrf)
        }
    }

    private suspend fun HttpResponse.session(): Session {
        val setCookie = headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith("daymark_owner=") }
        return Session(setCookie.substringBefore(';'), json().string("csrfToken"))
    }

    private suspend fun createAccount(client: HttpClient, server: DeviceServer, token: String = DEVICE_TEST_TOKEN, totp: String = code(server)): HttpResponse =
        client.post("/v1/owner/account") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Sam","totpSecret":"$seedB64","totpCode":"$totp"}""")
        }

    @Test
    fun `before the account the token reaches every owner route, and after it none, with the same 401 as a wrong token`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            // Positive control: today's token works on every route in the list.
            for ((m, p, b) in ownerRoutes) {
                val res = client.call(m, p, b, bearer(DEVICE_TEST_TOKEN))
                assertNotEquals(HttpStatusCode.Unauthorized, res.status, "$m $p before: ${res.bodyAsText()}")
            }
            assertEquals(HttpStatusCode.OK, createAccount(client, server).status)
            val wrong = client.call("GET", "/v1/snapshots", null, bearer("not-the-token"))
            for ((m, p, b) in ownerRoutes) {
                val res = client.call(m, p, b, bearer(DEVICE_TEST_TOKEN))
                assertEquals(HttpStatusCode.Unauthorized, res.status, "$m $p after")
                assertEquals(wrong.bodyAsText(), res.bodyAsText(), "$m $p: a retired token reads as a wrong one")
            }
            val status = client.get("/v1/owner/account").json()
            assertEquals("true", status.string("exists"))
            assertEquals("false", status.string("tokenAccepted"))
        }
    }

    @Test
    fun `the account takes the owner's one id, so stored data and a paired phone carry on, and the token is not one`() {
        val server = DeviceServer()
        val phone = TestPhone()
        testApplication {
            server.start(this)
            server.pair(client, phone)
            val put = client.put("/v1/snapshots/main/1") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN"); setBody("before") }
            assertEquals(HttpStatusCode.Created, put.status, put.bodyAsText())
            val ownerId = server.account.devices.ownerId

            val made = createAccount(client, server)
            assertEquals(HttpStatusCode.OK, made.status, made.bodyAsText())
            assertEquals(ownerId, server.account.signIn.ownerId)

            // The phone paired before makes the same signed request after, and succeeds.
            assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots/main/1").status)
            // The same request with the token gets the 401.
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/snapshots/main/1") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN") }.status)
            // The account's session reads what the token stored, under the same owner.
            val session = made.session()
            val read = client.get("/v1/snapshots/main/1", session.auth)
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals("before", read.bodyAsText())
            assertEquals(listOf("device.registered", "owner.account_created"), server.ownerLog())
        }
    }

    @Test
    fun `the account is made once, and a second attempt is refused whatever it carries`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            val first = createAccount(client, server)
            assertEquals(HttpStatusCode.OK, first.status)
            // The token is retired, so it no longer authenticates the attempt at all.
            assertEquals(HttpStatusCode.Unauthorized, createAccount(client, server, totp = code(server, 1)).status)
            // The account's own session is told the account exists.
            val again = client.post("/v1/owner/account") {
                first.session().auth(this)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Other","totpSecret":"$seedB64","totpCode":"${code(server, 1)}"}""")
            }
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertEquals(1, server.ownerLog().count { it == "owner.account_created" })
        }
    }

    @Test
    fun `six digits that do not match make nothing and leave the token working`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            val plausible = (-1L..1L).map { code(server, it) }.toSet()
            val wrong = generateSequence(0) { it + 1 }.map { it.toString().padStart(6, '0') }.first { it !in plausible }
            assertNotEquals(code(server), wrong)
            assertEquals(HttpStatusCode.UnprocessableEntity, createAccount(client, server, totp = wrong).status)
            assertFalse(server.account.signIn.exists())
            assertEquals(HttpStatusCode.OK, client.get("/v1/snapshots") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN") }.status)
        }
    }

    @Test
    fun `a session needs its anti-CSRF token to change anything, and a wrong one is the same 401`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            val session = createAccount(client, server).session()
            // Reading needs only the cookie.
            assertEquals(HttpStatusCode.OK, client.get("/v1/snapshots") { header(HttpHeaders.Cookie, session.cookie) }.status)
            // Writing without the header, or with a wrong one, is refused; with it, accepted.
            val bare = client.put("/v1/snapshots/main/1") { header(HttpHeaders.Cookie, session.cookie); setBody("x") }
            assertEquals(HttpStatusCode.Unauthorized, bare.status)
            val wrongCsrf = session.csrf.let { if (it.last() == 'A') it.dropLast(1) + "B" else it.dropLast(1) + "A" }
            assertNotEquals(session.csrf, wrongCsrf)
            val forged = client.put("/v1/snapshots/main/1") { header(HttpHeaders.Cookie, session.cookie); header("X-CSRF-Token", wrongCsrf); setBody("x") }
            assertEquals(HttpStatusCode.Unauthorized, forged.status)
            assertEquals(HttpStatusCode.Created, client.put("/v1/snapshots/main/1") { session.auth(this); setBody("x") }.status)
        }
    }

    @Test
    fun `signing in gives a session, a wrong code does not, and a lockout writes one row on arming`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            createAccount(client, server)
            server.now += 30_000
            val good = client.post("/v1/owner/session") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Sam","code":"${code(server)}"}""")
            }
            assertEquals(HttpStatusCode.OK, good.status, good.bodyAsText())
            assertEquals(HttpStatusCode.OK, client.get("/v1/owner/session") { header(HttpHeaders.Cookie, good.session().cookie) }.status)

            val unknownName = client.post("/v1/owner/session") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Nobody","code":"000000"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, unknownName.status)
            // The same code again is a replay, refused like a wrong one, with the same words.
            repeat(6) {
                val replay = client.post("/v1/owner/session") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Sam","code":"${code(server)}"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, replay.status)
                assertEquals(unknownName.bodyAsText(), replay.bodyAsText())
            }
            assertEquals(1, server.ownerLog().count { it == "lockout" }, server.ownerLog().toString())
        }
    }

    @Test
    fun `a CLI credential writes snapshots and nothing else, cannot mint another, and stops at revoke`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            val session = createAccount(client, server).session()
            val minted = client.post("/v1/owner/cli") {
                session.auth(this)
                contentType(ContentType.Application.Json)
                setBody("""{"label":"laptop push"}""")
            }
            assertEquals(HttpStatusCode.Created, minted.status, minted.bodyAsText())
            val cli = minted.json().string("token")
            val credentialId = minted.json().string("credentialId")
            assertTrue(cli.startsWith("dmcli_"))

            assertEquals(HttpStatusCode.Created, client.put("/v1/snapshots/main/1") { header(HttpHeaders.Authorization, "Bearer $cli"); setBody("x") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/snapshots/main/1", bearer(cli)).status)
            for ((m, p, b) in listOf(
                Triple("GET", "/v1/devices", null),
                Triple("POST", "/v1/devices/pairing", null),
                Triple("PUT", "/v1/owner/notifications", """{"email":null,"events":[]}"""),
                Triple("POST", "/v1/keydoc", "{}"),
            )) {
                val res = client.call(m, p, b, bearer(cli))
                assertEquals(HttpStatusCode.Forbidden, res.status, "$m $p")
                assertTrue(res.bodyAsText().contains(CLI_REFUSED_MESSAGE), "$m $p")
            }
            // The CLI cannot mint itself a successor or list credentials: the account-only routes are refused it.
            val selfMint = client.post("/v1/owner/cli") {
                header(HttpHeaders.Authorization, "Bearer $cli")
                contentType(ContentType.Application.Json)
                setBody("""{"label":"another"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, selfMint.status)
            assertTrue(selfMint.bodyAsText().contains(CLI_REFUSED_MESSAGE) || selfMint.bodyAsText().contains(ACCOUNT_ONLY_MESSAGE))

            val revoked = client.post("/v1/owner/cli/$credentialId/revoke", session.auth)
            assertEquals(HttpStatusCode.NoContent, revoked.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/snapshots", bearer(cli)).status)
            assertEquals(listOf("owner.account_created", "cli.issued", "cli.revoked"), server.ownerLog())
        }
    }

    @Test
    fun `a paired phone is refused the account's routes, as every console-only route`() {
        val server = DeviceServer()
        val phone = TestPhone()
        testApplication {
            server.start(this)
            server.pair(client, phone)
            createAccount(client, server)
            val res = server.signedGet(client, phone, "/v1/owner/cli")
            assertEquals(HttpStatusCode.Forbidden, res.status)
        }
    }

    @Test
    fun `an administrator's or a clinician's session cookie is no owner credential`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            val session = createAccount(client, server).session()
            val value = session.cookie.substringAfter('=')
            for (name in listOf("daymark_admin", "daymark_session")) {
                val res = client.get("/v1/snapshots") { header(HttpHeaders.Cookie, "$name=$value") }
                assertEquals(HttpStatusCode.Unauthorized, res.status, name)
            }
            // Positive control: the same value under the owner's cookie name is the owner.
            assertEquals(HttpStatusCode.OK, client.get("/v1/snapshots") { header(HttpHeaders.Cookie, "daymark_owner=$value") }.status)
        }
    }

    @Test
    fun `the emailed token reset is gone once the account exists`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            val before = client.post("/v1/recovery/request") { contentType(ContentType.Application.Json); setBody("""{"email":"a@example.com"}""") }
            assertEquals(HttpStatusCode.Accepted, before.status)
            createAccount(client, server)
            val after = client.post("/v1/recovery/request") { contentType(ContentType.Application.Json); setBody("""{"email":"a@example.com"}""") }
            assertEquals(HttpStatusCode.NotFound, after.status)
            val confirm = client.post("/v1/recovery/confirm") { contentType(ContentType.Application.Json); setBody("""{"confirmToken":"x"}""") }
            assertEquals(HttpStatusCode.NotFound, confirm.status)
        }
    }

    @Test
    fun `changing the retired token at a restart disconnects no phone`() {
        val dir = java.nio.file.Files.createTempDirectory("owner-account").toFile()
        val first = DeviceServer(dataDir = dir)
        val phone = TestPhone()
        testApplication {
            first.start(this)
            first.pair(client, phone)
            createAccount(client, first)
        }
        first.account.close(); first.ownerAudit.close()
        val second = DeviceServer(dataDir = dir, authToken = "a-different-token-after-the-account")
        testApplication {
            second.start(this)
            assertEquals(HttpStatusCode.OK, second.signedGet(client, phone, "/v1/snapshots").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/snapshots", bearer("a-different-token-after-the-account")).status)
        }
    }
}
