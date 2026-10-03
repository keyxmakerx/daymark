package com.daymark.companion

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.daymark.companion.admin.AdminStore
import com.daymark.companion.admin.SetupCode
import com.daymark.companion.auth.Totp
import com.daymark.companion.routes.SERVER_AUDIT_REF
import com.daymark.companion.storage.AuditStore
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Claiming a new server with the one-time code from its log, and the administrator's sign-in (#322).
 *
 * Every test reads the code the way an operator does: from the log line the start printed. Nothing
 * here reaches into [SetupCode] for it, so a start that stopped printing the code, or printed one the
 * claim does not accept, fails here.
 */
class AdminClaimTest {

    private val logger = LoggerFactory.getLogger("com.daymark.companion") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeTest
    fun attach() {
        appender.context = logger.loggerContext
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private val ownerToken = "owner-token-for-the-admin-claim-test"

    /** The test's clock, shared by the store and the code, starting on a step boundary. */
    private var now = 1_800_000_000_000L - (1_800_000_000_000L % 30_000L)

    private fun config(dir: String, reset: Boolean = false) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        setupMode = SetupMode.SOLO, cookieSecure = false, adminReset = reset,
        totpLockoutFails = 3, totpLockoutSeconds = 300L,
    )

    private val seed = ByteArray(20) { (it * 7 + 3).toByte() }
    private val seedB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(seed)
    private fun totp(atMs: Long = now) = Totp.code(seed, atMs / 1000)

    /** A six-digit code no step in the verifier's window accepts at [atMs]. */
    private fun wrongTotp(atMs: Long = now): String {
        val plausible = (-1L..1L).map { Totp.code(seed, atMs / 1000 + it * 30) }.toSet()
        return generateSequence(0) { it + 1 }.map { it.toString().padStart(6, '0') }.first { it !in plausible }
    }

    private val codeLine = Regex("""Setup code for this server: ([A-Z0-9-]+) \.""")

    private fun printedCodes(): List<String> =
        appender.list.mapNotNull { codeLine.find(it.formattedMessage)?.groupValues?.get(1) }

    private class Server(val dir: String, val admins: AdminStore, val audit: AuditStore, val code: SetupCode)

    private fun server(dir: String = Files.createTempDirectory("admin-claim").toString()) = Server(
        dir,
        AdminStore(dir) { now },
        AuditStore(dir, dbName = ADMIN_AUDIT_DB),
        SetupCode(clock = { now }),
    )

    /** Starts the server now, so its start-up log lines exist before the test reads them. */
    private suspend fun ApplicationTestBuilder.start(s: Server, reset: Boolean = false) {
        application {
            module(config(s.dir, reset), adminStore = s.admins, adminAuditStore = s.audit, setupCode = s.code)
        }
        startApplication()
    }

    private suspend fun HttpClient.claim(code: String, name: String = "Sam", totpCode: String = totp()): HttpResponse =
        post("/v1/admin/claim") {
            contentType(ContentType.Application.Json)
            setBody("""{"setupCode":"$code","name":"$name","totpSecret":"$seedB64","totpCode":"$totpCode"}""")
        }

