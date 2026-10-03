package com.daymark.companion

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `DAYMARK_COOKIE_INSECURE` drops `Secure` from the clinician's session cookie, for a plain-http
 * test origin. On a server whose public address is https it has no use, and `Config.fromEnv` —
 * what `main` runs — refuses to start with it, naming both settings (#181).
 */
class InsecureCookieRefusalTest {

    private val portal = mapOf("DAYMARK_THERAPIST_AUTH" to "1")
    private val insecure = mapOf("DAYMARK_COOKIE_INSECURE" to "1")
    private fun address(value: String) = mapOf("DAYMARK_PUBLIC_BASE_URL" to value)

    private fun refused(env: Map<String, String>): String =
        assertFailsWith<StartupRefusal>("expected a refusal for $env") { Config.fromEnv(env) }.message

    @Test
    fun `the switch with an https address is refused, naming both settings and never the address`() {
        val message = refused(portal + insecure + address("https://planted-host.example"))
        assertTrue(
            message.startsWith(
                "Refusing to start: DAYMARK_COOKIE_INSECURE is on while DAYMARK_PUBLIC_BASE_URL is an https address.",
            ),
            message,
        )
        assertTrue("Remove DAYMARK_COOKIE_INSECURE" in message, message)
        assertFalse("planted-host" in message, "a refusal names the setting, never its value: $message")
    }

    @Test
    fun `the switch with a plain-http test address starts, and the cookie is not Secure`() {
        val config = Config.fromEnv(portal + insecure + address("http://localhost:8101"))
        assertFalse(config.cookieSecure)
    }

    @Test
    fun `without the switch an https address starts, and the cookie is Secure`() {
        // The positive control: the refusal is about the switch, not the address.
        val config = Config.fromEnv(portal + address("https://daymark.example.com"))
        assertTrue(config.cookieSecure)
    }

    @Test
    fun `either spelling of the switch, and either case of the scheme, is caught`() {
        for (on in listOf("1", "true", "TRUE")) {
            for (url in listOf("https://daymark.example.com", "HTTPS://daymark.example.com")) {
                val message = refused(portal + ("DAYMARK_COOKIE_INSECURE" to on) + address(url))
                assertTrue(message.startsWith("Refusing to start: DAYMARK_COOKIE_INSECURE is on"), "$on $url: $message")
            }
        }
        // And a value that does not turn the switch on refuses nothing.
        assertTrue(Config.fromEnv(portal + ("DAYMARK_COOKIE_INSECURE" to "0") + address("https://daymark.example.com")).cookieSecure)
    }

    @Test
    fun `an https WebAuthn origin standing in for the address is caught too, and named`() {
        val message = refused(portal + insecure + ("DAYMARK_WEBAUTHN_ORIGINS" to "https://daymark.example.com"))
        assertTrue(
            message.startsWith(
                "Refusing to start: DAYMARK_COOKIE_INSECURE is on while the first entry of " +
                    "DAYMARK_WEBAUTHN_ORIGINS, standing in for the unset DAYMARK_PUBLIC_BASE_URL, is an https address.",
            ),
            message,
        )
    }

    @Test
    fun `a sync-only server with no address has nothing to compare the switch against, and starts`() {
        val config = Config.fromEnv(insecure + ("DAYMARK_AUTH_TOKEN" to "owner-token"))
        assertFalse(config.cookieSecure)
    }
}
