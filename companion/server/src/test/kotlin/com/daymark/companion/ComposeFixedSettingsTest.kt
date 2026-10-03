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

    /** Keys a `.env` line sets or shows how to set, commented out or not: `KEY=` at the start. */
    private fun named(text: String): Set<String> =
        text.lines().mapNotNull { NAMED.find(it.trim())?.groupValues?.get(1) }.toSet()

    /** Keys a compose file, or the §8 SMTP override, fills from `.env` with `${KEY`. */
    private fun interpolated(text: String): Set<String> =
        INTERPOLATED.findAll(text).map { it.groupValues[1] }.toSet()

    /** The SMTP override `docs/COMPANION_DEPLOYMENT.md` §8 gives the operator to write. */
    private fun smtpOverride(): String {
        val doc = File(companion, "../docs/COMPANION_DEPLOYMENT.md")
        assertTrue(doc.isFile, "${doc.path} must exist")
        val section = doc.readText().substringAfter("## 8. ").substringBefore("\n## 9. ")
        return section.substringAfter("```yaml").substringBefore("```")
    }

    @Test
    fun `every setting the example environment shows how to set reaches the server`() {
        // #443: the example showed email and limit settings that compose never passed on, so
        // uncommenting them changed nothing and said nothing.
        val reached = interpolated(read("docker-compose.yml")) + interpolated(read("docker-compose.no-egress.yml")) +
            interpolated(smtpOverride())
        // The readers really read: compose's own and the override's keys are found.
        assertTrue("DAYMARK_DOMAIN" in reached && "DAYMARK_SMTP_HOST" in reached, "found $reached")
        val shown = named(read(".env.example"))
        assertTrue("DAYMARK_DOMAIN" in shown && "DAYMARK_SMTP_HOST" in shown, "found $shown")
        assertEquals(emptySet(), shown - reached, "shown in .env.example but never passed to the server")
        // Planted: the line #443 found is caught.
        assertEquals(setOf("DAYMARK_MAX_VERSIONS"), named("# DAYMARK_MAX_VERSIONS=200\n") - reached)
    }

    @Test
    fun `every limit the example lists as fixed is fixed by compose`() {
        val fixed = fixedByCompose(read("docker-compose.yml"))
        for (key in listOf("DAYMARK_MAX_BLOB_BYTES", "DAYMARK_MAX_VERSIONS", "DAYMARK_RATE_LIMIT_RPS", "DAYMARK_AUTH_TOKEN_FILE")) {
            assertTrue(key in fixed, "$key is described as fixed by compose, and compose does not set it")
        }
    }

    private companion object {
        val NAMED = Regex("""^#?\s*(DAYMARK_[A-Z0-9_]+)=""")
        val INTERPOLATED = Regex("""\$\{(DAYMARK_[A-Z0-9_]+)""")

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
