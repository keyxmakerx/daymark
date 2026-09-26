package com.daymark.companion

import com.daymark.companion.auth.Secrets
import com.daymark.companion.routes.ErrorDto
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A revoked phone is refused everywhere, at once (#186).
 *
 * The routes are not listed here. [walk] reads them from the running server's route tree and asks
 * each which credentials it takes: a route that answers a wrong token 401 and either the owner's token
 * or a registered phone's signature something else authenticates the owner. Every such route must
 * take the phone's signature (or, where the owner console alone manages devices, answer it 403), and
 * once the console revokes the phone, must answer each of its requests — fresh time, fresh nonce —
 * the one 401 every refusal gets. A route added later is walked without anyone listing it, and the
 * planted route in the positive control shows that a route taking only the token is caught.
 */
class DeviceKeyRevocationTest {

    private val unauthorized = HttpStatusCode.Unauthorized to """{"error":"unauthorized"}"""
    private val consoleOnly = HttpStatusCode.Forbidden to """{"error":"devices are managed from the owner console"}"""

    /** The routes only the owner console may call: device management (#189). */
    private val consoleRoutes = setOf(
        "GET /v1/devices", "POST /v1/devices/pairing", "GET /v1/devices/pairing/{codeId}",
        "POST /v1/devices/pairing/{codeId}/confirm", "POST /v1/devices/{keyId}/revoke",
    )

    /** A relationship's inbox token, so the routes behind one are asked past it. */
    private val inboxToken = "inbox-token-for-the-route-walk-0123456789"
    private val relRef = Secrets.relRefOf(inboxToken)

    private data class Mounted(val method: HttpMethod, val template: String) {
        override fun toString() = "${method.value} $template"
    }

    /** A route as the tree prints it: its path, then `(method:X)`; selectors that match no path in brackets. */
    private fun mounted(text: String): Mounted? {
        val method = Regex("""/\(method:([A-Z]+)\)$""").find(text) ?: return null
        val template = text.removeSuffix(method.value).split('/').filterNot { it.startsWith("(") }.joinToString("/").ifEmpty { "/" }
        return Mounted(HttpMethod.parse(method.groupValues[1]), template)
    }

    /** A concrete path for [template]: the fixture's relationship and a channel it has, and a filler elsewhere. */
    private fun pathFor(template: String): String = template.split('/').joinToString("/") { segment ->
        when (segment) {
            "{relRef}" -> relRef
            "{channel}" -> "grants"
            "{lineage}" -> "lin1"
            "{version}" -> "1"
            else -> if (segment.startsWith("{")) "x" else segment
        }
    }

    private val jsonBody = "{}".toByteArray()

    private suspend fun HttpClient.ask(route: Mounted, credential: io.ktor.client.request.HttpRequestBuilder.(target: String, body: ByteArray?) -> Unit): Pair<HttpStatusCode, String> {
        val target = pathFor(route.template)
        val body = if (route.method == HttpMethod.Get || route.method == HttpMethod.Delete) null else jsonBody
        val res = request(target) {
            method = route.method
            header("X-Rel-Token", inboxToken)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            credential(target, body)
        }
        return res.status to res.bodyAsText()
    }

    private class Walk(val ownerRoutes: List<Mounted>, val problems: List<String>, val all: List<String>)

    /** [phone]'s signature on a request to [route], made now with a fresh nonce. */
    private suspend fun HttpClient.signedBy(route: Mounted, phone: TestPhone, server: DeviceServer) =
        ask(route) { target, body -> signedWith(phone.headers(route.method.value, target, body ?: ByteArray(0), server.seconds)) }

    /**
     * Every route of the running [app], asked with a wrong token, the owner's token and [phone]'s
     * signature. A route that answers the wrong token 401 and either of the others anything else
     * authenticates the owner; for each, what is wrong with how it treats the registered phone.
     */
    private suspend fun ApplicationTestBuilder.walk(app: Application, server: DeviceServer, phone: TestPhone): Walk {
        val routes = app.routing { }.getAllRoutes().mapNotNull { mounted(it.toString()) }.distinct()
        val ownerRoutes = mutableListOf<Mounted>()
        val problems = mutableListOf<String>()
        for (route in routes) {
            val wrong = client.ask(route) { _, _ -> header(HttpHeaders.Authorization, "Bearer not-the-owner-token") }
            val token = client.ask(route) { _, _ -> header(HttpHeaders.Authorization, "Bearer ${server.authToken}") }
            val signed = client.signedBy(route, phone, server)
            val authenticatesOwner = wrong.first == HttpStatusCode.Unauthorized &&
                (token.first != HttpStatusCode.Unauthorized || signed.first != HttpStatusCode.Unauthorized)
            if (!authenticatesOwner) continue
            ownerRoutes += route
            val expected = if (route.toString() in consoleRoutes) consoleOnly else null
            when {
                expected != null && signed != expected ->
                    problems += "$route answered the phone ${signed.first.value} ${signed.second}, not the console's 403"
                expected == null && (signed.first == HttpStatusCode.Unauthorized || signed.first == HttpStatusCode.TooManyRequests) ->
                    problems += "$route refused a registered phone: ${signed.first.value} ${signed.second}"
            }
        }
        return Walk(ownerRoutes, problems, routes.map { it.toString() })
    }

    /** Each of [routes] asked by the revoked [phone], signing afresh: anything but the one 401. */
    private suspend fun ApplicationTestBuilder.askRevoked(routes: List<Mounted>, server: DeviceServer, phone: TestPhone): List<String> =
        routes.mapNotNull { route ->
            val signed = client.signedBy(route, phone, server)
            if (signed == unauthorized) null else "$route answered the revoked phone ${signed.first.value} ${signed.second}"
        }

    private fun server() = DeviceServer(mode = SetupMode.PRACTICE)

    @Test
    fun revokedDeviceIsRefusedOnEveryRoute() {
        val server = server()
        testApplication {
            lateinit var app: Application
            server.start(this)
            application { app = this }
            startApplication()
            val phone = TestPhone()
            server.pair(client, phone)

            val before = walk(app, server, phone)
            assertEquals(emptyList(), before.problems, "a registered phone reaches every route the token does, but the console's")
            // The walk is not empty: it found the owner's routes of every group, the console's among them.
            val found = before.ownerRoutes.map { it.toString() }
            for (expected in listOf(
                "GET /v1/snapshots", "PUT /v1/keydoc/{version}", "POST /v1/invite", "GET /v1/rel/{relRef}/{channel}/{lineage}",
                "POST /v1/relations/{relRef}/pairing", "POST /v1/orgs", "PUT /v1/owner/notifications", "GET /v1/owner/audit",
                "GET /v1/devices/registration",
            ) + consoleRoutes) {
                assertTrue(expected in found, "the walk must find $expected among the owner's routes: $found")
            }

            // The owner console's Revoke.
            val revoke = client.post("/v1/devices/${phone.keyId}/revoke") { header(HttpHeaders.Authorization, "Bearer ${server.authToken}") }
            assertEquals(HttpStatusCode.NoContent, revoke.status)

            assertEquals(
                emptyList(),
                askRevoked(before.ownerRoutes, server, phone),
                "every route answers the revoked phone the one 401, from the next request on",
            )

            // Nor can it pair itself again, or confirm another phone.
            val minted = server.mint(client)
            val redeem = server.redeem(client, phone, minted.code)
            assertEquals(HttpStatusCode.NotFound to """{"error":"no such pairing code"}""", redeem.status to redeem.bodyAsText(), "redeem")
            val other = TestPhone()
            assertEquals(HttpStatusCode.Accepted, server.redeem(client, other, minted.code).status, "control: the code was live")
            val target = "/v1/devices/pairing/${minted.codeId}/confirm"
            val body = """{"keyId":"${other.keyId}"}"""
            val confirm = client.post(target) {
                signedWith(phone.headers("POST", target, body.toByteArray(), server.seconds))
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(unauthorized, confirm.status to confirm.bodyAsText(), "confirm")
            val mint = client.post("/v1/devices/pairing") { signedWith(phone.headers("POST", "/v1/devices/pairing", timeSeconds = server.seconds)) }
            assertEquals(unauthorized, mint.status to mint.bodyAsText(), "mint")
        }
    }

    @Test
    fun `positive control - a planted route that takes only the token is found and caught`() {
        val server = server()
        testApplication {
            lateinit var app: Application
            server.start(this)
            application {
                app = this
                routing {
                    get("/v1/planted-token-only") {
                        if (call.request.headers[HttpHeaders.Authorization] == "Bearer ${server.authToken}") {
                            call.respond(HttpStatusCode.OK)
                        } else {
                            call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                        }
                    }
                }
            }
            startApplication()
            val phone = TestPhone()
            server.pair(client, phone)
            val walked = walk(app, server, phone)
            assertTrue("GET /v1/planted-token-only" in walked.all, "the walk reads the real tree: ${walked.all}")
            assertTrue("GET /v1/planted-token-only" in walked.ownerRoutes.map { it.toString() }, "and asks it as an owner route")
            assertEquals(1, walked.problems.size, "exactly the planted route is caught: ${walked.problems}")
            assertTrue(walked.problems.single().startsWith("GET /v1/planted-token-only refused a registered phone: 401"), walked.problems.single())
        }
    }

    @Test
    fun tokenReissueRevokesEveryDeviceKeyOfTheOwner() {
        val server = server()
        testApplication {
            server.start(this)
            val phones = listOf(TestPhone(), TestPhone())
            phones.forEach { server.pair(client, it) }
            phones.forEach { assertEquals(HttpStatusCode.OK, server.signedGet(client, it, "/v1/snapshots").status, "control: each phone works") }

            // The emailed re-issue: a registered address, a confirmation link, and the confirm.
            val notify = client.put("/v1/owner/notifications") {
                header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
                contentType(ContentType.Application.Json)
                setBody("""{"email":"owner@example.org","events":[]}""")
            }
            assertEquals(HttpStatusCode.NoContent, notify.status)
            val link = assertNotNull(server.account.requestReissue("owner@example.org", ttlSeconds = 3_600))
            val confirm = client.post("/v1/recovery/confirm") {
                contentType(ContentType.Application.Json)
                setBody("""{"confirmToken":"${link.confirmToken}"}""")
            }
            assertEquals(HttpStatusCode.OK, confirm.status)
            val newToken = confirm.json().string("newToken")

            phones.forEach { assertEquals(unauthorized, server.signedGet(client, it, "/v1/snapshots").let { r -> r.status to r.bodyAsText() }, "a re-issued token disconnects every phone") }
            assertEquals(
                listOf("device.registered", "device.registered", "device.revoked:reissue", "device.revoked:reissue"),
                server.ownerLog(),
                "one row for each phone the re-issue revoked",
            )
            // Control: the new token is the owner's, and a phone pairs again by QR.
            val again = TestPhone()
            val reissued = DeviceServer(dataDir = server.dataDir, authToken = newToken)
            val minted = client.post("/v1/devices/pairing") { header(HttpHeaders.Authorization, "Bearer $newToken") }
            assertEquals(HttpStatusCode.Created, minted.status)
            assertEquals(HttpStatusCode.Accepted, server.redeem(client, again, minted.json().string("code")).status)
            assertEquals(HttpStatusCode.Created, reissued.confirm(client, minted.json().string("codeId"), again.keyId).status)
            assertEquals(HttpStatusCode.OK, server.signedGet(client, again, "/v1/snapshots").status)
        }
    }

    @Test
    fun `a token the operator changes revokes every device key of the owner too`() {
        val first = server()
        val phone = TestPhone()
        testApplication {
            first.start(this)
            first.pair(client, phone)
            assertEquals(HttpStatusCode.OK, first.signedGet(client, phone, "/v1/snapshots").status, "control: the phone works")
        }
        first.account.close()

        val changed = DeviceServer(dataDir = first.dataDir, now = first.now, mode = SetupMode.PRACTICE, authToken = "a-new-token-the-operator-set")
        testApplication {
            changed.start(this)
            assertEquals(unauthorized, changed.signedGet(client, phone, "/v1/snapshots").let { it.status to it.bodyAsText() })
            assertEquals(listOf("device.registered", "device.revoked:reissue"), changed.ownerLog())
        }
        changed.account.close()

        // Control: a restart with the token unchanged revokes nothing more.
        val same = DeviceServer(dataDir = first.dataDir, now = first.now, mode = SetupMode.PRACTICE, authToken = "a-new-token-the-operator-set")
        testApplication {
            same.start(this)
            assertEquals(emptyList(), same.account.revokedAtStart)
            assertEquals(listOf("device.registered", "device.revoked:reissue"), same.ownerLog())
        }
    }
}
