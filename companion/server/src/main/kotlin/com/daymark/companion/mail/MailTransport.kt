package com.daymark.companion.mail

/** A single sent message, captured verbatim (used by [InMemoryMailTransport] for tests). */
data class OutgoingMail(val from: String, val to: String, val subject: String, val body: String)

/**
 * The wire-level sink. Implementations only move already-rendered, already-guarded content.
 * They never render or inspect [MailMessage] — that has happened upstream in [Mailer].
 */
interface MailTransport {
    /** Deliver one message. May throw; [Mailer] catches and maps to [MailResult.Failed]. */
    fun send(from: String, to: String, rendered: RenderedMail)
}
