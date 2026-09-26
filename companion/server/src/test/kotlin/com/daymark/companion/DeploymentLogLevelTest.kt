package com.daymark.companion

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A new install logs at `info` (#367). The image, the compose file and `.env.example` each set
 * `DAYMARK_LOG_LEVEL` to the code's own default, so whoever runs a Companion sees the three lines
 * `warn` hides: the startup settings line, the SMTP-enabled line and "readiness restored".
 */
class DeploymentLogLevelTest {

    /** Gradle runs the tests from companion/server; the deployment files sit one level up. */
    private val companion = File(System.getProperty("user.dir"), "..").canonicalFile

    private fun deploymentFile(name: String): String {
        val file = File(companion, name)
        assertTrue(file.isFile, "${file.path} must exist: a file that is not read sets nothing, and proves nothing")
        return file.readText()
    }

    @Test
    fun `the code's own default is info`() {
        assertEquals("info", Config.fromEnv(emptyMap()).logLevel)
    }

    @Test
    fun `the image, the compose file and the example environment each set it once, to the code's default`() {
        val fallback = Config.fromEnv(emptyMap()).logLevel
        for (name in listOf("Dockerfile", "docker-compose.yml", ".env.example")) {
            // Exactly one value, so a renamed or commented-out key fails here as surely as a wrong one.
            assertEquals(listOf(fallback), logLevelsSetIn(deploymentFile(name)), "DAYMARK_LOG_LEVEL in $name")
        }
    }

    @Test
    fun `the no-egress override does not set it to anything else`() {
        // An override wins over the base file, so a level set there would be the one that runs. The
        // reader is shown to find the compose syntax in the test above and in the planted cases below.
        assertTrue(logLevelsSetIn(deploymentFile("docker-compose.no-egress.yml")).all { it == "info" })
    }

    @Test
    fun `the reader finds the setting in each file's syntax, and reports a wrong one`() {
        // The positive control: planted text in each syntax, carrying the value the files must not.
        assertEquals(listOf("warn"), logLevelsSetIn("ENV DAYMARK_PORT=8080 \\\n    DAYMARK_LOG_LEVEL=warn\n"))
        assertEquals(listOf("warn"), logLevelsSetIn("    environment:\n      DAYMARK_LOG_LEVEL: \"warn\"\n"))
        assertEquals(listOf("warn"), logLevelsSetIn("DAYMARK_LOG_LEVEL=warn         # chatty\n"))
        assertEquals(listOf("info", "warn"), logLevelsSetIn("DAYMARK_LOG_LEVEL=info\nDAYMARK_LOG_LEVEL=warn\n"))
        // And what is not a setting of this key is not read as one.
        assertEquals(emptyList(), logLevelsSetIn("# DAYMARK_LOG_LEVEL=info\n"))
        assertEquals(emptyList(), logLevelsSetIn("    # DAYMARK_LOG_LEVEL: \"info\"\n"))
        assertEquals(emptyList(), logLevelsSetIn("DAYMARK_LOGLEVEL=info\n"))
        assertEquals(emptyList(), logLevelsSetIn("MY_DAYMARK_LOG_LEVEL=info\n"))
    }

    private companion object {
        val SETTING = Regex("""(?<![A-Za-z0-9_])DAYMARK_LOG_LEVEL\s*[=:]\s*"?([A-Za-z]+)""")

        /** Every value `DAYMARK_LOG_LEVEL` is set to in [text], outside comment lines, in order. */
        fun logLevelsSetIn(text: String): List<String> =
            text.lines()
                .filterNot { it.trimStart().startsWith("#") }
                .flatMap { line -> SETTING.findAll(line).map { it.groupValues[1] }.toList() }
    }
}
