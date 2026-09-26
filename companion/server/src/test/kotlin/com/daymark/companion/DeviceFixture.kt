package com.daymark.companion

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
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

/** The owner's bearer token on every server a device test starts. */
internal const val DEVICE_TEST_TOKEN = "owner-token-for-device-tests"

/**
 * A server for the device tests (#186, #189): its data directory, a clock the test moves, and the
 * stores the test reads back. [start] may be called again on the same directory, which is a restart.
 */
internal class DeviceServer(
    val dataDir: File = Files.createTempDirectory("device-test").toFile(),
    var now: Long = 1_790_000_000_000L,
    val publicBaseUrl: String? = "https://daymark.example.com",
    val mode: SetupMode = SetupMode.SOLO,
    val lockoutFails: Int = 100_000,
    val rateLimitRps: Int = 100_000,
    val authToken: String = DEVICE_TEST_TOKEN,
) {
    lateinit var account: OwnerAccountStore
    lateinit var ownerAudit: AuditStore

    fun config() = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dataDir.path, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = authToken,
        maxBlobBytes = 1_048_576L, maxRequestBytes = 2_097_152L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = lockoutFails, authLockoutSeconds = 900L, rateLimitRps = rateLimitRps,
        setupMode = mode, publicBaseUrl = publicBaseUrl,
    )

    /** The server's time in whole seconds, as a phone on the same clock signs it. */
    val seconds: Long get() = now / 1000

    fun start(builder: ApplicationTestBuilder) {
        account = OwnerAccountStore(dataDir.path, authToken, clock = { now })
        ownerAudit = AuditStore(dataDir.path, dbName = OWNER_AUDIT_DB, clock = { now / 1000 })
        val cfg = config()
        builder.application { module(cfg, accountStore = account, ownerAuditStore = ownerAudit) }
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
