package com.daymark.companion.mail

import com.daymark.companion.auth.Secrets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerAccountStoreTest {

    private fun tmpDir() = Files.createTempDirectory("owner-account-test").toString()

    /**
     * Read a column of `owner_token` directly via a fresh JDBC connection, bypassing
     * OwnerAccountStore's own accessors entirely — the store's own accessors could paper over a
     * bug in what actually lands on disk. WAL mode allows a second connection to the same file.
     */
    private fun rawTokenColumn(dir: String, column: String): String {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${Path.of(dir).resolve("owner-account.db")}").use { conn ->
            conn.prepareStatement("SELECT $column FROM owner_token WHERE id=1").use { ps ->
                ps.executeQuery().use { rs -> rs.next(); return rs.getString(1) }
            }
        }
    }

    @Test
    fun `owner token and bootstrap_token are stored as a digest, never the plaintext`() {
        // Positive control FIRST: prove rawTokenColumn can actually see a planted plaintext value
        // before trusting it to report "not the plaintext" for the real bootstrap path below.
        // Without this, a reader that is silently broken (wrong table/column name, or one that
        // always returns null) would make the negative assertions pass for the wrong reason.
        val controlDir = tmpDir()
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${Path.of(controlDir).resolve("owner-account.db")}").use { conn ->
            conn.createStatement()
                .use { it.execute("CREATE TABLE owner_token (id INTEGER PRIMARY KEY, token TEXT, bootstrap_token TEXT, updated_at INTEGER)") }
            conn.prepareStatement("INSERT INTO owner_token(id, token, bootstrap_token, updated_at) VALUES (1,?,?,0)").use { ps ->
                ps.setString(1, "planted-plaintext-value")
                ps.setString(2, "planted-plaintext-value")
                ps.executeUpdate()
            }
        }
        assertEquals(
            "planted-plaintext-value",
            rawTokenColumn(controlDir, "token"),
            "positive control: the raw column reader must see a planted plaintext value",
        )

        // The real assertion: a freshly bootstrapped store must not leave the plaintext at rest.
        val dir = tmpDir()
        val store = OwnerAccountStore(dir, envToken = "super-secret-owner-token")
        val storedToken = rawTokenColumn(dir, "token")
        val storedBootstrap = rawTokenColumn(dir, "bootstrap_token")
        assertNotEquals("super-secret-owner-token", storedToken, "token column must not hold the plaintext")
        assertNotEquals("super-secret-owner-token", storedBootstrap, "bootstrap_token column must not hold the plaintext")
        assertEquals(Secrets.tokenHash("super-secret-owner-token"), storedToken, "token column must hold the digest")
        assertEquals(Secrets.tokenHash("super-secret-owner-token"), storedBootstrap, "bootstrap_token column must hold the digest")
        store.close()
    }

    @Test
    fun `first boot bootstraps the token from the env value`() {
        val dir = tmpDir()
        val store = OwnerAccountStore(dir, envToken = "env-token-1")
        assertEquals(Secrets.tokenHash("env-token-1"), store.currentTokenHash())
        store.close()
    }

    @Test
    fun `an operator changing the env token makes the new value the accepted one`() {
        val dir = tmpDir()
        val first = OwnerAccountStore(dir, envToken = "env-token-1")
        assertEquals(Secrets.tokenHash("env-token-1"), first.currentTokenHash())
        first.close()

        // Simulate a redeploy with a NEW secret file/env value: the operator's explicit change wins.
        val second = OwnerAccountStore(dir, envToken = "env-token-2")
        assertEquals(Secrets.tokenHash("env-token-2"), second.currentTokenHash())
        second.close()
    }

    @Test
    fun `an upgrade from a plaintext-at-rest deployment re-seeds from the env value, not a lockout`() {
        // Simulates issue #113's migration case: an existing deployment has a PLAINTEXT value
        // sitting in bootstrap_token, from before this fix. A digest of the (unchanged) env token
        // can never equal that plaintext, so bootstrapToken's mismatch check reads as "the
        // operator changed the env var" and re-seeds both columns — safe, because the operator's
        // env var is exactly what they can still present, never a lockout.
        val dir = tmpDir()
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${Path.of(dir).resolve("owner-account.db")}").use { conn ->
            conn.createStatement()
                .use { it.execute("CREATE TABLE owner_token (id INTEGER PRIMARY KEY, token TEXT, bootstrap_token TEXT, updated_at INTEGER)") }
            conn.prepareStatement("INSERT INTO owner_token(id, token, bootstrap_token, updated_at) VALUES (1,?,?,0)").use { ps ->
                ps.setString(1, "legacy-plaintext-token")
                ps.setString(2, "legacy-plaintext-token")
                ps.executeUpdate()
            }
        }

        // Boot with the SAME env value the legacy plaintext held — the operator changed nothing.
        val store = OwnerAccountStore(dir, envToken = "legacy-plaintext-token")
        assertEquals(
            Secrets.tokenHash("legacy-plaintext-token"),
            store.currentTokenHash(),
            "post-upgrade boot must accept the operator's env-var token",
        )
        assertNotEquals(
            "legacy-plaintext-token",
            rawTokenColumn(dir, "token"),
            "the re-seed must leave a digest, not the plaintext, at rest",
        )
        store.close()
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
    fun `email registration is stored in one spelling, lowercase`() {
        val dir = tmpDir()
        val store = OwnerAccountStore(dir, envToken = "t")
        store.setNotificationSettings("Owner@Example.ORG", emptySet())
        assertEquals("owner@example.org", store.registeredEmail())
        store.close()
    }

    @Test
    fun `registeredEmail returns just the email without parsing events`() {
        val dir = tmpDir()
        val store = OwnerAccountStore(dir, envToken = "t")
        assertNull(store.registeredEmail())
        store.setNotificationSettings("owner@example.org", setOf(MailMessage.ReviewKind.NEW_ASSIGNMENT))
        assertEquals("owner@example.org", store.registeredEmail())
        store.close()
    }
}
