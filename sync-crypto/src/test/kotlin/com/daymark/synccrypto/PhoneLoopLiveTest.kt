package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone's whole loop against the real Companion server (#432): pair by typing the address and the
 * code, confirm on the "web" with the owner's token as the console does, keep the key and make it again,
 * open the sync key with the passphrase, send copies, and read them back with the token and open them.
 * And the refusals: an http address and a mistyped code send nothing, a wrong code gets the one refusal
 * once, and a revoked phone's next request gets 401 and nothing follows it.
 *
 * It runs the server jar, so it runs by hand: build it with `(cd companion/server && ./gradlew
 * shadowJar)` and pass its path as `-Ddaymark.serverJar=...`. Without that every test here is skipped,
 * and reported as skipped. The server's rate limit is raised, as the web's integration test raises it,
 * because the test's own token requests share the phone's address; the lockout is left as it is.
 *
 * The phone's requests go through [HttpsTransport], the class the phone sends with. The one thing
 * changed is where an https URL is opened: at the server's local http port, after the transport's own
 * https check has run.
 */
class PhoneLoopLiveTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val phone = Recording()
    private val pairing = PhonePairing(sodium, phone)
    private val phoneSync = PhoneSync(sodium, phone)

    /** What the phone sent, and what came back: the status, or -1 for no answer. */
    private class Sent(val method: String, val target: String, val nonce: String?, val status: Int)

    private class Recording : Transport {
        val sent: MutableList<Sent> = Collections.synchronizedList(ArrayList())

        /** Runs before each request goes out, with its method and target. */
        var before: (String, String) -> Unit = { _, _ -> }
        private val inner = HttpsTransport { url -> URI("http://127.0.0.1:$port${url.file}").toURL().openConnection() as HttpURLConnection }

        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray): TransportAnswer {
            val target = if (url.startsWith(ADDRESS)) url.substring(ADDRESS.length) else url
            before(method, target)
            val answer = try {
                inner.send(method, url, headers, body)
            } catch (e: IOException) {
                sent += Sent(method, target, headers[DeviceSignature.NONCE_HEADER], -1)
                throw e
            }
            sent += Sent(method, target, headers[DeviceSignature.NONCE_HEADER], answer.status)
            return answer
        }

        fun lines(): List<String> = synchronized(sent) { sent.map { "${it.method} ${it.target} ${it.status}" } }
    }

    // --- the web's side: the owner's token, as the console uses it ---

    private class WebAnswer(val status: Int, val body: ByteArray) {
        val json: Map<*, *> get() = Answers.jsonObject(body) ?: throw AssertionError("not a JSON object: ${String(body)}")
    }

    private fun web(method: String, target: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap()): WebAnswer {
        val connection = URI("http://127.0.0.1:$port$target").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $TOKEN")
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size.toLong())
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val bytes = (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            return WebAnswer(status, bytes)
        } finally {
            connection.disconnect()
        }
    }

    /** A new code, as the console mints one: its id and the code the console shows, `XXXXX-XXXXX`. */
    private fun mint(): Pair<String, String> {
        val minted = web("POST", "/v1/devices/pairing")
        assertEquals(201, minted.status)
        assertEquals("the QR's address is the server's https address", ADDRESS, minted.json["baseUrl"])
        val code = minted.json["code"] as String
        return (minted.json["codeId"] as String) to code.chunked(5).joinToString("-")
    }

    /** Pairs a phone as a person does, and returns what it keeps, kept and read back as on the next start. */
    private fun pairedPhone(): PairedServer {
        val (codeId, shown) = mint()
        val ready = pairing.read(ADDRESS, shown) as? PhonePairing.Reading.Ready ?: throw AssertionError("the code was not read")
        val waiting = pairing.redeem(ready) as? PhonePairing.Redemption.Waiting ?: throw AssertionError("not redeemed: ${phone.lines()}")
        val pending = waiting.pending

        // Both screens show the same six words: the console computes them from the key the server relays.
        val state = web("GET", "/v1/devices/pairing/$codeId").json
        assertEquals("redeemed", state["state"])
        assertEquals(pending.words, DeviceWords.of(sodium, state["publicKey"] as String))
        val keyId = state["keyId"] as String

        // The phone asks while the person compares, and the web confirms once the phone has been told to wait.
        val asking = Executors.newSingleThreadExecutor()
        try {
            val registration = asking.submit<PhonePairing.Registration> { pairing.awaitRegistration(pending) }
            val waitUntil = System.currentTimeMillis() + 20_000
            while (phone.lines().none { it == "GET /v1/devices/registration 202" }) {
                assertTrue("the phone never asked: ${phone.lines()}", System.currentTimeMillis() < waitUntil)
                Thread.sleep(100)
            }
            assertEquals(201, web("POST", "/v1/devices/pairing/$codeId/confirm", """{"keyId":"$keyId"}""".toByteArray(), mapOf("Content-Type" to "application/json")).status)
            val registered = registration.get(30, TimeUnit.SECONDS) as? PhonePairing.Registration.Registered
                ?: throw AssertionError("not registered: ${phone.lines()}")
            assertEquals(keyId, registered.server.key.keyId)

            // Kept the moment the poll says registered, and made again from what was kept.
            val kept = KeptLink.of(registered.server).encode()
            val readBack = KeptLink.decode(kept) ?: throw AssertionError("the kept bytes did not read back")
            return readBack.server(sodium) ?: throw AssertionError("the kept key did not come back")
        } finally {
            asking.shutdownNow()
        }
    }

    private fun unlocked(server: PairedServer): PhoneSync.Unlock.Opened =
        phoneSync.unlock(server, PASSPHRASE) as? PhoneSync.Unlock.Opened ?: throw AssertionError("the key did not open: ${phone.lines()}")

    @Test
    fun thePhonePairsOpensTheKeyAndSendsCopiesThatTheWebOpens() {
        val server = pairedPhone()
        val opened = unlocked(server)
        assertArrayEquals("the phone's sync key is the web's", webSyncKey, opened.syncKey)

        val lineage = PhoneLineage.create(sodium)
        val first = """{"version":12,"entries":[{"id":1,"note":"first"}]}""".toByteArray()
        val second = """{"version":12,"entries":[{"id":1,"note":"first"},{"id":2,"note":"second"}]}""".toByteArray()
        val sentFirst = phoneSync.send(server, opened.syncKey, opened.keyDocumentTag, lineage, first) as? PhoneSync.Sending.Sent
            ?: throw AssertionError("not sent: ${phone.lines()}")
        assertEquals(0L, sentFirst.version)
        val sentSecond = phoneSync.send(server, opened.syncKey, opened.keyDocumentTag, lineage, second) as? PhoneSync.Sending.Sent
            ?: throw AssertionError("not sent: ${phone.lines()}")
        assertEquals(1L, sentSecond.version)

        // The web reads both back with the token, and opens each with the key its own passphrase gives.
        for ((version, plaintext) in listOf(0L to first, 1L to second)) {
            val stored = web("GET", "/v1/snapshots/$lineage/$version")
            assertEquals(200, stored.status)
            assertArrayEquals(plaintext, SyncCrypto(sodium).decryptSnapshot(stored.body, webSyncKey, lineage, version))
        }
        // The console lists the phone, paired and not revoked.
        val devices = web("GET", "/v1/devices").json["devices"] as List<*>
        assertTrue(devices.any { (it as Map<*, *>)["keyId"] == server.key.keyId && it["revokedAt"] == null })

        // Nothing the phone sent was refused, and no two of its signed requests shared a nonce.
        assertTrue("a request was refused: ${phone.lines()}", phone.sent.all { it.status in 200..299 })
        val nonces = phone.sent.mapNotNull { it.nonce }
        assertEquals(nonces.size, nonces.toSet().size)
        assertEquals(
            listOf("GET /v1/keydoc 200", "GET /v1/snapshots/$lineage 200", "PUT /v1/snapshots/$lineage/0 201"),
            phone.lines().takeLast(6).take(3),
        )
    }

    @Test
    fun aCopyThatMeetsAnotherAtItsVersionIsReadAgainAndWrittenOnceMore() {
        val server = pairedPhone()
        val opened = unlocked(server)
        val lineage = PhoneLineage.create(sodium)
        // Right before the phone's first write, another write takes the version it read as next.
        var raced = false
        phone.before = { method, target ->
            if (method == "PUT" && !raced) {
                raced = true
                assertEquals("/v1/snapshots/$lineage/0", target)
                val other = SyncCrypto(sodium).encryptSnapshot("{}".toByteArray(), webSyncKey, lineage, 0)
                assertEquals(201, web("PUT", target, other).status)
            }
        }
        val plaintext = """{"version":12,"entries":[]}""".toByteArray()
        val sent = phoneSync.send(server, opened.syncKey, opened.keyDocumentTag, lineage, plaintext) as? PhoneSync.Sending.Sent
            ?: throw AssertionError("not sent: ${phone.lines()}")
        assertEquals(1L, sent.version)
        assertEquals(
            listOf("PUT /v1/snapshots/$lineage/0 409", "GET /v1/snapshots/$lineage 200", "PUT /v1/snapshots/$lineage/1 201"),
            phone.lines().takeLast(3),
        )
        assertArrayEquals(plaintext, SyncCrypto(sodium).decryptSnapshot(web("GET", "/v1/snapshots/$lineage/1").body, webSyncKey, lineage, 1))
    }

    @Test
    fun anHttpAddressIsDeclinedBeforeAnyRequest() {
        val (_, shown) = mint()
        val typed = pairing.read("http://127.0.0.1:$port", shown)
        assertEquals(PhoneWords.HTTP_ADDRESS, (typed as PhonePairing.Reading.Declined).words)
        val pasted = pairing.read("daymark-pair:v1?server=http%3A%2F%2F127.0.0.1%3A$port&code=${shown.replace("-", "")}", "")
        assertEquals(PhoneWords.HTTP_ADDRESS, (pasted as PhonePairing.Reading.Declined).words)
        assertEquals("the phone sent something", emptyList<String>(), phone.lines())
        // The control: the same code at the https address is read, and still nothing has been sent.
        assertTrue(pairing.read(ADDRESS, shown) is PhonePairing.Reading.Ready)
        assertEquals(emptyList<String>(), phone.lines())
    }

    @Test
    fun aWrongCodeGetsTheOneRefusal_once_andAMistypedOneIsNeverSent() {
        val (_, shown) = mint()
        val canonical = shown.replace("-", "")
        // A code in its right form that the server never minted: the first symbol changed, derived from
        // the code, and the check symbol made again so that it checks out.
        val first = PairingCode.ALPHABET.first { it != canonical[0] }
        val payload = first + canonical.substring(1, 9)
        val wrong = payload + PairingCode.checkSymbol(payload)
        assertTrue(wrong != canonical)
        val ready = pairing.read(ADDRESS, wrong) as? PhonePairing.Reading.Ready ?: throw AssertionError("a well-formed code was not read")
        val refused = pairing.redeem(ready) as? PhonePairing.Redemption.Stopped ?: throw AssertionError("a code nobody minted was taken")
        assertEquals(PhoneWords.CODE_NOT_TAKEN, refused.words)
        assertEquals(listOf("POST /v1/devices/redeem 404"), phone.lines())

        // One symbol mistyped fails the check symbol, and is never sent.
        val mistyped = canonical.substring(0, 9) + PairingCode.ALPHABET.first { it != canonical[9] }
        assertEquals(PhoneWords.CODE_DOES_NOT_CHECK_OUT, (pairing.read(ADDRESS, mistyped) as PhonePairing.Reading.Declined).words)
        assertEquals(1, phone.lines().size)

        // The control: the code the console showed is taken.
        assertTrue(pairing.redeem(pairing.read(ADDRESS, shown) as PhonePairing.Reading.Ready) is PhonePairing.Redemption.Waiting)
        assertEquals("POST /v1/devices/redeem 202", phone.lines().last())
    }

    @Test
    fun aRevokedPhoneIsToldOnce_andNothingFollowsTheRefusal() {
        val server = pairedPhone()
        val opened = unlocked(server)
        assertEquals(204, web("POST", "/v1/devices/${server.key.keyId}/revoke").status)
        val before = phone.sent.size

        val send = phoneSync.send(server, opened.syncKey, opened.keyDocumentTag, PhoneLineage.create(sodium), "{}".toByteArray())
        val stop = send as? PhoneSync.Sending.Stopped ?: throw AssertionError("a revoked phone sent a copy")
        assertEquals(PhoneWords.DISCONNECTED, stop.words)
        assertEquals(PhoneSync.Then.PAIR_AGAIN, stop.then)
        assertEquals(listOf("GET /v1/keydoc 401"), phone.lines().drop(before))

        val unlock = phoneSync.unlock(server, PASSPHRASE) as PhoneSync.Unlock.Stopped
        assertEquals(PhoneSync.Then.PAIR_AGAIN, unlock.then)
        assertEquals(listOf("GET /v1/keydoc 401", "GET /v1/keydoc 401"), phone.lines().drop(before))

        // The poll stops at its first refusal too.
        val pending = PendingPairing(ADDRESS, server.key, server.key.words(), System.currentTimeMillis() + 60_000, PhoneClock.SYSTEM.elapsedMillis() + 60_000)
        val registration = pairing.awaitRegistration(pending) as PhonePairing.Registration.Stopped
        assertEquals(PhoneWords.TIME_RAN_OUT, registration.words)
        assertEquals(listOf("GET /v1/keydoc 401", "GET /v1/keydoc 401", "GET /v1/devices/registration 401"), phone.lines().drop(before))
    }

    @Test
    fun aWrongPassphraseOpensNothing() {
        val server = pairedPhone()
        val stop = phoneSync.unlock(server, "$PASSPHRASE, mistyped") as? PhoneSync.Unlock.Stopped ?: throw AssertionError("a wrong passphrase opened the key")
        assertEquals(PhoneWords.WRONG_PASSPHRASE, stop.words)
        assertEquals(PhoneSync.Then.TRY_AGAIN, stop.then)
        assertEquals("GET /v1/keydoc 200", phone.lines().last())
    }

    companion object {
        private const val TOKEN = "live-test-owner-token"
        private const val ADDRESS = "https://daymark.live.test"
        private const val PASSPHRASE = "the live test's own sync passphrase"

        /** The recovery code of the web's vector (dataKeyVector.test.ts), for the key document's second slot. */
        private const val RECOVERY_CODE = "K7M2Q-XR9CT-4HWAZ-P3NE8-GUV6D-YJF59"

        private var process: Process? = null
        private var port = 0
        private lateinit var webSyncKey: ByteArray

        @BeforeClass
        @JvmStatic
        fun startTheServer() {
            val jar = System.getProperty("daymark.serverJar")
            assumeTrue("the server jar is not given (-Ddaymark.serverJar=...); this test runs by hand", jar != null)
            port = ServerSocket(0).use { it.localPort }
            val dataDir = Files.createTempDirectory("phone-loop-live-")
            val builder = ProcessBuilder("java", "-jar", jar)
                .redirectErrorStream(true)
                .redirectOutput(dataDir.resolve("server.log").toFile())
            builder.environment().apply {
                put("DAYMARK_SETUP_MODE", "solo")
                put("DAYMARK_AUTH_TOKEN", TOKEN)
                put("DAYMARK_PUBLIC_BASE_URL", ADDRESS)
                put("DAYMARK_DATA_DIR", dataDir.toString())
                put("DAYMARK_PORT", port.toString())
                put("DAYMARK_BIND_ADDR", "127.0.0.1")
                put("DAYMARK_WEB_DIR", "/nonexistent-web")
                put("DAYMARK_LOG_LEVEL", "warn")
                put("DAYMARK_RATE_LIMIT_RPS", "1000")
            }
            process = builder.start()
            val upBy = System.currentTimeMillis() + 60_000
            while (true) {
                val up = try {
                    (URI("http://127.0.0.1:$port/healthz").toURL().openConnection() as HttpURLConnection).let { it.responseCode.also { _ -> it.disconnect() } } == 200
                } catch (_: IOException) {
                    false
                }
                if (up) break
                check(System.currentTimeMillis() < upBy) { "the server did not start; its log is in $dataDir" }
                Thread.sleep(200)
            }
            createTheOwnersKey()
        }

        /**
         * The owner's key as the console's first run makes it (docs/SYNC_PROTOCOL.md §3): a random master
         * locked under the passphrase and under a recovery code, created with `If-None-Match: *`. The
         * web's own sync key is then read from the document the server serves, with the passphrase.
         */
        private fun createTheOwnersKey() {
            val sodium = LazySodiumJava(SodiumJava())
            val crypto = SyncCrypto(sodium)
            val master = sodium.randomBytesBuf(32)
            val slots = listOf(
                crypto.wrapSlot(master, PASSPHRASE, KeyDocument.SlotKind.PASSPHRASE, SyncCrypto.KdfParams.DEFAULT, sodium.randomBytesBuf(16), sodium.randomBytesBuf(24)),
                crypto.wrapSlot(master, RecoveryCode.parse(RECOVERY_CODE).canonical, KeyDocument.SlotKind.RECOVERY, SyncCrypto.KdfParams.DEFAULT, sodium.randomBytesBuf(16), sodium.randomBytesBuf(24)),
            )
            master.fill(0)
            val document = KeyDocument.WrappedKey(slots).toJson().toByteArray()
            val connection = URI("http://127.0.0.1:$port/v1/keydoc").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $TOKEN")
            connection.setRequestProperty("If-None-Match", "*")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(document.size.toLong())
            connection.outputStream.use { it.write(document) }
            check(connection.responseCode == 201) { "the key document was not created: ${connection.responseCode}" }
            connection.disconnect()

            val read = URI("http://127.0.0.1:$port/v1/keydoc").toURL().openConnection() as HttpURLConnection
            read.setRequestProperty("Authorization", "Bearer $TOKEN")
            val served = read.inputStream.use { it.readBytes() }
            read.disconnect()
            webSyncKey = crypto.openWithPassphrase(KeyDocument.parse(String(served)), PASSPHRASE).syncKey
        }

        @AfterClass
        @JvmStatic
        fun stopTheServer() {
            process?.destroyForcibly()?.waitFor(10, TimeUnit.SECONDS)
        }
    }
}
