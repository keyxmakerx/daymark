package com.daymark.companion

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
import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test
    fun `a phone cannot change where recovery mail goes, and can read it`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        suspend fun putByToken(email: String) = client.put("/v1/owner/notifications") {
            header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","events":[]}""")
        }
        assertEquals(HttpStatusCode.NoContent, putByToken("owner@example.org").status)

        val elsewhere = """{"email":"someone-else@example.org","events":[]}"""
        val byPhone = client.put("/v1/owner/notifications") {
            signedWith(phone.headers("PUT", "/v1/owner/notifications", elsewhere.toByteArray(), server.seconds))
            contentType(ContentType.Application.Json)
            setBody(elsewhere)
        }
        assertEquals(phoneRefused, byPhone.status to byPhone.bodyAsText())
        assertEquals("owner@example.org", server.account.registeredEmail(), "the address is as the owner set it")

        // Reading it changes nothing, and the phone may.
        val read = client.get("/v1/owner/notifications") { signedWith(phone.headers("GET", "/v1/owner/notifications", timeSeconds = server.seconds)) }
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals("owner@example.org", read.json().string("email"))

        // Control: the same change with the owner console's token is taken, so the refusal was the rule's.
        assertEquals(HttpStatusCode.NoContent, putByToken("someone-else@example.org").status)
        assertEquals("someone-else@example.org", server.account.registeredEmail())
    }
}
