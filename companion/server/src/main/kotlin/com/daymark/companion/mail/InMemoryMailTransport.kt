package com.daymark.companion.mail

import java.util.Collections

/**
 * Records every message it is asked to send. Used both as the no-op sink when SMTP is
 * disabled and as the assertion point in tests. Thread-safe.
 */
class InMemoryMailTransport : MailTransport {
    private val store = Collections.synchronizedList(mutableListOf<OutgoingMail>())

    /** Snapshot of everything sent so far. */
    val sent: List<OutgoingMail> get() = synchronized(store) { store.toList() }

    fun clear() = synchronized(store) { store.clear() }

    override fun send(from: String, to: String, rendered: RenderedMail) {
        store.add(OutgoingMail(from, to, rendered.subject, rendered.textBody))
    }
}
