package com.daymark.companion

import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.OwnerAccountStore
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
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.File
import java.net.Socket
import java.nio.file.Files
import kotlin.test.assertEquals

/** The owner's bearer token on every server a device test starts. */
internal const val DEVICE_TEST_TOKEN = "owner-token-for-device-tests"

/**
 * A server for the device tests (#186, #189): its data directory, a clock the test moves, and the
 * stores the test reads back. [start] may be called again on the same directory, which is a restart.
 * [startNetty] runs the same server on a real Netty engine, for requests only a socket can make.
 */
internal class DeviceServer(
    val dataDir: File = Files.createTempDirectory("device-test").toFile(),
    @Volatile var now: Long = 1_790_000_000_000L,
    val publicBaseUrl: String? = "https://daymark.example.com",
    val mode: SetupMode = SetupMode.SOLO,
    val lockoutFails: Int = 100_000,
    val rateLimitRps: Int = 100_000,
    val authToken: String = DEVICE_TEST_TOKEN,
    val maxRequestBytes: Long = 2_097_152L,
    /** The server's mail, for a test that reads what was sent; null for the configuration's own. */
    val mailer: Mailer? = null,
    /** `DAYMARK_TRUSTED_PROXIES`: a test on Netty trusts 127.0.0.1 to send requests from addresses of its choosing. */
    val trustedProxies: String? = null,
) {
    lateinit var account: OwnerAccountStore
    lateinit var ownerAudit: AuditStore

    /**
     * Called after every reading of the owner store's clock, on the thread that read it: a test's way to
     * know where a request has got to. The reading is taken first, so a test that moves [now] from here
     * moves it for the next reading and never for the one that called it.
     */
    @Volatile var onClockRead: (() -> Unit)? = null

    fun config() = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dataDir.path, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = authToken,
        maxBlobBytes = maxRequestBytes / 2, maxRequestBytes = maxRequestBytes,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = lockoutFails, authLockoutSeconds = 900L, rateLimitRps = rateLimitRps,
        setupMode = mode, publicBaseUrl = publicBaseUrl,
        trustedProxies = ClientAddress.parseTrusted(trustedProxies),
    )

    /** The server's time in whole seconds, as a phone on the same clock signs it. */
    val seconds: Long get() = now / 1000

    private fun open() {
        account = OwnerAccountStore(dataDir.path, authToken, clock = { val reading = now; onClockRead?.invoke(); reading })
        ownerAudit = AuditStore(dataDir.path, dbName = OWNER_AUDIT_DB, clock = { now / 1000 })
    }

    fun start(builder: ApplicationTestBuilder) {
        open()
        val cfg = config()
        builder.application { module(cfg, mailer = mailer, accountStore = account, ownerAuditStore = ownerAudit) }
    }

    /** The same server on a real Netty engine on a free local port; close it when done. */
    fun startNetty(): LiveServer {
        open()
        val cfg = config()
        val engine = embeddedServer(Netty, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            module(cfg, mailer = mailer, accountStore = account, ownerAuditStore = ownerAudit)
        }.start(wait = false)
        val port = runBlocking { engine.engine.resolvedConnectors().first().port }
        return LiveServer(this, port) { engine.stop(100, 2_000) }
    }

    /** The owner's log, oldest first, as `action` or `action:by` for a row that carries one. */
    fun ownerLog(): List<String> =
        ownerAudit.list(account.devices.ownerId, limit = 200).reversed().map { e -> e.action + (e.meta?.get("by")?.let { ":$it" } ?: "") }

    // ---- The ceremony, step by step -------------------------------------------------------------

    suspend fun mint(client: HttpClient): Minted {
        val res = client.post("/v1/devices/pairing") { header(HttpHeaders.Authorization, "Bearer $authToken") }
        assertEquals(HttpStatusCode.Created, res.status, "mint: ${res.bodyAsText()}")
        val json = res.json()
        return Minted(json.string("codeId"), json.string("code"), json.string("baseUrl"), json.string("expiresAt").toLong())
    }

    suspend fun redeem(client: HttpClient, phone: TestPhone, code: String, proof: String = phone.redeemProof(code)): HttpResponse =
        client.post("/v1/devices/redeem") {
            contentType(ContentType.Application.Json)
            setBody(phone.redeemBody(code, proof))
        }

    suspend fun confirm(client: HttpClient, codeId: String, keyId: String): HttpResponse =
        client.post("/v1/devices/pairing/$codeId/confirm") {
            header(HttpHeaders.Authorization, "Bearer $authToken")
            contentType(ContentType.Application.Json)
            setBody("""{"keyId":"$keyId"}""")
        }

    /** Mint, redeem and confirm: [phone]'s key is registered. */
    suspend fun pair(client: HttpClient, phone: TestPhone) {
        val minted = mint(client)
        val redeemed = redeem(client, phone, minted.code)
        assertEquals(HttpStatusCode.Accepted, redeemed.status, "redeem: ${redeemed.bodyAsText()}")
        val confirmed = confirm(client, minted.codeId, phone.keyId)
        assertEquals(HttpStatusCode.Created, confirmed.status, "confirm: ${confirmed.bodyAsText()}")
    }

    /** A GET of [target], signed by [phone] now, with a fresh nonce. */
    suspend fun signedGet(client: HttpClient, phone: TestPhone, target: String): HttpResponse =
        client.get(target) { signedWith(phone.headers("GET", target, timeSeconds = seconds)) }

    data class Minted(val codeId: String, val code: String, val baseUrl: String, val expiresAt: Long)
}

