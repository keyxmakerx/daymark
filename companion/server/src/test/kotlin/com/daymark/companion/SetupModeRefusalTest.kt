package com.daymark.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `DAYMARK_SETUP_MODE` as `Config.fromEnv` — what `main` runs — reads it (#330).
 *
 * One of three words, in any case, with space around it; blank is unset. Anything else stops the
 * server at start, in one line that names the setting and never the value — it is never read as a
 * shape, least of all as everything on. `DAYMARK_THERAPIST_AUTH`, the switch the mode replaces, may
 * be left out beside a mode, and when it is set it must agree with it. With no mode the switch
 * decides, as it did before the setting existed. Every refusal here has its positive control: the
 * configuration beside it that starts.
 */
class SetupModeRefusalTest {

    private val address = mapOf("DAYMARK_PUBLIC_BASE_URL" to "https://daymark.example.com")
    private fun mode(value: String) = mapOf("DAYMARK_SETUP_MODE" to value)
    private fun switch(value: String) = mapOf("DAYMARK_THERAPIST_AUTH" to value)

    private fun refused(env: Map<String, String>): String =
        assertFailsWith<StartupRefusal>("expected a refusal for $env") { Config.fromEnv(env) }.message

    private val unknown = "Refusing to start: DAYMARK_SETUP_MODE is not one of solo, paired or practice."

    @Test
    fun `each shape is read from DAYMARK_SETUP_MODE, in any case and with space around it`() {
        for (shape in SetupMode.entries) {
            val word = shape.wire
            for (spelling in listOf(word, word.uppercase(), " ${word.replaceFirstChar { it.uppercase() }} ", "\t$word\n")) {
                val config = Config.fromEnv(mode(spelling) + address)
                assertEquals(shape, config.setupMode, "'$spelling'")
                assertEquals(shape, config.shape, "'$spelling'")
            }
        }
    }

    @Test
    fun `a value that names none of the three is refused, naming the setting`() {
        val nearMisses = listOf(
            "sol", "solo2", "solos", "pair", "pairs", "paired2", "practise", "practices", "prac",
            "solo,paired", "solo paired", "paired|practice", "s o l o", "1", "0", "true", "on", "off",
            "all", "none", "default", "clinic", "therapist", "single",
        )
        for (value in nearMisses) {
            assertNull(SetupMode.parse(value), value)
            assertTrue(refused(mode(value) + address).startsWith(unknown), value)
        }
    }

    @Test
    fun `a refused value is never repeated back`() {
        val mark = "zqx"
        for (value in listOf(mark, "solo-$mark", "$mark paired", "PRACTICE_${mark.uppercase()}")) {
            assertTrue(mark in value.lowercase(), "the planted mark must be in every case, or the check proves nothing")
            val message = refused(mode(value) + address)
            assertTrue(message.startsWith(unknown), message)
            assertFalse(mark in message.lowercase(), "a refusal names the setting, never its value: $message")
        }
    }

    @Test
    fun `an unknown value is refused whatever else is set, so it never falls back to everything on`() {
        for (other in listOf(emptyMap(), switch("1"), switch("0"), switch("true"))) {
            assertTrue(refused(mode("everything") + other + address).startsWith(unknown), "$other")
        }
    }

    @Test
    fun `blank is unset, not unknown`() {
        // Compose passes every setting it names, empty when .env leaves it out.
        for (blank in listOf("", " ", "\t")) {
            assertNull(Config.fromEnv(mode(blank)).setupMode, "'$blank'")
            assertEquals(SetupMode.PRACTICE, Config.fromEnv(mode(blank) + switch("1") + address).shape, "'$blank'")
        }
    }

    @Test
    fun `solo with DAYMARK_THERAPIST_AUTH on is refused, naming both settings`() {
        for (on in listOf("1", "true", "TRUE", " 1 ")) {
            val message = refused(mode("solo") + switch(on) + address)
            assertTrue(
                message.startsWith("Refusing to start: DAYMARK_SETUP_MODE is solo while DAYMARK_THERAPIST_AUTH is on"),
                "'$on': $message",
            )
            assertTrue("Remove DAYMARK_THERAPIST_AUTH" in message, message)
        }
    }

    @Test
    fun `solo starts with DAYMARK_THERAPIST_AUTH off or left out`() {
        // The positive control for the refusal above: it is about the switch being on, not about solo.
        for (off in listOf(emptyMap(), switch(""), switch("0"), switch("false"))) {
            val config = Config.fromEnv(mode("solo") + off)
            assertEquals(SetupMode.SOLO, config.shape, "$off")
            assertFalse(config.buildsLinks, "$off: a solo server hands out no invitation links")
        }
    }

    @Test
    fun `paired or practice with DAYMARK_THERAPIST_AUTH set to off is refused, naming both settings`() {
        for (shape in listOf("paired", "practice")) {
            for (off in listOf("0", "false", "no", "off", "yes", "zqx")) {
                val message = refused(mode(shape) + switch(off) + address)
                assertTrue(
                    message.startsWith(
                        "Refusing to start: DAYMARK_SETUP_MODE is $shape, which turns the clinician portal on, " +
                            "while DAYMARK_THERAPIST_AUTH is set to turn it off",
                    ),
                    "$shape '$off': $message",
                )
                assertFalse("zqx" in message, "the switch's value is never repeated back: $message")
            }
        }
    }

    @Test
    fun `paired and practice start with DAYMARK_THERAPIST_AUTH on or left out, and switch the portal on`() {
        // The positive control for the refusal above, and the mode alone is enough.
        for (shape in listOf(SetupMode.PAIRED, SetupMode.PRACTICE)) {
            for (on in listOf(emptyMap(), switch(""), switch("1"), switch("true"))) {
                val config = Config.fromEnv(mode(shape.wire) + on + address)
                assertEquals(shape, config.shape, "$on")
                assertTrue(config.buildsLinks, "$on: a portal hands out invitation links")
            }
        }
    }

    @Test
    fun `a shape with the clinician portal needs the public address, and the refusal names the setup mode`() {
        for (shape in listOf("paired", "practice")) {
            for (on in listOf(emptyMap(), switch("1"))) {
                val message = refused(mode(shape) + on)
                assertTrue(message.startsWith("Refusing to start: DAYMARK_PUBLIC_BASE_URL is not set."), message)
                assertTrue("DAYMARK_SETUP_MODE is $shape, so this server sends links" in message, message)
                assertFalse("DAYMARK_THERAPIST_AUTH" in message, "the mode is what switched the portal on: $message")
            }
            // #180's other refusal, an address no link can be built on, applies the same way.
            val unusable = refused(mode(shape) + ("DAYMARK_PUBLIC_BASE_URL" to "no-scheme.example"))
            assertTrue(unusable.startsWith("Refusing to start: DAYMARK_PUBLIC_BASE_URL is not a usable address"), unusable)
        }
        // A solo server that only syncs builds no links and needs no address: the positive control.
        assertEquals(SetupMode.SOLO, Config.fromEnv(mode("solo") + ("DAYMARK_AUTH_TOKEN" to "owner-token")).shape)
    }

    @Test
    fun `with no mode, DAYMARK_THERAPIST_AUTH decides, as before`() {
        assertEquals(SetupMode.SOLO, Config.fromEnv(emptyMap()).shape)
        assertEquals(SetupMode.PRACTICE, Config.fromEnv(switch("1") + address).shape)
        assertEquals(SetupMode.PRACTICE, Config.fromEnv(switch("true") + address).shape)
        assertEquals(SetupMode.SOLO, Config.fromEnv(switch("0")).shape)
        // Anything but 1 or true has always been off.
        assertEquals(SetupMode.SOLO, Config.fromEnv(switch("yes")).shape)
        for (env in listOf(emptyMap(), switch("1") + address)) {
            assertNull(Config.fromEnv(env).setupMode, "an assumed shape is not a chosen one: $env")
        }
        // And the refusal that names the switch still names it when the switch is what turned the portal on.
        assertTrue("DAYMARK_THERAPIST_AUTH is on" in refused(switch("1")))
    }
}
