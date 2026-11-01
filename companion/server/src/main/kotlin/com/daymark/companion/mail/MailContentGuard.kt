package com.daymark.companion.mail

import java.net.URI
import java.time.format.DateTimeFormatter

/** Thrown when a rendered message contains anything beyond the allowed template + link + expiry. */
class MailContentViolation(message: String) : Exception(message)

/**
 * The structural enforcement of the "no record / plaintext content ever" guarantee.
 *
 * [assertClean] runs on EVERY rendered message before it is handed to a transport. It
 * verifies two independent things:
 *
 *  1. URL validity: the message's link is https (or http only when the dev
 *     `allowInsecureLinks` flag is set) and has a non-empty host.
 *  2. Body/subject minimalism: after removing the known-fixed template text, the exact URL,
 *     the ISO expiry, and any operator-provided display name, whatever remains contains no
 *     "record-like" free text. A crafted display name that smuggles content is rejected.
 *
 * This is defense-in-depth on top of the sealed [MailMessage] type (callers already cannot
 * pass a body). It is intentionally strict: it is easier to add a new template token to the
 * whitelist than to discover a leak in production.
 */
object MailContentGuard {
    private val ISO = DateTimeFormatter.ISO_INSTANT

    /** A short battery of sentinel strings that must NEVER appear in an email we send. */
    private val RECORD_SENTINELS = listOf(
        "score", "PHQ", "GAD", "mood", "journal", "assessment", "self-harm",
        "diagnos", "symptom", "note:", "band", "check-in", "sleep",
    )

    fun assertClean(msg: MailMessage, rendered: RenderedMail, allowInsecureLinks: Boolean = false) {
        val url = urlOf(msg)
        url?.let { assertUrl(it, allowInsecureLinks) }

        // Whitelist: fixed template text + the URL + (invite) the ISO expiry + display name.
        val allowedFragments = mutableListOf<String>()
        when (msg) {
            is MailMessage.TherapistInvite -> {
                allowedFragments += MailTemplates.INVITE_SUBJECT
                allowedFragments += INVITE_TEMPLATE_TOKENS
                allowedFragments += url.toString()
                allowedFragments += ISO.format(msg.expiresAt)
                msg.displayName?.let { allowedFragments += it }
            }
            is MailMessage.ReviewNotification -> {
                allowedFragments += MailTemplates.REVIEW_SUBJECT
                allowedFragments += REVIEW_TEMPLATE_TOKENS
                allowedFragments += url.toString()
            }
            is MailMessage.SecurityNotice -> {
                allowedFragments += MailTemplates.SECURITY_SUBJECT
                allowedFragments += securityTemplateTokens(msg.event)
            }
        }

        val residualSubject = strip(rendered.subject, allowedFragments)
        val residualBody = strip(rendered.textBody, allowedFragments)

        // After removing every allowed fragment, only whitespace/punctuation may remain.
        val leftover = (residualSubject + " " + residualBody)
        if (leftover.any { it.isLetterOrDigit() }) {
            throw MailContentViolation(
                "rendered message contains text outside the fixed template + link + expiry",
            )
        }

        // Belt-and-braces: even if a sentinel somehow appeared inside an allowed fragment
        // (e.g. a smuggled display name), reject on the rendered text.
        //
        // The link is cut out before the scan. Nothing in it comes from anyone's record: the
        // host is the operator's own deployment and the rest is ids and random secrets this
        // server just minted (see buildInviteLink / buildRecoveryLink). Scanning it made the
        // guard fail closed on mail that was never a leak:
        //
        //  - An operator self-hosting at mood.example.org — an obvious hostname for a mood
        //    journal — could not send a single message. Every invite, review and recovery mail
        //    hit "mood" and threw. The recovery route answers 202 regardless, so the owner saw
        //    a success and no mail ever arrived; they had no way to tell recovery was dead.
        //  - Tokens are 43 random base64url chars, so lowercased they hit a three-letter
        //    sentinel like "phq" or "gad" around 1 in 400, and an invite link carries two of
        //    them. Roughly one invite in two hundred vanished for no reason anyone could see.
        //
        // Everything the scan exists for is still in the haystack: the template text, the
        // expiry, the operator-supplied display name, and anything that survived the strip
        // above. Replacing rather than deleting the link keeps the text either side of it from
        // being spliced into a sentinel that was never written.
        val whole = rendered.subject + "\n" + rendered.textBody
        val hay = (url?.let { whole.replace(it.toString(), "\n") } ?: whole).lowercase()
        RECORD_SENTINELS.firstOrNull { hay.contains(it.lowercase()) }?.let {
            throw MailContentViolation("rendered message contains a record-like token: $it")
        }
    }

    /** Null for message kinds with no link (e.g. [MailMessage.SecurityNotice]) — URL validation is skipped for those. */
    private fun urlOf(msg: MailMessage): URI? = when (msg) {
        is MailMessage.TherapistInvite -> msg.inviteUrl
        is MailMessage.ReviewNotification -> msg.portalUrl
        is MailMessage.SecurityNotice -> null
    }

    private fun assertUrl(url: URI, allowInsecureLinks: Boolean) {
        val scheme = url.scheme?.lowercase()
        val ok = scheme == "https" || (allowInsecureLinks && scheme == "http")
        if (!ok) {
            throw MailContentViolation("link scheme must be https (got '${url.scheme}')")
        }
        if (url.host.isNullOrBlank()) {
            throw MailContentViolation("link host must be non-empty")
        }
    }

    /** Remove each allowed fragment (first occurrence) from [text]. Longest first so a URL
     *  containing template words is stripped before the words are. */
    private fun strip(text: String, fragments: List<String>): String {
        var out = text
        for (frag in fragments.sortedByDescending { it.length }) {
            if (frag.isEmpty()) continue
            var idx = out.indexOf(frag)
            while (idx >= 0) {
                out = out.removeRange(idx, idx + frag.length)
                idx = out.indexOf(frag)
            }
        }
        return out
    }

    // The non-URL, non-expiry literal fragments of each template. Kept in sync with
    // MailTemplates by construction (tests assert the whole body strips clean).
    private val INVITE_TEMPLATE_TOKENS = listOf(
        "Hello",
        "You have been invited to Daymark Companion.",
        "Open this single-use link to continue:",
        "This link expires at",
        "Confirm the short verification code with the person who invited you.",
        "This email contains no personal or health information.",
    )

    private val REVIEW_TEMPLATE_TOKENS = listOf(
        "There is something waiting for you to review in Daymark Companion.",
        "Open the portal to see it:",
        "This email contains no personal or health information.",
    )

    private fun securityTemplateTokens(event: MailMessage.SecurityEvent): List<String> {
        val eventLine = when (event) {
            MailMessage.SecurityEvent.SIGN_IN_RECOVERED ->
                "Someone got back into your Daymark Companion with your recovery code and made a new sign-in. Every earlier sign-in has been signed out."
        }
        return listOf(
            eventLine,
            "If you did not do this, someone else may have your recovery code. Sign in to your Companion and check your paired phones and clinicians.",
            "This email contains no personal or health information.",
        )
    }
}
