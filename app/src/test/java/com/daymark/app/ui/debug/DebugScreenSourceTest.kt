package com.daymark.app.ui.debug

import com.daymark.app.backup.repoFile
import com.daymark.app.stats.RuleReadout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timing debug screen: that it cannot appear in a release build, that it describes the rules
 * rather than the person, and that it is drawn in the product's own vocabulary.
 *
 * ## Why this is a source test
 *
 * Compose needs a device or Robolectric, and this module has neither; `build.yml` runs `test` and
 * `assembleDebug` and nothing that could instantiate a composable. The repository's existing answer
 * to that is to assert component properties structurally over the source, which is what this does.
 *
 * ## The claim that matters most, and exactly how far it goes
 *
 * `BuildConfig.DEBUG` is checked in **three** places — the Settings row that opens the screen, the
 * route registration in `DaymarkAppScaffold`, and the first line of `DebugTimingScreen` itself — and
 * this asserts all three, each with a mutation that proves the assertion can fail. Any one of them
 * alone makes the screen unreachable in a release build, so the screen survives two of the three
 * being deleted by accident.
 *
 * What this does **not** claim: that the code is absent from the release APK. `BuildConfig.DEBUG` is
 * a compile-time `false` in every release variant and R8 drops the branches, but proving that needs
 * the APK, and the honest place for that check is `build.yml` next to the aapt2 permission check.
 * This proves the gates exist and are in the right place; it does not read bytecode.
 */
class DebugScreenSourceTest {

    private companion object {
        const val SCREEN = "app/src/main/java/com/daymark/app/ui/debug/DebugTimingScreen.kt"
        const val VIEWMODEL = "app/src/main/java/com/daymark/app/ui/debug/DebugTimingViewModel.kt"
        const val SCAFFOLD = "app/src/main/java/com/daymark/app/ui/DaymarkAppScaffold.kt"
        const val SETTINGS = "app/src/main/java/com/daymark/app/ui/settings/SettingsScreen.kt"
        const val ROUTES = "app/src/main/java/com/daymark/app/ui/navigation/Destinations.kt"

        /**
         * Words this product does not use, anywhere. No green, no success, no tick, no score, no
         * streak, no congratulation — and a debug screen full of counts is exactly where a "score"
         * would first seem harmless.
         */
        val FORBIDDEN = listOf(
            "success", "congrat", "streak", "score", "well done", "good job", "achievement",
            "✓", "✔", "✅",
        )
    }

    private val screen = strip(read(SCREEN))
    private val viewModel = strip(read(VIEWMODEL))
    private val scaffold = strip(read(SCAFFOLD))
    private val settings = strip(read(SETTINGS))
    private val routes = strip(read(ROUTES))

    private fun read(rel: String) = repoFile(rel).readText()

    // ---------------------------------------------------------------------------------------------
    // Guards.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `every source was found and stripped to something that is still code`() {
        assertTrue(screen.contains("fun DebugTimingScreen("))
        assertTrue(viewModel.contains("class DebugTimingViewModel"))
        assertTrue(scaffold.contains("NavHost("))
        assertTrue(settings.contains("fun SettingsScreen("))
        assertTrue(routes.contains("object Routes"))

