package com.daymark.companion

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The trusted-proxy contract's one *silent* failure mode.
 *
 * Setting `DAYMARK_TRUSTED_PROXIES` wrong (or forgetting it) behind a reverse proxy does not error.
 * The app simply ignores `X-Forwarded-For` and keys every lockout and rate limit on the proxy's
 * address, so all clients land in one bucket and eight bad tokens from one attacker lock out
 * everybody. Nothing distinguishes that from the correct response to a *spoofed* header, which is
 * why it has to be surfaced by inference: forwarded headers arriving while the allowlist is empty
 * means something is proxying and the app is deaf to it.
 *
 * The once-per-process bound is part of the contract, not an optimisation: the trigger is an
 * attacker-supplied header, so an unbounded version would be a log-flood amplifier — the disk-fill
 * footgun the `local` log driver caps exist to prevent, reintroduced at the application layer.
 */
class ProxyMisconfigWarningTest {

    private val logger = LoggerFactory.getLogger("com.daymark.companion.proxy") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    private val config = Config(
        bindAddr = "127.0.0.1",
        port = 8080,
        dataDir = "/tmp",
        basePath = "/",
        webDir = "build/test-web",
        logLevel = "info",
        authToken = null,
        maxBlobBytes = 26_214_400L,
        maxRequestBytes = 27_262_976L,
        maxVersions = 200,
        perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8,
        authLockoutSeconds = 900L,
        rateLimitRps = 100,
    )

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

    private fun warnings(): List<String> =
        appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    @Test
    fun `a forwarded header with an empty allowlist warns exactly once, however many requests arrive`() =
        testApplication {
            application { module(config) }
            repeat(5) {
                client.get("/healthz") { header("X-Forwarded-For", "203.0.113.9") }
            }
            val hits = warnings().filter { it.contains("X-Forwarded-For") }
            assertEquals(1, hits.size, "expected one warning, got: $hits")
            assertTrue(
                hits.single().contains("DAYMARK_TRUSTED_PROXIES"),
                "the warning must name the variable to set: ${hits.single()}",
            )
        }

    @Test
    fun `no forwarded header means no warning`() = testApplication {
        // The overwhelmingly common case for a directly-reachable deployment. Warning here would
        // make the message noise, and a warning people learn to scroll past is worse than none.
        application { module(config) }
        client.get("/healthz")
        assertTrue(warnings().none { it.contains("X-Forwarded-For") }, "unexpected: ${warnings()}")
    }

    @Test
    fun `a configured allowlist silences it even when the header is present`() = testApplication {
        // Correctly configured: the header is being honoured, so there is nothing to report.
        application { module(config.copy(trustedProxies = ClientAddress.parseTrusted("10.89.0.2/32"))) }
        client.get("/healthz") { header("X-Forwarded-For", "203.0.113.9") }
        assertTrue(warnings().none { it.contains("X-Forwarded-For") }, "unexpected: ${warnings()}")
    }
}
