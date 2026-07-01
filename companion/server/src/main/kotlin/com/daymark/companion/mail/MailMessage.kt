package com.daymark.companion.mail

import java.net.URI
import java.time.Instant

/**
 * The ONLY two kinds of message the mailer can send. By design there is NO free-form body
 * field: callers cannot pass a subject or body string. This is where the "no record /
 * plaintext content ever" invariant lives structurally — every field here is a link, a
 * timestamp, or a non-identifying operator-chosen label.
 *
 * See docs/COMPANION_THERAPIST.md (share/invite lifecycle) and the SMTP exception note.
 */
sealed interface MailMessage {
    /** Recipient address. Supplied by the auth/invite slice, never an attacker batch list. */
    val to: String

    /**
     * A therapist sign-up / bootstrap invitation. Carries ONLY the single-use,
     * capability-scoped, expiring link and its expiry. The link is the same non-secret
     * bootstrap link the OOB short-code path uses; email is a convenience delivery, not an
     * auth bypass (the OOB short code stays the mandatory security-bearing channel).
     */
    data class TherapistInvite(
        override val to: String,
        val inviteUrl: URI,
        val expiresAt: Instant,
        /**
         * Optional operator-chosen, non-identifying display name (e.g. a nickname for the
         * relationship). Guarded so it cannot smuggle record content.
         */
        val displayName: String? = null,
    ) : MailMessage

    /**
     * A minimal "you have something to review" notification. Reveals NOTHING about the
     * content — no which-record, no counts, no plaintext. The [kind] only tips the subject
     * line between a few fixed, coarse phrasings.
     */
    data class ReviewNotification(
        override val to: String,
        val portalUrl: URI,
        val kind: ReviewKind,
    ) : MailMessage

    enum class ReviewKind { NEW_ASSIGNMENT, NEW_SHARE, PLAN_ACCEPTED }
}
