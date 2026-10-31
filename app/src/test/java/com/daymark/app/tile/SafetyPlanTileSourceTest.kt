package com.daymark.app.tile

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import com.daymark.app.ui.replaceOnce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Quick Settings tile (#522) is drawn on the lock screen, so it may say "Daymark" and nothing
 * else, and it must open the plan through the app's own lock. Read as text, because a TileService
 * cannot run here. Every check is first shown a planted breach in a copy of the real file
 * (CLAUDE.md §5). Runs through `tools/jvm-source-tests.sh`.
 */
class SafetyPlanTileSourceTest {

    private companion object {
        const val TILE = "app/src/main/java/com/daymark/app/tile/SafetyPlanTileService.kt"
        const val MANIFEST = "app/src/main/AndroidManifest.xml"
        const val ACTIVITY = "app/src/main/java/com/daymark/app/MainActivity.kt"
        const val SCAFFOLD = "app/src/main/java/com/daymark/app/ui/DaymarkAppScaffold.kt"

        /** Anything that would put words on the tile beyond the label the manifest gives it. */
        val SAYS_MORE = Regex("""\.(label|subtitle|stateDescription|contentDescription)\s*=""")
    }

    private fun serviceBlock(manifest: String): String =
        manifest.substringAfter("android:name=\".tile.SafetyPlanTileService\"").substringBefore("</service>")

    @Test
    fun the_tile_is_labelled_with_the_app_name_only() {
        val manifest = repoFile(MANIFEST).readText()
        val block = serviceBlock(manifest)
        assertTrue("the tile service is not declared", block.contains("QS_TILE"))
        fun labelledByName(b: String) = Regex("""android:label="([^"]*)"""").find(b)?.groupValues?.get(1) == "@string/app_name"
        assertTrue(labelledByName(block))
        assertFalse(labelledByName(block.replace("@string/app_name", "@string/safety_plan")))
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"app_name\">Daymark</string>"))
    }

    @Test
    fun the_tile_never_writes_words_of_its_own() {
        val code = codeOnly(repoFile(TILE).readText())
        assertFalse(SAYS_MORE.containsMatchIn(code))
        val planted = replaceOnce(code, "it.state = Tile.STATE_INACTIVE", "it.subtitle = \"Safety plan\"")
        assertTrue(SAYS_MORE.containsMatchIn(planted))
    }

    /** Off until the person adds it: Daymark never asks the system to place it. */
    @Test
    fun daymark_never_adds_the_tile_itself() {
        val hits = repoFile(MANIFEST).parentFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && codeOnly(it.readText()).contains("requestAddTileService") }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
    }

    /**
     * The tile restarts the activity, which starts locked, and the scaffold that opens the plan is
     * only reached past the lock. Opening the plan anywhere before that branch would skip the PIN.
     */
    @Test
    fun the_plan_opens_only_past_the_lock() {
        val tile = codeOnly(repoFile(TILE).readText())
        assertTrue(tile.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP"))
        assertTrue(tile.contains("if (isLocked) unlockAndRun"))

        val activity = codeOnly(repoFile(ACTIVITY).readText())
        fun gated(code: String): Boolean {
            val lock = code.indexOf("lockEnabled && !unlockState.unlocked -> LockScreen(")
            val uses = Regex("""openSafetyPlan = openSafetyPlan""").findAll(code).map { it.range.first }.toList()
            return lock > 0 && uses.size == 1 && uses.single() > lock
        }
        assertTrue(gated(activity))
        assertFalse(gated(replaceOnce(activity, "lockEnabled && !unlockState.unlocked -> LockScreen(", "false -> LockScreen(").replace("false -> LockScreen(", "")))

        val scaffold = codeOnly(repoFile(SCAFFOLD).readText())
        assertTrue(Regex("""else if \(openSafetyPlan\) \{\s*navController\.navigate\(Routes\.SAFETY_PLAN\)""").containsMatchIn(scaffold))
    }
}
