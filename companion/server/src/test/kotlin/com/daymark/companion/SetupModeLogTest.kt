package com.daymark.companion

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A start says which shape it serves, once, at info (#330). With no `DAYMARK_SETUP_MODE` the line
 * says the shape was assumed, from which setting, and how to choose. Requests add nothing, and the
 * line carries no value the operator typed.
 */
class SetupModeLogTest {

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

    private val token = "owner-token-planted-in-the-log-test"

    private fun config(mode: SetupMode?, therapistAuth: Boolean) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = Files.createTempDirectory("shape-log").toString(), basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = token,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 100,
        therapistAuthEnabled = therapistAuth, setupMode = mode,
    )

    /** Every line about the shape one start logs, after five requests. */
    private fun shapeLines(config: Config): List<ILoggingEvent> {
        testApplication {
            application { module(config) }
            repeat(5) { client.get("/healthz"); client.get("/v1/config") }
        }
        return appender.list.filter { "DAYMARK_SETUP_MODE" in it.formattedMessage || "shape" in it.formattedMessage }
    }

    private fun assertOneInfoLine(lines: List<ILoggingEvent>, config: Config): String {
        assertEquals(1, lines.size, "one line per start, not per request: ${lines.map { it.formattedMessage }}")
        val line = lines.single()
        assertEquals(Level.INFO, line.level, line.formattedMessage)
        assertFalse(token in line.formattedMessage, "no value the operator typed: ${line.formattedMessage}")
        assertFalse(config.dataDir in line.formattedMessage, "no path: ${line.formattedMessage}")
        return line.formattedMessage
    }

    @Test
    fun `no mode with DAYMARK_THERAPIST_AUTH on says once that it assumed practice, and how to choose`() {
        val config = config(null, therapistAuth = true)
        val line = assertOneInfoLine(shapeLines(config), config)
        assertTrue(
            line.startsWith(
                "Serving the practice shape, assumed because DAYMARK_SETUP_MODE is not set and DAYMARK_THERAPIST_AUTH is on",
            ),
            line,
        )
        assertTrue("Set DAYMARK_SETUP_MODE to solo, paired or practice to choose" in line, line)
    }

    @Test
    fun `no mode with DAYMARK_THERAPIST_AUTH off says once that it assumed solo, and how to choose`() {
        val config = config(null, therapistAuth = false)
        val line = assertOneInfoLine(shapeLines(config), config)
        assertTrue(
            line.startsWith(
                "Serving the solo shape, assumed because DAYMARK_SETUP_MODE is not set and DAYMARK_THERAPIST_AUTH is off",
            ),
            line,
        )
        assertTrue("Set DAYMARK_SETUP_MODE to solo, paired or practice to choose" in line, line)
    }

    @Test
    fun `a chosen mode is named once, and not as an assumption`() {
        for (mode in SetupMode.entries) {
            appender.list.clear()
            val config = config(mode, therapistAuth = false)
            val line = assertOneInfoLine(shapeLines(config), config)
            assertTrue(line.startsWith("Serving the ${mode.wire} shape, as DAYMARK_SETUP_MODE says"), line)
            assertFalse("assumed" in line, line)
        }
    }
}
