package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.routes.THERAPIST_MAX_PER_WINDOW
import com.daymark.companion.storage.RelationStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The relationship surface had exactly one rate limit and the therapist could not reach it.
 *
 * `resolveRole` consulted `AuthGuard` — and therefore its token bucket — only when an
 * `Authorization` header was present. A therapist authenticates with a session cookie instead, so
 * every request they made skipped the meter entirely. What that bought them: each assignments or
 * gameplans PUT ends in a real SMTP send to the owner, so one signed-in therapist could keep
 * "you have something to review" mail arriving in the owner's inbox as fast as the network allowed,
 * indefinitely, from the one party this whole surface exists to constrain.
 *
 * The first test here fails against that code: it returned 200 to request number
 * THERAPIST_MAX_PER_WINDOW + 1 exactly as it did to number one.
 *
 * The other two pin the placement, which is as load-bearing as the limit. A budget charged too
 * early is a denial-of-service handed to strangers, and a budget charged too broadly locks the
 * owner out of their own console.
 */
class RelationRateLimitTest {

    private val ownerToken = "owner-token-abc"
    private val inboxToken = "inbox-token-256-bit-example-xyz"
    private val relRef = Secrets.relRefOf(inboxToken)

    private fun config(dataDir: String) = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dataDir, basePath = "/",
        webDir = "build/test-web", logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 26_215_424L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L,
        // Deliberately far above anything these tests send: the OWNER bucket must never be what
        // fails, or "the therapist is now metered" would be indistinguishable from "everything is".
        rateLimitRps = 100_000,
        therapistAuthEnabled = true,
        relMaxVersions = 50, relQuotaBytes = 268_435_456L,
        cookieSecure = false,
    )

    private fun tmpDir() = Files.createTempDirectory("rel-ratelimit-test").toString()

    private fun stores(dir: String, config: Config) = Triple(
        com.daymark.companion.storage.BlobStore(dir, config.maxBlobBytes, config.maxVersions, config.perTokenQuotaBytes),
        RelationStore(dir, config.maxBlobBytes, config.relMaxVersions, config.relQuotaBytes),
        AuthStore(dir),
    )

    /** A therapist session bound to [rel]: the raw session id (cookie) and its anti-CSRF token. */
    private data class TSession(val cookie: String, val csrf: String)

    private fun therapistSession(auth: AuthStore, rel: String): TSession {
        val session = auth.createSession("cred-" + rel.take(8), rel, 900L, 28_800L)
        return TSession(session.sessionId, session.csrfToken)
    }

    @Test
    fun `a cookie-authenticated therapist runs out of budget`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        val ts = therapistSession(auth, relRef)

        suspend fun read() = client.get("/v1/rel/$relRef/assignments") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${ts.cookie}")
        }

        // The whole budget must actually be spendable. Asserting only the refusal would pass just
        // as well against a limiter that refused the FIRST request, which is a different bug.
        repeat(THERAPIST_MAX_PER_WINDOW) { i ->
            assertEquals(HttpStatusCode.OK, read().status, "request ${i + 1} should still be inside the budget")
        }
        // Old behaviour: 200, forever. Nothing on the cookie path ever consulted a limiter.
        assertEquals(HttpStatusCode.TooManyRequests, read().status)
    }

    @Test
    fun `the owner's own bearer traffic never spends the therapist's budget`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        val ts = therapistSession(auth, relRef)

        // Well past the therapist window, on the same source address.
        repeat(THERAPIST_MAX_PER_WINDOW + 5) {
            val owner = client.get("/v1/rel/$relRef/assignments") {
                header("X-Rel-Token", inboxToken)
                header(HttpHeaders.Authorization, "Bearer $ownerToken")
            }
            assertEquals(HttpStatusCode.OK, owner.status)
        }
        // If the gate keyed on the address alone rather than on "presented a session cookie", the
        // owner reviewing their own inbox would have locked their therapist out of the portal.
        val therapist = client.get("/v1/rel/$relRef/assignments") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${ts.cookie}")
        }
        assertEquals(HttpStatusCode.OK, therapist.status)
    }

    @Test
    fun `a stranger with a bogus inbox token cannot burn the therapist's budget`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        val ts = therapistSession(auth, relRef)

        // Anyone who can reach the server can invent a cookie. If the budget were charged before
        // the inbox-token check, this loop alone would lock the real therapist out of their
        // caseload from the same NAT — a rate limit turned into a weapon against the person it
        // was meant to protect.
        repeat(THERAPIST_MAX_PER_WINDOW + 5) {
            val stranger = client.get("/v1/rel/$relRef/assignments") {
                header("X-Rel-Token", "not-the-token")
                header(HttpHeaders.Cookie, "daymark_session=made-up")
            }
            assertEquals(HttpStatusCode.Unauthorized, stranger.status)
        }
        val therapist = client.get("/v1/rel/$relRef/assignments") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${ts.cookie}")
        }
        assertEquals(HttpStatusCode.OK, therapist.status)
    }

    /**
     * Pins what the setting allowlist actually does, so the corrected wording on the PUT route has
     * something executable behind it.
     *
     * The allowlist is only ever consulted when the caller volunteers `X-Setting-Key`. The shipping
     * therapist client (`assignClient.publishAssignment`) sends no such header, so in production the
     * gate is not entered at all — while `RelationStore`'s KDoc describes it as guaranteeing that no
     * PIN/lock/encryption key can transit this channel. This test does not fail against the old
     * code; nothing about the behaviour changed. It exists so that the next person to read that
     * guarantee can see, in one place, that an untagged assignment is stored without objection.
     */
    @Test
    fun `an assignment with no X-Setting-Key is stored untagged - the allowlist is never consulted`() = testApplication {
        val dir = tmpDir()
        val cfg = config(dir)
        val (blob, rel, auth) = stores(dir, cfg)
        application { module(cfg, blob, null, rel, auth) }
        val ts = therapistSession(auth, relRef)

        suspend fun publish(lineage: String, tag: String?) = client.put("/v1/rel/$relRef/assignments/$lineage/0") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${ts.cookie}")
            header("X-CSRF-Token", ts.csrf)
            if (tag != null) header("X-Setting-Key", tag)
            setBody(byteArrayOf(1, 2, 3))
        }
        suspend fun versionsOf(lineage: String) = client.get("/v1/rel/$relRef/assignments/$lineage") {
            header("X-Rel-Token", inboxToken)
            header(HttpHeaders.Cookie, "daymark_session=${ts.cookie}")
        }.bodyAsText()

        // The detector first: a tag that IS sent shows up in the metadata, so its absence below
        // means "no tag was recorded" rather than "this assertion cannot see tags".
        assertEquals(HttpStatusCode.Created, publish("tagged", "theme").status)
        assertTrue(versionsOf("tagged").contains("\"settingKey\":\"theme\""))

        // And the case the real client actually produces: accepted, with nothing recorded to check.
        assertEquals(HttpStatusCode.Created, publish("untagged", null).status)
        assertFalse(versionsOf("untagged").contains("settingKey"))
    }
}
