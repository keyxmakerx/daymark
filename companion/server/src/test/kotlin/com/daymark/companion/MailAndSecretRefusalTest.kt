package com.daymark.companion

import org.junit.jupiter.api.Assumptions.assumeFalse
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A bad email setting or an unreadable secret file is refused the way every other setting is: one
 * line beginning `Refusing to start:`, naming the setting and never its value (#380). `main` turns
 * a [StartupRefusal] into that line and exit status 78; `StartupProcessTest` covers that half.
 */
class MailAndSecretRefusalTest {

    /** Values that must never come back in a refusal: each would identify the deployment. */
    private val host = "mail.private-host.example"
    private val from = "front-desk@private-host.example"

    private val base = mapOf(
        "DAYMARK_PUBLIC_BASE_URL" to "https://daymark.example.com",
        "DAYMARK_SMTP_HOST" to host,
        "DAYMARK_SMTP_FROM" to from,
    )

    private fun refusal(env: Map<String, String>): String {
        val message = assertFailsWith<StartupRefusal> { Config.fromEnv(env) }.message
        assertTrue(message.startsWith("Refusing to start: "), message)
        assertFalse('\n' in message, message)
        assertFalse(host in message, "the refusal repeats the host: $message")
        assertFalse(from in message, "the refusal repeats the sender: $message")
        return message
    }

    @Test
    fun `a correct email configuration starts, so the refusals below are about their one setting`() {
        val config = Config.fromEnv(base + ("DAYMARK_SMTP_TLS" to "implicit") + ("DAYMARK_SMTP_PORT" to "465"))
        assertTrue(config.smtpEnabled)
        assertEquals(465, config.mailer.port)
    }

    @Test
    fun `email without encryption is refused, naming the setting`() {
        for (plain in listOf("none", "plain", "PlainText")) {
            val message = refusal(base + ("DAYMARK_SMTP_TLS" to plain))
            assertTrue("DAYMARK_SMTP_TLS" in message && "without encryption" in message, message)
            assertFalse(plain in message, "the refusal repeats the value: $message")
        }
    }

    @Test
    fun `an unrecognised encryption setting is refused, naming the setting and never the value`() {
        val odd = "tls-please-mail.private-host.example"
        val message = refusal(base + ("DAYMARK_SMTP_TLS" to odd))
        assertTrue("DAYMARK_SMTP_TLS" in message && "starttls or implicit" in message, message)
        assertFalse(odd in message, message)
    }

    @Test
    fun `email switched on with no sender address is refused`() {
        val message = refusal(base - "DAYMARK_SMTP_FROM")
        assertTrue("DAYMARK_SMTP_FROM" in message, message)
    }

    @Test
    fun `a port out of range is refused, and never echoed`() {
        for (port in listOf("0", "65536", "99999")) {
            val message = refusal(base + ("DAYMARK_SMTP_PORT" to port))
            assertTrue("DAYMARK_SMTP_PORT" in message, message)
            assertFalse(port in message, "the refusal repeats the port: $message")
        }
    }

    @Test
    fun `with email off, its settings are not checked, as before`() {
        // Positive control for "only when switched on": the same missing sender starts here.
        val config = Config.fromEnv(mapOf("DAYMARK_SMTP_PORT" to "99999"))
        assertFalse(config.smtpEnabled)
    }

    @Test
    fun `a secret file the server cannot read is refused, naming the setting and never the path`() {
        // Root reads any file, so this case cannot be made as root. Skipped there, reported as
        // skipped, never as passed (#394).
        assumeFalse(System.getProperty("user.name") == "root", "runs only as a user other than root")
        val dir = Files.createTempDirectory("secret-refusal").toFile()
        try {
            val secret = File(dir, "smtp-pass-for-private-host").apply { writeText("hunter2") }
            assertTrue(secret.setReadable(false, false))
            assertFalse(secret.canRead(), "the test could not make the file unreadable")
            val message = refusal(base + ("DAYMARK_SMTP_PASS_FILE" to secret.path))
            assertTrue("DAYMARK_SMTP_PASS_FILE" in message, message)
            assertFalse(secret.path in message || secret.name in message, "the refusal repeats the path: $message")
        } finally {
            dir.walkBottomUp().forEach { it.setReadable(true, false); it.delete() }
        }
    }

    @Test
    fun `a readable secret file is read, so the refusal above is about readability`() {
        val dir = Files.createTempDirectory("secret-read").toFile()
        try {
            val secret = File(dir, "pass").apply { writeText("  hunter2\n") }
            val config = Config.fromEnv(base + ("DAYMARK_SMTP_PASS_FILE" to secret.path))
            assertEquals("hunter2", config.mailer.pass)
        } finally {
            dir.deleteRecursively()
        }
    }
}
