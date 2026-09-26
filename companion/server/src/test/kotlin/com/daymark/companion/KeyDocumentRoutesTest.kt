package com.daymark.companion

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.daymark.companion.routes.KEY_DOCUMENT_HEADER
import com.daymark.companion.routes.KEY_DOCUMENT_KEYPARAMS
import com.daymark.companion.routes.KEY_DOCUMENT_VERSION_HEADER
import com.daymark.companion.routes.KEY_DOCUMENT_WRAPPED
import com.daymark.companion.routes.PRECONDITION_FAILED_MESSAGE
import com.daymark.companion.routes.PRECONDITION_REQUIRED_MESSAGE
import com.daymark.companion.storage.KeyDocumentStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner's key document over HTTP (#258): the wrapped key's insert-only versions, the create that
 * names the state it read, the read that answers with the wrapped key or the key parameters, the key
 * parameters written once and superseded by the wrapped key's presence, the owner's token on every
 * route, the shape and size a document may have, and a restart in between. docs/SYNC_PROTOCOL.md
 * §1.2 and §2 are the contract restated here.
 */
class KeyDocumentRoutesTest {

    private val token = "owner-token-key-document-test"

    private fun config(dataDir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dataDir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = token,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        // Every missing or wrong token below counts toward the lockout; none may lock the owner out.
        authLockoutFails = 1_000, authLockoutSeconds = 900L, rateLimitRps = 10_000,
    )

    private fun tmpDir(): String = Files.createTempDirectory("keydoc-routes").toString()

