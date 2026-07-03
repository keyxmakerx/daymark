package com.daymark.companion.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import java.net.URI
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioural proof that a disabled mailer NEVER opens a socket: a GreenMail server is
 * running, but a mailer built from a host-less config sends nothing to it.
 */
class DisabledMailerTest {

    private lateinit var greenMail: GreenMail

    @BeforeTest
    fun start() {
        greenMail = GreenMail(ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))
        greenMail.start()
    }

    @AfterTest
    fun stop() {
        greenMail.stop()
    }

    @Test
    fun `disabled config returns Disabled and connects to nothing`() {
        val cfg = MailerConfig.fromEnv(emptyMap()) // host == null → disabled
        assertFalse(cfg.enabled)
        val mailer = Mailer.forConfig(cfg)

        val result = mailer.send(
            MailMessage.TherapistInvite(
                to = "therapist@example.org",
                inviteUrl = URI("https://companion.example.org/invite/xyz"),
                expiresAt = Instant.parse("2026-09-01T00:00:00Z"),
            ),
        )
        assertEquals(MailResult.Disabled, result)
        // Nothing was delivered — no socket was opened.
        assertFalse(greenMail.waitForIncomingEmail(500, 1))
        assertTrue(greenMail.receivedMessages.isEmpty())
    }

    @Test
    fun `injected in-memory transport records send when enabled`() {
        val cfg = MailerConfig(
            host = "mail.example.org",
            port = 587,
            user = null,
            pass = null,
            from = "companion@example.org",
            tls = MailerConfig.TlsMode.STARTTLS,
        )
        val transport = InMemoryMailTransport()
        val mailer = Mailer.forConfig(cfg, transport)
        val result = mailer.send(
            MailMessage.ReviewNotification(
                to = "therapist@example.org",
                portalUrl = URI("https://companion.example.org/portal"),
                kind = MailMessage.ReviewKind.PLAN_ACCEPTED,
            ),
        )
        assertEquals(MailResult.Sent, result)
        assertEquals(1, transport.sent.size)
        assertEquals("companion@example.org", transport.sent[0].from)
        assertEquals("therapist@example.org", transport.sent[0].to)
        assertEquals(MailTemplates.REVIEW_SUBJECT, transport.sent[0].subject)
    }
}
