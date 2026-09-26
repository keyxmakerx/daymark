package com.daymark.companion

import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.auth.OwnerAuth
import com.daymark.companion.auth.Secrets
import com.daymark.companion.routes.ErrorDto
import com.daymark.companion.routes.PHONE_REFUSED_ROUTES
import com.daymark.companion.routes.owner
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
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A revoked phone is refused everywhere, at once (#186), and a registered one reaches every owner route
 * but those the one list keeps from it (#189).
 *
 * The routes are not listed here. [walk] reads them from the running server's route tree and asks
 * each which credentials it takes: a route that answers a wrong token 401 and either the owner's token
 * or a registered phone's signature something else authenticates the owner. Which of those must refuse
 * the phone is not listed here either: it is `PHONE_REFUSED_ROUTES`, the list the gate itself reads.
 * Each route on it must answer the phone the one 403, every other owner route must take it, and every
 * route on it must be one the server mounts, so a renamed route cannot leave the list pointing at
 * nothing. Once the console revokes the phone, every owner route must answer each of its requests —
 * fresh time, fresh nonce — the one 401 every refusal gets.
 *
 * A route added later is walked without anyone listing it. The positive control plants three: one
 * taking only the token, which is caught; an operator route on the gate's list, which is walked and
 * answers the phone 403; and one the list names but whose handler lets the phone through, which is
 * caught.
 */
class DeviceKeyRevocationTest {

    private val unauthorized = HttpStatusCode.Unauthorized to """{"error":"unauthorized"}"""
    private val phoneRefused = HttpStatusCode.Forbidden to """{"error":"a paired phone cannot do this"}"""

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

    /** What every request of the walk carries besides its credential: the relationship's inbox token, which the relationship routes read. */
    private val carried = mapOf("X-Rel-Token" to inboxToken)

    /** [route] asked with [credential], which attaches [carried] too: a token's request as it is, a phone's signed. */
    private suspend fun HttpClient.ask(route: Mounted, credential: io.ktor.client.request.HttpRequestBuilder.(target: String, body: ByteArray?) -> Unit): Pair<HttpStatusCode, String> {
        val target = pathFor(route.template)
        val body = if (route.method == HttpMethod.Get || route.method == HttpMethod.Delete) null else jsonBody
        val res = request(target) {
            method = route.method
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
        ask(route) { target, body -> signedWith(phone.headers(route.method.value, target, body ?: ByteArray(0), server.seconds, signed = carried)) }

    /** The bearer [token]'s request to [route]. */
    private suspend fun HttpClient.withToken(route: Mounted, token: String) =
        ask(route) { _, _ -> signedWith(carried + (HttpHeaders.Authorization to "Bearer $token")) }

    /**
     * Every route of the running [app], asked with a wrong token, the owner's token and [phone]'s
     * signature. A route that answers the wrong token 401 and either of the others anything else
     * authenticates the owner; for each, what is wrong with how it treats the registered phone, held
     * to [refused]: the routes that must answer it the one 403, and no others.
     */
    private suspend fun ApplicationTestBuilder.walk(app: Application, server: DeviceServer, phone: TestPhone, refused: Set<String>): Walk {
        val routes = app.routing { }.getAllRoutes().mapNotNull { mounted(it.toString()) }.distinct()
        val ownerRoutes = mutableListOf<Mounted>()
        val problems = mutableListOf<String>()
        for (route in routes) {
            val wrong = client.withToken(route, "not-the-owner-token")
            val token = client.withToken(route, server.authToken)
            val signed = client.signedBy(route, phone, server)
            val authenticatesOwner = wrong.first == HttpStatusCode.Unauthorized &&
                (token.first != HttpStatusCode.Unauthorized || signed.first != HttpStatusCode.Unauthorized)
            if (!authenticatesOwner) continue
            ownerRoutes += route
            val listed = route.toString() in refused
            when {
                listed && signed != phoneRefused ->
                    problems += "$route is on the list, and answered the phone ${signed.first.value} ${signed.second}"
                !listed && (signed.first == HttpStatusCode.Unauthorized || signed.first == HttpStatusCode.TooManyRequests) ->
                    problems += "$route refused a registered phone: ${signed.first.value} ${signed.second}"
                !listed && signed == phoneRefused ->
                    problems += "$route answered the phone the list's 403, and is not on the list"
            }
        }
        val walked = ownerRoutes.map { it.toString() }.toSet()
        for (entry in refused - walked) problems += "$entry is on the list, and the server mounts no such owner route"
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

            val before = walk(app, server, phone, PHONE_REFUSED_ROUTES)
            assertEquals(emptyList(), before.problems, "a registered phone reaches every owner route but those on the list, which answer it 403")
            // The walk is not empty: it found the owner's routes of every group, and the list's among them
            // (the walk itself fails for an entry it did not find).
            val found = before.ownerRoutes.map { it.toString() }
            for (expected in listOf(
                "GET /v1/snapshots", "PUT /v1/keydoc/{version}", "POST /v1/invite", "GET /v1/rel/{relRef}/{channel}/{lineage}",
                "POST /v1/relations/{relRef}/pairing", "GET /v1/owner/notifications", "GET /v1/owner/audit",
                "GET /v1/devices/registration",
            )) {
                assertTrue(expected in found, "the walk must find $expected among the owner's routes: $found")
            }
            assertTrue(PHONE_REFUSED_ROUTES.isNotEmpty() && found.containsAll(PHONE_REFUSED_ROUTES), "control: the list is walked")

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
    fun `positive control - planted routes are walked, a new operator route is held to the 403, and each wrong one is caught`() {
        val server = server()
        val operator = "POST /v1/planted-operator"
        val ungated = "POST /v1/planted-operator-ungated"
        testApplication {
            lateinit var app: Application
            server.start(this)
            application {
                app = this
                // The gate with the one list and a new operator route on it, as a route added to the list
                // in the code would be. Its own budget; the same owner, keys and nonces as the server's.
                val guard = AuthGuard(server.account.currentTokenHash(), 100_000, 900_000L, 100_000)
                val gate = OwnerAuth(guard, server.account.devices, 2_097_152L, PHONE_REFUSED_ROUTES + operator)
                routing {
                    // Takes the token and knows nothing of phones: a route that missed the change.
                    get("/v1/planted-token-only") {
                        if (call.request.headers[HttpHeaders.Authorization] == "Bearer ${server.authToken}") {
                            call.respond(HttpStatusCode.OK)
                        } else {
                            call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                        }
                    }
                    // A new operator route, on the gate's list: the phone must get the 403.
                    post("/v1/planted-operator") {
                        call.owner(gate) ?: return@post
                        call.respond(HttpStatusCode.Created)
                    }
                    // A route the list names whose handler lets any owner credential through.
                    post("/v1/planted-operator-ungated") {
                        if (gate.check(call) !is OwnerAuth.Outcome.Ok) return@post call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
                        call.respond(HttpStatusCode.Created)
                    }
                }
            }
            startApplication()
            val phone = TestPhone()
            server.pair(client, phone)
            val walked = walk(app, server, phone, PHONE_REFUSED_ROUTES + operator + ungated)
            val owners = walked.ownerRoutes.map { it.toString() }
            for (planted in listOf("GET /v1/planted-token-only", operator, ungated)) {
                assertTrue(planted in walked.all, "the walk reads the real tree: $planted in ${walked.all}")
                assertTrue(planted in owners, "and asks $planted as an owner route")
            }
            assertEquals(
                listOf(
                    "GET /v1/planted-token-only refused a registered phone: 401",
                    "$ungated is on the list, and answered the phone 201",
                ),
                walked.problems.map { it.substringBefore(" {").trimEnd() }.sorted(),
                "exactly the two wrong planted routes are caught, and the new operator route is not: ${walked.problems}",
            )
            // The new operator route answers the phone the one 403, and the owner's token as before.
            val byPhone = client.post("/v1/planted-operator") { signedWith(phone.headers("POST", "/v1/planted-operator", timeSeconds = server.seconds)) }
            assertEquals(phoneRefused, byPhone.status to byPhone.bodyAsText())
            val byToken = client.post("/v1/planted-operator") { header(HttpHeaders.Authorization, "Bearer ${server.authToken}") }
            assertEquals(HttpStatusCode.Created, byToken.status)

            // A list entry the server mounts no route for is caught too: a renamed route is not left open.
            val stale = walk(app, server, phone, PHONE_REFUSED_ROUTES + operator + "POST /v1/planted-no-such-route")
            assertTrue(
                "POST /v1/planted-no-such-route is on the list, and the server mounts no such owner route" in stale.problems,
                "a stale entry is named: ${stale.problems}",
            )
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
