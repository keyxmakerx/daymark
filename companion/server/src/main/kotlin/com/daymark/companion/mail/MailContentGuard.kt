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
        assertUrl(url, allowInsecureLinks)

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
        // (e.g. a smuggled display name), reject on the raw rendered text.
        val hay = (rendered.subject + "\n" + rendered.textBody).lowercase()
        RECORD_SENTINELS.firstOrNull { hay.contains(it.lowercase()) }?.let {
            throw MailContentViolation("rendered message contains a record-like token: $it")
        }
    }

    private fun urlOf(msg: MailMessage): URI = when (msg) {
        is MailMessage.TherapistInvite -> msg.inviteUrl
        is MailMessage.ReviewNotification -> msg.portalUrl
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
}
