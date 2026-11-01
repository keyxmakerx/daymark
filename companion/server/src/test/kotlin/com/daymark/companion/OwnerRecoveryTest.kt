package com.daymark.companion

import com.daymark.companion.auth.OwnerSignIn
import com.daymark.companion.auth.Totp
import com.daymark.companion.mail.InMemoryMailTransport
import com.daymark.companion.mail.MailTemplates
import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.MailerConfig
import com.daymark.companion.routes.NO_ADDRESS_MESSAGE
import com.daymark.companion.routes.RECOVERY_REFUSED
import io.ktor.client.HttpClient
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Getting back in by the owner's own key (#325). The test plays the console: it makes the account
 * with a recovery public key, as the console does with the key it derives from the master, and then
 * signs challenges with the matching private key, or with a key that is not it.
 *
 * Every refusal is checked to be the one sentence, and each check that makes it is broken on its own:
 * a replay, an expired challenge, a challenge signed for another owner or another address, and a
 * signature by the owner's share-signing key.
 */
class OwnerRecoveryTest {

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** An Ed25519 pair, as the console derives one: [public] is what the server is given. */
    private class Pair25519 {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val public = ByteArray(32).also { Ed25519.generatePublicKey(secret, 0, it, 0) }
        fun sign(message: ByteArray): ByteArray = ByteArray(64).also { Ed25519.sign(secret, 0, message, 0, message.size, it, 0) }
    }

    private val recovery = Pair25519()
    /** The owner's other signing key: the one that signs shares and grants. Never a recovery key. */
    private val shareSigning = Pair25519()

    private val oldSeed = ByteArray(20) { (it * 7 + 1).toByte() }
    private val newSeed = ByteArray(20) { (it * 13 + 3).toByte() }

    /** A wrapped key with both kinds of slot, each told apart by its salt. */
    private val document = (
        """{"v":1,"slots":[""" +
            """{"kind":"passphrase","kdf":{"alg":"argon2id","memMiB":256,"ops":3},"saltB64":"PASSPHRASE_SALT","nonceB64":"n1","ctB64":"c1"},""" +
            """{"kind":"recovery","kdf":{"alg":"argon2id","memMiB":256,"ops":3},"saltB64":"RECOVERY_SALT","nonceB64":"n2","ctB64":"c2"}""" +
            """]}"""
        ).toByteArray()

    private suspend fun setUp(client: HttpClient, server: DeviceServer, withKeyDoc: Boolean = true) {
        if (withKeyDoc) {
            val stored = client.post("/v1/keydoc") {
                header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
                header(HttpHeaders.IfNoneMatch, "*")
                setBody(document)
            }
            assertEquals(HttpStatusCode.Created, stored.status, stored.bodyAsText())
        }
        val made = client.post("/v1/owner/account") {
            header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"Sam","totpSecret":"${b64.encodeToString(oldSeed)}","totpCode":"${Totp.code(oldSeed, server.seconds)}",""" +
                    """"recoveryPublicKey":"${b64.encodeToString(recovery.public)}"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, made.status, made.bodyAsText())
    }

    private data class Challenge(val id: String, val text: String, val ownerId: String)

    private suspend fun challenge(client: HttpClient): Challenge {
        val res = client.post("/v1/owner/recovery/challenge")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val j = res.json()
        return Challenge(j.string("challengeId"), j.string("challenge"), j.string("ownerId"))
    }

    private suspend fun recover(
        client: HttpClient,
        server: DeviceServer,
        c: Challenge,
        signer: Pair25519 = recovery,
        message: ByteArray = OwnerSignIn.recoveryMessage(c.ownerId, ADDRESS, c.text),
        code: String = Totp.code(newSeed, server.seconds),
    ): HttpResponse = client.post("/v1/owner/recovery") {
        contentType(ContentType.Application.Json)
        setBody(
            """{"challengeId":"${c.id}","signature":"${b64.encodeToString(signer.sign(message))}","name":"Sam again",""" +
                """"totpSecret":"${b64.encodeToString(newSeed)}","totpCode":"$code"}""",
        )
    }

    private suspend fun assertRefused(res: HttpResponse, why: String) {
        assertEquals(HttpStatusCode.Unauthorized, res.status, why)
        assertTrue(res.bodyAsText().contains(RECOVERY_REFUSED), "$why: ${res.bodyAsText()}")
    }