internal suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

/** A status and a body, as a socket read them. */
internal data class RawResponse(val status: Int, val body: String)

/**
 * The server on Netty, on [port], and HTTP/1.1 written by hand to it: a request can stop halfway
 * through its body, and be chunked or not, exactly as a person on the path could send it.
 */
internal class LiveServer(val server: DeviceServer, val port: Int, private val stop: () -> Unit) : AutoCloseable {

    /** A whole request, answered: [headers] as given, the body framed by Content-Length. */
    fun send(method: String, target: String, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)): RawResponse =
        open().use { c ->
            c.write(head(method, target, headers, chunked = false, length = body.size.toLong()))
            c.write(body)
            c.readResponse()
        }

    /** The owner console's request, with the token. */
    fun owner(method: String, target: String, body: String = ""): RawResponse =
        send(method, target, mapOf("Authorization" to "Bearer ${server.authToken}", "Content-Type" to "application/json"), body.toByteArray())

    /** Mint, redeem and confirm over the socket: [phone]'s key is registered. */
    fun pair(phone: TestPhone) {
        val minted = Json.parseToJsonElement(owner("POST", "/v1/devices/pairing").also { check(it.status == 201) { "mint: $it" } }.body).jsonObject
        val code = minted.string("code")
        val redeemed = send("POST", "/v1/devices/redeem", mapOf("Content-Type" to "application/json"), phone.redeemBody(code).toByteArray())
        check(redeemed.status == 202) { "redeem: $redeemed" }
        val confirmed = owner("POST", "/v1/devices/pairing/${minted.string("codeId")}/confirm", """{"keyId":"${phone.keyId}"}""")
        check(confirmed.status == 201) { "confirm: $confirmed" }
    }

    fun open(): RawConnection = RawConnection(Socket("127.0.0.1", port))

    override fun close() {
        stop()
        runCatching { server.account.close() }
        runCatching { server.ownerAudit.close() }
    }

    companion object {
        /** A request line and headers, framed by Content-Length [length], or chunked. */
        fun head(method: String, target: String, headers: Map<String, String>, chunked: Boolean, length: Long): ByteArray = buildString {
            append("$method $target HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append(if (chunked) "Transfer-Encoding: chunked\r\n" else "Content-Length: $length\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)

        /** One chunk of a chunked body. */
        fun chunk(bytes: ByteArray): ByteArray = "${bytes.size.toString(16)}\r\n".toByteArray() + bytes + "\r\n".toByteArray()

        val LAST_CHUNK: ByteArray = "0\r\n\r\n".toByteArray()
    }
}

/** One socket to the server, written and read by hand. */
internal class RawConnection(private val socket: Socket) : AutoCloseable {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()

    init {
        socket.soTimeout = 20_000
        socket.tcpNoDelay = true
    }

    fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /** Write [bytes] if the server still takes them; a server that already answered may have closed its side. */
    fun writeIfOpen(bytes: ByteArray) {
        runCatching { write(bytes) }
    }

    /** The answer, if the server has sent one within [waitMs] of asking; null while it still waits for more of the request. */
    fun answerWithin(waitMs: Long): RawResponse? {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) return readResponse()
            Thread.sleep(10)
        }
        return null
    }

    fun readResponse(): RawResponse {
        val status = readLine().split(' ')[1].toInt()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val body = when {
            headers["content-length"] != null -> input.readNBytes(headers.getValue("content-length").toInt())
            headers["transfer-encoding"]?.contains("chunked") == true -> generateSequence {
                val size = readLine().substringBefore(';').trim().toInt(16)
                if (size == 0) null else input.readNBytes(size).also { readLine() }
            }.fold(ByteArray(0)) { acc, part -> acc + part }
            else -> ByteArray(0)
        }
        return RawResponse(status, body.toString(Charsets.UTF_8))
    }

    private fun readLine(): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
        }
        return line.toString()
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