        // The comments have to go, or every absence check below is reading prose. All five files
        // discuss these gates at length, and the screen's own header quotes the flag this counts.
        assertTrue("KDoc survived stripping", read(SCREEN).contains("/**"))
        assertFalse("KDoc survived stripping", screen.contains("/**"))
        assertTrue(
            "the screen's header mentions the gate, and that mention must not be what is counted",
            read(SCREEN).contains("Three gates"),
        )
        assertFalse("the screen's header survived stripping", screen.contains("Three gates"))
    }

    // ---------------------------------------------------------------------------------------------
    // Gate one: the entry point in Settings.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the settings entry point is inside a debug check`() {
        assertTrue("SettingsScreen never calls onOpenTimingDebug", settings.contains("onOpenTimingDebug()"))
        assertGated(settings, "onOpenTimingDebug()", "the Settings row that opens the debug screen")
    }

    @Test
    fun `the settings check fails against a settings screen whose gate was removed`() {
        val ungated = removeGate(settings)
        assertNotEquals("mutation did not land", settings, ungated)
        assertTrue("mutation removed the row as well as the gate", ungated.contains("onOpenTimingDebug()"))
        assertFalse(
            "detector is broken: an ungated Settings row still reads as gated",
            isGated(ungated, "onOpenTimingDebug()"),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Gate two: the route.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the route registration is inside a debug check, and nothing else navigates to it`() {
        assertTrue("the route constant is missing", routes.contains("const val DEBUG_TIMING"))
        assertTrue(
            "the debug route is not registered at all",
            scaffold.contains("composable(Routes.DEBUG_TIMING"),
        )
        assertGated(scaffold, "composable(Routes.DEBUG_TIMING", "the debug route registration")

        // Every mention of the route in the whole navigation graph is gated, not merely the
        // registration: a `navigate(Routes.DEBUG_TIMING)` left outside would be a dead call in a
        // release build rather than an open door, but it would also be the first thing somebody
        // copies when adding a second entry point.
        for (index in occurrences(scaffold, "Routes.DEBUG_TIMING")) {
            assertTrue(
                "a mention of Routes.DEBUG_TIMING sits outside every BuildConfig.DEBUG block",
                gatedRegions(scaffold).any { index in it },
            )
        }

        // It is not a tab, and it is not a start destination.
        assertFalse("the debug screen became a top-level destination", routes.contains("DEBUG_TIMING,"))
        assertFalse(
            "the debug screen became the start destination",
            scaffold.contains("startDestination = Routes.DEBUG_TIMING"),
        )
    }

    @Test
    fun `the route check fails against a scaffold whose gate was removed`() {
        val ungated = removeGate(scaffold)
        assertNotEquals("mutation did not land", scaffold, ungated)
        assertTrue("mutation removed the route as well as the gate", ungated.contains("composable(Routes.DEBUG_TIMING"))
        assertFalse(
            "detector is broken: an ungated route still reads as gated",
            isGated(ungated, "composable(Routes.DEBUG_TIMING"),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Gate three: the screen itself.
    // ---------------------------------------------------------------------------------------------

    /**
     * The screen refuses to draw before it does anything else.
     *
     * Before the first `Scaffold(`, because a check placed after the content has been composed is
     * not a check at all — and because that is the shape a later refactor most easily produces
     * while leaving the flag's name in the file.
     */
    @Test
    fun `the screen returns immediately when the build is not a debug build`() {
        val body = screen.substringAfter("fun DebugTimingScreen(")

        assertTrue("the screen does not check the flag itself", body.contains("if (!BuildConfig.DEBUG) return"))
        assertTrue(
            "the screen's check comes after it has started composing",
            body.indexOf("if (!BuildConfig.DEBUG) return") < body.indexOf("Scaffold("),
        )
        assertTrue(
            "BuildConfig is not imported from the package the namespace generates",
            screen.contains("import com.daymark.app.BuildConfig"),
        )
    }

    @Test
    fun `the screen check fails against a screen that dropped its own guard`() {
        val ungated = screen.replace("    if (!BuildConfig.DEBUG) return\n", "")
        assertNotEquals("mutation did not land", screen, ungated)

        val body = ungated.substringAfter("fun DebugTimingScreen(")
        assertFalse("detector is broken", body.contains("if (!BuildConfig.DEBUG) return"))
    }

    /** All three gates spell the flag the same way, and it is the one the build actually generates. */
    @Test
    fun `all three gates read the same flag`() {
        for ((name, source) in listOf("screen" to screen, "scaffold" to scaffold, "settings" to settings)) {
            assertTrue("$name does not read BuildConfig.DEBUG", source.contains("BuildConfig.DEBUG"))
            assertTrue("$name does not import BuildConfig", source.contains("import com.daymark.app.BuildConfig"))
        }

        // `buildConfig = true` has to be on, or none of the three compiles. AGP 8 stopped
        // generating the class by default, and this app had no other use of it.
        val gradle = read("app/build.gradle.kts")
        assertTrue("BuildConfig generation is off, so the gates cannot compile", gradle.contains("buildConfig = true"))
        assertTrue("the namespace decides BuildConfig's package", gradle.contains("namespace = \"com.daymark.app\""))
    }

    // ---------------------------------------------------------------------------------------------
    // What it may say.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the screen uses semantic tokens and no raw colour`() {
        val raw = Regex("""Color\(0[xX][0-9a-fA-F]{6,8}\)""").findAll(screen).map { it.value }.toList()
        assertEquals("a raw colour literal appeared on the debug screen", emptyList<String>(), raw)

        // Detector: the same read does fire on a planted literal.
        assertEquals(
            1,
            Regex("""Color\(0[xX][0-9a-fA-F]{6,8}\)""")
                .findAll(screen + "\nval leaked = Color(0xFF00FF00)\n").count(),
        )

        assertTrue("the screen draws nothing from the theme", screen.contains("MaterialTheme.colorScheme"))
    }

    @Test
    fun `nothing on the screen reads as a score or a congratulation`() {
        val words = (screen + viewModel).lowercase()
        for (term in FORBIDDEN) {
            assertFalse("the debug screen says \"$term\"", words.contains(term.lowercase()))
        }

        // Detector, one per shape: a word and a mark, so a check blind to either fails here.
        val planted = (words + " well done, a new streak ✓").lowercase()
        assertTrue("detector is broken", FORBIDDEN.any { planted.contains(it.lowercase()) })
        assertTrue("detector is broken", planted.contains("✓"))
    }

    /**
     * `RuleReadout.Hold` is a closed list of five, and the screen prints one of them rather than
     * composing its own sentence.
     *
     * A "why not" the screen could assemble is a "why not" that could one day say something about
     * the person, which is the whole reason that enum has no free-text member.
     */
    @Test
    fun `the why-not sentence comes from the closed list and the list is still closed`() {
        assertEquals(
            "RuleReadout.Hold gained or lost a member — it is five fixed sentences and no sixth",
            5,
            RuleReadout.Hold.entries.size,
        )

        assertTrue("the screen does not print the engine's own reason", screen.contains("feature.hold.label"))

        // The screen names no Hold member, so it cannot branch on one and say something else in its
        // place. Referring to the enum type at all is the first step towards a sixth sentence that
        // lives here instead of there.
        val named = RuleReadout.Hold.entries.filter { screen.contains("Hold." + it.name) }
        assertEquals("the screen branches on a specific Hold", emptyList<RuleReadout.Hold>(), named)
        assertTrue("detector is broken", (screen + "Hold.SaidStop").contains("Hold.SaidStop"))
    }

    // ---------------------------------------------------------------------------------------------
    // What it may read.
    // ---------------------------------------------------------------------------------------------

    /**
     * The screen reads the reception ledger and the person's frequency setting. Nothing else.
     *
     * A debug screen is exactly where an inference first gets written down as a sentence, so the
     * check is on the inputs rather than on the output: with no mood, no entry, no goal and no
     * screener reachable from this ViewModel, there is nothing for a sentence to be about.
     */
    @Test
    fun `the view model can reach the ledger and the settings, and nothing about the person`() {
        val constructor = viewModel.substringAfter("class DebugTimingViewModel").substringBefore(") : ViewModel()")
        val injected = Regex("""private val \w+: (\w+)""").findAll(constructor)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            "the debug screen gained a dependency — check what it can now read about the person",
            listOf("OfferLedgerRepository", "SettingsRepository"),
            injected,
        )

        for (forbidden in listOf(
            "EntryRepository", "MoodEntry", "JournalRepository", "GoalRepository", "SleepLogDao",
            "AssessmentDao", "TrackerDao", "ThoughtRecordDao", "Signals", "MoodStats",
            "MoodPatterns", "MoodCorrelations",
        )) {
            assertFalse("the debug view model reaches $forbidden", viewModel.contains(forbidden))
            assertFalse("the debug screen reaches $forbidden", screen.contains(forbidden))
        }

        // Detector: the parser reads a constructor rather than one line, and the absence list is
        // capable of firing.
        assertTrue("the constructor did not parse", injected.size == 2)
        assertTrue("detector is broken", (viewModel + "EntryRepository").contains("EntryRepository"))
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing.
    // ---------------------------------------------------------------------------------------------

    /**
     * Source with comments removed and string literals kept.
     *
     * A scanner rather than the regex the other source tests use, and it has to be, because
     * `SettingsScreen` passes a wildcard MIME type to a file picker — a slash and a star, inside a
     * string. The regex reads those two characters as the start of a block comment and deletes
     * everything up to the next real comment close, which in that file is two hundred lines later
     * and includes the debug gate. Every absence check here would then have passed over nothing at
     * all, and the presence checks would have failed for a reason nobody would have guessed.
     * Kotlin block comments also nest, so the depth is counted rather than assumed to be one.
     *
     * The same pair of characters cannot be written in this comment for exactly that reason, which
     * is the joke and also the second half of the bug: `the stripper keeps code after a comment
     * opener that is inside a string` builds the case out of code instead.
     *
     * String contents are deliberately kept: the copy this screen shows is exactly what the
     * forbidden-word check needs to read.
     */
    private fun strip(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var blockDepth = 0
        while (index < source.length) {
            val two = if (index + 1 < source.length) source.substring(index, index + 2) else ""
            when {
                blockDepth > 0 -> when (two) {
                    "/*" -> { blockDepth++; index += 2 }
                    "*/" -> { blockDepth--; index += 2 }
                    else -> { if (source[index] == '\n') out.append('\n'); index++ }
                }
                two == "/*" -> { blockDepth++; index += 2 }
                two == "//" -> while (index < source.length && source[index] != '\n') index++
                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3)
                    val stop = if (end < 0) source.length else end + 3
                    out.append(source, index, stop)
                    index = stop
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index]
                    out.append(quote)
                    index++
                    while (index < source.length) {
                        val c = source[index]
                        out.append(c)
                        index++
                        if (c == '\\' && index < source.length) {
                            out.append(source[index])
                            index++
                        } else if (c == quote) {
                            break
                        }
                    }
                }
                else -> { out.append(source[index]); index++ }
            }
        }
        return out.toString()
    }

    /**
     * The stripper itself, because everything above is a claim about what it returned.
     *
     * The first case is the one that actually bit: a slash-star inside a string literal must not
     * start a comment.
     */
    @Test
    fun `the stripper keeps code after a comment opener that is inside a string`() {
        val source = """
            val mime = "text/*"
            val kept = 1
            val gone = 2
        """.trimIndent().replace("val gone", "// val gone")

        val stripped = strip(source)
        assertTrue("a string swallowed the code after it", stripped.contains("val kept = 1"))
        assertTrue("the string itself was dropped", stripped.contains("text/*"))
        assertFalse("a line comment survived", stripped.contains("val gone"))

        // Nested block comments, which Kotlin allows and the regex version gets wrong.
        val nested = strip("val a = 1 /* outer /* inner */ still comment */ val b = 2")
        assertTrue(nested.contains("val a = 1"))
        assertTrue(nested.contains("val b = 2"))
        assertFalse(nested.contains("still comment"))

        // And it does remove the thing it is there to remove.
        assertFalse("a block comment survived", strip("/** doc */ val c = 3").contains("doc"))
        assertTrue(strip("/** doc */ val c = 3").contains("val c = 3"))
    }

    /** Every index at which [needle] occurs in [source]. */
    private fun occurrences(source: String, needle: String): List<Int> {
        val found = ArrayList<Int>()
        var index = source.indexOf(needle)
        while (index >= 0) {
            found.add(index)
            index = source.indexOf(needle, index + 1)
        }
        return found
    }

    /**
     * The character ranges covered by `if (BuildConfig.DEBUG) { ... }` blocks, braces matched.
     *
     * Matching braces rather than searching for the flag nearby is the point: "the flag is
     * mentioned somewhere in this file" is exactly the assertion that would stay green after
     * somebody moved the row out of the block.
     */
    private fun gatedRegions(source: String): List<IntRange> {
        val marker = "if (BuildConfig.DEBUG) {"
        return occurrences(source, marker).map { start ->
            var depth = 0
            var index = start + marker.length - 1
            while (index < source.length) {
                when (source[index]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return@map start..index
                    }
                }
                index++
            }
            start..source.length - 1
        }
    }

    private fun isGated(source: String, needle: String): Boolean {
        val regions = gatedRegions(source)
        val at = occurrences(source, needle)
        return at.isNotEmpty() && at.all { index -> regions.any { index in it } }
    }

    private fun assertGated(source: String, needle: String, what: String) {
        assertTrue(
            "$what is not inside an `if (BuildConfig.DEBUG)` block",
            isGated(source, needle),
        )
    }

    /** Removes the gate and keeps what it guarded — the mutation the detectors have to see. */
    private fun removeGate(source: String): String =
        source.replace("if (BuildConfig.DEBUG) {", "run {")
}