    private suspend fun signIn(client: HttpClient, name: String, code: String) = client.post("/v1/owner/session") {
        contentType(ContentType.Application.Json)
        setBody("""{"name":"$name","code":"$code"}""")
    }

    @Test
    fun `a correct signature over a fresh challenge makes a new sign-in, ends the old sessions and writes one row`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            setUp(client, server)
            val old = signIn(client, "Sam", Totp.code(oldSeed, server.seconds + 30))
            assertEquals(HttpStatusCode.OK, old.status)
            val oldCookie = old.headers.getAll(HttpHeaders.SetCookie)!!.first { it.startsWith("daymark_owner=") }.substringBefore(';')
            assertEquals(HttpStatusCode.OK, client.get("/v1/owner/session") { header(HttpHeaders.Cookie, oldCookie) }.status, "control: the old session works")

            val recovered = recover(client, server, challenge(client))
            assertEquals(HttpStatusCode.OK, recovered.status, recovered.bodyAsText())
            assertTrue(recovered.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("daymark_owner=") }, "a session for the new sign-in")

            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/owner/session") { header(HttpHeaders.Cookie, oldCookie) }.status, "the old session is ended")
            assertEquals(1, server.ownerLog().count { it == "owner.recovered" })
            // The new name and authenticator sign in; the old ones do not.
            server.now += 60_000
            assertEquals(HttpStatusCode.Unauthorized, signIn(client, "Sam", Totp.code(oldSeed, server.seconds)).status)
            assertEquals(HttpStatusCode.OK, signIn(client, "Sam again", Totp.code(newSeed, server.seconds)).status)
        }
    }

    @Test
    fun `a challenge is good once`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            setUp(client, server)
            val c = challenge(client)
            assertEquals(HttpStatusCode.OK, recover(client, server, c).status, "control: the first use works")
            server.now += 30_000
            assertRefused(recover(client, server, c), "a replayed challenge")
            assertEquals(1, server.ownerLog().count { it == "owner.recovered" })
        }
    }

    @Test
    fun `a challenge expires ten minutes after it is given`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            setUp(client, server)
            val stale = challenge(client)
            server.now += 10 * 60 * 1000L + 1
            assertRefused(recover(client, server, stale), "an expired challenge")
            // Control: a fresh one at the same moment works, so the refusal was the expiry.
            assertEquals(HttpStatusCode.OK, recover(client, server, challenge(client)).status)
        }
    }

    @Test
    fun `a signature for another owner, another address or another key is refused, and each control passes`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            setUp(client, server)
            var c = challenge(client)
            assertRefused(recover(client, server, c, message = OwnerSignIn.recoveryMessage("own_someone_else", ADDRESS, c.text)), "another owner")
            c = challenge(client)
            assertRefused(recover(client, server, c, message = OwnerSignIn.recoveryMessage(c.ownerId, "https://elsewhere.example.com", c.text)), "another address")
            c = challenge(client)
            assertRefused(recover(client, server, c, signer = shareSigning), "the share-signing key")
            c = challenge(client)
            assertRefused(recover(client, server, c, message = "not-the-context\n${c.ownerId}\n$ADDRESS\n${c.text}".toByteArray()), "another context")
            assertEquals(0, server.ownerLog().count { it == "owner.recovered" }, "no refusal recovered anything")
            // Control: the same steps with the owner's id, this address and the recovery key work.
            assertEquals(HttpStatusCode.OK, recover(client, server, challenge(client)).status)
        }
    }

    @Test
    fun `a wrong six digits changes nothing and spends nothing`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            setUp(client, server)
            val c = challenge(client)
            val right = Totp.code(newSeed, server.seconds)
            val wrong = if (right == "000000") "111111" else "000000"
            assertNotEquals(right, wrong)
            assertEquals(HttpStatusCode.UnprocessableEntity, recover(client, server, c, code = wrong).status)
            assertEquals(HttpStatusCode.OK, recover(client, server, c).status, "the same challenge still works")
        }
    }

    @Test
    fun `the challenge carries the recovery slot and never the passphrase slot`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            setUp(client, server)
            val res = client.post("/v1/owner/recovery/challenge")
            val body = res.bodyAsText()
            val slots = res.json().getValue("recoveryKey").jsonObject.getValue("slots").jsonArray
            assertEquals(listOf("recovery"), slots.map { it.jsonObject.getValue("kind").jsonPrimitive.content })
            assertTrue(body.contains("RECOVERY_SALT"))
            assertFalse(body.contains("PASSPHRASE_SALT"))
            // Positive control: the stored document does hold the passphrase slot the answer leaves out.
            val stored = client.get("/v1/keydoc") { header(HttpHeaders.Cookie, "x=y") }
            assertEquals(HttpStatusCode.Unauthorized, stored.status, "and the whole document needs a sign-in")
            assertTrue(document.toString(Charsets.UTF_8).contains("PASSPHRASE_SALT"))
        }
    }

    @Test
    fun `no recovery code on the server, no account, or no public address each say so and give no challenge`() {
        testApplication {
            val server = DeviceServer()
            server.start(this)
            assertEquals(HttpStatusCode.NotFound, client.post("/v1/owner/recovery/challenge").status, "no account yet")
            setUp(client, server, withKeyDoc = false)
            assertEquals(HttpStatusCode.Conflict, client.post("/v1/owner/recovery/challenge").status, "no wrapped key")
        }
        testApplication {
            val server = DeviceServer(publicBaseUrl = null)
            server.start(this)
            setUp(client, server)
            val res = client.post("/v1/owner/recovery/challenge")
            assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
            assertTrue(res.bodyAsText().contains(NO_ADDRESS_MESSAGE))
        }
    }

    @Test
    fun `the recovery key is set once, with the account, and nothing replaces it`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            setUp(client, server)
            val signIn = server.account.signIn
            val recorded = signIn.recoveryKey()
            assertEquals(b64.encodeToString(recovery.public), recorded)
            assertFalse(signIn.registerRecoveryKey(b64.encodeToString(shareSigning.public)), "a second key is not taken")
            // A second account attempt, carrying another key, makes nothing and moves nothing.
            val again = client.post("/v1/owner/account") {
                header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"Other","totpSecret":"${b64.encodeToString(newSeed)}","totpCode":"${Totp.code(newSeed, server.seconds)}",""" +
                        """"recoveryPublicKey":"${b64.encodeToString(shareSigning.public)}"}""",
                )
            }
            assertNotEquals(HttpStatusCode.OK, again.status)
            assertEquals(recorded, signIn.recoveryKey())
            // And a recovery keeps it: the key is the owner's, not the sign-in's.
            assertEquals(HttpStatusCode.OK, recover(client, server, challenge(client)).status)
            assertEquals(recorded, signIn.recoveryKey())
        }
    }

    @Test
    fun `an account cannot be made without a usable recovery key`() {
        val server = DeviceServer()
        testApplication {
            server.start(this)
            for (key in listOf("", "short", b64.encodeToString(ByteArray(32)))) {
                val res = client.post("/v1/owner/account") {
                    header(HttpHeaders.Authorization, "Bearer ${server.authToken}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"Sam","totpSecret":"${b64.encodeToString(oldSeed)}","totpCode":"${Totp.code(oldSeed, server.seconds)}","recoveryPublicKey":"$key"}""")
                }
                assertEquals(HttpStatusCode.BadRequest, res.status, "key '$key'")
            }
            assertFalse(server.account.signIn.exists())
        }
    }

    @Test
    fun `a recovery sends the fixed notice to the registered address`() {
        val transport = InMemoryMailTransport()
        val mailer = Mailer.forConfig(
            MailerConfig(host = "mail.example.org", port = 587, user = null, pass = null, from = "companion@example.org", tls = MailerConfig.TlsMode.STARTTLS),
            transport,
        )
        val server = DeviceServer(mailer = mailer)
        testApplication {
            server.start(this)
            setUp(client, server)
            val put = client.put("/v1/owner/notifications") {
                header(HttpHeaders.Cookie, "x=y")
                contentType(ContentType.Application.Json)
                setBody("""{"email":"owner@example.org","events":[]}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, put.status, "control: a stranger cannot set it")
            server.account.setNotificationSettings("owner@example.org", emptySet())
            assertEquals(HttpStatusCode.OK, recover(client, server, challenge(client)).status)
            val deadline = System.currentTimeMillis() + 2_000
            while (transport.sent.isEmpty() && System.currentTimeMillis() < deadline) delay(20)
            assertEquals(1, transport.sent.size)
            val sent = transport.sent.single()
            assertEquals("owner@example.org", sent.to)
            assertEquals(MailTemplates.SECURITY_SUBJECT, sent.subject)
            assertTrue(sent.body.contains("recovery code"))
        }
    }

    private companion object {
        /** DeviceServer's public address. */
        const val ADDRESS = "https://daymark.example.com"
    }
}
