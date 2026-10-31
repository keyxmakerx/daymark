package com.daymark.app.ui.safety

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Signs of a high" and "Signs of a low" (#523) are the person's own words for the person's own
 * eyes. Nothing reads them to infer, alert or suggest, and no diagnosis is named. Read as text,
 * with each check first shown a planted breach (CLAUDE.md §5). Runs through
 * `tools/jvm-source-tests.sh`.
 */
class SafetyPlanSignsSourceTest {

    private companion object {
        const val ENTITY = "app/src/main/java/com/daymark/app/data/entity/SafetyPlanItem.kt"
        const val SCREEN = "app/src/main/java/com/daymark/app/ui/safety/SafetyPlanScreen.kt"

        /** The only files that may name the two sections: where they are defined and where they are written. */
        val ALLOWED = setOf("SafetyPlanItem.kt", "SafetyPlanScreen.kt")
        val NAMES = Regex("""SIGNS_OF_A_(HIGH|LOW)|signs_of_a_(high|low)|SafetyPlanSection\.signs\b""")
        val DIAGNOSIS = Regex("""(?i)bipolar|mania|manic|hypomani|depress|diagnos|disorder|episode""")
    }

    private fun mainKotlin() = repoFile("app/src/main/AndroidManifest.xml").parentFile.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun nothing_but_the_plan_reads_the_signs() {
        val files = mainKotlin()
        assertTrue(files.size > 100)
        val readers = files.filter { NAMES.containsMatchIn(codeOnly(it.readText())) }.map { it.name }.toSet()
        assertEquals(ALLOWED, readers)
        // The control: a rule that looked at the lows would be seen.
        assertTrue(NAMES.containsMatchIn("plan[SafetyPlanSection.SIGNS_OF_A_LOW]?.isNotEmpty()"))
    }

    @Test
    fun the_sections_name_no_diagnosis() {
        val entity = repoFile(ENTITY).readText()
        val block = entity.substringAfter("SIGNS_OF_A_HIGH(").substringBefore("THINGS_THAT_HELP(")
        val words = Regex(""""([^"]*)"""").findAll(block).map { it.groupValues[1] }.toList()
        assertTrue(words.contains("Signs of a high") && words.contains("Signs of a low"))
        assertFalse(words.any { DIAGNOSIS.containsMatchIn(it) })
        assertTrue(DIAGNOSIS.containsMatchIn("Signs of a manic episode"))

        val offers = Regex("""title = "([^"]*)"|line = "([^"]*)"""").findAll(repoFile(SCREEN).readText())
            .map { it.value }.toList()
        assertTrue(offers.isNotEmpty())
        assertFalse(offers.any { DIAGNOSIS.containsMatchIn(it) })
    }

    /** Offered as one card, never placed as two empty sections. */
    @Test
    fun the_signs_wait_to_be_offered() {
        val entity = codeOnly(repoFile(ENTITY).readText())
        for (name in listOf("SIGNS_OF_A_HIGH(", "SIGNS_OF_A_LOW(")) {
            val decl = entity.substringAfter(name).substringBefore("),")
            assertTrue("$name is not optional", decl.contains("optional = true"))
        }
        val screen = codeOnly(repoFile(SCREEN).readText())
        fun gated(code: String) = code.contains("SafetyPlanSection.signs.filter { signsOffered || plan[it].orEmpty().isNotEmpty() }")
        assertTrue(gated(screen))
        assertFalse(gated(screen.replace("signsOffered || plan[it]", "true || plan[it]")))
    }
}
