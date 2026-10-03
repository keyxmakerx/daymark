package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The paired phone's errands (#432), against [FakeServer], which checks every signature as the server
 * does: opening the sync key with the passphrase, and sending a copy at the next version. What each test
 * pins: every request is signed over what was sent, with a nonce of its own; a refusal stops the errand
 * and nothing is sent again; a 409 is read again and written once more, and only once; a copy is sealed
 * only under the key the server's document still opens to, and only under this phone's own lineage.
 */
class PhoneSyncTest {

    private val sodium = LazySodiumJava(SodiumJava())
    private val server = FakeServer(sodium)
    private val clock = FakeClock()
    private val phoneSync = PhoneSync(sodium, server, clock)
    private val key = DeviceKey.generate(sodium).also { server.know(it.publicKey) }
    private val paired = PairedServer(FakeServer.ADDRESS, key)
    private val lineage = PhoneLineage.create(sodium)
    private val syncKey = sodium.randomBytesBuf(32)
    private val tag = "\"" + "ab".repeat(32) + "\""
    private val plaintext = """{"version":12,"entries":[{"id":1,"note":"a day"}]}""".toByteArray(Charsets.UTF_8)

    private fun stopped(unlock: PhoneSync.Unlock) = unlock as? PhoneSync.Unlock.Stopped ?: throw AssertionError("opened")
    private fun stopped(sending: PhoneSync.Sending) = sending as? PhoneSync.Sending.Stopped ?: throw AssertionError("sent")

    /** The fake's answers for a send: the key document under [tag], [versions] stored, and [put] for each write. */
    private fun serve(versions: MutableList<Long>, put: (FakeServer.Request) -> TransportAnswer = { FakeServer.answer(201, "{}") }) {
        server.answer = { request ->
            assertEquals("${request.method} ${request.target} is not signed by the phone's key over what was sent", key.keyId, request.signedBy)
            when {
                request.method == "GET" && request.target == "/v1/keydoc" -> FakeServer.answer(200, "{}", "ETag" to tag)
                request.method == "GET" && request.target == "/v1/snapshots/$lineage" ->
                    FakeServer.answer(200, """{"lineage":"$lineage","versions":[${versions.joinToString(",") { """{"version":$it,"size":1,"contentHash":"x","createdAt":1}""" }}]}""")
                request.method == "PUT" -> put(request)
                else -> throw AssertionError("unexpected ${request.method} ${request.target}")
            }
        }
    }

