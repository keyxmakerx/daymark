package com.daymark.companion.mail

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailerConfigTest {

    @Test
    fun `disabled by default when no host`() {
        val cfg = MailerConfig.fromEnv(emptyMap())
        assertFalse(cfg.enabled)
        assertNull(cfg.host)
        // validate() is a no-op when disabled.
        cfg.validate()
    }

    @Test
    fun `defaults port 587 and starttls when enabled`() {
        val cfg = MailerConfig.fromEnv(
            mapOf("DAYMARK_SMTP_HOST" to "mail.example.org", "DAYMARK_SMTP_FROM" to "companion@example.org"),
        )
        assertTrue(cfg.enabled)
        assertEquals(587, cfg.port)
        assertEquals(MailerConfig.TlsMode.STARTTLS, cfg.tls)
        cfg.validate()
    }

    @Test
    fun `implicit tls parsed`() {
        val cfg = MailerConfig.fromEnv(
            mapOf(
                "DAYMARK_SMTP_HOST" to "mail.example.org",
                "DAYMARK_SMTP_PORT" to "465",
                "DAYMARK_SMTP_TLS" to "implicit",
                "DAYMARK_SMTP_FROM" to "companion@example.org",
            ),
        )
        assertEquals(MailerConfig.TlsMode.IMPLICIT, cfg.tls)
        assertEquals(465, cfg.port)
    }

    @Test
    fun `tls none is rejected when enabled`() {
        assertFailsWith<MailerConfigException> {
            MailerConfig.fromEnv(
                mapOf(
                    "DAYMARK_SMTP_HOST" to "mail.example.org",
                    "DAYMARK_SMTP_TLS" to "none",
                    "DAYMARK_SMTP_FROM" to "companion@example.org",
                ),
            )
        }
    }

    @Test
    fun `unknown tls value is rejected`() {
        assertFailsWith<MailerConfigException> {
            MailerConfig.fromEnv(
                mapOf("DAYMARK_SMTP_HOST" to "mail.example.org", "DAYMARK_SMTP_TLS" to "banana"),
            )
        }
    }

    @Test
    fun `missing FROM when enabled fails validate`() {
        val cfg = MailerConfig.fromEnv(mapOf("DAYMARK_SMTP_HOST" to "mail.example.org"))
        assertTrue(cfg.enabled)
        assertFailsWith<MailerConfigException> { cfg.validate() }
    }

    @Test
    fun `PASS_FILE wins over inline PASS`() {
        val tmp = Files.createTempFile("smtp-pass", ".txt")
        Files.writeString(tmp, "  file-secret\n")
        try {
            val cfg = MailerConfig.fromEnv(
                mapOf(
                    "DAYMARK_SMTP_HOST" to "mail.example.org",
                    "DAYMARK_SMTP_FROM" to "companion@example.org",
                    "DAYMARK_SMTP_USER" to "companion",
                    "DAYMARK_SMTP_PASS" to "inline-secret",
                    "DAYMARK_SMTP_PASS_FILE" to tmp.toString(),
                ),
            )
            assertEquals("file-secret", cfg.pass)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `toString does not leak the password`() {
        val cfg = MailerConfig.fromEnv(
            mapOf(
                "DAYMARK_SMTP_HOST" to "mail.example.org",
                "DAYMARK_SMTP_FROM" to "companion@example.org",
                "DAYMARK_SMTP_USER" to "companion",
                "DAYMARK_SMTP_PASS" to "hunter2-topsecret",
            ),
        )
        val s = cfg.toString()
        assertFalse(s.contains("hunter2-topsecret"), "password leaked in toString(): $s")
        assertTrue(s.contains("<redacted>"))
    }
}