    private fun serve(dataDir: String, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(config(dataDir)) }
        block()
    }

    /** A wrapped key in the web's shape (companion/web/src/lib/recovery/dataKey.ts), told apart by [marker]. */
    private fun wrapped(marker: String): ByteArray =
        (
            """{"v":1,"slots":[{"kind":"passphrase","kdf":{"alg":"argon2id","memMiB":256,"ops":3},""" +
                """"saltB64":"$marker","nonceB64":"q6urq6urq6urq6urq6urq6urq6urq6ur","ctB64":"q6urq6urq6ur"}]}"""
            ).toByteArray()

    /** Key parameters in the shape of docs/SYNC_PROTOCOL.md §1.2, told apart by [salt]. */
    private fun keyparams(salt: String): ByteArray =
        """{"v":1,"alg":"xchacha20poly1305","kdf":{"alg":"argon2id","memMiB":256,"ops":3},"saltB64":"$salt"}""".toByteArray()

    /** The strong ETag the server gives [bytes], worked out here rather than asked of the server: their SHA-256, quoted. */
    private fun etag(bytes: ByteArray): String =
        "\"" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + "\""

    /** Callers that are not the owner: no token at all, and somebody else's. */
    private val strangers = listOf(null, "not-the-owner-token")

    /** The state a first run's create names: no key document of either kind. */
    private val firstRun = HttpHeaders.IfNoneMatch to "*"

    /** The state an enrolment's create names: the key parameters with [etag]. */
    private fun enrolling(etag: String) = HttpHeaders.IfMatch to etag

    private suspend fun ApplicationTestBuilder.getDoc(auth: String? = token) =
        client.get("/v1/keydoc") { auth?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private suspend fun ApplicationTestBuilder.create(
        body: ByteArray,
        condition: Pair<String, String>? = firstRun,
        auth: String? = token,
    ) = client.post("/v1/keydoc") {
        auth?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        condition?.let { (name, value) -> header(name, value) }
        setBody(body)
    }

    /** An enrolment as a device makes one: read the key parameters, then create against their ETag. */
    private suspend fun ApplicationTestBuilder.enrol(body: ByteArray): HttpResponse {
        val read = getDoc()
        assertEquals(KEY_DOCUMENT_KEYPARAMS, read.headers[KEY_DOCUMENT_HEADER], "an enrolment reads key parameters")
        return create(body, enrolling(read.headers[HttpHeaders.ETag]!!))
    }

    private suspend fun ApplicationTestBuilder.newVersion(version: String, body: ByteArray, auth: String? = token) =
        client.put("/v1/keydoc/$version") {
            auth?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.getKeyparams(auth: String? = token) =
        client.get("/v1/keyparams") { auth?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private suspend fun ApplicationTestBuilder.putKeyparams(body: ByteArray, auth: String? = token) =
        client.put("/v1/keyparams") {
            auth?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }

    /** The wrapped key the read serves: its version, from the header, its ETag, and its bytes. */
    private suspend fun assertServesWrapped(res: HttpResponse, version: Long, document: ByteArray) {
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(KEY_DOCUMENT_WRAPPED, res.headers[KEY_DOCUMENT_HEADER])
        assertEquals(version.toString(), res.headers[KEY_DOCUMENT_VERSION_HEADER])
        assertEquals(etag(document), res.headers[HttpHeaders.ETag])
        assertContentEquals(document, res.bodyAsBytes(), "the wrapped key, byte for byte")
    }

    private suspend fun assertPreconditionFailed(res: HttpResponse, what: String = "") {
        assertEquals(HttpStatusCode.PreconditionFailed, res.status, what)
        assertEquals("""{"error":"$PRECONDITION_FAILED_MESSAGE"}""", res.bodyAsText(), what)
    }

    // ── The owner's token ────────────────────────────────────────────────────────────────────────

    @Test
    fun `every key-document route refuses a caller without the owner's token, and answers the owner`() = serve(tmpDir()) {
        // The key parameters' write, then its read.
        for (auth in strangers) assertEquals(HttpStatusCode.Unauthorized, putKeyparams(keyparams("STRANGER"), auth).status)
        assertEquals(HttpStatusCode.NotFound, getKeyparams().status, "a refused write stored nothing")
        assertEquals(HttpStatusCode.NoContent, putKeyparams(keyparams("OWNERSALT")).status)
        for (auth in strangers) {
            val res = getKeyparams(auth)
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertFalse("OWNERSALT" in res.bodyAsText(), "a refused read carries none of the document")
            assertNull(res.headers[HttpHeaders.ETag], "a refused read names no document")
        }
        assertEquals(HttpStatusCode.OK, getKeyparams().status)

        // The create, carrying the state an owner's would, and none: the token is asked first, so a
        // stranger learns nothing from which precondition would have failed.
        val tag = etag(keyparams("OWNERSALT"))
        for (auth in strangers) {
            assertEquals(HttpStatusCode.Unauthorized, create(wrapped("FIRST"), enrolling(tag), auth).status)
            assertEquals(HttpStatusCode.Unauthorized, create(wrapped("FIRST"), null, auth).status)
        }
        assertEquals(KEY_DOCUMENT_KEYPARAMS, getDoc().headers[KEY_DOCUMENT_HEADER], "a refused create stored nothing")
        assertEquals(HttpStatusCode.Created, create(wrapped("FIRST"), enrolling(tag)).status)

        // The new version.
        for (auth in strangers) assertEquals(HttpStatusCode.Unauthorized, newVersion("2", wrapped("SECOND"), auth).status)
        assertEquals("1", getDoc().headers[KEY_DOCUMENT_VERSION_HEADER], "a refused new version stored nothing")
        assertEquals(HttpStatusCode.Created, newVersion("2", wrapped("SECOND")).status)

        // The read, with a wrapped key there to be leaked.
        for (auth in strangers) {
            val res = getDoc(auth)
            assertEquals(HttpStatusCode.Unauthorized, res.status)
            assertEquals("""{"error":"unauthorized"}""", res.bodyAsText())
            assertNull(res.headers[KEY_DOCUMENT_HEADER], "a refused read does not say which document exists")
            assertNull(res.headers[HttpHeaders.ETag], "a refused read names no document")
        }
        assertServesWrapped(getDoc(), 2, wrapped("SECOND"))
    }

    // ── The read ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the read serves the key parameters while no wrapped key exists, and the wrapped key once one does`() = serve(tmpDir()) {
        val none = getDoc()
        assertEquals(HttpStatusCode.NotFound, none.status, "no key document of either kind")
        assertEquals("""{"error":"no key document"}""", none.bodyAsText())
        assertNull(none.headers[KEY_DOCUMENT_HEADER])
        assertNull(none.headers[HttpHeaders.ETag])

        assertEquals(HttpStatusCode.NoContent, putKeyparams(keyparams("SALT")).status)
        val params = getDoc()
        assertEquals(HttpStatusCode.OK, params.status)
        assertEquals(KEY_DOCUMENT_KEYPARAMS, params.headers[KEY_DOCUMENT_HEADER])
        assertNull(params.headers[KEY_DOCUMENT_VERSION_HEADER], "the key parameters carry no version")
        assertEquals(etag(keyparams("SALT")), params.headers[HttpHeaders.ETag], "the ETag is the SHA-256 of the bytes served")
        assertEquals("no-store", params.headers[HttpHeaders.CacheControl])
        assertContentEquals(keyparams("SALT"), params.bodyAsBytes())
        assertEquals(etag(keyparams("SALT")), getKeyparams().headers[HttpHeaders.ETag], "the key parameters' own read names them the same way")

        // With only key parameters there, the enrolment that names them is taken.
        val created = create(wrapped("ENROLLED"), enrolling(params.headers[HttpHeaders.ETag]!!))
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("""{"version":1}""", created.bodyAsText())
        val doc = getDoc()
        assertServesWrapped(doc, 1, wrapped("ENROLLED"))
        assertEquals("no-store", doc.headers[HttpHeaders.CacheControl])
    }

    // ── The create names the state it read ───────────────────────────────────────────────────────

    @Test
    fun `a first run is taken on an empty store, and refused with 412 once key parameters exist`() {
        // The positive control: nothing of either kind, and the first run's master is taken.
        serve(tmpDir()) {
            assertEquals(HttpStatusCode.Created, create(wrapped("MINTED"), firstRun).status)
            assertServesWrapped(getDoc(), 1, wrapped("MINTED"))
        }
        // The legacy writer's race: the first run read nothing, and key parameters were published
        // before its create arrived. A random master taken now would strand what that writer wrote.
        serve(tmpDir()) {
            assertEquals(HttpStatusCode.NotFound, getDoc().status, "the first run reads no key document")
            assertEquals(HttpStatusCode.NoContent, putKeyparams(keyparams("LEGACY")).status, "a legacy writer publishes key parameters")
            assertPreconditionFailed(create(wrapped("MINTED"), firstRun))
            val now = getDoc()
            assertEquals(KEY_DOCUMENT_KEYPARAMS, now.headers[KEY_DOCUMENT_HEADER], "no second master was minted")
            assertContentEquals(keyparams("LEGACY"), now.bodyAsBytes())
        }
    }

    @Test
    fun `an enrolment is refused with 412 unless the key parameters are the bytes it read, and taken when they are`() {
        val dir = tmpDir()
        val file = File(dir, KeyDocumentStore.KEYPARAMS_FILE)
        serve(dir) {
            assertPreconditionFailed(create(wrapped("EARLY"), enrolling(etag(keyparams("A")))), "no key parameters to enrol from")

            assertEquals(HttpStatusCode.NoContent, putKeyparams(keyparams("A")).status)
            val read = getDoc().headers[HttpHeaders.ETag]!!
            assertEquals(etag(keyparams("A")), read)
            // The salt changes under the enrolment. Not through the API, which writes key parameters
            // once, but as a restore from an older backup, a hand edit, or another server answering at
            // this address would change it.
            file.writeBytes(keyparams("B"))
            assertPreconditionFailed(create(wrapped("FROM-A"), enrolling(read)), "a stale ETag")

            val reread = getDoc()
            assertEquals(KEY_DOCUMENT_KEYPARAMS, reread.headers[KEY_DOCUMENT_HEADER], "nothing stored")
            val fresh = reread.headers[HttpHeaders.ETag]!!
            assertNotEquals(read, fresh, "the ETag names the bytes, so a changed salt changes it")
            // The positive control: the enrolment that read the key parameters as they are is taken.
            assertEquals(HttpStatusCode.Created, create(wrapped("FROM-B"), enrolling(fresh)).status)
            assertServesWrapped(getDoc(), 1, wrapped("FROM-B"))
        }
    }

    @Test
    fun `a create that names no state, or names one in any other form, is refused with 428`() {
        val forms: List<Pair<String, List<Pair<String, String>>>> = listOf(
            "no precondition" to emptyList(),
            "If-Match: *" to listOf(HttpHeaders.IfMatch to "*"),
            "a weak ETag" to listOf(HttpHeaders.IfMatch to "W/\"abc\""),
            "two ETags" to listOf(HttpHeaders.IfMatch to "\"abc\", \"def\""),
            "an unquoted ETag" to listOf(HttpHeaders.IfMatch to "abc"),
            "If-None-Match naming an ETag" to listOf(HttpHeaders.IfNoneMatch to "\"abc\""),
            "both headers" to listOf(HttpHeaders.IfNoneMatch to "*", HttpHeaders.IfMatch to "\"abc\""),
        )
        suspend fun ApplicationTestBuilder.assertRequired() {
            for ((what, headers) in forms) {
                val res = client.post("/v1/keydoc") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    headers.forEach { (name, value) -> header(name, value) }
                    setBody(wrapped("UNSTATED"))
                }
                assertEquals(428, res.status.value, what)
                assertEquals("""{"error":"$PRECONDITION_REQUIRED_MESSAGE"}""", res.bodyAsText(), what)
            }
        }
        serve(tmpDir()) {
            assertRequired()
            assertEquals(HttpStatusCode.NotFound, getDoc().status, "nothing stored")
            // The positive control: the same create, naming the state it read, is taken.
            assertEquals(HttpStatusCode.Created, create(wrapped("UNSTATED"), firstRun).status)
        }
        // Over key parameters too, where a create naming nothing was once an enrolment.
        serve(tmpDir()) {
            assertEquals(HttpStatusCode.NoContent, putKeyparams(keyparams("SALT")).status)
            assertRequired()
            assertEquals(KEY_DOCUMENT_KEYPARAMS, getDoc().headers[KEY_DOCUMENT_HEADER], "nothing stored")
            assertEquals(HttpStatusCode.Created, enrol(wrapped("UNSTATED")).status)
        }
    }

    // ── The key parameters: written once, then superseded ────────────────────────────────────────

    @Test
    fun `the key parameters are written once, and a second write is refused with 409 leaving the first bytes`() {
        val dir = tmpDir()
        val file = File(dir, KeyDocumentStore.KEYPARAMS_FILE)
        serve(dir) {
            assertEquals(HttpStatusCode.NoContent, putKeyparams(keyparams("FIRST")).status)
            val second = putKeyparams(keyparams("SECOND"))
            assertEquals(HttpStatusCode.Conflict, second.status)
            assertEquals("""{"error":"the key parameters already exist"}""", second.bodyAsText())
            val read = getKeyparams()
            assertContentEquals(keyparams("FIRST"), read.bodyAsBytes())
            assertEquals(etag(keyparams("FIRST")), read.headers[HttpHeaders.ETag])
        }
        assertContentEquals(keyparams("FIRST"), file.readBytes())
    }

    @Test
    fun `the key parameters are served until a wrapped key exists, and answer 410 from then on`() = serve(tmpDir()) {
        assertEquals(HttpStatusCode.NoContent, putKeyparams(keyparams("SALT")).status)
        val before = getKeyparams()
        assertEquals(HttpStatusCode.OK, before.status, "served while no wrapped key exists")
        assertContentEquals(keyparams("SALT"), before.bodyAsBytes())

        assertEquals(HttpStatusCode.Created, enrol(wrapped("ENROLLED")).status)
        val after = getKeyparams()
        assertEquals(HttpStatusCode.Gone, after.status, "not served once a wrapped key exists")
        assertEquals("""{"error":"superseded by the wrapped key"}""", after.bodyAsText())
        assertNull(after.headers[HttpHeaders.ETag])
        assertEquals(KEY_DOCUMENT_WRAPPED, getDoc().headers[KEY_DOCUMENT_HEADER], "nor by the key-document read")
    }

    @Test
    fun `the key parameters answer 410 to a write once a wrapped key exists, leaving the file as it was`() {
        val dir = tmpDir()
        val file = File(dir, KeyDocumentStore.KEYPARAMS_FILE)
        serve(dir) {
            assertEquals(HttpStatusCode.NoContent, putKeyparams(keyparams("FIRST")).status)
            assertEquals(HttpStatusCode.Created, enrol(wrapped("ENROLLED")).status)
            val refused = putKeyparams(keyparams("THIRD"))
            assertEquals(HttpStatusCode.Gone, refused.status, "not written once a wrapped key exists")
            assertEquals("""{"error":"superseded by the wrapped key"}""", refused.bodyAsText())
        }
        assertContentEquals(keyparams("FIRST"), file.readBytes(), "never deleted and never overwritten")

        // A first run's master supersedes key parameters that were never published: a legacy writer
        // arriving later publishes none.
        val firstRunDir = tmpDir()
        serve(firstRunDir) {
            assertEquals(HttpStatusCode.Created, create(wrapped("MINTED"), firstRun).status)
            assertEquals(HttpStatusCode.Gone, putKeyparams(keyparams("LATE")).status)
            assertEquals(HttpStatusCode.Gone, getKeyparams().status)
        }
        assertFalse(File(firstRunDir, KeyDocumentStore.KEYPARAMS_FILE).exists(), "nothing written")
    }

    // ── Insert-only versions ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a second create is refused with 412 and the first document is unchanged byte for byte`() = serve(tmpDir()) {
        assertEquals(HttpStatusCode.Created, create(wrapped("FIRST")).status)
        assertPreconditionFailed(create(wrapped("SECOND")), "a second first run")
        assertServesWrapped(getDoc(), 1, wrapped("FIRST"))
        // The state is weighed before the content, as HTTP orders them: a body that would be refused
        // does not turn "read again" into "fix your document".
        assertPreconditionFailed(create("[]".toByteArray()), "a second first run with a body that is not an object")
        // Whatever state it names, the wrapped key's own ETag included.
        assertPreconditionFailed(create(wrapped("THIRD"), enrolling(etag(wrapped("FIRST")))), "an enrolment")
        assertServesWrapped(getDoc(), 1, wrapped("FIRST"))

        // Refused at any version, not only while version 1 is the newest.
        assertEquals(HttpStatusCode.Created, newVersion("2", wrapped("CHANGED")).status)
        assertPreconditionFailed(create(wrapped("FOURTH")), "after a new version")
        assertServesWrapped(getDoc(), 2, wrapped("CHANGED"))
    }

    @Test
    fun `of creates sent at once, exactly one is taken and the read serves that one`() = serve(tmpDir()) {
        val bodies = (1..16).map { wrapped("DEVICE$it") }
        val answers = coroutineScope { bodies.map { body -> async { body to create(body).status } }.awaitAll() }
        val taken = answers.filter { it.second == HttpStatusCode.Created }
        assertEquals(1, taken.size, "exactly one master is minted: ${answers.map { it.second.value }}")
        assertTrue(answers.all { it.second == HttpStatusCode.Created || it.second == HttpStatusCode.PreconditionFailed })
        assertServesWrapped(getDoc(), 1, taken.single().first)
    }

    @Test
    fun `versions append, and the read serves the newest`() = serve(tmpDir()) {
        assertEquals(HttpStatusCode.Created, create(wrapped("ONE")).status)
        val two = newVersion("2", wrapped("TWO"))
        assertEquals(HttpStatusCode.Created, two.status)
        assertEquals("""{"version":2}""", two.bodyAsText())
        assertServesWrapped(getDoc(), 2, wrapped("TWO"))
        assertEquals(HttpStatusCode.Created, newVersion("3", wrapped("THREE")).status)
        assertServesWrapped(getDoc(), 3, wrapped("THREE"))
    }

    @Test
    fun `a new version is taken only after the newest, so a stale or skipped write is refused and changes nothing`() = serve(tmpDir()) {
        val beforeAny = newVersion("2", wrapped("EARLY"))
        assertEquals(HttpStatusCode.Conflict, beforeAny.status, "no wrapped key yet: there is nothing to change")
        assertEquals(HttpStatusCode.NotFound, getDoc().status)

        assertEquals(HttpStatusCode.Created, create(wrapped("ONE")).status)
        assertEquals(HttpStatusCode.Created, newVersion("2", wrapped("TWO-A")).status)
        // Another device changed version 1 as well, and lost the race.
        val stale = newVersion("2", wrapped("TWO-B"))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("""{"error":"not the next version"}""", stale.bodyAsText())
        assertEquals(HttpStatusCode.Conflict, newVersion("4", wrapped("FOUR")).status, "a version skipped")
        assertServesWrapped(getDoc(), 2, wrapped("TWO-A"))

        // Version 1 is the create's alone, and a version is a number.
        for (v in listOf("1", "0", "-1")) {
            val res = newVersion(v, wrapped("LOW"))
            assertEquals(HttpStatusCode.BadRequest, res.status, "version $v")
            assertEquals("""{"error":"a new version is 2 or more"}""", res.bodyAsText())
        }
        for (v in listOf("x", "2.0", "3e0")) {
            assertEquals(HttpStatusCode.BadRequest, newVersion(v, wrapped("NAN")).status, "version $v")
        }
        assertServesWrapped(getDoc(), 2, wrapped("TWO-A"))

        // The positive control: the next version is still taken after every refusal.
        assertEquals(HttpStatusCode.Created, newVersion("3", wrapped("THREE")).status)
        assertServesWrapped(getDoc(), 3, wrapped("THREE"))
    }

    // ── Size and shape ───────────────────────────────────────────────────────────────────────────

    /** One JSON object of exactly [size] bytes. */
    private fun objectOfSize(size: Int): ByteArray =
        ("""{"pad":"""" + "a".repeat(size - 10) + """"}""").toByteArray().also { assertEquals(size, it.size) }

    @Test
    fun `a document over 16 KiB is refused with 413, and one of exactly 16 KiB is taken`() = serve(tmpDir()) {
        val limit = KeyDocumentStore.WRAPPED_KEY_MAX_BYTES
        assertEquals(16 * 1024, limit)
        assertEquals(HttpStatusCode.PayloadTooLarge, create(objectOfSize(limit + 1)).status)
        assertEquals(HttpStatusCode.NotFound, getDoc().status, "nothing stored")
        assertEquals(HttpStatusCode.Created, create(objectOfSize(limit)).status)
        assertServesWrapped(getDoc(), 1, objectOfSize(limit))

        assertEquals(HttpStatusCode.PayloadTooLarge, newVersion("2", objectOfSize(limit + 1)).status)
        assertServesWrapped(getDoc(), 1, objectOfSize(limit))
        assertEquals(HttpStatusCode.Created, newVersion("2", objectOfSize(limit)).status)
    }

    @Test
    fun `a body that is not one JSON object in UTF-8 is refused with 400 and stores nothing`() = serve(tmpDir()) {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val notAnObject: List<Pair<String, ByteArray>> = listOf(
            "empty" to ByteArray(0),
            "an array" to "[]".toByteArray(),
            "an array of an object" to "[{}]".toByteArray(),
            "a string" to "\"text\"".toByteArray(),
            "a number" to "1".toByteArray(),
            "null" to "null".toByteArray(),
            "true" to "true".toByteArray(),
            "cut short" to "{\"v\":1".toByteArray(),
            "something after it" to "{\"v\":1} x".toByteArray(),
            "two objects" to "{}{}".toByteArray(),
            "not JSON" to "not json".toByteArray(),
            "an unquoted key" to "{v:1}".toByteArray(),
            "a trailing comma" to "{\"v\":1,}".toByteArray(),
            "a comment" to "{/* c */}".toByteArray(),
            "a byte-order mark" to bom + "{}".toByteArray(),
            "malformed UTF-8" to "{\"v\":\"".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\"}".toByteArray(),
            "an encoded surrogate" to "{\"v\":\"".toByteArray() + byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) + "\"}".toByteArray(),
        )
        for ((what, body) in notAnObject) {
            val res = create(body)
            assertEquals(HttpStatusCode.BadRequest, res.status, what)
            assertEquals("""{"error":"the key document must be a JSON object"}""", res.bodyAsText(), what)
        }
        assertEquals(HttpStatusCode.NotFound, getDoc().status, "nothing stored")

        // The positive control: the server checks the shape, never the contents. Any one object is taken.
        assertEquals(HttpStatusCode.Created, create("{}".toByteArray()).status)
        for ((what, body) in notAnObject) assertEquals(HttpStatusCode.BadRequest, newVersion("2", body).status, what)
        assertServesWrapped(getDoc(), 1, "{}".toByteArray())
    }

    @Test
    fun `a document nested deeper than 32 is refused with 400, and brackets inside a string are not nesting`() = serve(tmpDir()) {
        val max = KeyDocumentStore.WRAPPED_KEY_MAX_DEPTH
        assertEquals(32, max)
        /** An object [depth] deep: the object itself, and arrays inside it. */
        fun nested(depth: Int) = ("{\"a\":" + "[".repeat(depth - 1) + "]".repeat(depth - 1) + "}").toByteArray()
        val deep = listOf(
            "one level too deep" to nested(max + 1),
            // As deep as the size allows. Parsed a level at a time, this overflowed the stack: a 500.
            "as deep as 16 KiB allows" to nested(8_000),
            // An escaped backslash ends its escape, so the quote after it closes the string and the
            // brackets after that are real. Counted as text, they would reach the parser uncounted.
            "after an escaped backslash" to ("{\"a\":\"\\\\\",\"b\":" + "[".repeat(8_000) + "]".repeat(8_000) + "}").toByteArray(),
        )
        for ((what, body) in deep) {
            assertTrue(body.size <= KeyDocumentStore.WRAPPED_KEY_MAX_BYTES, what)
            val res = create(body)
            assertEquals(HttpStatusCode.BadRequest, res.status, what)
            assertEquals("""{"error":"the key document is nested too deeply"}""", res.bodyAsText(), what)
        }
        assertEquals(HttpStatusCode.NotFound, getDoc().status, "nothing stored")

        assertEquals(HttpStatusCode.Created, create(nested(max)).status, "exactly as deep as the limit is taken")
        // Brackets inside a string, after an escaped quote too, are text: this is one level deep.
        val inString = ("{\"a\":\"\\\"" + "[".repeat(8_000) + "\"}").toByteArray()
        assertEquals(HttpStatusCode.Created, newVersion("2", inString).status)
        assertServesWrapped(getDoc(), 2, inString)
    }

    // ── A restart ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the wrapped key and the superseded key parameters survive a restart`() {
        val dir = tmpDir()
        serve(dir) {
            assertEquals(HttpStatusCode.NoContent, putKeyparams(keyparams("SALT")).status)
            assertEquals(HttpStatusCode.Created, enrol(wrapped("ONE")).status)
            assertEquals(HttpStatusCode.Created, newVersion("2", wrapped("TWO")).status)
        }
        assertTrue(File(dir, KeyDocumentStore.DB_FILE).isFile, "the wrapped key lives in ${KeyDocumentStore.DB_FILE}")

        // A second application over the same volume: a new store, nothing carried over in memory.
        serve(dir) {
            assertServesWrapped(getDoc(), 2, wrapped("TWO"))
            assertEquals(HttpStatusCode.Gone, getKeyparams().status)
            assertEquals(HttpStatusCode.Gone, putKeyparams(keyparams("NEW")).status)
            assertPreconditionFailed(create(wrapped("AGAIN"), firstRun), "a first run")
            assertPreconditionFailed(create(wrapped("AGAIN"), enrolling(etag(keyparams("SALT")))), "an enrolment")
            assertEquals(HttpStatusCode.Conflict, newVersion("2", wrapped("STALE")).status)
            assertEquals(HttpStatusCode.Created, newVersion("3", wrapped("THREE")).status)
            assertServesWrapped(getDoc(), 3, wrapped("THREE"))
        }
        assertContentEquals(keyparams("SALT"), File(dir, KeyDocumentStore.KEYPARAMS_FILE).readBytes())
    }

    // ── The log ──────────────────────────────────────────────────────────────────────────────────

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

    @Test
    fun `nothing a key-document route logs carries a document, an ETag, the token or the volume's path`() {
        val dir = tmpDir()
        var tag = ""
        serve(dir) {
            // Every answer the routes give, taken and refused.
            putKeyparams(keyparams("KDMARKSALT"))
            putKeyparams(keyparams("KDMARKSALT2"))
            tag = getDoc().headers[HttpHeaders.ETag]!!
            create(wrapped("KDMARKFIRST"), null)
            create(wrapped("KDMARKFIRST"), firstRun)
            create(wrapped("KDMARKFIRST"), enrolling(tag))
            create(wrapped("KDMARKSECOND"), enrolling(tag))
            newVersion("2", wrapped("KDMARKTHIRD"))
            newVersion("2", wrapped("KDMARKFOURTH"))
            newVersion("3", objectOfSize(KeyDocumentStore.WRAPPED_KEY_MAX_BYTES + 1))
            newVersion("3", "[\"KDMARKFIFTH\"]".toByteArray())
            getKeyparams()
            putKeyparams(keyparams("KDMARKSIXTH"))
            getDoc()
        }
        val lines = appender.list.map { it.formattedMessage }
        // The positive control: the appender hears this application, so an empty list is not the verdict.
        assertTrue(lines.any { "shape" in it }, "the appender must hear the application's start: $lines")
        val forbidden = listOf("KDMARK", tag.trim('"'), token, dir)
        assertTrue(forbidden.all { it.isNotEmpty() }, "every forbidden text is one a line could contain")
        for (line in lines) {
            for (secret in forbidden) assertFalse(secret in line, "a log line carries $secret: $line")
        }
    }

    @Test
    fun `a volume that refuses the key documents answers 507, and logs one line per failure with no path`() {
        val dir = tmpDir()
        val kp = File(dir, KeyDocumentStore.KEYPARAMS_FILE)
        val staging = File(dir, "tmp")
        serve(dir) {
            // Something unreadable where the key parameters go: a directory, which root cannot read as a file either.
            assertTrue(kp.mkdir() && File(kp, "occupied").createNewFile())
            for (res in listOf(getDoc(), getKeyparams())) {
                assertEquals(HttpStatusCode.InsufficientStorage, res.status)
                assertEquals("""{"error":"insufficient storage"}""", res.bodyAsText())
            }
            // Nothing there, and a staging area that cannot stage: a file where its directory goes.
            assertTrue(kp.deleteRecursively() && staging.deleteRecursively() && staging.createNewFile())
            val put = putKeyparams(keyparams("SALT"))
            assertEquals(HttpStatusCode.InsufficientStorage, put.status)
            assertEquals("""{"error":"insufficient storage"}""", put.bodyAsText())

            // The positive control: the same volume, repaired, takes the same write and serves it.
            assertTrue(staging.delete() && staging.mkdir())
            assertEquals(HttpStatusCode.NoContent, putKeyparams(keyparams("SALT")).status)
            assertEquals(KEY_DOCUMENT_KEYPARAMS, getDoc().headers[KEY_DOCUMENT_HEADER])
        }
        val failures = appender.list.map { it.formattedMessage }.filter { "key document store" in it }
        assertEquals(3, failures.size, "one line per failed request: $failures")
        for (line in failures) {
            assertFalse(dir in line || KeyDocumentStore.KEYPARAMS_FILE in line, "no path: $line")
            assertNotEquals("key document store I/O error: null", line, "the line names the failure's class")
        }
    }
}
