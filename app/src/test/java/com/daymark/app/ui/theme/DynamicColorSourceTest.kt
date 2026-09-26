package com.daymark.app.ui.theme

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dynamic colour is off unless the person turns it on (#309).
 *
 * The paper palette is the point of the app, and the wallpaper's colours bring hues and an alarm
 * colour the design keeps out (`docs/DESIGN.md`, "Colour tokens"). So the stored setting reads as off
 * when nothing is stored, and the theme and the Settings screen start from the same answer.
 *
 * Changing that default is safe only because a stored value is always somebody's choice: the Settings
 * switch is the one thing that writes it. If anything wrote the default at first run or at startup,
 * "never set" and "chose on" would look the same, and the new default would switch off a setting a
 * person had chosen. So this holds the writers too: the property is assigned in one place, the view
 * model's `setDynamicColor`, which only the Settings switch calls; the key is put in one place, the
 * property's setter; and the key's string is spelled once, in its declaration.
 *
 * ## How it reads
 *
 * Every production Kotlin file, through [codeOnly], so a comment or a string can neither hide a write
 * nor fake one. The key's string is looked for in the raw text, where it lives. Each check is shown a
 * planted example made from the real files.
 *
 * ## What it will not catch
 *
 * A write that names neither the property after a dot nor the key: `with(settings) { dynamicColor =
 * true }`, a reflective set, or the preferences file copied wholesale by something new. Nothing does
 * any of these; SettingsRepository.kt states the rule where the property is declared.
 */
class DynamicColorSourceTest {

    private companion object {
        const val SETTINGS = "app/src/main/java/com/daymark/app/data/SettingsRepository.kt"
        const val THEME = "app/src/main/java/com/daymark/app/ui/theme/Theme.kt"
        const val VIEW_MODEL = "app/src/main/java/com/daymark/app/ui/settings/SettingsViewModel.kt"
        const val SCREEN = "app/src/main/java/com/daymark/app/ui/settings/SettingsScreen.kt"
        const val ACTIVITY = "app/src/main/java/com/daymark/app/MainActivity.kt"

        /** The stored default, `getBoolean(KEY_DYNAMIC_COLOR, <literal>)`. */
        val STORED_DEFAULT = Regex("""getBoolean\(\s*KEY_DYNAMIC_COLOR\s*,\s*(\w+)\s*\)""")

        /** `DaymarkTheme`'s parameter default. */
        val THEME_DEFAULT = Regex("""\bdynamicColor\s*:\s*Boolean\s*=\s*(\w+)""")

        /** The Settings screen's state before it has read anything. */
        val STATE_DEFAULT = Regex("""\bval\s+dynamicColor\s*:\s*Boolean\s*=\s*(\w+)""")

        /** The property assigned through any receiver. */
        val PROPERTY_WRITE = Regex("""\.dynamicColor\s*=(?!=)""")

        /** The key put into the preferences by any `put…` call. */
        val KEY_WRITE = Regex("""\bput\w*\(\s*KEY_DYNAMIC_COLOR\b""")

        /** The one function that assigns the property, named anywhere. */
        val SETTER_NAME = Regex("""\bsetDynamicColor\b""")

        /** The key's string, as it is spelled in its declaration. */
        const val KEY_STRING = "\"dynamic_color\""
    }

    /** `app/src`, found above a file known to be in it. */
    private val src: File = run {
        var dir: File? = repoFile(SETTINGS).parentFile
        while (dir != null && !(dir.name == "src" && dir.parentFile?.name == "app")) dir = dir.parentFile
        checkNotNull(dir) { "app/src is not above $SETTINGS" }
    }

    /** Every production Kotlin file, by its path from the repository root, `app/src/main/...`. */
    private val tree: Map<String, String> =
        src.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val rel = f.relativeTo(src).invariantSeparatorsPath
                !rel.startsWith("test/") && !rel.startsWith("androidTest/")
            }
            .sortedBy { it.path }
            .associate { "app/src/" + it.relativeTo(src).invariantSeparatorsPath to it.readText() }

    private fun lineOf(text: String, index: Int) = text.substring(0, index).count { it == '\n' } + 1

    private fun storedDefault(settingsSource: String): String? =
        STORED_DEFAULT.find(codeOnly(settingsSource))?.groupValues?.get(1)

    /** Everything in [files] that writes the setting or reaches its writer, as "path:line what". */
    private fun writes(files: Map<String, String>): List<String> {
        val found = ArrayList<String>()
        for ((path, text) in files) {
            val code = codeOnly(text)
            for (m in PROPERTY_WRITE.findAll(code)) found += "$path:${lineOf(code, m.range.first)} assigns the property"
            for (m in KEY_WRITE.findAll(code)) found += "$path:${lineOf(code, m.range.first)} puts the key"
            for (m in SETTER_NAME.findAll(code)) found += "$path:${lineOf(code, m.range.first)} names setDynamicColor"
            var at = text.indexOf(KEY_STRING)
            while (at >= 0) {
                found += "$path:${lineOf(text, at)} spells the key"
                at = text.indexOf(KEY_STRING, at + 1)
            }
        }
        return found
    }

    /** The line of the first [needle] in [path] after [after], which must be there. */
    private fun lineAfter(path: String, after: String, needle: String): Int {
        val text = tree.getValue(path)
        val start = text.indexOf(after)
        assertTrue("$path no longer holds \"$after\"", start >= 0)
        val at = text.indexOf(needle, start)
        assertTrue("$path no longer holds \"$needle\" after \"$after\"", at >= 0)
        return lineOf(text, at)
    }

    /** The writes there should be: one each, where the rule says, and nowhere else. */
    private fun expectedWrites(): List<String> = listOf(
        "$SETTINGS:${lineAfter(SETTINGS, "var dynamicColor", "putBoolean(KEY_DYNAMIC_COLOR")} puts the key",
        "$SETTINGS:${lineAfter(SETTINGS, "const val KEY_DYNAMIC_COLOR", KEY_STRING)} spells the key",
        "$SCREEN:${lineAfter(SCREEN, "Text(\"Dynamic color\")", "viewModel::setDynamicColor")} names setDynamicColor",
        "$VIEW_MODEL:${lineAfter(VIEW_MODEL, "fun setDynamicColor", "fun setDynamicColor")} names setDynamicColor",
        "$VIEW_MODEL:${lineAfter(VIEW_MODEL, "fun setDynamicColor", "settings.dynamicColor = enabled")} assigns the property",
    ).sorted()

    @Test
    fun `the tree was read, and the scanner sees code`() {
        // Guards everything below: an empty walk reads as "nothing writes it" for free.
        assertTrue("the walk found implausibly few files: ${tree.size}", tree.size > 200)
        for (path in listOf(SETTINGS, THEME, VIEW_MODEL, SCREEN, ACTIVITY)) {
            assertTrue("the walk missed $path", path in tree)
        }
        val settings = tree.getValue(SETTINGS)
        assertTrue("a word only SettingsRepository's comments hold is not there to test", settings.contains("Only an unset preference"))
        assertFalse("the scanner left a comment", codeOnly(settings).contains("Only an unset preference"))
        assertTrue("the scanner ate the code", codeOnly(settings).contains("KEY_DYNAMIC_COLOR"))
    }

    @Test
    fun `a new install shows the paper palette`() {
        assertEquals(
            "With nothing stored, dynamic colour must read as off: the paper palette comes first (#309)",
            "false",
            storedDefault(tree.getValue(SETTINGS)),
        )
        assertEquals(
            "DaymarkTheme's own default must agree",
            "false",
            THEME_DEFAULT.find(codeOnly(tree.getValue(THEME)))?.groupValues?.get(1),
        )
        assertEquals(
            "the Settings screen's state must not start with the switch on",
            "false",
            STATE_DEFAULT.find(codeOnly(tree.getValue(VIEW_MODEL)))?.groupValues?.get(1),
        )
    }

    @Test
    fun `the check sees the default turned back on`() {
        val settings = tree.getValue(SETTINGS)
        val current = storedDefault(settings)
        assertTrue("the stored default was not found at all", current == "true" || current == "false")
        val other = if (current == "false") "true" else "false"
        val planted = settings.replace("getBoolean(KEY_DYNAMIC_COLOR, $current)", "getBoolean(KEY_DYNAMIC_COLOR, $other)")
        assertNotEquals("nothing was planted", settings, planted)
        assertEquals("the check cannot see the default flipped to $other", other, storedDefault(planted))
    }

    @Test
    fun `only the Settings switch writes the setting`() {
        assertEquals(
            "A stored value must always be a choice somebody made, or the default cannot change without " +
                "switching off what a person chose (#309). The writes are:\n" +
                writes(tree).sorted().joinToString("\n") + "\n",
            expectedWrites(),
            writes(tree).sorted(),
        )
    }

    @Test
    fun `the check sees a write at startup, a key spelled out, and a second caller`() {
        val activity = tree.getValue(ACTIVITY)
        val anchor = "super.onCreate(savedInstanceState)"
        assertTrue("MainActivity no longer calls $anchor", activity.contains(anchor))
        val plants = mapOf(
            "the default written at startup" to "\n        settings.dynamicColor = settings.dynamicColor",
            "the key written by its string" to
                "\n        getSharedPreferences(\"daymark_settings\", MODE_PRIVATE).edit().putBoolean(\"dynamic_color\", true).apply()",
            "the view model's writer called from somewhere new" to "\n        settingsViewModel.setDynamicColor(true)",
        )
        // Counted against the tree as it is, so the control still means something while the test
        // above is red.
        val before = writes(tree)
        for ((what, line) in plants) {
            val planted = tree + (ACTIVITY to activity.replaceFirst(anchor, anchor + line))
            assertNotEquals("nothing was planted for $what", tree, planted)
            val after = writes(planted)
            assertEquals("the check cannot see $what: $after", before.size + 1, after.size)
            assertEquals(
                "the check sees $what somewhere other than MainActivity: $after",
                before.count { it.startsWith("$ACTIVITY:") } + 1,
                after.count { it.startsWith("$ACTIVITY:") },
            )
        }
    }
}
