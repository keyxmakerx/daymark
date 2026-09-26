package com.daymark.companion

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
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
        } finally {
            run.process.destroy()
            if (!run.process.waitFor(15, TimeUnit.SECONDS)) run.process.destroyForcibly().waitFor()
            dataDir.deleteRecursively()
        }
    }

    private companion object {
        /** What Ktor logs once a connector is bound and taking requests. */
        const val SERVING = "Responding at"
        val JVM_OPTION_VARIABLES = setOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
    }
}
