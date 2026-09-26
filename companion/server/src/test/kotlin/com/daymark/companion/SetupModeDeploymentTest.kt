package com.daymark.companion

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DAYMARK_SETUP_MODE` reaches the server from the deployment files (#330).
 *
 * Compose hands the container only the settings its `environment:` block names, so a mode written
 * in `.env` and not passed through would change nothing and say nothing. The switch it replaces is
 * passed empty unless `.env` sets it, so a mode set on its own starts rather than meeting a `0`
 * compose supplied. CI boots compose from a copy of `.env.example`, so the example must start too.
 */
class SetupModeDeploymentTest {

    /** Gradle runs the tests from companion/server; the deployment files sit one level up. */
    private val companion = File(System.getProperty("user.dir"), "..").canonicalFile

    private fun deploymentFile(name: String): String {
        val file = File(companion, name)
        assertTrue(file.isFile, "${file.path} must exist: a file that is not read sets nothing, and proves nothing")
        return file.readText()
    }

    @Test
    fun `compose passes the setup mode and the switch it replaces from dot-env, empty when unset`() {
        val compose = deploymentFile("docker-compose.yml")
        assertEquals(listOf("\${DAYMARK_SETUP_MODE:-}"), composeValues(compose, "DAYMARK_SETUP_MODE"))
        assertEquals(listOf("\${DAYMARK_THERAPIST_AUTH:-}"), composeValues(compose, "DAYMARK_THERAPIST_AUTH"))
    }

    @Test
    fun `the example environment starts, and chooses solo`() {
        val example = envValues(deploymentFile(".env.example"))
        // What the container sees: each setting compose passes through, empty when .env leaves it out.
        val env = mapOf(
            "DAYMARK_SETUP_MODE" to example["DAYMARK_SETUP_MODE"].orEmpty(),
            "DAYMARK_THERAPIST_AUTH" to example["DAYMARK_THERAPIST_AUTH"].orEmpty(),
            "DAYMARK_PUBLIC_BASE_URL" to "https://${example.getValue("DAYMARK_DOMAIN")}",
        )
        val config = Config.fromEnv(env)
        assertEquals(SetupMode.SOLO, config.setupMode, "the example chooses a shape, so the first-run screen does not ask")
        assertEquals(SetupMode.SOLO, config.shape)
    }

    @Test
    fun `the readers find each setting in its file's syntax, and skip comments`() {
        // The positive control for both tests above, on planted text carrying other values.
        val compose = "      # DAYMARK_SETUP_MODE: \"practice\"\n      DAYMARK_SETUP_MODE: \"\${DAYMARK_SETUP_MODE:-solo}\"\n"
        assertEquals(listOf("\${DAYMARK_SETUP_MODE:-solo}"), composeValues(compose, "DAYMARK_SETUP_MODE"))
        assertEquals(emptyList(), composeValues("      # DAYMARK_THERAPIST_AUTH: \"1\"\n", "DAYMARK_THERAPIST_AUTH"))
        val env = envValues("# DAYMARK_THERAPIST_AUTH=1\nDAYMARK_SETUP_MODE=paired   # a comment\nDAYMARK_DOMAIN=x.example\n")
        assertEquals(mapOf("DAYMARK_SETUP_MODE" to "paired", "DAYMARK_DOMAIN" to "x.example"), env)
    }

    private companion object {
        /** Every value [key] is given in a compose `environment:` block, outside comment lines. */
        fun composeValues(text: String, key: String): List<String> {
            val setting = Regex("""^\s*${Regex.escape(key)}:\s*"([^"]*)"""")
            return text.lines().filterNot { it.trimStart().startsWith("#") }
                .mapNotNull { setting.find(it)?.groupValues?.get(1) }
        }

        /** The `KEY=value` lines of a `.env` file, outside comments, with a trailing comment removed. */
        fun envValues(text: String): Map<String, String> =
            text.lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .associate { line ->
                    val (key, raw) = line.split('=', limit = 2)
                    key.trim() to raw.substringBefore(" #").trim()
                }
    }
}
