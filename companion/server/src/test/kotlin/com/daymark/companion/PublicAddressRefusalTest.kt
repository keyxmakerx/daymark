package com.daymark.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The public address every link this server hands out is built on (#180).
 *
 * While the clinician portal or outbound email is on, `Config.fromEnv` — what `main` runs — refuses
 * to start without an address, or with one no link can be built on, in one line that names the
 * setting and gives an example. A sync-only server builds no links and needs none.
 */
class PublicAddressRefusalTest {

    private val portal = mapOf("DAYMARK_THERAPIST_AUTH" to "1")
    private val smtp = mapOf("DAYMARK_SMTP_HOST" to "mail.example.org", "DAYMARK_SMTP_FROM" to "companion@example.org")
    private val address = mapOf("DAYMARK_PUBLIC_BASE_URL" to "https://daymark.example.com")

    private fun refused(env: Map<String, String>): String =
        assertFailsWith<StartupRefusal>("expected a refusal for $env") { Config.fromEnv(env) }.message

    @Test
    fun `the clinician portal without a public address is refused, naming the setting and an example`() {
        val message = refused(portal)
        assertTrue(message.startsWith("Refusing to start: DAYMARK_PUBLIC_BASE_URL is not set."), message)
        assertTrue("DAYMARK_THERAPIST_AUTH is on" in message, message)
        assertTrue(Config.EXAMPLE_PUBLIC_BASE_URL in message, message)
    }

    @Test
    fun `outbound email without a public address is refused, naming only what is on`() {
        val message = refused(smtp)
        assertTrue(message.startsWith("Refusing to start: DAYMARK_PUBLIC_BASE_URL is not set."), message)
        assertTrue("DAYMARK_SMTP_HOST is set" in message, message)
        assertFalse("DAYMARK_THERAPIST_AUTH" in message, message)
        assertTrue("DAYMARK_THERAPIST_AUTH is on and DAYMARK_SMTP_HOST is set" in refused(portal + smtp))
    }

    @Test
    fun `with a public address the same configurations start, and it is the address links use`() {
        // The positive control for both refusals above: the check is about the address, not the features.
        assertEquals("https://daymark.example.com", Config.fromEnv(portal + address).publicBaseUrl)
        assertEquals("https://daymark.example.com", Config.fromEnv(smtp + address).publicBaseUrl)
        assertTrue(Config.fromEnv(portal + smtp + address).buildsLinks)
    }

    @Test
    fun `the first WebAuthn origin still stands in for an unset public address`() {
        val config = Config.fromEnv(portal + ("DAYMARK_WEBAUTHN_ORIGINS" to " https://daymark.example.com , https://other.example"))
        assertEquals("https://daymark.example.com", config.publicBaseUrl)
    }

    @Test
    fun `a sync-only server starts without a public address, and does not read one`() {
        val config = Config.fromEnv(mapOf("DAYMARK_AUTH_TOKEN" to "owner-token"))
        assertTrue(config.syncEnabled)
        assertFalse(config.buildsLinks)
        assertNull(config.publicBaseUrl)
        // Not consulted, so not checked: an address no link will ever be built on refuses nothing.
        Config.fromEnv(mapOf("DAYMARK_AUTH_TOKEN" to "owner-token", "DAYMARK_PUBLIC_BASE_URL" to "not an address"))
    }

    @Test
    fun `an address no link can be built on is refused the same way, and never repeated back`() {
        val mark = "planted-host"
        val unusable = listOf(
            "$mark.example", // no scheme
            "ftp://$mark.example", // neither http nor https
            "https:///$mark", // no host
            "https:$mark.example", // no authority at all
            "https://$mark.example@evil.example", // a user name: the link's host would be evil.example
            "https://$mark.example?x=1", // a query the path would be appended to
            "https://$mark.example#x", // a fragment the path would be appended to
            "https://https://$mark.example", // the scheme typed twice: a host named "https"
            "https://$mark.example:0", // no such port
            "https://$mark.example:99999",
            "https://$mark example", // a space
            "https://${mark}_lan", // an underscore is not a host name
        )
        for (value in unusable) {
            assertTrue(mark in value, "the planted mark must be in every case, or the last check proves nothing")
            assertFalse(Config.isUsableBaseUrl(value), value)
            val message = refused(portal + ("DAYMARK_PUBLIC_BASE_URL" to value))
            assertTrue(message.startsWith("Refusing to start: DAYMARK_PUBLIC_BASE_URL is not a usable address"), message)
            assertTrue(Config.EXAMPLE_PUBLIC_BASE_URL in message, message)
            assertFalse(mark in message, "a refusal names the setting, never its value: $message")
        }
    }

    @Test
    fun `the addresses people actually type are usable`() {
        // The positive control for the list above: a check that refused everything would pass it too.
        val usable = listOf(
            "https://daymark.example.com",
            "https://daymark.example.com/",
            "HTTPS://Daymark.Example.com",
            "https://daymark.example.com:8443",
            "https://example.org/daymark",
            "http://localhost:8101",
            "http://127.0.0.1:8101",
            "https://[::1]:8443",
            "https://xn--bcher-kva.example",
        )
        for (value in usable) {
            assertTrue(Config.isUsableBaseUrl(value), value)
            assertEquals(value, Config.fromEnv(portal + ("DAYMARK_PUBLIC_BASE_URL" to value)).publicBaseUrl)
        }
    }

    @Test
    fun `a WebAuthn origin standing in for the address is checked too, and the refusal names that setting`() {
        val message = refused(portal + ("DAYMARK_WEBAUTHN_ORIGINS" to "planted-host.example, https://daymark.example.com"))
        assertTrue(
            message.startsWith(
                "Refusing to start: the first entry of DAYMARK_WEBAUTHN_ORIGINS, standing in for the unset " +
                    "DAYMARK_PUBLIC_BASE_URL, is not a usable address",
            ),
            message,
        )
        assertFalse("planted-host" in message, message)
    }

    @Test
    fun `a refusal is one line`() {
        assertFailsWith<IllegalArgumentException> { StartupRefusal("Refusing to start: one\nand another") }
        assertFailsWith<IllegalArgumentException> { StartupRefusal("Refusing to start: one\rand another") }
        StartupRefusal("Refusing to start: one line") // the positive control: a single line is accepted
    }
}