    @Test
    fun unlockOpensTheWrappedKeyWithThePassphrase_andNotWithAnother() {
        server.answer = { request ->
            assertEquals("GET /v1/keydoc", "${request.method} ${request.target}")
            assertEquals(key.keyId, request.signedBy)
            FakeServer.answer(200, Wrapped.document, "ETag" to tag, "X-Key-Document" to "wrapped")
        }
        val opened = phoneSync.unlock(paired, Wrapped.PASSPHRASE) as? PhoneSync.Unlock.Opened ?: throw AssertionError("not opened")
        assertArrayEquals(Wrapped.syncKey, opened.syncKey)
        assertEquals(tag, opened.keyDocumentTag)
        assertEquals(1, server.requests.size)

        val wrong = stopped(phoneSync.unlock(paired, Wrapped.PASSPHRASE + "!"))
        assertEquals(PhoneWords.WRONG_PASSPHRASE, wrong.words)
        assertEquals(PhoneSync.Then.TRY_AGAIN, wrong.then)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun unlockDoesNotDeriveFromTheKeyParameters_andSaysToFinishOnTheWeb() {
        val keyParams = """{"v":1,"alg":"xchacha20poly1305","kdf":{"alg":"argon2id","memMiB":256,"ops":3},"saltB64":"AAECAwQFBgcICQoLDA0ODw"}"""
        server.answer = { FakeServer.answer(200, keyParams, "ETag" to tag, "X-Key-Document" to "keyparams") }
        val stop = stopped(phoneSync.unlock(paired, "any passphrase at all"))
        assertEquals(PhoneWords.KEY_NOT_READY, stop.words)
        assertEquals(PhoneSync.Then.TRY_AGAIN, stop.then)
    }

    @Test
    fun unlockStopsOnEveryAnswerItCannotUse_afterOneRequest() {
        val cases = listOf(
            FakeServer.answer(401, """{"error":"unauthorized"}""") to (PhoneWords.DISCONNECTED to PhoneSync.Then.PAIR_AGAIN),
            FakeServer.answer(404, """{"error":"no key document"}""") to (PhoneWords.NO_KEY_YET to PhoneSync.Then.TRY_AGAIN),
            FakeServer.answer(429, """{"error":"rate limited"}""") to (PhoneWords.PAUSED to PhoneSync.Then.TRY_AGAIN),
            FakeServer.answer(502) to (PhoneWords.UNREACHABLE to PhoneSync.Then.TRY_AGAIN),
            FakeServer.answer(403, """{"error":"a paired phone cannot do this"}""") to (PhoneWords.KEY_UNREADABLE to PhoneSync.Then.TRY_AGAIN),
            FakeServer.answer(200, Wrapped.document) to (PhoneWords.KEY_UNREADABLE to PhoneSync.Then.TRY_AGAIN),
            FakeServer.answer(200, "{\"v\":2,\"slots\":[]}", "ETag" to tag) to (PhoneWords.KEY_UNREADABLE to PhoneSync.Then.TRY_AGAIN),
            TransportAnswer(200, mapOf("ETag" to listOf(tag)), byteArrayOf(0xC3.toByte(), 0x28)) to (PhoneWords.KEY_UNREADABLE to PhoneSync.Then.TRY_AGAIN),
        )
        for ((answer, expected) in cases) {
            val before = server.requests.size
            server.answer = { answer }
            val stop = stopped(phoneSync.unlock(paired, Wrapped.PASSPHRASE))
            assertEquals("${answer.status}", expected.first, stop.words)
            assertEquals("${answer.status}", expected.second, stop.then)
            assertEquals("${answer.status} was sent again", before + 1, server.requests.size)
        }
        server.answer = { throw IOException("no route") }
        val stop = stopped(phoneSync.unlock(paired, Wrapped.PASSPHRASE))
        assertEquals(PhoneWords.UNREACHABLE, stop.words)
        assertEquals(PhoneSync.Then.TRY_AGAIN, stop.then)
    }

    @Test
    fun aCopyIsSentAsTheNextVersion_sealedUnderTheSyncKeyForThatVersion() {
        val versions = mutableListOf(0L, 3L, 1L)
        var envelope: ByteArray? = null
        serve(versions) { request ->
            assertEquals("application/octet-stream", request.headers["Content-Type"])
            envelope = request.body
            FakeServer.answer(201, """{"lineage":"$lineage","version":4}""")
        }
        val sent = phoneSync.send(paired, syncKey, tag, lineage, plaintext) as? PhoneSync.Sending.Sent ?: throw AssertionError("not sent")
        assertEquals(4L, sent.version)
        assertEquals(clock.now, sent.atMillis)
        assertEquals(listOf("GET /v1/keydoc", "GET /v1/snapshots/$lineage", "PUT /v1/snapshots/$lineage/4"), server.targets())
        val crypto = SyncCrypto(sodium)
        assertArrayEquals(plaintext, crypto.decryptSnapshot(envelope!!, syncKey, lineage, 4))
        assertEquals(SyncCrypto.FMT_PADDED, envelope!![4])
        // Sealed for version 4 and no other: under another version's associated data it does not open.
        assertTrue(runCatching { crypto.decryptSnapshot(envelope!!, syncKey, lineage, 3) }.isFailure)
        val nonces = server.requests.map { it.headers.getValue(DeviceSignature.NONCE_HEADER) }
        assertEquals("a nonce was used twice", nonces.size, nonces.toSet().size)
    }

    @Test
    fun theFirstCopyIsVersionZero() {
        serve(mutableListOf())
        assertEquals(0L, (phoneSync.send(paired, syncKey, tag, lineage, plaintext) as PhoneSync.Sending.Sent).version)
        // A 404 for the list is none as well.
        server.answer = { request ->
            when (request.method to request.target) {
                "GET" to "/v1/keydoc" -> FakeServer.answer(200, "{}", "ETag" to tag)
                "GET" to "/v1/snapshots/$lineage" -> FakeServer.answer(404, """{"error":"not found"}""")
                else -> FakeServer.answer(201, "{}")
            }
        }
        assertEquals(0L, (phoneSync.send(paired, syncKey, tag, lineage, plaintext) as PhoneSync.Sending.Sent).version)
        assertEquals("PUT /v1/snapshots/$lineage/0", server.targets().last())
    }

    @Test
    fun aKeyDocumentOtherThanTheOneOpenedSendsNothing() {
        serve(mutableListOf())
        val otherTag = "\"" + "cd".repeat(32) + "\""
        assertNotEquals(tag, otherTag)
        val stop = stopped(phoneSync.send(paired, syncKey, otherTag, lineage, plaintext))
        assertEquals(PhoneWords.KEY_CHANGED, stop.words)
        assertEquals(PhoneSync.Then.UNLOCK_AGAIN, stop.then)
        assertEquals(listOf("GET /v1/keydoc"), server.targets())
        // None at all is the same: the key the copy would be sealed under is not the server's any more.
        server.answer = { FakeServer.answer(404, """{"error":"no key document"}""") }
        assertEquals(PhoneSync.Then.UNLOCK_AGAIN, stopped(phoneSync.send(paired, syncKey, tag, lineage, plaintext)).then)
        assertEquals(2, server.requests.size)
        // The control: the tag it was opened under sends.
        serve(mutableListOf())
        assertTrue(phoneSync.send(paired, syncKey, tag, lineage, plaintext) is PhoneSync.Sending.Sent)
    }

    @Test
    fun aConflictIsReadAgainAndWrittenOnceMore_andOnlyOnce() {
        val versions = mutableListOf(0L)
        var writes = 0
        serve(versions) { request ->
            writes++
            if (writes == 1) {
                versions += 1L // another write took version 1 between the read and the write
                FakeServer.answer(409, """{"error":"version already exists"}""")
            } else {
                assertEquals("/v1/snapshots/$lineage/2", request.target)
                FakeServer.answer(201, "{}")
            }
        }
        assertEquals(2L, (phoneSync.send(paired, syncKey, tag, lineage, plaintext) as PhoneSync.Sending.Sent).version)
        assertEquals(
            listOf("GET /v1/keydoc", "GET /v1/snapshots/$lineage", "PUT /v1/snapshots/$lineage/1", "GET /v1/snapshots/$lineage", "PUT /v1/snapshots/$lineage/2"),
            server.targets(),
        )

        // Two conflicts in a row stop, after exactly two writes.
        server.requests.clear()
        serve(mutableListOf(5L)) { FakeServer.answer(409, """{"error":"version already exists"}""") }
        val stop = stopped(phoneSync.send(paired, syncKey, tag, lineage, plaintext))
        assertEquals(PhoneWords.NOT_SENT, stop.words)
        assertEquals(PhoneSync.Then.TRY_AGAIN, stop.then)
        assertEquals(2, server.requests.count { it.method == "PUT" })
    }

    @Test
    fun aRefusalAtAnyStepStops_andNothingIsSentAfterIt() {
        val steps = listOf("GET /v1/keydoc", "GET /v1/snapshots/$lineage", "PUT /v1/snapshots/$lineage/1")
        for ((index, step) in steps.withIndex()) {
            for ((status, expected) in listOf(401 to (PhoneWords.DISCONNECTED to PhoneSync.Then.PAIR_AGAIN), 429 to (PhoneWords.PAUSED to PhoneSync.Then.TRY_AGAIN))) {
                server.requests.clear()
                serve(mutableListOf(0L))
                val serving = server.answer
                server.answer = { request ->
                    if ("${request.method} ${request.target}" == step) FakeServer.answer(status, """{"error":"refused"}""") else serving(request)
                }
                val stop = stopped(phoneSync.send(paired, syncKey, tag, lineage, plaintext))
                assertEquals("$status at $step", expected.first, stop.words)
                assertEquals("$status at $step", expected.second, stop.then)
                assertEquals("$status at $step: something was sent after it", steps.take(index + 1), server.targets())
            }
        }
    }

    @Test
    fun noAnswerStopsTheSend_andTheWordsSayWhetherTheCopyWentOut() {
        serve(mutableListOf(0L))
        val serving = server.answer
        server.answer = { request -> if (request.method == "GET" && request.target.startsWith("/v1/snapshots/")) throw IOException("no route") else serving(request) }
        assertEquals(PhoneWords.UNREACHABLE, stopped(phoneSync.send(paired, syncKey, tag, lineage, plaintext)).words)
        server.requests.clear()
        server.answer = { request -> if (request.method == "PUT") throw IOException("reset") else serving(request) }
        val stop = stopped(phoneSync.send(paired, syncKey, tag, lineage, plaintext))
        assertEquals(PhoneWords.NOT_SENT, stop.words)
        assertEquals("a write with no answer was sent again", 1, server.requests.count { it.method == "PUT" })
    }

    @Test
    fun aVersionListThatIsNotThisLineagesSendsNothing() {
        for (body in listOf("""{"lineage":"phone_other","versions":[]}""", """{"lineage":"$lineage","versions":[{"version":-1}]}""", """{"lineage":"$lineage"}""", "[]")) {
            server.requests.clear()
            server.answer = { request ->
                if (request.target == "/v1/keydoc") FakeServer.answer(200, "{}", "ETag" to tag) else FakeServer.answer(200, body)
            }
            assertEquals(body, PhoneWords.NOT_SENT, stopped(phoneSync.send(paired, syncKey, tag, lineage, plaintext)).words)
            assertEquals(body, 0, server.requests.count { it.method == "PUT" })
        }
    }

    @Test
    fun thePhoneWritesOnlyUnderItsOwnLineage() {
        serve(mutableListOf())
        for (name in listOf("lane_AAECAwQFBgcICQoLDA0ODw", "phone_", "phone_AAECAwQFBgcICQoLDA0ODx", "devA", "phone_AAECAwQFBgcICQoLDA0ODw=")) {
            assertEquals(name, PhoneWords.NOT_SENT, stopped(phoneSync.send(paired, syncKey, tag, name, plaintext)).words)
        }
        assertEquals("a request went out for a lineage that is not the phone's", 0, server.requests.size)
        // The control: the phone's own is sent.
        assertTrue(phoneSync.send(paired, syncKey, tag, lineage, plaintext) is PhoneSync.Sending.Sent)
    }

    @Test
    fun aLineageIsThePrefixAndSixteenRandomBytes_neverALane() {
        val made = (1..200).map { PhoneLineage.create(sodium) }
        assertEquals("a lineage was made twice", made.size, made.toSet().size)
        for (name in made) {
            assertTrue(name, name.startsWith("phone_"))
            assertFalse(name, name.startsWith("lane_"))
            assertEquals(28, name.length)
            assertTrue(name, Regex("^[A-Za-z0-9_-]{1,64}$").matches(name))
            assertTrue(name, PhoneLineage.isPhoneLineage(name))
        }
        assertFalse(PhoneLineage.isPhoneLineage("lane_" + made[0].removePrefix("phone_")))
    }

    @Test
    fun aCopyLargerThanTheServerTakesIsNotSent() {
        serve(mutableListOf())
        // The largest plaintext whose sealed envelope the server still takes, found from the envelope's own size rule.
        var fits = PhoneSync.MAX_BLOB_BYTES - 45
        while (SyncCrypto.snapshotBlobLength(fits) > PhoneSync.MAX_BLOB_BYTES) fits -= 1024
        var over = fits
        while (SyncCrypto.snapshotBlobLength(over) <= PhoneSync.MAX_BLOB_BYTES) over += 1
        val stop = stopped(phoneSync.send(paired, syncKey, tag, lineage, ByteArray(over.toInt())))
        assertEquals(PhoneWords.NOT_SENT, stop.words)
        assertEquals("a request went out for a copy the server would refuse", 0, server.requests.size)
        // The control: one byte less is sent.
        assertTrue(phoneSync.send(paired, syncKey, tag, lineage, ByteArray((over - 1).toInt())) is PhoneSync.Sending.Sent)
    }

    /** One wrapped key, made once for the class: Argon2id at 256 MiB is seconds, and the tests open it twice. */
    private object Wrapped {
        const val PASSPHRASE = "a long enough sync passphrase"
        private val sodium = LazySodiumJava(SodiumJava())
        private val crypto = SyncCrypto(sodium)
        private val master = sodium.randomBytesBuf(32)
        val syncKey: ByteArray = crypto.deriveSubkey(master, 1, 32)
        val document: String = KeyDocument.WrappedKey(
            listOf(
                crypto.wrapSlot(
                    master,
                    PASSPHRASE,
                    KeyDocument.SlotKind.PASSPHRASE,
                    SyncCrypto.KdfParams.DEFAULT,
                    sodium.randomBytesBuf(16),
                    sodium.randomBytesBuf(24),
                ),
            ),
        ).toJson()
    }
}
