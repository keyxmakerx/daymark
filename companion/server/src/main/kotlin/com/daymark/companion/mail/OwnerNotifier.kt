package com.daymark.companion.mail

import java.net.URI
import org.slf4j.LoggerFactory

/**
 * Fires the owner-facing [MailMessage.ReviewNotification] events from Track T2's mini-spec
 * (therapist enrolled, new inbox item), gated on the owner having registered an email AND
 * opted into that [MailMessage.ReviewKind]. A no-op (never throws, never gates the caller's own
 * operation) when nothing is registered, the event isn't opted into, or SMTP is disabled —
 * identical posture to [Mailer.send] itself.
 */
class OwnerNotifier(
    private val accountStore: OwnerAccountStore,
    private val mailer: Mailer,
) {
    private val log = LoggerFactory.getLogger(OwnerNotifier::class.java)

    /**
     * Never throws, regardless of the failure (a bad DB read included, not just a failed send) —
     * callers rely on this to never turn their own already-committed operation into an error.
     */
    fun notify(event: MailMessage.ReviewKind, portalUrl: URI) {
        runCatching {
            val settings = accountStore.getNotificationSettings()
            val email = settings.email ?: return@runCatching
            if (event !in settings.events) return@runCatching
            mailer.send(MailMessage.ReviewNotification(email, portalUrl, event))
        }.onFailure { log.warn("owner notification failed (event={}): {}", event, it.javaClass.simpleName) }
    }
}
