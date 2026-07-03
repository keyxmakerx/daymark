package com.daymark.companion.mail

import java.time.format.DateTimeFormatter

/** A rendered message: just a subject and a plaintext body. No HTML (smaller surface). */
data class RenderedMail(val subject: String, val textBody: String)

/**
 * Pure, fixed, license-clean templates. English-only (v1). The ONLY interpolated values
 * are the link URL and — for invites — the ISO-8601 expiry. A non-identifying display name
 * may appear in the salutation; [MailContentGuard] later asserts nothing else leaked in.
 *
 * Deliberately terse: the body says essentially "there's a link, go use it" and nothing
 * about what is behind it.
 */
object MailTemplates {
    // Fixed subject strings. Kept as constants so the guard can whitelist them exactly.
    const val INVITE_SUBJECT = "Your Daymark Companion invitation"
    const val REVIEW_SUBJECT = "You have something to review in Daymark Companion"
    const val RECOVERY_SUBJECT = "Confirm your Daymark Companion access-token recovery"
    const val SECURITY_SUBJECT = "Security notice from your Daymark Companion"

    private val ISO = DateTimeFormatter.ISO_INSTANT

    fun render(msg: MailMessage): RenderedMail = when (msg) {
        is MailMessage.TherapistInvite -> renderInvite(msg)
        is MailMessage.ReviewNotification -> renderNotification(msg)
        is MailMessage.AccessRecovery -> renderRecovery(msg)
        is MailMessage.SecurityNotice -> renderSecurityNotice(msg)
    }

    private fun renderInvite(msg: MailMessage.TherapistInvite): RenderedMail {
        val salutation = msg.displayName?.let { "Hello $it,\n\n" } ?: ""
        val expiry = ISO.format(msg.expiresAt)
        val body = buildString {
            append(salutation)
            append("You have been invited to Daymark Companion.\n\n")
            append("Open this single-use link to continue:\n")
            append(msg.inviteUrl.toString())
            append("\n\n")
            append("This link expires at ")
            append(expiry)
            append(".\n\n")
            append("Confirm the short verification code with the person who invited you.\n")
            append("This email contains no personal or health information.\n")
        }
        return RenderedMail(INVITE_SUBJECT, body)
    }

    private fun renderNotification(msg: MailMessage.ReviewNotification): RenderedMail {
        // Coarse, content-free. The kind does NOT change the body text (no leakage of which
        // record type); it exists only so future subject variants could differ if desired.
        val body = buildString {
            append("There is something waiting for you to review in Daymark Companion.\n\n")
            append("Open the portal to see it:\n")
            append(msg.portalUrl.toString())
            append("\n\n")
            append("This email contains no personal or health information.\n")
        }
        return RenderedMail(REVIEW_SUBJECT, body)
    }

    private fun renderRecovery(msg: MailMessage.AccessRecovery): RenderedMail {
        val expiry = ISO.format(msg.expiresAt)
        val body = buildString {
            append("A request was made to recover access to your Daymark Companion server.\n\n")
            append("If this was you, open this single-use link to continue:\n")
            append(msg.confirmUrl.toString())
            append("\n\n")
            append("This link expires at ")
            append(expiry)
            append(".\n\n")
            append("If you did not request this, you can ignore this email — nothing changes unless the link above is opened.\n")
            append("This email contains no personal or health information.\n")
        }
        return RenderedMail(RECOVERY_SUBJECT, body)
    }

    private fun renderSecurityNotice(msg: MailMessage.SecurityNotice): RenderedMail {
        val body = buildString {
            when (msg.event) {
                MailMessage.SecurityEvent.TOKEN_REISSUED ->
                    append("Your Daymark Companion server access token was just re-issued. The previous token no longer works.\n\n")
            }
            append("If you did not do this, your registered email address may be compromised — check your Companion deployment.\n")
            append("This email contains no personal or health information.\n")
        }
        return RenderedMail(SECURITY_SUBJECT, body)
    }
}
