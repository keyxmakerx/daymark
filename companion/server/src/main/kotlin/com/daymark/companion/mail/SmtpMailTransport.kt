package com.daymark.companion.mail

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

/**
 * The real SMTP client, backed by jakarta.mail / angus-mail. It only ever connects to the
 * single configured [MailerConfig.host]:[MailerConfig.port] — there is no relay, no MX
 * resolution, no redirect. TLS is mandatory:
 *
 *  - STARTTLS mode sets `mail.smtp.starttls.enable=true` AND `.required=true` (so a server
 *    that cannot upgrade is refused, not silently downgraded to plaintext).
 *  - IMPLICIT mode uses the `smtps` transport (TLS from the first byte).
 *
 * Server identity is checked (`ssl.checkserveridentity=true`) and `ssl.trust` is NOT set to
 * '*' — the JVM default trust store applies. (Tests point at GreenMail with a per-test trust
 * override; production never does.)
 */
class SmtpMailTransport(
    private val cfg: MailerConfig,
    /** Test-only: trust this exact host's self-signed cert. Never set in production. */
    private val trustHostForTests: String? = null,
) : MailTransport {

    private val session: Session = buildSession()
    private val protocol: String = if (cfg.tls == MailerConfig.TlsMode.IMPLICIT) "smtps" else "smtp"

    override fun send(from: String, to: String, rendered: RenderedMail) {
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            setRecipient(Message.RecipientType.TO, InternetAddress(to))
            subject = rendered.subject
            setText(rendered.textBody, "UTF-8")
        }
        // Explicitly resolve the transport for OUR protocol and connect to the single
        // configured host:port. We do NOT use the static Transport.send(...) with user/pass,
        // because that overload resolves the default protocol and would fall back to plain
        // SMTP on port 25 — bypassing our TLS + pinned-host config.
        val transport = session.getTransport(protocol)
        try {
            if (!cfg.user.isNullOrBlank()) {
                transport.connect(cfg.host, cfg.port, cfg.user, cfg.pass)
            } else {
                transport.connect(cfg.host, cfg.port, null, null)
            }
            transport.sendMessage(msg, arrayOf(InternetAddress(to)))
        } finally {
            runCatching { transport.close() }
        }
    }

    private fun buildSession(): Session {
        val implicit = cfg.tls == MailerConfig.TlsMode.IMPLICIT
        val protocol = if (implicit) "smtps" else "smtp"
        val props = Properties().apply {
            put("mail.transport.protocol", protocol)
            put("mail.$protocol.host", cfg.host ?: "")
            put("mail.$protocol.port", cfg.port.toString())
            put("mail.$protocol.connectiontimeout", cfg.connectTimeoutMs.toString())
            put("mail.$protocol.timeout", cfg.readTimeoutMs.toString())
            put("mail.$protocol.writetimeout", cfg.readTimeoutMs.toString())
            // Server-identity checking is ON in production. It is relaxed ONLY when a test
            // trust host is pinned (GreenMail's self-signed cert has no matching SAN), which
            // never happens in a real deployment.
            put("mail.$protocol.ssl.checkserveridentity", if (trustHostForTests != null) "false" else "true")

            if (implicit) {
                put("mail.smtps.ssl.enable", "true")
            } else {
                // STARTTLS required — refuse to send if the server won't upgrade.
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }

            if (!cfg.user.isNullOrBlank()) {
                put("mail.$protocol.auth", "true")
            }

            // Test-only: trust GreenMail's self-signed cert for exactly one host. Production
            // leaves this unset so the JVM trust store + identity check apply.
            trustHostForTests?.let { put("mail.$protocol.ssl.trust", it) }
        }

        val authenticator = if (!cfg.user.isNullOrBlank()) {
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(cfg.user, cfg.pass ?: "")
            }
        } else {
            null
        }

        return Session.getInstance(props, authenticator)
    }
}
