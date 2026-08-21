package com.daymark.companion

import com.daymark.companion.auth.AuthStore
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.RelationStore
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invitation link has to reach a page.
 *
 * `buildInviteLink` has always addressed invitations to `{base}/portal/invite#id=...&s=...`, and for
 * the whole life of the therapist feature NOTHING served that path — the only occurrence of the
 * string in the repository was the line constructing it. Every invitation the server ever sent was a
 * 404, and that single break is why the clinician half of the product was unusable and why the
 * sign-in form ended up asking for nine hand-pasted cryptographic values.
 *
 * It is worth a test of its own rather than a line in a routing test, because of HOW it stayed
 * broken: nothing failed. The client had a parser for the fragment, the server had a handler for
 * every `/v1` route the page would call, both suites were green, and the one thing missing was the
 * step that hands a browser the HTML. A test that exercises the API but never asks for the page
 * cannot see that, which is the shape this repository keeps rediscovering.
 *
 * So the assertion is deliberately end-of-the-line: ask for the path a real invitation names, and
 * require the therapist entry document back.
 */
class InvitePathRouteTest {

    private val ownerToken = "owner-token-abc"

    /** Marker text unique to the therapist entry, so "served something" cannot pass for "served this". */
    private val therapistMarker = "<div id=\"therapist-app\"></div>"
    private val ownerMarker = "<div id=\"owner-app\"></div>"

    private fun config(dataDir: String, webDir: String, basePath: String = "/") = Config(
        bindAddr = "127.0.0.1", port = 8080, dataDir = dataDir, basePath = basePath,
        webDir = webDir, logLevel = "info", authToken = ownerToken,
        maxBlobBytes = 26_214_400L, maxRequestBytes = 27_262_976L,
        maxVersions = 200, perTokenQuotaBytes = 5_368_709_120L,
        authLockoutFails = 8, authLockoutSeconds = 900L, rateLimitRps = 500,
        therapistAuthEnabled = true, totpLockoutFails = 3, totpLockoutSeconds = 60L,
        inviteTtlSeconds = 3600L, cookieSecure = false,
    )

    /** A web root holding the two entry documents, distinguishable from each other. */
    private fun webRoot(): String {
        val dir = Files.createTempDirectory("invite-path-web").toFile()
        File(dir, "therapist.html").writeText("<!doctype html><html><body>$therapistMarker</body></html>")
        File(dir, "index.html").writeText("<!doctype html><html><body>$ownerMarker</body></html>")
        return dir.absolutePath
    }

    private class Stores(val auth: AuthStore, val rel: RelationStore, val audit: AuditStore)

    private fun stores(dir: String, cfg: Config) = Stores(
        AuthStore(dir),
        RelationStore(dir, cfg.maxBlobBytes, cfg.relMaxVersions, cfg.relQuotaBytes),
        AuditStore(dir),
    )

    @Test
    fun `the path an invitation names serves the therapist entry`() = testApplication {
        val data = Files.createTempDirectory("invite-path-data").toString()
        val cfg = config(data, webRoot())
        val s = stores(data, cfg)
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }

        // followRedirects is OFF so the redirect itself is the assertion. This test previously
        // asserted a 200 carrying the therapist marker — which was TRUE and which shipped a page
        // that rendered nothing at all, because relative asset URLs resolve against /portal/ from
        // this path. Asserting the destination is the only version of this check that means
        // anything; see the reasoning beside the route in Application.kt.
        val res = client.config { followRedirects = false }.get("/portal/invite")
        assertEquals(HttpStatusCode.Found, res.status, "the link the server itself emails must not 404")
        assertEquals(
            "/therapist",
            res.headers[HttpHeaders.Location],
            "it must land on a path whose RELATIVE asset URLs resolve — serving the markup here " +
                "returns 200 and a blank page, which is worse than a 404 because it looks like it worked",
        )
    }

    @Test
    fun `it is the therapist entry and not the owner viewer`() = testApplication {
        val data = Files.createTempDirectory("invite-path-data").toString()
        val cfg = config(data, webRoot())
        val s = stores(data, cfg)
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }

        // The detector, proved in the other direction: the root really does serve the owner's
        // document, so the assertion above is a fact about routing rather than about both paths
        // happening to return the same bytes.
        val root = client.get("/")
        assertTrue(root.bodyAsText().contains(ownerMarker), "the root should still be the owner viewer")

        // Following the redirect must arrive at the therapist entry, not the owner's viewer.
        val invite = client.get("/portal/invite")
        assertTrue(invite.bodyAsText().contains(therapistMarker), "the invite path must reach the therapist entry")
        assertTrue(!invite.bodyAsText().contains(ownerMarker), "the invite path must not fall through to index.html")
    }

    @Test
    fun `it is served under a configured base path too`() = testApplication {
        // A deployment behind a reverse proxy sets DAYMARK_BASE_PATH, and buildInviteLink includes
        // it. The route has to move with it, or invitations 404 on exactly the deployments most
        // likely to be reachable by a clinician at all.
        val data = Files.createTempDirectory("invite-path-data").toString()
        val cfg = config(data, webRoot(), basePath = "/daymark")
        val s = stores(data, cfg)
        application { module(cfg, null, null, s.rel, s.auth, s.audit) }

        // The redirect has to carry the base path with it, or a proxied deployment lands on a
        // /therapist that does not exist there.
        val hop = client.config { followRedirects = false }.get("/daymark/portal/invite")
        assertEquals(HttpStatusCode.Found, hop.status)
        assertEquals("/daymark/therapist", hop.headers[HttpHeaders.Location])

        val res = client.get("/daymark/portal/invite")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains(therapistMarker))
    }
}
