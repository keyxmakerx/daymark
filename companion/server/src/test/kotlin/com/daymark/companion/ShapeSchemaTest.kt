package com.daymark.companion

import com.daymark.companion.storage.Schema
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A start checks and changes only the databases its shape opens (#193, #330).
 *
 * All ten files are planted, each an unversioned database holding one table of its own, and a server
 * of each shape is started on them. The ones its shape opens are adopted: they gain their structure,
 * are copied first, and are marked with the version this release brings them to. The rest are left
 * byte for byte as they were, unversioned, so a server that changes shape keeps the files it no longer
 * opens without reading them. The adopted ones are the positive control: they show the planted files
 * are the ones the stores open.
 */
class ShapeSchemaTest {

    private val every = listOf(
        "index.db", "wrapped-key.db", "owner-account.db", "owner-audit.db",
        "auth.db", "rel-index.db", "audit.db", "pairing.db",
        "org.db", "org-audit.db",
    )

    /**
     * What each shape opens, restated from the issue's table (#330) rather than read from the code. The
     * owner's log is the sync API's (#189): a phone pairs in every shape.
     */
    private val opens = mapOf(
        SetupMode.SOLO to every.take(4),
        SetupMode.PAIRED to every.take(8),
        SetupMode.PRACTICE to every,
    )

    /** The version each database is brought to: owner-account.db's second holds the phones (#186, #189). */
    private val versionOf = every.associateWith { if (it == "owner-account.db") 2 else 1 }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun userVersion(file: File): Int = DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
        c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }
    }

    @Test
    fun `a start checks and changes only the databases its shape opens`() {
        for ((mode, opened) in opens) {
            val dataDir = Files.createTempDirectory("shape-schema").toFile()
            val webDir = Files.createTempDirectory("shape-schema-web").toFile()
            try {
                for (name in every) {
                    DriverManager.getConnection("jdbc:sqlite:${File(dataDir, name).path}").use { c ->
                        c.createStatement().use { st ->
                            st.execute("PRAGMA journal_mode=WAL")
                            st.execute("CREATE TABLE planted (x INTEGER)")
                            st.execute("INSERT INTO planted VALUES (1)")
                        }
                    }
                }
                val before = every.associateWith { sha256(File(dataDir, it)) }
                val config = Config(
                    bindAddr = "127.0.0.1", port = 8080, dataDir = dataDir.path, basePath = "/",
                    webDir = webDir.path, logLevel = "info", authToken = "owner-token-shape-schema",
                    maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
                    maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
                    authLockoutFails = 100, authLockoutSeconds = 1L, rateLimitRps = 100,
                    setupMode = mode, cookieSecure = false,
                )
                testApplication {
                    application { module(config) }
                    assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
                }

                val copies = File(dataDir, Schema.PRE_MIGRATE_DIR).list()?.toList().orEmpty()
                for (name in every) {
                    val file = File(dataDir, name)
                    if (name in opened) {
                        assertEquals(versionOf.getValue(name), userVersion(file), "${mode.wire}: $name is opened, so it is adopted and marked")
                        assertEquals(1, copies.count { it.startsWith(name.removeSuffix(".db") + ".v0.") }, "${mode.wire}: $name was copied first: $copies")
                    } else {
                        assertEquals(before.getValue(name), sha256(file), "${mode.wire}: $name is not opened, so not a byte of it changes")
                        assertEquals(0, userVersion(file), "${mode.wire}: $name is left unversioned")
                    }
                }
                assertEquals(opened.size, copies.size, "${mode.wire}: a copy of each database it changed, and no other: $copies")
            } finally {
                dataDir.deleteRecursively()
                webDir.deleteRecursively()
            }
        }
    }
}
