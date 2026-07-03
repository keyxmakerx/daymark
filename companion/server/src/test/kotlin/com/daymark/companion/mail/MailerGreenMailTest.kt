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
 * End-to-end proof that the mailer actually speaks SMTP over TLS to an embedded fake server
 * and emits the right envelope/subject/body — with NO record content.
 *
 * NOTE: GreenMail 2.1.2 does not implement the STARTTLS command, so this test exercises the
 * IMPLICIT-TLS (`smtps`) path against GreenMail's secure server, which uses a self-signed
 * cert (trusted here for the loopback host only). The STARTTLS path shares all of the same
 * [Mailer]/[MailContentGuard]/render code; only the session properties differ, and those are
 * unit-asserted separately. This still proves the transport really performs a TLS SMTP
 * handshake and delivers the right envelope.
 */
class MailerGreenMailTest {

    private lateinit var greenMail: GreenMail
    private lateinit var setup: ServerSetup

    @BeforeTest
    fun start() {
        // Implicit TLS (smtps) on a random loopback port; GreenMail uses a self-signed cert.
        setup = ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS)
        greenMail = GreenMail(setup)
        greenMail.start()
        greenMail.setUser("companion@example.org", "companion", "s3cret")
    }

    @AfterTest
    fun stop() {
        greenMail.stop()
    }

    private fun mailerConfig(): MailerConfig = MailerConfig(
        host = "127.0.0.1",
        port = greenMail.smtps.port,
        user = "companion",
        pass = "s3cret",
        from = "companion@example.org",
        tls = MailerConfig.TlsMode.IMPLICIT,
    )

    private fun mailer(): Mailer {
        val cfg = mailerConfig()
        // Trust GreenMail's self-signed cert for THIS host only (test-only).
        val transport = SmtpMailTransport(cfg, trustHostForTests = "127.0.0.1")
        return Mailer.forConfig(cfg, transport)
    }

    @Test
    fun `sends therapist invite over tls`() {
        val result = mailer().send(
            MailMessage.TherapistInvite(
                to = "therapist@example.org",
                inviteUrl = URI("https://companion.example.org/invite/xyz789"),
                expiresAt = Instant.parse("2026-09-01T00:00:00Z"),
            ),
        )
        assertEquals(MailResult.Sent, result)
        assertTrue(greenMail.waitForIncomingEmail(5000, 1))

        val msgs = greenMail.receivedMessages
        assertEquals(1, msgs.size)
        val m = msgs[0]
        assertEquals(MailTemplates.INVITE_SUBJECT, m.subject)
        assertEquals("companion@example.org", m.from[0].toString())
        assertEquals("therapist@example.org", m.allRecipients[0].toString())
        val body = m.content.toString()
        assertTrue(body.contains("https://companion.example.org/invite/xyz789"), "invite link missing")
        // No record content.
        for (sentinel in listOf("PHQ", "score", "mood", "diagnos", "journal")) {
            assertFalse(body.lowercase().contains(sentinel.lowercase()), "record sentinel leaked: $sentinel")
        }
    }

    @Test
    fun `sends access recovery link over tls`() {
        val result = mailer().send(
            MailMessage.AccessRecovery(
                to = "owner@example.org",
                confirmUrl = URI("https://companion.example.org/recover#t=abc123"),
                expiresAt = Instant.parse("2026-09-01T00:00:00Z"),
            ),
        )
        assertEquals(MailResult.Sent, result)
        assertTrue(greenMail.waitForIncomingEmail(5000, 1))
        val m = greenMail.receivedMessages[0]
        assertEquals(MailTemplates.RECOVERY_SUBJECT, m.subject)
        val body = m.content.toString()
        assertTrue(body.contains("https://companion.example.org/recover#t=abc123"))
    }

    @Test
    fun `sends token-reissued security notice over tls with no link`() {
        val result = mailer().send(
            MailMessage.SecurityNotice(to = "owner@example.org", event = MailMessage.SecurityEvent.TOKEN_REISSUED),
        )
        assertEquals(MailResult.Sent, result)
        assertTrue(greenMail.waitForIncomingEmail(5000, 1))
        val m = greenMail.receivedMessages[0]
        assertEquals(MailTemplates.SECURITY_SUBJECT, m.subject)
        assertTrue(m.content.toString().contains("re-issued"))
    }

    @Test
    fun `sends review notification over tls`() {
        val result = mailer().send(
            MailMessage.ReviewNotification(
                to = "therapist@example.org",
                portalUrl = URI("https://companion.example.org/portal"),
                kind = MailMessage.ReviewKind.NEW_ASSIGNMENT,
            ),
        )
        assertEquals(MailResult.Sent, result)
        assertTrue(greenMail.waitForIncomingEmail(5000, 1))

        val m = greenMail.receivedMessages[0]
        assertEquals(MailTemplates.REVIEW_SUBJECT, m.subject)
        val body = m.content.toString()
        assertTrue(body.contains("https://companion.example.org/portal"))
        // The kind must not leak into the body.
        assertFalse(body.contains("NEW_ASSIGNMENT"))
        assertFalse(body.lowercase().contains("assignment"))
    }
}
