package com.daymark.companion.mail

import org.slf4j.LoggerFactory

/** Outcome of a send. Callers treat [Disabled] as success-ish (fall back to OOB delivery). */
sealed interface MailResult {
    data object Sent : MailResult
    data object Disabled : MailResult
    data class Failed(val reason: String) : MailResult
}

/**
 * Public façade for the ONE outbound exception. A [Mailer] is built once at startup via
 * [forConfig] and dependency-injected into the (future) invite/notification services, just
 * like `blobStore`/`guard` are today.
 *
 * The send path is: render (fixed template) → guard (no-content assertion) → transport.
 * Callers pass only strongly-typed [MailMessage] variants — there is no API to supply a
 * body — so the "no record / plaintext content" guarantee cannot be bypassed by a caller.
 *
 * Sending is BEST-EFFORT: a [Disabled] or [Failed] result must NOT gate the caller's own
 * operation (an invite still exists and can be delivered OOB). Email is a convenience.
 */
class Mailer internal constructor(
    private val cfg: MailerConfig,
    private val transport: MailTransport,
    private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(Mailer::class.java)

    fun send(msg: MailMessage): MailResult {
        if (!enabled) {
            log.debug("mailer disabled; dropping message (kind={})", kindOf(msg))
            return MailResult.Disabled
        }
        val rendered = MailTemplates.render(msg)
        try {
            MailContentGuard.assertClean(msg, rendered, cfg.allowInsecureLinks)
        } catch (e: MailContentViolation) {
            // A content violation is a bug/attack, never delivered. Do not log the body.
            log.error("refusing to send: content guard rejected message (kind={}): {}", kindOf(msg), e.message)
            return MailResult.Failed("content-guard: ${e.message}")
        }
        return try {
            transport.send(cfg.from ?: "", msg.to, rendered)
            log.debug("sent mail (kind={})", kindOf(msg))
            MailResult.Sent
        } catch (e: Exception) {
            // Log the class/message only — never the recipient or body at info+.
            log.warn("mail send failed (kind={}): {}", kindOf(msg), e.javaClass.simpleName)
            MailResult.Failed(e.javaClass.simpleName)
        }
    }

    private fun kindOf(msg: MailMessage): String = when (msg) {
        is MailMessage.TherapistInvite -> "invite"
        is MailMessage.ReviewNotification -> "notification.${msg.kind}"
        is MailMessage.AccessRecovery -> "recovery"
        is MailMessage.SecurityNotice -> "security.${msg.event}"
    }

    companion object {
        /**
         * Build a [Mailer] from config. When SMTP is disabled (no host) this returns a
         * mailer that NEVER opens a socket: it wraps an in-memory sink and short-circuits to
         * [MailResult.Disabled]. When enabled it validates the config (throwing on plaintext
         * / missing From) and wraps a real [SmtpMailTransport].
         *
         * [transport] overrides the transport for tests (e.g. an InMemory- or GreenMail-
         * backed one); it does not change the enabled/disabled decision, which follows cfg.
         */
        fun forConfig(cfg: MailerConfig, transport: MailTransport? = null): Mailer {
            if (!cfg.enabled) {
                // Disabled: no socket, ever. The InMemory sink is only reached if someone
                // wiped `enabled` — which cannot happen since we pass enabled=false.
                return Mailer(cfg, transport ?: InMemoryMailTransport(), enabled = false)
            }
            cfg.validate()
            val t = transport ?: SmtpMailTransport(cfg)
            return Mailer(cfg, t, enabled = true)
        }
    }
}
