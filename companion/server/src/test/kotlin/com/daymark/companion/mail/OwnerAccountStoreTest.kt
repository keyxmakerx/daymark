package com.daymark.companion.mail

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerAccountStoreTest {

    private fun tmpDir() = Files.createTempDirectory("owner-account-test").toString()

    @Test
    fun `first boot bootstraps the token from the env value`() {
        val dir = tmpDir()
        val store = OwnerAccountStore(dir, envToken = "env-token-1")
        assertEquals("env-token-1", store.currentToken())
        store.close()
    }

    @Test
    fun `a runtime rotation survives a restart when the env token is unchanged`() {
        val dir = tmpDir()
        val first = OwnerAccountStore(dir, envToken = "env-token-1")
        val rotated = first.rotateToken()
        assertNotEquals("env-token-1", rotated)
        first.close()

        // Simulate a restart with the SAME env value: the rotated token must still be live.
        val second = OwnerAccountStore(dir, envToken = "env-token-1")
        assertEquals(rotated, second.currentToken())
        second.close()
    }

    @Test
    fun `an operator changing the env token overrides a prior runtime rotation`() {
        val dir = tmpDir()
        val first = OwnerAccountStore(dir, envToken = "env-token-1")
        first.rotateToken()
        first.close()

        // Simulate a redeploy with a NEW secret file/env value: the operator's explicit change wins.
        val second = OwnerAccountStore(dir, envToken = "env-token-2")
        assertEquals("env-token-2", second.currentToken())
        second.close()
    }

    @Test
    fun `notification settings default empty then round-trip`() {
        val dir = tmpDir()
        val store = OwnerAccountStore(dir, envToken = "t")
        val initial = store.getNotificationSettings()
        assertNull(initial.email)
        assertTrue(initial.events.isEmpty())

        store.setNotificationSettings("owner@example.org", setOf(MailMessage.ReviewKind.NEW_ASSIGNMENT, MailMessage.ReviewKind.THERAPIST_ENROLLED))
        val updated = store.getNotificationSettings()
        assertEquals("owner@example.org", updated.email)
        assertEquals(setOf(MailMessage.ReviewKind.NEW_ASSIGNMENT, MailMessage.ReviewKind.THERAPIST_ENROLLED), updated.events)

        // Removing the email disables the feature; prefs can be cleared too.
        store.setNotificationSettings(null, emptySet())
        val cleared = store.getNotificationSettings()
        assertNull(cleared.email)
        assertTrue(cleared.events.isEmpty())
        store.close()
    }

    @Test
    fun `requestReissue only mints for the registered email, constant-time compared`() {
        val dir = tmpDir()
        val store = OwnerAccountStore(dir, envToken = "t")
        // No email registered yet: never mints.
        assertNull(store.requestReissue("owner@example.org", 3600))

        store.setNotificationSettings("owner@example.org", emptySet())
        assertNull(store.requestReissue("someone-else@example.org", 3600))
        val minted = store.requestReissue("owner@example.org", 3600)
        assertNotNull(minted)
        assertEquals("owner@example.org", minted.email)
        store.close()
    }

    @Test
    fun `confirmReissue rotates the token exactly once and is single-use`() {
        val dir = tmpDir()
        val store = OwnerAccountStore(dir, envToken = "original-token")
        store.setNotificationSettings("owner@example.org", emptySet())
        val minted = store.requestReissue("owner@example.org", 3600)!!

        val result = store.confirmReissue(minted.confirmToken)
        assertTrue(result is ReissueConfirmOutcome.Rotated)
        val newToken = (result as ReissueConfirmOutcome.Rotated).newToken
        assertNotEquals("original-token", newToken)
        assertEquals(newToken, store.currentToken())

        // Second confirm of the same token is Gone (single-use).
        assertEquals(ReissueConfirmOutcome.Gone, store.confirmReissue(minted.confirmToken))
        store.close()
    }

    @Test
    fun `confirmReissue is Gone after expiry`() {
        var now = 0L
        val dir = tmpDir()
        val store = OwnerAccountStore(dir, envToken = "t", clock = { now })
        store.setNotificationSettings("owner@example.org", emptySet())
        val minted = store.requestReissue("owner@example.org", ttlSeconds = 10)!!
        now += 20_000
        assertEquals(ReissueConfirmOutcome.Gone, store.confirmReissue(minted.confirmToken))
        store.close()
    }

    @Test
    fun `confirmReissue rejects an unknown token`() {
        val dir = tmpDir()
        val store = OwnerAccountStore(dir, envToken = "t")
        assertEquals(ReissueConfirmOutcome.Gone, store.confirmReissue("not-a-real-token"))
        store.close()
    }

    @Test
    fun `allowReissueAttempt caps requests per source and refills over time`() {
        var now = 0L
        val dir = tmpDir()
        val store = OwnerAccountStore(dir, envToken = "t", clock = { now })
        assertTrue(store.allowReissueAttempt("peer", maxPerHour = 2))
        assertTrue(store.allowReissueAttempt("peer", maxPerHour = 2))
        assertFalse(store.allowReissueAttempt("peer", maxPerHour = 2))
        // A different source is independent.
        assertTrue(store.allowReissueAttempt("other-peer", maxPerHour = 2))
        // After the full window elapses, the bucket refills.
        now += 3_600_000
        assertTrue(store.allowReissueAttempt("peer", maxPerHour = 2))
        store.close()
    }
}