    private suspend fun HttpClient.signIn(name: String, code: String): HttpResponse =
        post("/v1/admin/session") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","code":"$code"}""")
        }

    private fun cookieOf(res: HttpResponse): String =
        res.headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith("daymark_admin=") }.substringBefore(';')

    private fun csrfOf(res: HttpResponse, body: String): String =
        Json.parseToJsonElement(body).jsonObject.getValue("csrfToken").jsonPrimitive.content

    // ---- What the log says ----------------------------------------------------------------

    @Test
    fun `an unclaimed server prints one code at WARN, carrying nothing else it holds`() {
        val s = server()
        testApplication {
            start(s)
            repeat(3) { client.get("/v1/admin/status") }
        }
        val lines = appender.list.filter { codeLine.containsMatchIn(it.formattedMessage) }
        assertEquals(1, lines.size, "one code per start, not per request")
        val line = lines.single()
        assertEquals(Level.WARN, line.level)
        assertFalse(ownerToken in line.formattedMessage, "no other credential in the line")
        assertFalse(s.dir in line.formattedMessage, "no path in the line")
        val code = printedCodes().single()
        // 30 symbols in six groups: comfortably over 128 bits from a 30-symbol alphabet.
        assertEquals(30, SetupCode.normalise(code).length)
        assertTrue(SetupCode.normalise(code).all { it in SetupCode.ALPHABET })
    }

    @Test
    fun `a claimed server prints no code`() {
        val s = server()
        assertEquals(AdminStore.ClaimStatus.OK, s.admins.claim("Sam", seedB64, 0L, allowWhenClaimed = false).status)
        testApplication {
            start(s)
            assertEquals(
                """{"claimed":true,"claimOpen":false}""",
                client.get("/v1/admin/status").bodyAsText(),
            )
        }
        assertEquals(emptyList(), printedCodes())
    }

    // ---- The claim ----------------------------------------------------------------------------

    @Test
    fun `the right code makes exactly one administrator, and then stops working`() {
        val s = server()
        testApplication {
            start(s)
            val code = printedCodes().single()
            assertEquals("""{"claimed":false,"claimOpen":true}""", client.get("/v1/admin/status").bodyAsText())

            val first = client.claim(code)
            assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
            assertEquals(1, s.admins.adminCount())
            assertTrue(cookieOf(first).length > "daymark_admin=".length)
            val setCookie = first.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("daymark_admin=") }
            assertTrue("HttpOnly" in setCookie && "SameSite=Strict" in setCookie, setCookie)

            // The same code, again, a different name: refused, and nothing more is made.
            val again = client.claim(code, name = "Other")
            assertEquals(HttpStatusCode.Unauthorized, again.status)
            assertEquals(1, s.admins.adminCount())
            assertEquals("""{"claimed":true,"claimOpen":false}""", client.get("/v1/admin/status").bodyAsText())

            // The response never echoes the code or the seed.
            val body = first.bodyAsText()
            assertFalse(SetupCode.normalise(code) in SetupCode.normalise(body))
            assertFalse(seedB64 in body)
        }
        // One claim row, carrying the random id and not the name.
        val rows = s.audit.list(SERVER_AUDIT_REF, limit = 10)
        assertEquals(listOf("server.claimed"), rows.map { it.action })
        assertFalse(rows.single().meta.orEmpty().values.any { "Sam" in it })
    }

    @Test
    fun `a wrong code is refused and locks nobody out`() {
        val s = server()
        testApplication {
            start(s)
            val code = printedCodes().single()
            val wrong = code.map { if (it == '-') it else if (it == 'A') 'B' else 'A' }.joinToString("")
            assertNotEquals(SetupCode.normalise(code), SetupCode.normalise(wrong))
            repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.claim(wrong).status) }
            assertEquals(0, s.admins.adminCount())
            // Typed loosely, the right code still works after the wrong ones.
            val loose = code.lowercase().replace("-", " ")
            assertEquals(HttpStatusCode.OK, client.claim(loose).status)
            assertEquals(1, s.admins.adminCount())
        }
    }

    @Test
    fun `an expired code is refused, and its replacement is printed and works`() {
        val s = server()
        testApplication {
            start(s)
            val first = printedCodes().single()
            now += SetupCode.DEFAULT_TTL_MS
            assertEquals(HttpStatusCode.Unauthorized, client.claim(first, totpCode = totp()).status)
            assertEquals(0, s.admins.adminCount())
            // What the housekeeping job does each minute.
            val replacement = assertNotNull(s.code.rotateIfExpired())
            printSetupCode(replacement)
            assertEquals(2, printedCodes().size)
            assertEquals(HttpStatusCode.Unauthorized, client.claim(first).status)
            assertEquals(HttpStatusCode.OK, client.claim(replacement.code).status)
        }
    }

    @Test
    fun `wrong six digits keep the code alive`() {
        val s = server()
        testApplication {
            start(s)
            val code = printedCodes().single()
            val res = client.claim(code, totpCode = wrongTotp())
            assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
            assertEquals(0, s.admins.adminCount())
            assertEquals(HttpStatusCode.OK, client.claim(code).status)
        }
    }

    @Test
    fun `a name the sign-in cannot carry is refused before the code is spent`() {
        val s = server()
        testApplication {
            start(s)
            val code = printedCodes().single()
            assertEquals(HttpStatusCode.BadRequest, client.claim(code, name = "Sam<script>").status)
            assertEquals(HttpStatusCode.OK, client.claim(code).status)
        }
    }

    // ---- Signing in, and what is behind it ---------------------------------------------------

    @Test
    fun `every administrator route refuses no session, and refuses the owner's token`() {
        val s = server()
        testApplication {
            start(s)
            val claimed = client.claim(printedCodes().single())
            val cookie = cookieOf(claimed)
            val csrf = csrfOf(claimed, claimed.bodyAsText())

            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/overview").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/v1/admin/overview") { header(HttpHeaders.Authorization, "Bearer $ownerToken") }.status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post("/v1/admin/session/logout") {
                    header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    header("X-CSRF-Token", csrf)
                }.status,
            )
            // The positive control: the administrator's own session opens the overview, which says
            // who is signed in, the shape and a count, and nothing else but this session's own
            // anti-CSRF token, so a reloaded console can still sign out.
            val overview = client.get("/v1/admin/overview") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, overview.status)
            assertEquals("""{"name":"Sam","setupMode":"solo","administrators":1,"csrfToken":"$csrf"}""", overview.bodyAsText())

            // Signing out needs the anti-CSRF token, and then the session is gone.
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/admin/session/logout") { header(HttpHeaders.Cookie, cookie) }.status)
            assertEquals(
                HttpStatusCode.NoContent,
                client.post("/v1/admin/session/logout") {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                }.status,
            )
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/overview") { header(HttpHeaders.Cookie, cookie) }.status)
        }
    }

    @Test
    fun `sign-in takes the name and a fresh code, once, and pauses after repeated wrong ones`() {
        val s = server()
        testApplication {
            start(s)
            client.claim(printedCodes().single())
            // The claim's own code is spent: it does not sign in again.
            assertEquals(HttpStatusCode.Unauthorized, client.signIn("Sam", totp()).status)
            now += 30_000
            val fresh = totp()
            val ok = client.signIn("Sam", fresh)
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/admin/overview") { header(HttpHeaders.Cookie, cookieOf(ok)) }.status)
            // Replayed, the same code is refused.
            assertEquals(HttpStatusCode.Unauthorized, client.signIn("Sam", fresh).status)
            // An unknown name answers exactly as a wrong code does.
            val unknown = client.signIn("Nobody", totp())
            assertEquals(HttpStatusCode.Unauthorized, unknown.status)
            now += 30_000
            assertEquals(unknown.bodyAsText(), client.signIn("Sam", wrongTotp()).bodyAsText())
            // The replay, the wrong code and one more make three in a row, which pauses this name.
            // A paused name answers exactly as an unknown one, so pausing tells a stranger nothing.
            val arming = client.signIn("Sam", wrongTotp())
            assertEquals(HttpStatusCode.Unauthorized, arming.status)
            assertEquals(unknown.bodyAsText(), arming.bodyAsText())
            val paused = client.signIn("Sam", totp())
            assertEquals(HttpStatusCode.Unauthorized, paused.status, "the right code waits the pause out")
            assertEquals(unknown.bodyAsText(), paused.bodyAsText())
            now += 300_000
            assertEquals(HttpStatusCode.OK, client.signIn("Sam", totp()).status)
        }
        // One lockout row, on arming; the probe that bounced off it wrote none.
        assertEquals(1, s.audit.list(SERVER_AUDIT_REF, limit = 50).count { it.action == "lockout" })
    }

    @Test
    fun `claim and sign-in attempts are metered per address, and a wrong code still locks nobody out`() {
        val s = server()
        testApplication {
            start(s)
            val code = printedCodes().single()
            val wrong = code.replaceFirstChar { if (it == 'A') 'B' else 'A' }
            assertNotEquals(code, wrong)
            repeat(10) { assertEquals(HttpStatusCode.Unauthorized, client.claim(wrong).status, "attempt ${it + 1} is answered") }
            assertEquals(HttpStatusCode.TooManyRequests, client.claim(wrong).status, "the eleventh waits")
            assertEquals(HttpStatusCode.TooManyRequests, client.claim(code).status, "even with the right code, from here, for now")
            // The budget refills; the code itself was never touched by the wrong ones.
            Thread.sleep(6_500)
            assertEquals(HttpStatusCode.OK, client.claim(code).status)

            now += 30_000
            repeat(10) { client.signIn("Nobody", totp()) }
            assertEquals(HttpStatusCode.TooManyRequests, client.signIn("Nobody", totp()).status)
        }
    }

    @Test
    fun `a state-changing request needs this session's own anti-CSRF token, not just any`() {
        val s = server()
        testApplication {
            start(s)
            val claimed = client.claim(printedCodes().single())
            val cookie = cookieOf(claimed)
            val csrf = csrfOf(claimed, claimed.bodyAsText())
            val forged = csrf.replaceFirstChar { if (it == 'A') 'B' else 'A' }
            assertNotEquals(csrf, forged)
            val refused = client.post("/v1/admin/session/logout") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", forged)
            }
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
            // Control: the session survived the forged attempt, and its own token works.
            assertEquals(HttpStatusCode.OK, client.get("/v1/admin/overview") { header(HttpHeaders.Cookie, cookie) }.status)
            assertEquals(
                HttpStatusCode.NoContent,
                client.post("/v1/admin/session/logout") {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                }.status,
            )
        }
    }

    @Test
    fun `a session ends after its idle time and at its absolute expiry`() {
        val s = server()
        testApplication {
            start(s)
            val idle = cookieOf(client.claim(printedCodes().single()))
            now += 899_000
            assertEquals(HttpStatusCode.OK, client.get("/v1/admin/overview") { header(HttpHeaders.Cookie, idle) }.status, "control: in time")
            now += 901_000
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/overview") { header(HttpHeaders.Cookie, idle) }.status)

            now += 30_000
            val busy = cookieOf(client.signIn("Sam", totp()))
            // Used every ten minutes, it still ends eight hours after sign-in.
            repeat(47) {
                now += 600_000
                assertEquals(HttpStatusCode.OK, client.get("/v1/admin/overview") { header(HttpHeaders.Cookie, busy) }.status)
            }
            now += 600_000
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/overview") { header(HttpHeaders.Cookie, busy) }.status)
        }
    }

    // ---- When every administrator is lost ------------------------------------------------------

    @Test
    fun `the reset setting prints a code on a claimed server, writes one row, and makes one more administrator`() {
        val s = server()
        assertEquals(AdminStore.ClaimStatus.OK, s.admins.claim("Lost", seedB64, 0L, allowWhenClaimed = false).status)
        testApplication {
            start(s, reset = true)
            val code = printedCodes().single()
            assertEquals(HttpStatusCode.OK, client.claim(code, name = "Found").status)
            assertEquals(2, s.admins.adminCount())
            assertEquals(HttpStatusCode.Unauthorized, client.claim(code, name = "Third").status)
        }
        assertEquals(
            listOf("server.claimed", "server.setup_code_reissued"),
            s.audit.list(SERVER_AUDIT_REF, limit = 10).map { it.action },
        )
    }

    @Test
    fun `without the reset setting, a claimed server makes no administrator whatever code is shown`() {
        val s = server()
        assertEquals(AdminStore.ClaimStatus.OK, s.admins.claim("Sam", seedB64, 0L, allowWhenClaimed = false).status)
        testApplication {
            start(s)
            // No code was printed; a code minted by hand stands in for a stolen one.
            val minted = s.code.mint()
            val res = client.claim(minted.code, name = "Intruder")
            assertEquals(HttpStatusCode.Conflict, res.status)
            assertEquals(1, s.admins.adminCount())
        }
    }

    @Test
    fun `a data directory that cannot hold the administrators' database leaves the server up and the console off`() = testApplication {
        // A directory where the database file should be: it cannot be opened, as on a /data
        // mounted read-only, and the permission bits play no part, so this runs as root too.
        val dir = Files.createTempDirectory("admin-claim")
        Files.createDirectory(dir.resolve("admin.db"))
        application { module(config(dir.toString()).copy(authToken = null)) }
        startApplication()
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        assertFalse("claimOpen" in client.get("/v1/admin/status").bodyAsText(), "the console answered")
        assertTrue(printedCodes().isEmpty(), "no setup code is printed for a console that is off")
        assertTrue(appender.list.any { it.level == Level.ERROR && "administrators' database" in it.formattedMessage })
    }

    @Test
    fun `the same start in a usable directory serves the console`() = testApplication {
        // The control for the test above: what is off there is on here.
        application { module(config(Files.createTempDirectory("admin-claim").toString()).copy(authToken = null)) }
        startApplication()
        assertTrue("claimOpen" in client.get("/v1/admin/status").bodyAsText())
        assertEquals(1, printedCodes().size)
    }
}
