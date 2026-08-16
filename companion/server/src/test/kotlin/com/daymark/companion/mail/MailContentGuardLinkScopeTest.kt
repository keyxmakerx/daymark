package com.daymark.companion.mail

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The sentinel scan's scope: it covers what came from a person's record, not the link this
 * server just minted.
 *
 * Every test in the first half FAILS against the pre-fix guard, which lowercased the whole
 * rendered body — link included — and rejected on any sentinel. An operator self-hosting at
 * mood.example.org could not send one message: invites, review notifications and recovery
 * mail all threw on their own hostname. Recovery answers 202 either way, so the owner was told
 * it worked while nothing was delivered and there was nothing to see in the response.
 *
 * The second half is the part that matters more: record content must still be rejected, and
 * these tests fail if the sentinel block is deleted.
 */
class MailContentGuardLinkScopeTest {

    private val expiry = Instant.parse("2026-08-01T00:00:00Z")

    /**
     * A deliberate copy of MailContentGuard.RECORD_SENTINELS (private there). If a sentinel is
     * added to the guard and not here, the new one goes untested — that is the trade for not
     * widening the guard's API for a test.
     */
    private val sentinels = listOf(
        "score", "PHQ", "GAD", "mood", "journal", "assessment", "self-harm",
        "diagnos", "symptom", "note:", "band", "check-in", "sleep",
    )

    // ---- the link is the server's own text, and must not be scanned ----

    @Test
    fun `a mood hostname deployment can send every kind of mail`() {
        // mood.example.org is an obvious hostname for a mood journal. Pre-fix, all three threw.
        val invite = MailMessage.TherapistInvite(
            to = "t@example.org",
            inviteUrl = URI("https://mood.example.org/invite/abc123"),
            expiresAt = expiry,
        )
        MailContentGuard.assertClean(invite, MailTemplates.render(invite))

        val review = MailMessage.ReviewNotification(
            to = "t@example.org",
            portalUrl = URI("https://mood.example.org/portal"),
            kind = MailMessage.ReviewKind.NEW_SHARE,
        )
        MailContentGuard.assertClean(review, MailTemplates.render(review))

        val recovery = MailMessage.AccessRecovery(
            to = "owner@example.org",
            confirmUrl = URI("https://mood.example.org/recover#t=abc123"),
            expiresAt = expiry,
        )
        MailContentGuard.assertClean(recovery, MailTemplates.render(recovery))
    }

    @Test
    fun `no sentinel word in the hostname blocks a send`() {
        // Not just "mood": every sentinel is a plausible label for someone's deployment, and
        // "self-harm" or "check-in" as a subdomain is a hostname, not a disclosure.
        for (sentinel in sentinels) {
            val label = sentinel.lowercase().removeSuffix(":")
            val msg = MailMessage.TherapistInvite(
                to = "t@example.org",
                inviteUrl = URI("https://$label.example.org/invite/abc123"),
                expiresAt = expiry,
            )
            MailContentGuard.assertClean(msg, MailTemplates.render(msg))
        }
    }

    @Test
    fun `a random token that happens to spell a sentinel still sends`() {
        // Tokens are 43 random base64url chars. Lowercased, a three-letter sentinel like "phq"
        // or "gad" lands in one roughly every few hundred, and an invite link carries two of
        // them — so pre-fix a fraction of a percent of invites silently never arrived, with
        // nothing in common between the ones that did.
        val invite = MailMessage.TherapistInvite(
            to = "t@example.org",
            inviteUrl = URI("https://companion.example.org/invite/7Kx2phqR9vTgadWz"),
            expiresAt = expiry,
        )
        MailContentGuard.assertClean(invite, MailTemplates.render(invite))
    }

    // ---- record content must still be rejected ----

