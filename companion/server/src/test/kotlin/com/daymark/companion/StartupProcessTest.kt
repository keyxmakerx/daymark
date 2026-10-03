package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.storage.BlobStore
import com.daymark.companion.storage.Schema
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `main` as a container runs it: a process of its own, with its own output and exit status.
 *
 * A refused configuration leaves exactly one line, naming the setting, and exit status 78 — not a
 * stack trace an operator has to read past, on every restart, to find what to change (#180). A
 * correct configuration gets past every refusal and serves. That is the positive control, and it
 * is also what proves this harness reads the server's output at all, so "one line" cannot be an
 * empty capture that happened to pass.
 */
class StartupProcessTest {

    private class Run(val process: Process, val reader: Thread, val lines: List<String>)

    private fun launch(env: Map<String, String>): Run {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val builder = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.daymark.companion.ApplicationKt")
            .redirectErrorStream(true)
        // Hermetic: no DAYMARK_* setting from the shell running the tests reaches the server under
        // test, and no JVM option variable adds a "Picked up ..." line of the JVM's own.
        builder.environment().keys.removeIf { it.startsWith("DAYMARK_") || it in JVM_OPTION_VARIABLES }
        builder.environment().putAll(env)
        val process = builder.start()
        val lines = CopyOnWriteArrayList<String>()
        val reader = thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine { lines += it } }
        return Run(process, reader, lines)
    }

    private fun settings(dataDir: File, vararg more: Pair<String, String>) = mapOf(
        "DAYMARK_BIND_ADDR" to "127.0.0.1",
        "DAYMARK_PORT" to "0",
        "DAYMARK_DATA_DIR" to dataDir.path,
        "DAYMARK_WEB_DIR" to File(dataDir, "no-web").path,
        "DAYMARK_THERAPIST_AUTH" to "1",
        // Named rather than left to the default, so the startup line below is shown whatever the
        // default is; DeploymentLogLevelTest is the test that owns the default.
        "DAYMARK_LOG_LEVEL" to "info",
    ) + more

    @Test
    fun `a refused configuration exits 78 with one line naming the setting, and no stack trace`() {
        val dataDir = Files.createTempDirectory("startup-refused").toFile()
        try {
            val run = launch(settings(dataDir))
            val exited = run.process.waitFor(60, TimeUnit.SECONDS)
            if (!exited) run.process.destroyForcibly()
            run.reader.join(10_000)
            assertTrue(exited, "a refused configuration must exit, not serve: ${run.lines}")
            assertEquals(EXIT_CONFIG, run.process.exitValue(), "${run.lines}")
            val out = run.lines.filter { it.isNotBlank() }
            assertEquals(1, out.size, "exactly one line: $out")
            assertTrue("ERROR" in out.single() && "Refusing to start: DAYMARK_PUBLIC_BASE_URL is not set." in out.single(), out.single())
            assertTrue(out.none { "Exception" in it || it.trimStart().startsWith("at ") }, "no stack trace: $out")
            assertEquals(emptyList(), dataDir.list()!!.toList(), "it refuses before it touches the volume")
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `an unknown setup mode exits 78 with one line naming the setting, never the value`() {
        // Everything else is correct, so the mode is the only thing it can be refusing (#330).
        val dataDir = Files.createTempDirectory("startup-refused-mode").toFile()
        try {
            val run = launch(
                settings(
                    dataDir,
                    "DAYMARK_PUBLIC_BASE_URL" to "https://daymark.example.com",
                    "DAYMARK_SETUP_MODE" to "zqx-everything",
                ),
            )
            val exited = run.process.waitFor(60, TimeUnit.SECONDS)
            if (!exited) run.process.destroyForcibly()
            run.reader.join(10_000)
            assertTrue(exited, "an unknown setup mode must exit, not serve: ${run.lines}")
            assertEquals(EXIT_CONFIG, run.process.exitValue(), "${run.lines}")
            val out = run.lines.filter { it.isNotBlank() }
            assertEquals(1, out.size, "exactly one line: $out")
            assertTrue(
                "ERROR" in out.single() &&
                    "Refusing to start: DAYMARK_SETUP_MODE is not one of solo, paired or practice." in out.single(),
                out.single(),
            )
            assertTrue("zqx" !in out.single(), "the value is never repeated back: ${out.single()}")
            assertEquals(emptyList(), dataDir.list()!!.toList(), "it refuses before it touches the volume")
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `a correct configuration gets past every refusal and serves`() {
        val dataDir = Files.createTempDirectory("startup-serves").toFile()
        val run = launch(
            settings(
                dataDir,
                "DAYMARK_PUBLIC_BASE_URL" to "https://daymark.example.com",
                "DAYMARK_AUTH_TOKEN" to "owner-token-startup-test",
            ),
        )
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (run.process.isAlive && run.lines.none { SERVING in it } && System.nanoTime() < deadline) {
                Thread.sleep(100)
            }
            assertTrue(run.lines.any { SERVING in it }, "a correct configuration must start serving: ${run.lines}")
            assertTrue(run.process.isAlive, "and keep serving: ${run.lines}")
            assertTrue(run.lines.any { "Daymark Companion starting on" in it }, "${run.lines}")
            assertTrue(run.lines.none { "Refusing to start" in it }, "${run.lines}")
            // No DAYMARK_SETUP_MODE and the switch on: the shape it assumed, said once, at info (#330).
            val shape = run.lines.filter { "DAYMARK_SETUP_MODE" in it }
            assertEquals(1, shape.size, "one line about the shape: ${run.lines}")
            assertTrue(" INFO " in shape.single(), shape.single())
            assertTrue(
                "Serving the practice shape, assumed because DAYMARK_SETUP_MODE is not set and " +
                    "DAYMARK_THERAPIST_AUTH is on" in shape.single(),
                shape.single(),
            )
        } finally {
            run.process.destroy()
            if (!run.process.waitFor(15, TimeUnit.SECONDS)) run.process.destroyForcibly().waitFor()
            dataDir.deleteRecursively()
        }
    }

    /*
     * A database the release must not open, or cannot change, refuses the start the way a setting does
     * (#193): one line naming the database and its versions, exit 78, and the file as it was. These
     * two plant the database and let the real server find it; the correct configuration above, which
     * starts on an empty volume, is the control that the same settings serve when the database is fine.
     */

    @Test
    fun `a database newer than this release exits 78 with one line naming it and both versions, and is left as it was`() {
        val dataDir = Files.createTempDirectory("startup-refused-newer").toFile()
        try {
            val index = File(dataDir, "index.db")
            DriverManager.getConnection("jdbc:sqlite:${index.path}").use { c ->
                c.createStatement().use { st ->
                    st.execute("PRAGMA journal_mode=WAL")
                    st.execute("CREATE TABLE snapshots (lineage TEXT, version INTEGER)")
                    st.execute("PRAGMA user_version = ${BlobStore.SCHEMA.current + 1}")
                }
            }
            val before = sha256(index)
            val run = launch(
                settings(
                    dataDir,
                    "DAYMARK_PUBLIC_BASE_URL" to "https://daymark.example.com",
                    "DAYMARK_AUTH_TOKEN" to "owner-token-startup-test",
                ),
            )
            val exited = run.process.waitFor(60, TimeUnit.SECONDS)
            if (!exited) run.process.destroyForcibly()
            run.reader.join(10_000)
            assertTrue(exited, "a newer database must stop the start, not be served: ${run.lines}")
            assertEquals(EXIT_CONFIG, run.process.exitValue(), "${run.lines}")
            val refusal = run.lines.filter { "Refusing to start" in it }
            assertEquals(1, refusal.size, "exactly one refusal line: ${run.lines}")
            val newer = BlobStore.SCHEMA.current + 1
            assertTrue(
                " ERROR " in refusal.single() &&
                    "Refusing to start: index.db is at structure version $newer, newer than version " +
                    "${BlobStore.SCHEMA.current}, the newest this release knows; nothing was changed." in refusal.single(),
                refusal.single(),
            )
            assertTrue(dataDir.path !in refusal.single(), "no path: ${refusal.single()}")
            assertTrue(run.lines.none { "Exception" in it || it.trimStart().startsWith("at ") }, "no stack trace: ${run.lines}")
            assertTrue(run.lines.none { SERVING in it }, "no port was bound: ${run.lines}")
            assertEquals(before, sha256(index), "the newer database is left byte for byte as it was")
            assertFalse(File(dataDir, Schema.PRE_MIGRATE_DIR).exists(), "and nothing was copied")
        } finally {
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun `a change that fails partway exits 78 with one line naming the database and both versions, and the file is as it was`() {
        /*
         * An auth.db from before versions were kept, missing the last_used_step column version 1
         * adds, and missing the unique index on totp.rel_ref, with two credentials for one
         * relationship so that the index cannot be built. No real database was ever in this state:
         * it is the way to make the real version 1 fail after it has already added the column,
         * through the real server and nothing else.
         */
        val dataDir = Files.createTempDirectory("startup-refused-change").toFile()
        try {
            val auth = File(dataDir, "auth.db")
            DriverManager.getConnection("jdbc:sqlite:${auth.path}").use { c ->
                c.createStatement().use { st ->
                    st.execute("PRAGMA journal_mode=WAL")
                    st.execute(
                        "CREATE TABLE totp (credential_id TEXT NOT NULL PRIMARY KEY, rel_ref TEXT NOT NULL, " +
                            "secret_b64 TEXT NOT NULL, fail_count INTEGER NOT NULL DEFAULT 0, " +
                            "locked_until INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)",
                    )
                    st.execute("INSERT INTO totp(credential_id, rel_ref, secret_b64, created_at) VALUES ('c1', 'rel', 's1', 1)")
                    st.execute("INSERT INTO totp(credential_id, rel_ref, secret_b64, created_at) VALUES ('c2', 'rel', 's2', 2)")
                }
            }
            val before = sha256(auth)
            val run = launch(settings(dataDir, "DAYMARK_PUBLIC_BASE_URL" to "https://daymark.example.com"))
            val exited = run.process.waitFor(60, TimeUnit.SECONDS)
            if (!exited) run.process.destroyForcibly()
            run.reader.join(10_000)
            assertTrue(exited, "a failed change must stop the start, not be served: ${run.lines}")
            assertEquals(EXIT_CONFIG, run.process.exitValue(), "${run.lines}")
            val refusal = run.lines.filter { "Refusing to start" in it }
            assertEquals(1, refusal.size, "exactly one refusal line: ${run.lines}")
            assertTrue(
                " ERROR " in refusal.single() &&
                    "Refusing to start: auth.db could not be changed from structure version 0 to " +
                    "${AuthStore.SCHEMA.current} (SQLITE_CONSTRAINT_UNIQUE); the change was rolled back and " +
                    "the file is as it was" in refusal.single(),
                refusal.single(),
            )
            assertTrue(dataDir.path !in refusal.single() && "'rel'" !in refusal.single(), "no path, no row: ${refusal.single()}")
            assertTrue(run.lines.none { "Exception" in it || it.trimStart().startsWith("at ") }, "no stack trace: ${run.lines}")
            assertTrue(run.lines.none { SERVING in it }, "no port was bound: ${run.lines}")
            assertEquals(before, sha256(auth), "the column added before the failure is rolled back with it")
            assertEquals(
                emptyList(),
                File(dataDir, Schema.PRE_MIGRATE_DIR).list()?.toList().orEmpty(),
                "the copy taken first is removed: the file it copied is untouched, and a restart must not pile them up",
            )
        } finally {
            dataDir.deleteRecursively()
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** What Ktor logs once a connector is bound and taking requests. */
        const val SERVING = "Responding at"
        val JVM_OPTION_VARIABLES = setOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
    }
}
