package com.daymark.companion

import com.daymark.companion.SetupMode.PAIRED
import com.daymark.companion.SetupMode.PRACTICE
import com.daymark.companion.SetupMode.SOLO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Each shape switches on only what it needs (#330): every route group, every page and every store,
 * checked against the issue's own table, which this file restates, rather than against the code's.
 *
 * The gated routes are not listed by hand. [everyRoute] walks the route tree of a server with
 * everything switched on, so a route added to a group later is asked here without anyone remembering
 * to, and a route in no known group fails the first test until someone decides which shape serves it.
 * The routes every shape serves ARE listed by hand, in [SERVED_IN_EVERY_SHAPE]: a route is grouped by
 * its path, so a clinician route under an owner's prefix would otherwise be classified as served in
 * solo, and pass.
 *
 * Each shape has a positive control — something that IS served there — so a server with everything
 * switched off cannot pass the tests of the shapes that switch things on. A route that is on must
 * answer from its own handler, and the router is asked which route took each call, because a route
 * that went missing is rarely a 404 here: the static handler answers any GET it has no file for with
 * the owner's page, and a neighbouring route with a path parameter can take the call.
 */
class ShapeRoutingTest {

    /** A server this file starts: the shape it asks for, and the shape it must behave as. */
    private data class Case(val name: String, val mode: SetupMode?, val therapistAuth: Boolean, val serves: SetupMode)

    private val soloSet = Case("solo", SOLO, therapistAuth = false, serves = SOLO)
    private val pairedSet = Case("paired", PAIRED, therapistAuth = false, serves = PAIRED)
    private val practiceSet = Case("practice", PRACTICE, therapistAuth = false, serves = PRACTICE)
    private val switchOnNoMode = Case("no mode, DAYMARK_THERAPIST_AUTH on", null, therapistAuth = true, serves = PRACTICE)
    private val switchOffNoMode = Case("no mode, DAYMARK_THERAPIST_AUTH off", null, therapistAuth = false, serves = SOLO)
    private val everyCase = listOf(soloSet, pairedSet, practiceSet, switchOnNoMode, switchOffNoMode)

    private fun config(case: Case, dataDir: String, webDir: String, basePath: String = "/") = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dataDir, basePath = basePath,
        webDir = webDir, logLevel = "info", authToken = OWNER_TOKEN,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        // Every route is asked without credentials; nothing here may lock the owner token out.
        authLockoutFails = 100_000, authLockoutSeconds = 1L, rateLimitRps = 100_000,
        therapistAuthEnabled = case.therapistAuth, setupMode = case.mode, cookieSecure = false,
    )

    /**
     * A server [serve] started: its directories, its route tree, and the route the router handed its
     * latest call to, as the tree prints it — null when the router found none and answered itself.
     */
    private class Served(val dataDir: File, val webDir: File) {
        lateinit var app: Application
        val answeredBy = AtomicReference<String?>()

        /** Every route this server mounts, read from its route tree while it runs. */
        fun routes(): List<Mounted> = app.routing { }.getAllRoutes().map { mounted(it.toString()) }
    }

    private fun serve(case: Case, basePath: String = "/", block: suspend ApplicationTestBuilder.(Served) -> Unit) {
        val served = Served(Files.createTempDirectory("shape-data").toFile(), webRoot())
        val cfg = config(case, served.dataDir.path, served.webDir.path, basePath)
        try {
            testApplication {
                application {
                    module(cfg)
                    served.app = this
                    monitor.subscribe(RoutingRoot.RoutingCallStarted) { call -> served.answeredBy.set(call.route.toString()) }
                }
                startApplication()
                block(served)
            }
        } finally {
            served.dataDir.deleteRecursively()
            served.webDir.deleteRecursively()
        }
    }

    // ── Enumeration ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every route and page the server mounts is in a group this file checks`() {
        val unclassified = everyRoute.filter { it.group == null }
        assertEquals(emptyList(), unclassified, "a route in no group is served in every shape; decide which shapes serve it")
        // The positive control: the walk sees every group, so "nothing unclassified" is not an empty walk.
        for (group in Group.entries) {
            assertTrue(everyRoute.any { it.group == group }, "the walk found no route in $group: ${everyRoute.map { it.template }}")
        }
    }

    @Test
    fun `the routes under the prefixes every shape serves are exactly the ones this file names`() {
        for (case in everyCase) {
            serve(case) { served ->
                val mounted = served.routes().filter { it.group in ALWAYS_ON }
                val unnamed = mounted.filter { it.toString() !in SERVED_IN_EVERY_SHAPE }.map { "$it (${it.group})" }
                assertEquals(
                    emptyList(), unnamed,
                    "${case.name} mounts a route under a prefix every shape serves, which classifies it as served in " +
                        "every shape, solo included. Classify it on purpose: a clinician or practice route goes under " +
                        "one of its own group's prefixes (GROUPS), inside the group's gate, where the off-answer covers " +
                        "it in a shape without the group; a route every shape serves goes in SERVED_IN_EVERY_SHAPE",
                )
                // The other half of "exactly", and the positive control for the half above: every route the
                // list names is mounted, in every shape, so the filter saw the groups it filtered for.
                val gone = SERVED_IN_EVERY_SHAPE - mounted.map { it.toString() }.toSet()
                assertEquals(emptySet(), gone, "${case.name} mounts none of these, and every shape serves them")
            }
        }
    }

    @Test
    fun `with no owner token, every sync route answers that sync is not configured`() {
        val sync = everyRoute.filter { it.group == Group.SYNC }
        // The positive control: the walk found the sync routes, the key document's and the devices'
        // among them (#258, #189), so "each answered 503" is not a statement about an empty list.
        val named = listOf(
            "GET /v1/keyparams", "GET /v1/keydoc", "POST /v1/keydoc", "PUT /v1/keydoc/{version}",
            "POST /v1/devices/pairing", "POST /v1/devices/redeem", "GET /v1/devices/registration",
        )
        assertTrue(sync.map { it.toString() }.containsAll(named), "the walk must find $named: $sync")
        val dataDir = Files.createTempDirectory("shape-no-token").toFile()
        val webDir = webRoot()
        try {
            testApplication {
                application { module(config(soloSet, dataDir.path, webDir.path).copy(authToken = null)) }
                for (route in sync) {
                    val res = client.request(route.path) { method = route.method }
                    assertEquals(HttpStatusCode.ServiceUnavailable, res.status, "$route on a server with no owner token")
                    assertEquals(SYNC_OFF_BODY, res.bodyAsText(), "$route on a server with no owner token")
                }
            }
        } finally {
            dataDir.deleteRecursively()
            webDir.deleteRecursively()
        }
    }

    @Test
    fun `every page of the web build is one this file classifies`() {
        val web = File(System.getProperty("user.dir"), "../web").canonicalFile
        val pages = web.listFiles { f -> f.isFile && f.name.endsWith(".html") }!!.map { it.name }.toSet()
        assertTrue(Pages.OWNER in pages, "the reader must find the owner's page, or it is reading nothing: $pages")
        assertEquals(
            SERVED_IN.keys, pages,
            "a page of the build this file does not classify is served in every shape; add it to SERVED_IN and Pages",
        )
    }

    // ── Route groups, per shape ──────────────────────────────────────────────────────────────────

    @Test
    fun `solo - every clinician, pairing and practice route answers the off-answer, and sync is on`() = assertRoutes(soloSet)

    @Test
    fun `paired - the clinician routes are on, and every practice route answers the off-answer`() = assertRoutes(pairedSet)

    @Test
    fun `practice - the clinician and practice routes are on`() = assertRoutes(practiceSet)

    @Test
    fun `no mode with DAYMARK_THERAPIST_AUTH on - the routes are the practice shape's, as before`() = assertRoutes(switchOnNoMode)

    @Test
    fun `no mode with DAYMARK_THERAPIST_AUTH off - the routes are the solo shape's, as before`() = assertRoutes(switchOffNoMode)

    private fun assertRoutes(case: Case) {
        val routes = everyRoute
        // Each gated group is asked route by route, so an empty walk cannot pass for a gated one.
        for (group in GROUP_ON_IN.keys) {
            assertTrue(routes.count { it.group == group } >= 2, "${case.name}: the walk found too few $group routes: $routes")
        }
        serve(case) { served ->
            // A redirect would hand the call to a second route, and the route asked is the one that must answer.
            val noRedirects = client.config { followRedirects = false }
            for (route in routes.filter { it.group in ASKED }) {
                served.answeredBy.set(null)
                val res = noRedirects.request(route.path) { method = route.method }
                val body = res.bodyAsText()
                val offAnswer = res.status == HttpStatusCode.ServiceUnavailable && body == OFF_BODY
                val on = GROUP_ON_IN[route.group]?.contains(case.serves) ?: true
                if (on) {
                    assertFalse(offAnswer, "${case.name}: ${route.method.value} ${route.template} (${route.group}) must be on")
                    // From its own handler, for the method it was registered with, which only the router can
                    // say: a route that went missing is answered by the static handler (the owner's page, for a
                    // GET), by a neighbouring route with a path parameter, or by the router's own 404 or 405,
                    // and a real handler may answer 404 itself.
                    val answeredBy = served.answeredBy.get()?.let(::mounted)
                    assertEquals(
                        route, answeredBy,
                        "${case.name}: $route (${route.group}) must answer from its own handler, but " +
                            "${answeredBy?.let { "$it (${it.group})" } ?: "no route"} took the call and answered " +
                            "${res.status.value} ${body.take(80)}",
                    )
                } else {
                    assertEquals(
                        HttpStatusCode.ServiceUnavailable, res.status,
                        "${case.name}: ${route.method.value} ${route.template} (${route.group}) must answer 503",
                    )
                    assertEquals(OFF_BODY, body, "${case.name}: ${route.method.value} ${route.template}: the same body as before")
                }
            }
            // Positive controls: what IS on in this shape answers as itself, so an everything-off
            // server fails here, and an everything-on server fails the loop above.
            val sync = client.get("/v1/snapshots") { header(HttpHeaders.Authorization, "Bearer $OWNER_TOKEN") }
            assertEquals(HttpStatusCode.OK, sync.status, "${case.name}: sync is on in every shape")
            assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/config").status)
            if (case.serves in GROUP_ON_IN.getValue(Group.CLINICIAN)) {
                assertEquals(HttpStatusCode.Unauthorized, client.request("/v1/invite") { method = HttpMethod.Post }.status)
            }
            if (case.serves in GROUP_ON_IN.getValue(Group.PRACTICE)) {
                assertEquals(HttpStatusCode.Unauthorized, client.request("/v1/orgs") { method = HttpMethod.Post }.status)
            }
        }
    }

    // ── Pages, per shape ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `solo - the owner's page and the server console are served, and no spelling reaches the clinician or practice page`() =
        assertPages(soloSet)

    @Test
    fun `paired - the clinician's page is served, and no spelling reaches the practice page`() = assertPages(pairedSet)

    @Test
    fun `practice - every page is served, at every spelling`() = assertPages(practiceSet)

    @Test
    fun `no mode with DAYMARK_THERAPIST_AUTH on - every page is served, as before`() = assertPages(switchOnNoMode)

    @Test
    fun `no mode with DAYMARK_THERAPIST_AUTH off - the pages are the solo shape's`() = assertPages(switchOffNoMode)

    @Test
    fun `under a base path, solo and paired serve and refuse the same pages`() {
        assertPages(soloSet, basePath = "/daymark")
        assertPages(pairedSet, basePath = "/daymark")
    }

    @Test
    fun `where the clinician's page is off, nothing under its clean paths answers with its bytes`() {
        for (basePath in listOf("/", "/daymark")) {
            val base = basePath.trimEnd('/')
            // The positive control: the comparison sees the page where the page is served, so the
            // refusals below are not a comparison that could never match.
            serve(pairedSet, basePath) { served ->
                assertTrue(isClinicianPage(client.get("$base/therapist"), served), "paired: $base/therapist must be the page")
            }
            for (case in listOf(soloSet, switchOffNoMode)) {
                serve(case, basePath) { served ->
                    // Every route this server mounts under the page's clean paths, one added later included,
                    // and paths under them that no route names, which reach the static handler.
                    val mounted = served.routes().filter { relativeTo(it, base).group == Group.CLINICIAN_PAGE }
                    assertTrue(
                        mounted.any { it.template == "$base/therapist" },
                        "${case.name}: the walk must find $base/therapist, or it asks nothing that is mounted: $mounted",
                    )
                    val asked = mounted.map { it.method to it.path } +
                        CLINICIAN_PAGE_PREFIXES.flatMap { listOf("$base/$it/x", "$base/$it/") }.map { HttpMethod.Get to it }
                    for ((verb, path) in asked.distinct()) {
                        // Redirects are followed: a page reached through one is served all the same.
                        val res = client.request(path) { method = verb }
                        assertFalse(
                            isClinicianPage(res, served),
                            "${case.name}: ${verb.value} $path answers with ${Pages.CLINICIAN}'s bytes, " +
                                "a page this shape does not serve",
                        )
                    }
                }
            }
        }
    }

    private fun assertPages(case: Case, basePath: String = "/") {
        val base = basePath.trimEnd('/')
        serve(case, basePath) {
            val noRedirects = client.config { followRedirects = false }
            for ((page, shapes) in SERVED_IN) {
                val marker = MARKERS.getValue(page)
                for (path in listOf("/$page") + otherSpellings(page)) {
                    val res = client.get("$base$path")
                    if (case.serves in shapes) {
                        // Every spelling reaches the file where the page is served: the positive
                        // control for the refusals, which would otherwise be a list of paths that
                        // simply lead nowhere.
                        assertEquals(HttpStatusCode.OK, res.status, "${case.name} serves $page at $path")
                        assertTrue(marker in res.bodyAsText(), "${case.name}: $path must be $page")
                    } else {
                        assertRefused(res, marker, "${case.name} does not serve $page, at $path")
                    }
                }
            }

            // The clinician's page also has a clean path, and the invitation link leads to it.
            val clinicianServed = case.serves in SERVED_IN.getValue(Pages.CLINICIAN)
            val therapist = client.get("$base/therapist")
            if (clinicianServed) {
                assertEquals(HttpStatusCode.OK, therapist.status, "${case.name}: /therapist")
                assertTrue(MARKERS.getValue(Pages.CLINICIAN) in therapist.bodyAsText())
            } else {
                assertRefused(therapist, MARKERS.getValue(Pages.CLINICIAN), "${case.name}: /therapist")
            }
            for (invite in listOf("/portal/invite", "/portal/invite/")) {
                val res = noRedirects.get("$base$invite")
                if (clinicianServed) {
                    assertEquals(HttpStatusCode.Found, res.status, "${case.name}: $invite")
                    assertEquals("$base/therapist", res.headers[HttpHeaders.Location])
                } else {
                    assertRefused(res, MARKERS.getValue(Pages.CLINICIAN), "${case.name}: $invite")
                    assertEquals(null, res.headers[HttpHeaders.Location], "${case.name}: $invite leads nowhere")
                }
            }

            // In every shape: the owner's page at the base, a script of the build, and the probes.
            assertTrue(MARKERS.getValue(Pages.OWNER) in client.get("$base/").bodyAsText(), "${case.name}: the owner's page")
            assertEquals(HttpStatusCode.OK, client.get("$base/assets/app.js").status, "${case.name}: the build's scripts")
            for (probe in listOf("/healthz", "/readyz", "/v1/config")) {
                assertEquals(HttpStatusCode.OK, client.get(probe).status, "${case.name}: $probe")
            }
        }
    }

    /** One answer for a page this shape does not serve, at every spelling: 403, with nothing in it. */
    private suspend fun assertRefused(res: HttpResponse, marker: String, what: String) {
        val body = res.bodyAsText()
        assertFalse(marker in body, "$what: the page must not be served")
        assertEquals(HttpStatusCode.Forbidden, res.status, what)
        assertEquals("", body, what)
    }

    /** Whether [res] is the clinician's page: byte for byte the file in the web root [served] was given. */
    private suspend fun isClinicianPage(res: HttpResponse, served: Served): Boolean =
        res.bodyAsBytes().contentEquals(File(served.webDir, Pages.CLINICIAN).readBytes())

    /**
     * Other spellings of `/[page]` that the static handler resolves to the same file. Each is derived
     * from the page's name, never written as a literal that might happen to equal it, and checked to
     * differ from it; the practice shape, which serves every page, proves each one reaches the file.
     */
    private fun otherSpellings(page: String): List<String> {
        val firstEncoded = "%" + "%02X".format(page[0].code) + page.substring(1)
        return listOf(
            "/$page/",
            "/./$page",
            "/x/../$page",
            "/assets/../$page",
            "/assets/%2e%2e/$page",
            "/%2F$page",
            "/assets%2F..%2F$page",
            "/$firstEncoded",
        ).onEach { assertNotEquals("/$page", it, "a spelling must differ from the path it respells") }
    }

    // ── Stores, per shape ────────────────────────────────────────────────────────────────────────

    @Test
    fun `each shape opens only the stores of the groups it serves`() {
        for (case in everyCase) {
            serve(case) { served ->
                assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
                val files = served.dataDir.list()!!.filter { it.endsWith(".db") }.toSet()
                // The positive control: the listing sees the stores every shape opens, the wrapped key's
                // among them (#258), and the owner's own log, which a phone's pairing writes to (#189).
                assertTrue(
                    "owner-account.db" in files && "index.db" in files && "wrapped-key.db" in files && "owner-audit.db" in files,
                    "${case.name}: $files",
                )
                val clinician = case.serves in GROUP_ON_IN.getValue(Group.CLINICIAN)
                for (name in listOf("auth.db", "rel-index.db", "audit.db", "pairing.db")) {
                    assertEquals(clinician, name in files, "${case.name}: $name in $files")
                }
                val practice = case.serves in GROUP_ON_IN.getValue(Group.PRACTICE)
                for (name in listOf("org.db", "org-audit.db")) {
                    assertEquals(practice, name in files, "${case.name}: $name in $files")
                }
            }
        }
    }

    // ── /v1/config ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `v1 config publishes the chosen shape, and nothing when the shape was assumed`() {
        for (case in listOf(soloSet, pairedSet, practiceSet)) {
            serve(case) {
                assertEquals(
                    """{"smtpEnabled":false,"setupMode":"${case.mode!!.wire}"}""",
                    client.get("/v1/config").bodyAsText(),
                    case.name,
                )
            }
        }
        // Assumed: the body the server published before the setting existed, byte for byte, so the
        // first-run screen asks as it did and never reads an assumption as the operator's answer.
        for (case in listOf(switchOnNoMode, switchOffNoMode)) {
            serve(case) { assertEquals("""{"smtpEnabled":false}""", client.get("/v1/config").bodyAsText(), case.name) }
        }
    }

    @Test
    fun `the published field and ids are the ones the first-run screen reads`() {
        val shapeTs = File(System.getProperty("user.dir"), "../web/src/lib/setup/shape.ts")
        assertTrue(shapeTs.isFile, "${shapeTs.path} must exist: a contract read from nowhere proves nothing")
        val source = shapeTs.readText()
        assertTrue(Regex("""CONFIG_FIELD\s*=\s*'setupMode'""").containsMatchIn(source), "the screen reads setupMode")
        val ids = Regex("""\bid:\s*'([a-z]+)'""").findAll(source).map { it.groupValues[1] }.toSet()
        assertEquals(SetupMode.entries.map { it.wire }.toSet(), ids, "the screen's shape ids")
    }

    private companion object {
        const val OWNER_TOKEN = "owner-token-shape-test"

        /** What every clinician, pairing and practice route answered with DAYMARK_THERAPIST_AUTH off. */
        const val OFF_BODY = """{"error":"therapist portal not configured"}"""

        /** What every sync route answers on a server with no owner token. */
        const val SYNC_OFF_BODY = """{"error":"sync API not configured"}"""

        /** Text unique to each page, so "served something" cannot pass for "served this page". */
        val MARKERS = mapOf(
            Pages.OWNER to "<div id=\"owner-app\"></div>",
            Pages.SERVER_CONSOLE to "<div id=\"admin-app\"></div>",
            Pages.CLINICIAN to "<div id=\"therapist-app\"></div>",
            Pages.PRACTICE to "<div id=\"practice-app\"></div>",
        )

        /** The issue's table (#330): the shapes that serve each page of the web build. */
        val SERVED_IN = mapOf(
            "index.html" to setOf(SOLO, PAIRED, PRACTICE),
            "admin.html" to setOf(SOLO, PAIRED, PRACTICE),
            "therapist.html" to setOf(PAIRED, PRACTICE),
            "practice.html" to setOf(PRACTICE),
        )

        enum class Group { PROBE, SYNC, OWNER, CLINICIAN, PRACTICE, CLINICIAN_PAGE, STATIC }

        /** The issue's table (#330): the shapes that switch each gated route group on. */
        val GROUP_ON_IN = mapOf(
            Group.CLINICIAN to setOf(PAIRED, PRACTICE),
            Group.PRACTICE to setOf(PRACTICE),
        )

        /** The groups asked route by route; the page mounts are asked by [assertPages]. */
        val ASKED = setOf(Group.PROBE, Group.SYNC, Group.OWNER, Group.CLINICIAN, Group.PRACTICE)

        /**
         * The groups no shape switches off. Their prefixes do not say a route is served in every shape:
         * [SERVED_IN_EVERY_SHAPE] does.
         */
        val ALWAYS_ON: Set<Group> = ASKED - GROUP_ON_IN.keys

        /**
         * Every route under an [ALWAYS_ON] group's prefix, by method and path: the routes every shape
         * serves, solo included. Named one by one because a route is grouped by its path, and a clinician
         * route under an owner's prefix — outside the clinician group's gate, or inside it where no
         * off-answer covers it — would otherwise be classified as served in every shape, and pass. A route
         * added under one of these prefixes fails until someone decides, here, that every shape serves it.
         */
        val SERVED_IN_EVERY_SHAPE = setOf(
            "GET /healthz", "GET /readyz", "GET /v1/config",
            "GET /v1/keyparams", "PUT /v1/keyparams",
            "GET /v1/keydoc", "POST /v1/keydoc", "PUT /v1/keydoc/{version}",
            "GET /v1/snapshots", "GET /v1/snapshots/{lineage}",
            "GET /v1/snapshots/{lineage}/{version}", "PUT /v1/snapshots/{lineage}/{version}",
            "GET /v1/owner/notifications", "PUT /v1/owner/notifications",
            "POST /v1/recovery/request", "POST /v1/recovery/confirm",
            // Pairing a phone, and the phones paired (#186, #189): a phone pairs to sync, in every shape.
            "GET /v1/devices", "POST /v1/devices/pairing", "GET /v1/devices/pairing/{codeId}",
            "POST /v1/devices/pairing/{codeId}/confirm", "POST /v1/devices/{keyId}/revoke",
            "POST /v1/devices/redeem", "GET /v1/devices/registration",
            // The owner's own log: phones paired and revoked, and lockouts (#189).
            "GET /v1/owner/audit",
        )

        /** Each route's group, by the first two segments of its path. */
        val GROUPS = mapOf(
            "healthz" to Group.PROBE, "readyz" to Group.PROBE, "v1/config" to Group.PROBE,
            "v1/keyparams" to Group.SYNC, "v1/keydoc" to Group.SYNC, "v1/snapshots" to Group.SYNC,
            "v1/devices" to Group.SYNC,
            "v1/owner" to Group.OWNER, "v1/recovery" to Group.OWNER,
            "v1/rel" to Group.CLINICIAN, "v1/invite" to Group.CLINICIAN, "v1/totp" to Group.CLINICIAN,
            "v1/session" to Group.CLINICIAN, "v1/webauthn" to Group.CLINICIAN, "v1/relations" to Group.CLINICIAN,
            "v1/orgs" to Group.PRACTICE,
            "therapist" to Group.CLINICIAN_PAGE, "portal/invite" to Group.CLINICIAN_PAGE,
            "{...}" to Group.STATIC,
        )

        /** The clinician's page's clean paths, `/therapist` and the invitation link's: its group's prefixes. */
        val CLINICIAN_PAGE_PREFIXES = GROUPS.filterValues { it == Group.CLINICIAN_PAGE }.keys

        /** A mounted route: its method, its path template, and a concrete path that reaches it. */
        data class Mounted(val method: HttpMethod, val template: String) {
            val path: String = template.split('/').filter { it.isNotEmpty() }
                .joinToString("/", prefix = "/") { if (it.startsWith("{")) "p" else it }
            val group: Group? = GROUPS[template.split('/').filter { it.isNotEmpty() }.take(2).joinToString("/")]
                ?: GROUPS[template.split('/').filter { it.isNotEmpty() }.take(1).joinToString("/")]

            override fun toString() = "${method.value} $template"
        }

        /**
         * A route as its route tree prints it: its path with a `(method:X)` segment last, and `(…)`
         * segments for selectors that match no path, such as the static handler's.
         */
        fun mounted(text: String): Mounted {
            val method = Regex("""/\(method:([A-Z]+)\)$""").find(text)
                ?: error("a route with no method selector last: $text")
            val template = text.removeSuffix(method.value).split('/')
                .filterNot { it.startsWith("(") }
                .joinToString("/").ifEmpty { "/" }
            return Mounted(HttpMethod.parse(method.groupValues[1]), template)
        }

        /** [route] with the base path [base] taken off, where it is under it, as the page routes are. */
        fun relativeTo(route: Mounted, base: String): Mounted =
            if (base.isNotEmpty() && route.template.startsWith("$base/")) {
                Mounted(route.method, route.template.removePrefix(base))
            } else {
                route
            }

        /** Every route a server with every group on mounts, read from its route tree. */
        val everyRoute: List<Mounted> by lazy {
            val dataDir = Files.createTempDirectory("shape-routes").toFile()
            val webDir = webRoot()
            val cfg = Config(
                bindAddr = "127.0.0.1", port = 8080, dataDir = dataDir.path, basePath = "/",
                webDir = webDir.path, logLevel = "info", authToken = OWNER_TOKEN,
                maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
                maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
                authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 100,
                setupMode = PRACTICE,
            )
            // Read while the application runs: once it stops, its route tree is gone, and asking for it
            // then installs a new, empty one.
            var app: Application? = null
            var printed: List<String> = emptyList()
            testApplication {
                application { module(cfg); app = this }
                client.get("/healthz")
                printed = app!!.routing { }.getAllRoutes().map { it.toString() }
            }
            dataDir.deleteRecursively()
            webDir.deleteRecursively()
            printed.map(::mounted)
        }

        /** A web root holding every page of the build, each distinguishable, and one script. */
        fun webRoot(): File {
            val dir = Files.createTempDirectory("shape-web").toFile()
            for ((page, marker) in MARKERS) File(dir, page).writeText("<!doctype html><html><body>$marker</body></html>")
            File(dir, "assets").mkdirs()
            File(dir, "assets/app.js").writeText("export {}")
            return dir
        }
    }
}
