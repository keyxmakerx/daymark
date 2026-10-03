package com.daymark.companion

import com.daymark.companion.auth.DeviceSignature
import com.daymark.companion.auth.Secrets
import com.daymark.companion.routes.KEY_DOCUMENT_HEADER
import com.daymark.companion.routes.KEY_DOCUMENT_KEYPARAMS
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.sql.DriverManager
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The request headers an owner route acts on are covered by the phone's signature (#186), so a person
 * on the path who changes one, adds one or takes one away has changed a request the phone signed, and
 * it is refused as any other change is. SignedHeaderCoverageTest holds the signed list to every header
 * the server reads.
 */
class SignedHeaderTest {

    private val unauthorized = HttpStatusCode.Unauthorized to """{"error":"unauthorized"}"""
    private val day = 24L * 60 * 60 * 1000

    private suspend fun HttpClient.keyDocumentKind(server: DeviceServer): String? =
        get("/v1/keydoc") { header(HttpHeaders.Authorization, "Bearer ${server.authToken}") }.headers[KEY_DOCUMENT_HEADER]

    /**
     * A phone may not write the key documents at all (PhoneRouteRuleTest). The signature holds the
     * precondition headers regardless, so a swapped one is refused before that rule is reached.
     */
    @Test
    fun `a first run's precondition swapped on the path for an enrolment's is refused, and nothing is written`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val document = wrappedKey("PHONE")
        val firstRun = mapOf(HttpHeaders.IfNoneMatch to "*")

        // The phone's first run, signed when it found no key document, held on the path.
        val held = phone.headers("POST", "/v1/keydoc", document, server.seconds, signed = firstRun)

        // Meanwhile the owner console publishes key parameters: the state the held request names is gone.
        val published = client.put("/v1/keyparams") {
            header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
            setBody(keyParams("CONSOLE"))
        }
        assertEquals(HttpStatusCode.NoContent, published.status)
        val etag = client.get("/v1/keyparams") { header(HttpHeaders.Authorization, "Bearer ${server.authToken}") }.headers[HttpHeaders.ETag]
        assertEquals(KEY_DOCUMENT_KEYPARAMS, client.keyDocumentKind(server))

        // The attack: the held request with its If-None-Match swapped for an If-Match naming the key
        // parameters, which would make the phone's fresh master an enrolment beside the one they wrap.
        val swapped = held - HttpHeaders.IfNoneMatch + (HttpHeaders.IfMatch to etag!!)
        val attack = client.post("/v1/keydoc") { signedWith(swapped); setBody(document) }
        assertEquals(unauthorized, attack.status to attack.bodyAsText())
        assertEquals(KEY_DOCUMENT_KEYPARAMS, client.keyDocumentKind(server), "no wrapped key was written")

