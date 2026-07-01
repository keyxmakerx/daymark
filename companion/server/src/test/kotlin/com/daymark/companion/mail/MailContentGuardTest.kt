package com.daymark.companion.mail

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MailContentGuardTest {

    private val expiry = Instant.parse("2026-08-01T00:00:00Z")

    @Test
    fun `canonical invite passes`() {
        val msg = MailMessage.TherapistInvite(
            to = "t@example.org",
            inviteUrl = URI("https://companion.example.org/invite/abc123"),
            expiresAt = expiry,
        )
        MailContentGuard.assertClean(msg, MailTemplates.render(msg))
    }

    @Test
    fun `canonical notification passes`() {
        val msg = MailMessage.ReviewNotification(
            to = "t@example.org",
            portalUrl = URI("https://companion.example.org/portal"),
            kind = MailMessage.ReviewKind.NEW_SHARE,
        )
        MailContentGuard.assertClean(msg, MailTemplates.render(msg))
    }

    @Test
    fun `all review kinds render clean`() {
        for (kind in MailMessage.ReviewKind.values()) {
            val msg = MailMessage.ReviewNotification(
                to = "t@example.org",
                portalUrl = URI("https://companion.example.org/portal"),
                kind = kind,
            )
            MailContentGuard.assertClean(msg, MailTemplates.render(msg))
        }
    }

    @Test
    fun `non-https link rejected`() {
        val msg = MailMessage.TherapistInvite(
            to = "t@example.org",
            inviteUrl = URI("http://companion.example.org/invite/abc123"),
            expiresAt = expiry,
        )
        assertFailsWith<MailContentViolation> {
            MailContentGuard.assertClean(msg, MailTemplates.render(msg))
        }
    }

    @Test
    fun `http link allowed only under dev flag`() {
        val msg = MailMessage.TherapistInvite(
            to = "t@example.org",
            inviteUrl = URI("http://localhost:8080/invite/abc123"),
            expiresAt = expiry,
        )
        MailContentGuard.assertClean(msg, MailTemplates.render(msg), allowInsecureLinks = true)
    }

    @Test
    fun `empty host rejected`() {
        val msg = MailMessage.ReviewNotification(
            to = "t@example.org",
            portalUrl = URI("https:///portal"),
            kind = MailMessage.ReviewKind.NEW_ASSIGNMENT,
        )
        assertFailsWith<MailContentViolation> {
            MailContentGuard.assertClean(msg, MailTemplates.render(msg))
        }
    }

    @Test
    fun `injected free text in display name is rejected`() {
        // A hostile/buggy caller tries to smuggle content via displayName.
        val msg = MailMessage.TherapistInvite(
            to = "t@example.org",
            inviteUrl = URI("https://companion.example.org/invite/abc123"),
            expiresAt = expiry,
            displayName = "Patient PHQ score is 21",
        )
        assertFailsWith<MailContentViolation> {
            MailContentGuard.assertClean(msg, MailTemplates.render(msg))
        }
    }

    @Test
    fun `rendered body with extra sentence is rejected`() {
        val msg = MailMessage.ReviewNotification(
            to = "t@example.org",
            portalUrl = URI("https://companion.example.org/portal"),
            kind = MailMessage.ReviewKind.NEW_SHARE,
        )
        val tampered = RenderedMail(
            subject = MailTemplates.REVIEW_SUBJECT,
            textBody = MailTemplates.render(msg).textBody + "\nThe patient reported a low mood today.",
        )
        assertFailsWith<MailContentViolation> {
            MailContentGuard.assertClean(msg, tampered)
        }
    }

    @Test
    fun `benign display name passes`() {
        val msg = MailMessage.TherapistInvite(
            to = "t@example.org",
            inviteUrl = URI("https://companion.example.org/invite/abc123"),
            expiresAt = expiry,
            displayName = "Riverbank",
        )
        MailContentGuard.assertClean(msg, MailTemplates.render(msg))
    }
}
