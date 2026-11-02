package com.daymark.companion

import com.daymark.companion.auth.AuthStore

/**
 * A session for [accountId] that reaches [relRef], for tests that exercise a data-plane route and not
 * how the relationship came to be attached (#314). Attaches the relationship to the account through
 * the fixture-only door, then makes the account's session. StaffAccountTest proves no main source
 * uses that door.
 */
fun AuthStore.createSession(accountId: String, relRef: String, idleSeconds: Long, absoluteSeconds: Long): AuthStore.NewSession {
    attachForFixture(relRef, accountId)
    return createSession(accountId, idleSeconds, absoluteSeconds)
}
