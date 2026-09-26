package com.daymark.companion

import com.daymark.companion.auth.DeviceKeyStore
import com.daymark.companion.auth.DeviceSignature
import com.daymark.companion.auth.PairingCode
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pairing a phone by a code the owner console mints (#189): minted only over https, taken once, one
 * refusal for every code that is not live, a wrong guess burning nothing, a redeemed key that
 * authenticates nothing until the console confirms it, and a device list that holds no name.
 */
class PairingCodeRoutesTest {

    private val refusal = HttpStatusCode.NotFound to """{"error":"no such pairing code"}"""

    private fun rows(dataDir: File, table: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${File(dataDir, "owner-account.db").path}").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) } }
        }

    /** A canonical code that differs from [code]: its first symbol replaced by the next one, and the check symbol made right. */
    private fun anotherCode(code: String): String {
        val first = PairingCode.ALPHABET[(PairingCode.ALPHABET.indexOf(code[0]) + 1) % PairingCode.ALPHABET.length]
        val payload = first.toString() + code.substring(1, PairingCode.PAYLOAD_SYMBOLS)
        return (payload + PairingCode.checkSymbol(payload)).also {
            assertNotEquals(code, it, "the other code must differ from the code")
            assertTrue(PairingCode.isCanonical(it), "and be a code, so only its being wrong is tested")
        }
    }

    @Test
    fun `a code is minted under https and redeemed once`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val res = client.post("/v1/devices/pairing") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN") }
        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals("no-store", res.headers[HttpHeaders.CacheControl], "a code is a secret for two minutes; nothing keeps a copy")
        val body = res.json()
        val code = body.string("code")
        assertTrue(PairingCode.isCanonical(code), "ten symbols of the typed-code alphabet with the check symbol right")
        assertEquals(DeviceSignature.codeIdOf(code), body.string("codeId"), "the id is the code's digest, which the phone can compute")
        assertEquals("https://daymark.example.com", body.string("baseUrl"), "the QR carries the server's https address")
        assertEquals(server.now + DeviceKeyStore.CODE_TTL_MS, body.string("expiresAt").toLong())

        val phone = TestPhone()
        val first = server.redeem(client, phone, code)
        assertEquals(HttpStatusCode.Accepted, first.status)
        assertEquals(phone.keyId, first.json().string("keyId"))
        assertEquals(server.now + DeviceKeyStore.CONFIRM_WINDOW_MS, first.json().string("confirmBy").toLong())

        // The console sees the key that redeemed it, to show its words.
        val state = client.get("/v1/devices/pairing/${body.string("codeId")}") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN") }.json()
        assertEquals("redeemed", state.string("state"))
        assertEquals(phone.publicKeyB64, state.string("publicKey"))

        // Taken once: a second phone gets the refusal an unknown code gets.
        val second = server.redeem(client, TestPhone(), code)
        assertEquals(refusal, second.status to second.bodyAsText())
        // The same phone asking again, its answer lost, is told the same as the first time.
        assertEquals(HttpStatusCode.Accepted, server.redeem(client, phone, code).status)
        assertEquals(1, rows(server.dataDir, "pairing_redemptions"))
    }

    @Test
    fun `a used, an expired and an unknown code all get the one refusal`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val answers = mutableMapOf<String, Pair<HttpStatusCode, String>>()

        val used = server.mint(client)
        assertEquals(HttpStatusCode.Accepted, server.redeem(client, TestPhone(), used.code).status, "control: the code was live")
        server.redeem(client, TestPhone(), used.code).let { answers["used"] = it.status to it.bodyAsText() }

        val expired = server.mint(client)
        server.now += DeviceKeyStore.CODE_TTL_MS
        server.redeem(client, TestPhone(), expired.code).let { answers["expired"] = it.status to it.bodyAsText() }

        val unknown = generateSequence { PairingCode.generate() }.first { it != used.code && it != expired.code }
        server.redeem(client, TestPhone(), unknown).let { answers["unknown"] = it.status to it.bodyAsText() }

        // And the ways a redemption proves nothing, on a live code: the same answer, so none is an oracle.
        val live = server.mint(client)
        val phone = TestPhone()
        server.redeem(client, phone, live.code.lowercase()).let { answers["not a canonical code"] = it.status to it.bodyAsText() }
        server.redeem(client, phone, live.code, proof = TestPhone().redeemProof(live.code)).let { answers["another key's proof"] = it.status to it.bodyAsText() }

        assertEquals(5, answers.size)
        answers.forEach { (what, answer) -> assertEquals(refusal, answer, "$what: the one refusal") }
        // Positive control: the live code, redeemed properly, is taken — the refusals were not a dead route.
        assertEquals(HttpStatusCode.Accepted, server.redeem(client, phone, live.code).status)
    }

    @Test
    fun `a wrong guess burns nothing`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val minted = server.mint(client)
        val phone = TestPhone()
        repeat(3) {
            val wrong = server.redeem(client, phone, anotherCode(minted.code))
            assertEquals(refusal, wrong.status to wrong.bodyAsText())
        }
        assertEquals(HttpStatusCode.Accepted, server.redeem(client, phone, minted.code).status, "the right code still redeems")
    }

    @Test
    fun `under http the same request mints nothing, and redeem answers as if no code exists`() {
        val dataDir = kotlin.io.path.createTempDirectory("device-http").toFile()
        val https = DeviceServer(dataDir = dataDir)
        lateinit var live: DeviceServer.Minted
        testApplication {
            https.start(this)
            live = https.mint(client)
        }
        https.account.close()
        val minted = rows(dataDir, "pairing_codes")

        for (address in listOf("http://daymark.example.com", null)) {
            val http = DeviceServer(dataDir = dataDir, now = https.now, publicBaseUrl = address)
            testApplication {
                http.start(this)
                val mint = client.post("/v1/devices/pairing") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN") }
                assertEquals(HttpStatusCode.Conflict to """{"error":"pairing needs an https address"}""", mint.status to mint.bodyAsText(), "$address")
                assertEquals(minted, rows(dataDir, "pairing_codes"), "$address: nothing was minted")

                // A code minted over https, still within its two minutes, as if it never existed.
                val redeem = http.redeem(client, TestPhone(), live.code)
                assertEquals(refusal, redeem.status to redeem.bodyAsText(), "$address: redeem")
                val state = client.get("/v1/devices/pairing/${live.codeId}") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN") }
                assertEquals(refusal, state.status to state.bodyAsText(), "$address: the console's read")
            }
            http.account.close()
        }

        // Control: the same code, on the same volume, is live again once the address is https.
        val again = DeviceServer(dataDir = dataDir, now = https.now)
        testApplication {
            again.start(this)
            assertEquals(HttpStatusCode.Accepted, again.redeem(client, TestPhone(), live.code).status, "the code was live all along")
        }
    }

    @Test
    fun `a redeemed key authenticates nothing until the console confirms it`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val minted = server.mint(client)
        val phone = TestPhone()
        assertEquals(HttpStatusCode.Accepted, server.redeem(client, phone, minted.code).status)

        val pending = server.signedGet(client, phone, "/v1/snapshots")
        assertEquals(HttpStatusCode.Unauthorized to """{"error":"unauthorized"}""", pending.status to pending.bodyAsText(), "pending: refused")
        val poll = server.signedGet(client, phone, "/v1/devices/registration")
        assertEquals(HttpStatusCode.Accepted, poll.status, "the poll tells the key it waits, and nothing else")
        assertEquals("pending", poll.json().string("state"))
        // It cannot confirm itself.
        val selfConfirm = client.post("/v1/devices/pairing/${minted.codeId}/confirm") {
            val body = """{"keyId":"${phone.keyId}"}"""
            signedWith(phone.headers("POST", "/v1/devices/pairing/${minted.codeId}/confirm", body.toByteArray(), server.seconds))
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized, selfConfirm.status)

        // Positive control: the same key, confirmed, is the owner.
        assertEquals(HttpStatusCode.Created, server.confirm(client, minted.codeId, phone.keyId).status)
        assertEquals(HttpStatusCode.OK, server.signedGet(client, phone, "/v1/snapshots").status)
        val registered = server.signedGet(client, phone, "/v1/devices/registration")
        assertEquals(HttpStatusCode.OK to "registered", registered.status to registered.json().string("state"))
    }

    @Test
    fun `a key nobody confirms lapses`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val minted = server.mint(client)
        val phone = TestPhone()
        assertEquals(HttpStatusCode.Accepted, server.redeem(client, phone, minted.code).status)
        server.now += DeviceKeyStore.CONFIRM_WINDOW_MS
        assertEquals(HttpStatusCode.Unauthorized, server.signedGet(client, phone, "/v1/devices/registration").status)
        val late = server.confirm(client, minted.codeId, phone.keyId)
        assertEquals(refusal, late.status to late.bodyAsText())
        assertNull(server.account.devices.registeredKey(phone.keyId), "nothing lasting was written")
    }

    @Test
    fun `only the owner console's confirmation registers a key`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val paired = TestPhone()
        server.pair(client, paired)

        val minted = server.mint(client)
        val phone = TestPhone()
        assertEquals(HttpStatusCode.Accepted, server.redeem(client, phone, minted.code).status)

        // A registered phone may not add another.
        val target = "/v1/devices/pairing/${minted.codeId}/confirm"
        val body = """{"keyId":"${phone.keyId}"}"""
        val byDevice = client.post(target) {
            signedWith(paired.headers("POST", target, body.toByteArray(), server.seconds))
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Forbidden to """{"error":"a paired phone cannot do this"}""", byDevice.status to byDevice.bodyAsText())
        assertNull(server.account.devices.registeredKey(phone.keyId), "a device's confirmation wrote nothing")

        // Naming another key than the one that redeemed confirms nothing.
        val wrongKey = server.confirm(client, minted.codeId, TestPhone().keyId)
        assertEquals(refusal, wrongKey.status to wrongKey.bodyAsText())

        val confirmed = server.confirm(client, minted.codeId, phone.keyId)
        assertEquals(HttpStatusCode.Created, confirmed.status)
        assertEquals(server.now, confirmed.json().string("pairedAt").toLong())
        assertNotNull(server.account.devices.registeredKey(phone.keyId), "the console's confirmation wrote the key's row")
        // A second press answers as the first and writes nothing more.
        assertEquals(HttpStatusCode.OK, server.confirm(client, minted.codeId, phone.keyId).status)
        assertEquals(listOf("device.registered", "device.registered"), server.ownerLog(), "one row for each of the two keys, none for the second press")
    }

    @Test
    fun `the device list shows the pairing date and the key, and no name`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phones = listOf(TestPhone(), TestPhone())
        val pairedAt = phones.map { phone -> server.pair(client, phone); server.now.also { server.now += 1_000 } }

        val list = client.get("/v1/devices") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN") }
        assertEquals(HttpStatusCode.OK, list.status)
        val devices = list.json().getValue("devices").jsonArray.map { it.jsonObject }
        assertEquals(phones.map { it.keyId }, devices.map { it.string("keyId") })
        assertEquals(phones.map { it.publicKeyB64 }, devices.map { it.string("publicKey") }, "the key, from which the console shows the words")
        assertEquals(pairedAt, devices.map { it.string("pairedAt").toLong() })

        val allowed = setOf("keyId", "publicKey", "pairedAt", "revokedAt")
        fun unexpected(entry: Map<String, Any?>) = entry.keys - allowed
        devices.forEach { assertEquals(emptySet(), unexpected(it), "a device carries its date and key and nothing else") }
        // Positive control: the check sees a field it does not allow.
        assertEquals(setOf("name"), unexpected(devices.first() + ("name" to "planted")))

        // A phone reads no list: the console manages devices.
        assertEquals(HttpStatusCode.Forbidden, server.signedGet(client, phones.first(), "/v1/devices").status)
    }

    @Test
    fun `revoking is written at once, and once`() = testApplication {
        val server = DeviceServer()
        server.start(this)
        val phone = TestPhone()
        server.pair(client, phone)
        server.now += 5_000

        suspend fun revoke(keyId: String) = client.post("/v1/devices/$keyId/revoke") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN") }
        assertEquals(HttpStatusCode.NoContent, revoke(phone.keyId).status)
        val listed = client.get("/v1/devices") { header(HttpHeaders.Authorization, "Bearer $DEVICE_TEST_TOKEN") }
            .json().getValue("devices").jsonArray.single().jsonObject
        assertEquals(server.now, listed.string("revokedAt").toLong(), "the list says so at once")

        assertEquals(HttpStatusCode.NoContent, revoke(phone.keyId).status, "a second Revoke changes nothing")
        assertEquals(listOf("device.registered", "device.revoked:owner"), server.ownerLog(), "one row for the registration, one for the revocation")
        val unknown = revoke(TestPhone().keyId)
        assertEquals(HttpStatusCode.NotFound to """{"error":"no such device"}""", unknown.status to unknown.bodyAsText())
    }
}