    @Test
    fun `every sentinel is still caught in an operator-supplied display name`() {
        // The display name is the one field a caller controls, and the guard whitelists it, so
        // the residual check strips it away and cannot see it. The sentinel scan is the ONLY
        // thing standing between a smuggled display name and the wire: delete that block and
        // every iteration here stops throwing.
        for (sentinel in sentinels) {
            val msg = MailMessage.TherapistInvite(
                to = "t@example.org",
                inviteUrl = URI("https://companion.example.org/invite/abc123"),
                expiresAt = expiry,
                displayName = "Patient $sentinel 21",
            )
            val e = assertFailsWith<MailContentViolation> {
                MailContentGuard.assertClean(msg, MailTemplates.render(msg))
            }
            // Pin it to the sentinel scan, not the residual check, so this stays a real test of
            // the scan even if the whitelist changes shape later.
            assertTrue(
                e.message!!.contains("record-like token"),
                "expected the sentinel scan to reject '$sentinel', got: ${e.message}",
            )
        }
    }

    @Test
    fun `the same display name shape without a sentinel is accepted`() {
        // Proves the loop above is not passing because "Patient <x> 21" is rejected wholesale.
        val msg = MailMessage.TherapistInvite(
            to = "t@example.org",
            inviteUrl = URI("https://companion.example.org/invite/abc123"),
            expiresAt = expiry,
            displayName = "Patient riverbank 21",
        )
        MailContentGuard.assertClean(msg, MailTemplates.render(msg))
    }

    @Test
    fun `cutting the link out does not blind the scan to the rest of the message`() {
        // The worry with eliding the link is that it opens a hole around it. It does not: the
        // display name is still scanned even when the host itself contains a sentinel, so the
        // deployment that most needed the fix is not the one that loses the guard.
        val msg = MailMessage.TherapistInvite(
            to = "t@example.org",
            inviteUrl = URI("https://mood.example.org/invite/abc123"),
            expiresAt = expiry,
            displayName = "PHQ score 21",
        )
        val e = assertFailsWith<MailContentViolation> {
            MailContentGuard.assertClean(msg, MailTemplates.render(msg))
        }
        assertTrue(e.message!!.contains("record-like token"), "got: ${e.message}")
    }

    @Test
    fun `record content injected into the body is still rejected on a sentinel hostname`() {
        // Caught by the residual check rather than the sentinel scan (injected text is not a
        // whitelisted fragment). Asserted here because the fix must not let a mood.example.org
        // deployment become the one place tampering slips through.
        val msg = MailMessage.ReviewNotification(
            to = "t@example.org",
            portalUrl = URI("https://mood.example.org/portal"),
            kind = MailMessage.ReviewKind.NEW_SHARE,
        )
        val tampered = RenderedMail(
            subject = MailTemplates.REVIEW_SUBJECT,
            textBody = MailTemplates.render(msg).textBody + "\nThe patient reported a low mood today.",
        )
        assertFailsWith<MailContentViolation> { MailContentGuard.assertClean(msg, tampered) }
    }

    @Test
    fun `text smuggled up against the link is still rejected`() {
        // Only the exact link is cut, so record text glued to either end of it stays visible.
        val msg = MailMessage.ReviewNotification(
            to = "t@example.org",
            portalUrl = URI("https://companion.example.org/portal"),
            kind = MailMessage.ReviewKind.NEW_SHARE,
        )
        val url = msg.portalUrl.toString()
        val tampered = RenderedMail(
            subject = MailTemplates.REVIEW_SUBJECT,
            textBody = MailTemplates.render(msg).textBody.replace(url, url + "sleep quality poor" + url),
        )
        assertFailsWith<MailContentViolation> { MailContentGuard.assertClean(msg, tampered) }
    }

    @Test
    fun `link validation is unaffected by the scan change`() {
        // The scheme/host checks run before the scan and are not part of what moved.
        val msg = MailMessage.AccessRecovery(
            to = "owner@example.org",
            confirmUrl = URI("http://mood.example.org/recover#t=abc123"),
            expiresAt = expiry,
        )
        val e = assertFailsWith<MailContentViolation> {
            MailContentGuard.assertClean(msg, MailTemplates.render(msg))
        }
        assertTrue(e.message!!.contains("link scheme"), "got: ${e.message}")
    }
}
