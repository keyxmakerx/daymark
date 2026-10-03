package com.daymark.companion

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every setting `.env.example` sets takes effect under the shipped compose file (#381).
 *
 * Compose reads `.env` only to fill `${...}` in `docker-compose.yml`; it has no `env_file:`. So a
 * key compose writes as a fixed value in `environment:` is a key that does nothing in `.env`, and
 * an operator who edits it there sees no change and no message. `.env.example` may name such a key
 * in a comment, never set it.
 */
class ComposeFixedSettingsTest {

    private val companion = File(System.getProperty("user.dir"), "..").canonicalFile

    private fun read(name: String): String {
        val file = File(companion, name)
        assertTrue(file.isFile, "${file.path} must exist: a file that is not read proves nothing")
        return file.readText()
    }

    @Test
    fun `the example environment sets nothing that compose fixes`() {
        val fixed = fixedByCompose(read("docker-compose.yml"))
        // The reader really finds compose's fixed keys: these five are the ones #381 named.
        assertTrue(
            fixed.containsAll(listOf("DAYMARK_BIND_ADDR", "DAYMARK_PORT", "DAYMARK_BASE_PATH", "DAYMARK_DATA_DIR", "DAYMARK_LOG_LEVEL")),
            "the compose reader found $fixed",
        )
        assertEquals(emptySet(), setIn(read(".env.example")) intersect fixed, "set in .env.example but fixed by compose")
    }

    @Test
    fun `the readers tell a fixed value from one filled from the environment, and a setting from a comment`() {
        val compose = """
            |    environment:
            |      DAYMARK_PORT:      "8080"
            |      DAYMARK_TRUSTED_PROXIES: "${'$'}{DAYMARK_TRUSTED_PROXIES:-}"
            |      # DAYMARK_RATE_LIMIT_RPS: "5"
        """.trimMargin()
        assertEquals(setOf("DAYMARK_PORT"), fixedByCompose(compose))
        val env = "DAYMARK_PORT=9090\n# DAYMARK_BIND_ADDR=0.0.0.0\n#   DAYMARK_DATA_DIR   /data\nDAYMARK_DOMAIN=x\n"
        assertEquals(setOf("DAYMARK_PORT", "DAYMARK_DOMAIN"), setIn(env))
        // Planted: the same check that passes on the shipped files fails on this pair.
        assertEquals(setOf("DAYMARK_PORT"), setIn(env) intersect fixedByCompose(compose))
    }

    private companion object {
        val COMPOSE_LITERAL = Regex("""^\s+(DAYMARK_[A-Z0-9_]+):\s*+(?!"?\$\{)""")
        val ENV_SETTING = Regex("""^(DAYMARK_[A-Z0-9_]+)=""")

        /** Keys `docker-compose.yml` writes as a fixed value, not filled from `.env`. */
        fun fixedByCompose(text: String): Set<String> =
            text.lines().filterNot { it.trimStart().startsWith("#") }
                .mapNotNull { COMPOSE_LITERAL.find(it)?.groupValues?.get(1) }.toSet()

        /** Keys a `.env` file sets, outside comments. */
        fun setIn(text: String): Set<String> =
            text.lines().mapNotNull { ENV_SETTING.find(it.trim())?.groupValues?.get(1) }.toSet()
    }
}