        // Controls. The held signature is the phone's over the request as it was signed, and not over the
        // swapped one, so the 401 is the swap's; and the phone's key is live.
        fun signedOver(carrying: Map<String, String>) = DeviceSignature.requestMessage(
            "POST", "/v1/keydoc", DeviceSignature.bodyHash(document),
            held.getValue(DeviceSignature.TIME_HEADER), held.getValue(DeviceSignature.NONCE_HEADER),
            assertNotNull(DeviceSignature.signedHeaderValues { name -> carrying.filterKeys { it.equals(name, ignoreCase = true) }.values.toList() }),
        )
        val signature = assertNotNull(DeviceSignature.decodeCanonical(held.getValue(DeviceSignature.SIGNATURE_HEADER), DeviceSignature.SIGNATURE_BYTES))
        assertTrue(DeviceSignature.verify(phone.publicKey, signedOver(firstRun), signature), "the held request, as signed")
        assertFalse(DeviceSignature.verify(phone.publicKey, signedOver(mapOf(HttpHeaders.IfMatch to etag)), signature), "the swapped one")
        assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/keydoc").status)
        // And the state the swap names is one a writer reaches: the console's enrolment with that If-Match is taken.
        val enrolment = client.post("/v1/keydoc") {
            header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
            header(HttpHeaders.IfMatch, etag)
            setBody(document)
        }
        assertEquals(HttpStatusCode.Created, enrolment.status, enrolment.bodyAsText())
    }

    private fun shareMeta(expiry: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("""{"shareId":"s","version":1,"expiry":$expiry,"ownerSigningFp":"fp"}""".toByteArray())

    /** The end stored for each version of [lineage] on the shares channel, oldest first. */
    private fun storedEnds(server: DeviceServer, lineage: String): List<Long> =
        DriverManager.getConnection("jdbc:sqlite:${File(server.dataDir, "rel-index.db").path}").use { c ->
            c.prepareStatement("SELECT expiry FROM rel_blobs WHERE channel='shares' AND lineage=? ORDER BY version").use { ps ->
                ps.setString(1, lineage)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }
        }

    @Test
    fun `a share's end changed on the path is refused, and nothing is stored`() = testApplication {
        val server = DeviceServer(mode = SetupMode.PAIRED)
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        val inbox = "inbox-token-signed-headers-0123456789"
        val target = "/v1/rel/${Secrets.relRefOf(inbox)}/shares/journal/1"
        val sealed = byteArrayOf(7, 7, 7)
        // The relationship surface judges a share's end by the real clock.
        val chosen = System.currentTimeMillis() + 7 * day
        fun share(end: Long) = mapOf("X-Rel-Token" to inbox, "X-Share-Meta" to shareMeta(end))

        // The attack: the phone's share, its end moved from a week to eighty-nine days on the path.
        val held = phone.headers("PUT", target, sealed, server.seconds, signed = share(chosen))
        val later = held + ("X-Share-Meta" to shareMeta(chosen + 82 * day))
        val attack = client.put(target) { signedWith(later); setBody(sealed) }
        assertEquals(unauthorized, attack.status to attack.bodyAsText())
        assertEquals(emptyList(), storedEnds(server, "journal"), "nothing was stored")

        // Control: the same share as the phone signs it is taken, with the end the phone chose.
        val asSigned = client.put(target) {
            signedWith(phone.headers("PUT", target, sealed, server.seconds, signed = share(chosen)))
            setBody(sealed)
        }
        assertEquals(HttpStatusCode.Created, asSigned.status, asSigned.bodyAsText())
        assertEquals(listOf(chosen), storedEnds(server, "journal"))
    }

    @Test
    fun `a signed header changed, added, taken away, carried twice or carried empty is refused`() {
        val server = DeviceServer()
        server.startNetty().use { live ->
            val phone = TestPhone()
            live.pair(phone)
            val target = "/v1/snapshots"
            val refused = RawResponse(401, """{"error":"unauthorized"}""")
            // Each request signed afresh: a refused one has spent its nonce.
            fun signed(carrying: Map<String, String> = emptyMap()) = phone.headers("GET", target, timeSeconds = server.seconds, signed = carrying)
            for (name in DeviceSignature.SIGNED_HEADERS) {
                val value = "value-of-$name"
                val cases = mapOf(
                    "changed" to signed(mapOf(name to value)) + (name to "$value-changed"),
                    "taken away" to signed(mapOf(name to value)) - name,
                    "added" to signed() + (name to value),
                    // Two lines, told apart only by the case of the name, which HTTP ignores.
                    "carried twice" to signed(mapOf(name to value)) + (name.lowercase() to value),
                    // Its line reads as it does when the header is not carried at all: only the rule refuses it.
                    "carried empty" to signed() + (name to ""),
                )
                for ((what, headers) in cases) assertEquals(refused, live.send("GET", target, headers), "$name $what")
                // Control: carried as it was signed, it is taken.
                assertEquals(200, live.send("GET", target, signed(mapOf(name to value))).status, "$name as signed")
            }
        }
    }
}
