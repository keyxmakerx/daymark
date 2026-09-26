package com.daymark.app.ui.components

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No tick and no green in the phone's shared components, and no tick in the PDF report (#278).
 *
 * `CLAUDE.md` §4: no green, success, tick or score, anywhere. Beside a questionnaire a tick reads as
 * "you passed" and green as "this is good". The provenance badge's Validated tier was both at once,
 * in a component no screen had shown yet, which is why nothing caught it; the report marked a done
 * project step with a tick.
 *
 * ## What it covers
 *
 *  - Every Kotlin file in `ui/components/`. No tick glyph ([TICKS], or a `\u` escape of the first
 *    four) in code or in a string; no Material tick icon ([TICK_ICON]); and no green, which is a
 *    colour literal, a `#rrggbb` string, `Color.Green`, or a Color.kt token, whose value falls in the
 *    web consoles' green hue window: 75 to 165 degrees, saturation at least 0.1, lightness strictly
 *    between 0.06 and 0.94 (`companion/web/src/lib/components/invariants.tree.test.ts`, "Green, by
 *    hue rather than by blocklist").
 *  - `export/PdfReportGenerator.kt`, for ticks only. Its mood ramp is a person's data, and two of the
 *    five mood colours are green by hue.
 *
 * ## What it does not
 *
 * Anything outside those two places, so that a use elsewhere cannot trip it: the Checkbox a person
 * ticks to choose journal entries for a report is a control, not a verdict. A person's own mood
 * colours reaching a component through `MaterialTheme.moodColors` are their data, and may be green by
 * their own choice, so they are not ruled on. Nor is a colour built from channels, `Color(r, g, b)`.
 *
 * Comments are blanked first, so a rule can be stated in the glyphs it forbids; strings are kept, so
 * a glyph in one is seen. Each check is shown planted examples, in the real files and on their own.
 * These run in seconds through `tools/jvm-source-tests.sh`, and in CI with the rest.
 */
class TickAndGreenSourceTest {

    private companion object {
        const val BADGE = "app/src/main/java/com/daymark/app/ui/components/ProvenanceBadge.kt"
        const val SWIPE = "app/src/main/java/com/daymark/app/ui/components/SwipeToDeleteRow.kt"
        const val REPORT = "app/src/main/java/com/daymark/app/export/PdfReportGenerator.kt"
        const val COLORS = "app/src/main/java/com/daymark/app/ui/theme/Color.kt"

        /** Tick glyphs: heavy check mark button, heavy check mark, check mark, ballot box with check, light check mark, ballot box with bold check. */
        val TICKS = listOf("\u2705", "\u2714", "\u2713", "\u2611", "\uD83D\uDDF8", "\uD83D\uDDF9")

        /** The first four written as escapes inside a Kotlin string. */
        val TICK_ESCAPE = Regex("""\\u(?:2705|2714|2713|2611)""", RegexOption.IGNORE_CASE)

        /** A Material icon that draws a tick, in any style. `CheckBoxOutlineBlank` is an empty box and is not one. */
        val TICK_ICON = Regex(
            """\bIcons\.(?:\w+\.)*(?:Check|CheckCircle|CheckCircleOutline|Done|DoneAll|DoneOutline|TaskAlt|CheckBox|Verified)\b""",
        )

        /** `Color(0xAARRGGBB)`, with or without alpha or a Long suffix. */
        val COLOR_LITERAL = Regex("""\bColor\(\s*0[xX]([0-9a-fA-F]{6}|[0-9a-fA-F]{8})L?\s*\)""")

        /** `#rrggbb` or `#aarrggbb`, as `Color.parseColor` takes them. */
        val HEX_STRING = Regex("""#([0-9a-fA-F]{8}|[0-9a-fA-F]{6})\b""")

        /** The named greens of Compose and of `android.graphics`. */
        val NAMED_GREEN = Regex("""\bColor\.(?:Green|GREEN)\b""")

        /** A Color.kt declaration of a literal, `val Name = Color(0xAARRGGBB)`. */
        val TOKEN = Regex("""(?m)^val\s+(\w+)\s*=\s*Color\(\s*0[xX]([0-9a-fA-F]{8})\s*\)""")
    }

    /** `ui/components/`, found as the folder the badge is in. */
    private val componentsDir: File = repoFile(BADGE).parentFile

    private val components: List<File> =
        componentsDir.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }

    /** Color.kt's tokens, name to RRGGBB. */
    private val tokens: Map<String, String> =
        TOKEN.findAll(commentsBlanked(repoFile(COLORS).readText()))
            .associate { it.groupValues[1] to it.groupValues[2].takeLast(6).uppercase() }

    /**
     * [source] with its comments blanked and its code and literals kept. Built on [codeOnly]: the
     * quotes it keeps are exactly the literals' own, so the source between each pair of them is a
     * literal's content and goes back in. Each index is an index into [source] on the same line.
     */
    private fun commentsBlanked(source: String): String {
        val code = codeOnly(source)
        val out = StringBuilder(code)
        var open = -1
        for (i in code.indices) {
            val c = code[i]
            if (c != '"' && c != '\'') continue
            if (open < 0) {
                open = i
            } else if (c == code[open]) {
                for (k in open + 1 until i) out.setCharAt(k, source[k])
                open = -1
            }
        }
        return out.toString()
    }

    private fun lineOf(text: String, index: Int) = text.substring(0, index).count { it == '\n' } + 1

    /** The web's green window, ported as it stands. */
    private fun isGreen(rgb: String): Boolean {
        val r = rgb.substring(0, 2).toInt(16) / 255.0
        val g = rgb.substring(2, 4).toInt(16) / 255.0
        val b = rgb.substring(4, 6).toInt(16) / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val l = (max + min) / 2
        var hue = when {
            d == 0.0 -> 0.0
            max == r -> 60 * (((g - b) / d) % 6)
            max == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        if (hue < 0) hue += 360
        val s = if (d == 0.0) 0.0 else d / (1 - Math.abs(2 * l - 1))
        return hue >= 75 && hue <= 165 && s >= 0.1 && l > 0.06 && l < 0.94
    }

    /** Every tick in [source], one line each: a glyph or its escape outside a comment, or a tick icon. */
    private fun ticks(source: String): List<String> {
        val text = commentsBlanked(source)
        val found = ArrayList<String>()
        for (glyph in TICKS) {
            var at = text.indexOf(glyph)
            while (at >= 0) {
                found += "line ${lineOf(text, at)}: $glyph"
                at = text.indexOf(glyph, at + glyph.length)
            }
        }
        for (m in TICK_ESCAPE.findAll(text)) found += "line ${lineOf(text, m.range.first)}: ${m.value}"
        for (m in TICK_ICON.findAll(text)) found += "line ${lineOf(text, m.range.first)}: ${m.value}"
        return found
    }

    /** Every green in [source], one line each. */
    private fun greens(source: String): List<String> {
        val text = commentsBlanked(source)
        val found = ArrayList<String>()
        fun add(at: Int, what: String) {
            found += "line ${lineOf(text, at)}: $what"
        }
        for (m in COLOR_LITERAL.findAll(text)) {
            val rgb = m.groupValues[1].takeLast(6).uppercase()
            if (isGreen(rgb)) add(m.range.first, "${m.value} is green")
        }
        for (m in HEX_STRING.findAll(text)) {
            val rgb = m.groupValues[1].takeLast(6).uppercase()
            if (isGreen(rgb)) add(m.range.first, "${m.value} is green")
        }
        for (m in NAMED_GREEN.findAll(text)) add(m.range.first, m.value)
        for ((token, rgb) in tokens) {
            if (!isGreen(rgb)) continue
            for (m in Regex("""\b$token\b""").findAll(text)) add(m.range.first, "$token is green, #$rgb")
        }
        return found
    }

    @Test
    fun `the components and the report were read, and comments go while strings stay`() {
        // Guards everything below: an empty folder, or a blanker that ate the strings, reads as "no
        // tick anywhere" for free.
        assertTrue("ui/components holds implausibly few files: ${components.size}", components.size >= 10)
        assertTrue("the walk missed the badge", components.any { it.name == "ProvenanceBadge.kt" })
        assertTrue("the walk missed the swipe row", components.any { repoFile(SWIPE).name == it.name })
        assertTrue("Color.kt's tokens were not read: ${tokens.size}", tokens.size >= 30)

        val badge = repoFile(BADGE).readText()
        val blanked = commentsBlanked(badge)
        assertEquals("the blanker changed the length", badge.length, blanked.length)
        assertTrue("a phrase only the badge's comments hold is not there to test", badge.contains("departs from nothing"))
        assertFalse("the blanker left a comment", blanked.contains("departs from nothing"))
        assertTrue("the blanker lost a string", blanked.contains("\"Validated\""))
        assertTrue("the blanker lost the code", blanked.contains("enum class ProvenanceTier"))
        val report = commentsBlanked(repoFile(REPORT).readText())
        assertTrue("the blanker lost the report's strings", report.contains("\"No results in this range.\""))
    }

    @Test
    fun `the hue window is the web's, and it sorts this palette`() {
        // Calibrated on the values it is for: the two green moods are green by hue, and the paper,
        // the ink, the alarm and the other moods are not.
        for (green in listOf("MoodGood", "MoodRad")) assertTrue("$green is not green to the window", isGreen(tokens.getValue(green)))
        for (other in listOf("PaperBg", "PaperSheet", "InkText", "InkSoft", "Hairline", "Clay", "MoodAwful", "MoodBad", "MoodMeh")) {
            assertFalse("$other is green to the window", isGreen(tokens.getValue(other)))
        }
    }

    @Test
    fun `no tick in the components or the report`() {
        val found = (components.map { it } + repoFile(REPORT)).flatMap { f ->
            ticks(f.readText()).map { "${f.name} $it" }
        }
        assertTrue(
            "A tick marks success, and this product has none (CLAUDE.md §4, #278). These are ticks:\n" +
                found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `no green in the components`() {
        val found = components.flatMap { f -> greens(f.readText()).map { "${f.name} $it" } }
        assertTrue(
            "Green reads as \"this is good\", and this product has no success colour (CLAUDE.md §4, " +
                "#278). These are green:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `the check sees the tick put back on the Validated badge and on a done step`() {
        // Each planted in the real file, whatever the place holds today, and looked for on its line.
        val badge = repoFile(BADGE).readText()
        val validated = Regex("""VALIDATED\(\s*([^,]+),""").find(badge)
        assertTrue("the Validated tier is not declared as it was", validated != null)
        val badgePlanted = badge.replaceRange(validated!!.groups[1]!!.range, "\"\u2705\"")
        assertTrue("nothing was planted in the badge", badgePlanted != badge || validated.groupValues[1] == "\"\u2705\"")
        val badgeLine = lineOf(badge, validated.range.first)
        assertTrue(
            "the check cannot see the tick back on the Validated badge: ${ticks(badgePlanted)}",
            ticks(badgePlanted).contains("line $badgeLine: \u2705"),
        )

        val report = repoFile(REPORT).readText()
        val done = Regex("""if \(s\.done\) (\S+) else""").find(report)
        assertTrue("the report no longer marks a done step where it did", done != null)
        val reportPlanted = report.replaceRange(done!!.groups[1]!!.range, "\"\u2713\"")
        assertTrue("nothing was planted in the report", reportPlanted != report || done.groupValues[1] == "\"\u2713\"")
        val reportLine = lineOf(report, done.range.first)
        assertTrue(
            "the check cannot see the tick back on a done step: ${ticks(reportPlanted)}",
            ticks(reportPlanted).contains("line $reportLine: \u2713"),
        )
    }

    @Test
    fun `the check sees every shape of a tick and of a green`() {
        val tickShapes = listOf(
            "Text(\"\u2714 Done\")",
            "val mark = \"\\u2713\"",
            "val mark = '\u2611'",
            "val mark = \"\"\"raw \u2705\"\"\"",
            "Text(\"a \${if (ok) \"\u2713\" else \"\"} b\")",
            "Text(\"\uD83D\uDDF8 fine\")",
            "Icon(Icons.Filled.CheckCircle, contentDescription = null)",
            "Icon(Icons.AutoMirrored.Outlined.TaskAlt, contentDescription = null)",
            "Icon(imageVector = Icons.Default.Done, contentDescription = null)",
        )
        for (shape in tickShapes) assertEquals("the check cannot see:\n$shape", 1, ticks(shape).size)

        val rad = tokens.getValue("MoodRad")
        val greenShapes = listOf(
            "Surface(color = Color(0xFF$rad)) {}",
            "val ok = Color(0x$rad)",
            "val ok = Color(0xFF${rad}L)",
            "Box(Modifier.background(Color.Green))",
            "paint.color = android.graphics.Color.GREEN",
            "val ok = Color(android.graphics.Color.parseColor(\"#$rad\"))",
            "Surface(color = MoodRad) {}",
        )
        for (shape in greenShapes) assertEquals("the check cannot see:\n$shape", 1, greens(shape).size)
    }

    @Test
    fun `the check does not rule on what is not a tick or a green`() {
        val notTicks = listOf(
            "// \u2705 in a line comment",
            "/** \u2713 in a KDoc */ val x = 1",
            "Icon(Icons.Filled.CheckBoxOutlineBlank, contentDescription = null)",
            "canvas.drawText(if (s.done) Copy.STEP_DONE else \"\u00b7\", x, y, soft)",
            "Text(\"Validated\")",
        )
        for (shape in notTicks) assertEquals("the check calls this a tick:\n$shape", 0, ticks(shape).size)

        val notGreens = listOf(
            "Surface(color = Color(0xFF${tokens.getValue("MoodAwful")})) {}",
            "val night = Color(0xFF16150F)",
            "Box(Modifier.background(MaterialTheme.moodColors.forLevel(level)))",
            "// Color.Green in a comment",
            "Text(\"Step #1\")",
        )
        for (shape in notGreens) assertEquals("the check calls this green:\n$shape", 0, greens(shape).size)
    }
}
