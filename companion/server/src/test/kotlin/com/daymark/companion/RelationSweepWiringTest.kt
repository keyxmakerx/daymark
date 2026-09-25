package com.daymark.companion

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.BlobStore
import com.daymark.companion.storage.Channel
import com.daymark.companion.storage.RelationStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The relationship sweep as the running application schedules it (#338): once at start-up, before a
 * request is taken, then every hour, and stopped with the application.
 *
 * The store's clock and the scheduler's ticker are both injected, so a test moves days and hours by
 * changing numbers.
 */
class RelationSweepWiringTest {

    private val ownerToken = "owner-token-sweep"
    private val inboxToken = "inbox-token-sweep-0123456789"
    private val relRef = Secrets.relRefOf(inboxToken)
    private val day = 24L * 60 * 60 * 1000
    private val minute = 60L * 1000
    private var now = 1_800_000_000_000L
    private var ticks = 0L
    private val body = byteArrayOf(1, 2, 3, 4)

    private val logger = LoggerFactory.getLogger("com.daymark.companion.housekeeping") as Logger
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

    private class Fixture(
        val dir: String,
        val cfg: Config,
        val rel: RelationStore,
        val auth: AuthStore,
        val audit: AuditStore,
        val snapshots: BlobStore,
        val chores: Housekeeping,
    )

    private fun fixture(): Fixture {
        val dir = Files.createTempDirectory("rel-sweep-wiring").toString()
        val cfg = Config(
            bindAddr = "127.0.0.1", port = 8080, dataDir = dir, basePath = "/",
            webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
            maxBlobBytes = 1_000_000L, maxRequestBytes = 1_001_024L,
            maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
            authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
            therapistAuthEnabled = true, cookieSecure = false,
        )
        return Fixture(
            dir, cfg,
            RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes, clock = { now }),
            AuthStore(dir),
            AuditStore(dir),
            BlobStore(dir, cfg.maxBlobBytes, cfg.maxVersions, cfg.perTokenQuotaBytes),
            Housekeeping(ticker = { ticks }),
        )
    }

    private fun Fixture.file(channel: Channel, lineage: String, version: Long = 1): Path =
        Path.of(dir, "rel", relRef, channel.wire, lineage, "$version.blob")

    private fun io.ktor.server.testing.ApplicationTestBuilder.serve(f: Fixture) =
        application {
            module(f.cfg, f.snapshots, null, f.rel, f.auth, f.audit, relationClock = { now }, housekeeping = f.chores)
        }

    @Test
    fun `the application sweeps at start-up, and a read of what it removed answers 410`() = testApplication {
        val f = fixture()
        f.rel.put(relRef, Channel.ASSIGNMENTS, "task", 1, body, null, expiry = null)
        f.rel.put(relRef, Channel.GRANTS, "grant", 1, body, null, expiry = null)
        now += 91 * day // the assignment ended while the server was down
        assertTrue(Files.exists(f.file(Channel.ASSIGNMENTS, "task")), "precondition: its copy is still there")

        serve(f)
        startApplication()

        assertFalse(Files.exists(f.file(Channel.ASSIGNMENTS, "task")), "removed by the start-up run")
        assertTrue(Files.exists(f.file(Channel.GRANTS, "grant")), "control: a grant's copy is kept")
        val read = client.get("/v1/rel/$relRef/assignments/task/current") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.Gone, read.status, "410, never the 404 that reads as 'nothing was ever sent'")
        assertEquals("""{"error":"no longer available"}""", read.bodyAsText())
        val listed = client.get("/v1/rel/$relRef/assignments/task") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertTrue(listed.bodyAsText().contains("\"version\":1"), "the row is kept: ${listed.bodyAsText()}")
    }

    @Test
    fun `and again every hour`() = testApplication {
        val f = fixture()
        f.rel.put(relRef, Channel.ASSIGNMENTS, "task", 1, body, null, expiry = null)
        serve(f)
        startApplication()
        assertTrue(Files.exists(f.file(Channel.ASSIGNMENTS, "task")), "live at start-up, so kept")

        now += 91 * day // it ends while the server runs
        ticks = 59 * minute
        f.chores.runDue()
        assertTrue(Files.exists(f.file(Channel.ASSIGNMENTS, "task")), "not yet an hour since the last sweep")

        ticks = 60 * minute
        f.chores.runDue()
        assertFalse(Files.exists(f.file(Channel.ASSIGNMENTS, "task")), "removed by the hourly run")
    }

    @Test
    fun `a sync snapshot is untouched`() = testApplication {
        val f = fixture()
        val backup = byteArrayOf(5, 5, 5)
        f.snapshots.put("phone", 1, backup)
        f.rel.put(relRef, Channel.ASSIGNMENTS, "task", 1, body, null, expiry = null)
        now += 400 * day
        serve(f)
        startApplication()
        ticks = 60 * minute
        f.chores.runDue()

        assertFalse(Files.exists(f.file(Channel.ASSIGNMENTS, "task")), "control: the sweep ran")
        val read = client.get("/v1/snapshots/phone/1") { header(HttpHeaders.Authorization, "Bearer $ownerToken") }
        assertEquals(HttpStatusCode.OK, read.status)
        assertContentEquals(backup, read.bodyAsBytes())
    }

    @Test
    fun `the scheduler stops with the application`() {
        val f = fixture()
        testApplication {
            serve(f)
            startApplication()
            assertTrue(f.chores.isRunningInBackground, "control: running while the application is")
            assertFalse(f.chores.isClosed)
        }
        assertTrue(f.chores.isClosed, "stopped when the application stopped")
        assertFalse(f.chores.isRunningInBackground)
    }

    @Test
    fun `the sweep's log line carries counts only`() = testApplication {
        val f = fixture()
        f.rel.put(relRef, Channel.ASSIGNMENTS, "first-lineage", 7, body, null, expiry = null)
        f.rel.put(relRef, Channel.GAMEPLANS, "second-lineage", 8, body, null, expiry = null)
        f.rel.put(relRef, Channel.SHARES, "third-lineage", 9, body, null, expiry = now + 30 * day)
        // One copy that will not delete: a non-empty directory where the blob should be.
        val stuck = f.file(Channel.SHARES, "third-lineage", 9)
        Files.delete(stuck)
        Files.createDirectories(stuck.resolve("child"))
        now += 91 * day

        serve(f)
        startApplication()

        val lines = appender.list.filter { it.level == Level.WARN || it.level == Level.INFO }
        assertEquals(1, lines.size, lines.map { it.formattedMessage }.toString())
        val line = lines.single()
        assertEquals(Level.WARN, line.level, "a copy that would not delete is the operator's to know")
        assertEquals(
            "relationship sweep: 2 stored copies removed, 0 already gone, 1 could not be removed and are retried next sweep",
            line.formattedMessage,
        )
        assertTrue(line.argumentArray.all { it is Int }, "every argument is a count")
        // Nothing that names a relationship or an item. Control: each name is in the path of an item
        // this sweep handled, so a line that carried a path, or a name, would be caught here.
        val paths = listOf(
            f.file(Channel.ASSIGNMENTS, "first-lineage", 7),
            f.file(Channel.GAMEPLANS, "second-lineage", 8),
            stuck,
        ).map { it.toString() }
        val names = listOf(
            relRef, "first-lineage", "second-lineage", "third-lineage",
            Channel.ASSIGNMENTS.wire, Channel.GAMEPLANS.wire, Channel.SHARES.wire, "7.blob", "8.blob", "9.blob",
        )
        for (name in names) {
            assertTrue(paths.any { it.contains(name) }, "control: $name is a real name from this sweep")
            assertFalse(line.formattedMessage.contains(name), "the log line names $name")
        }
    }
}
