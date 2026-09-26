package com.daymark.companion

import com.daymark.companion.mail.InMemoryMailTransport
import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.MailerConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A paired phone is the owner's journal device, not the server operator's console (#186, #189): it is
 * kept off the routes that provision or administer the server, or govern how a credential is
 * re-issued. Each decided route has its own test here, so dropping it from the one list
 * (`PHONE_REFUSED_ROUTES`) turns exactly its test red; DeviceKeyRevocationTest's walk holds every
 * route on the list to the same 403 and every other owner route to taking the phone.
 */
class PhoneRouteRuleTest {

    private val phoneRefused = HttpStatusCode.Forbidden to """{"error":"a paired phone cannot do this"}"""

    private fun practices(dataDir: File): Int =
        DriverManager.getConnection("jdbc:sqlite:${File(dataDir, "org.db").path}").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM orgs").use { rs -> rs.next(); rs.getInt(1) } }
        }

    @Test
    fun `a phone cannot create a practice`() = testApplication {
        val server = DeviceServer(mode = SetupMode.PRACTICE)
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val body = """{"name":"A practice","adminMemberId":"member-1"}"""

        val byPhone = client.post("/v1/orgs") {
            signedWith(phone.headers("POST", "/v1/orgs", body.toByteArray(), server.seconds))
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(phoneRefused, byPhone.status to byPhone.bodyAsText())
        assertEquals(0, practices(server.dataDir), "the phone created no practice")

        // Control: the same request with the operator's token creates one, so the refusal was the rule's.
        val byToken = client.post("/v1/orgs") {
            header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, byToken.status, byToken.bodyAsText())
        assertEquals(1, practices(server.dataDir))
    }

    /** The server's mail, kept: what the recovery flow sent, and to whom. */
    private fun keptMail(): Pair<Mailer, InMemoryMailTransport> {
        val transport = InMemoryMailTransport()
        val cfg = MailerConfig(host = "mail.example.org", port = 587, user = null, pass = null, from = "companion@example.org", tls = MailerConfig.TlsMode.STARTTLS)
        return Mailer.forConfig(cfg, transport) to transport
    }

    /** The recovery mail is sent in the background: wait for [count] messages, then a moment for any stray one. */
    private suspend fun awaitSent(transport: InMemoryMailTransport, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (transport.sent.size < count && System.currentTimeMillis() < deadline) delay(20)
        delay(200)
    }

    @Test
    fun `a stolen phone's key cannot change where recovery mail goes, so it cannot have the console's token re-issued`() = testApplication {
        val (mailer, sent) = keptMail()
        val server = DeviceServer(mailer = mailer)
        server.start(this)
        val stolen = TestPhone()
        val other = TestPhone()
        server.pair(client, stolen)
        server.pair(client, other)
        suspend fun putByToken(email: String) = client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","events":[]}""")
        }
        suspend fun askForRecovery(email: String) = client.post("/v1/recovery/request") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email"}""")
        }
        assertEquals(HttpStatusCode.NoContent, putByToken("owner@example.org").status)

        // Whoever holds the stolen phone's key sets the address to theirs, and asks for a re-issue there.
        val theirs = """{"email":"thief@example.org","events":[]}"""
        val byPhone = client.put("/v1/owner/notifications") {
            signedWith(stolen.headers("PUT", "/v1/owner/notifications", theirs.toByteArray(), server.seconds))
            contentType(ContentType.Application.Json)
            setBody(theirs)
        }
        assertEquals(phoneRefused, byPhone.status to byPhone.bodyAsText())
        assertEquals("owner@example.org", server.account.registeredEmail(), "the address is as the owner set it")
        assertEquals(HttpStatusCode.Accepted, askForRecovery("thief@example.org").status, "the recovery flow answers every request the same")

        // Control: the owner's own request is mailed, to the owner, so the flow was live and a link to
        // the thief would have been seen here.
        assertEquals(HttpStatusCode.Accepted, askForRecovery("owner@example.org").status)
        awaitSent(sent, 1)
        assertEquals(listOf("owner@example.org"), sent.sent.map { it.to }, "one link, and it went to the owner")

        // The console's token and the owner's other phone are as they were.
        val devices = client.get("/v1/devices") { header(HttpHeaders.Authorization, "Bearer ${server.authToken}") }
        assertEquals(HttpStatusCode.OK, devices.status)
        assertEquals(HttpStatusCode.OK, server.signedGet(client, other, "/v1/snapshots").status)

        // Control: the same change with the owner console's token is taken, so the refusal was the rule's.
        assertEquals(HttpStatusCode.NoContent, putByToken("someone-else@example.org").status)
        assertEquals("someone-else@example.org", server.account.registeredEmail())
    }

    @Test
    fun `a phone cannot read the owner's notification settings`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val set = client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"owner@example.org","events":[]}""")
        }
        assertEquals(HttpStatusCode.NoContent, set.status)

        val byPhone = client.get("/v1/owner/notifications") { signedWith(phone.headers("GET", "/v1/owner/notifications", timeSeconds = server.seconds)) }
        assertEquals(phoneRefused, byPhone.status to byPhone.bodyAsText())
        assertFalse("owner@example.org" in byPhone.bodyAsText(), "the answer names no address")

        // Control: the owner console reads them with its token, so the address was there to be read.
        val byToken = client.get("/v1/owner/notifications") { header(HttpHeaders.Authorization, "Bearer ${server.authToken}") }
        assertEquals(HttpStatusCode.OK, byToken.status)
        assertEquals("owner@example.org", byToken.json().string("email"))
    }
}
