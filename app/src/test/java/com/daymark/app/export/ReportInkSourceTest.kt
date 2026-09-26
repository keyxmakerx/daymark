package com.daymark.app.export

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every word in the PDF report measures at least 4.5:1 on the white page, and the faint ink draws
 * marks only (#409).
 *
 * A clinician reads the report on paper or on a projector, where a pale grey fades first. The report
 * keeps its own palette, apart from the theme's, so it is held here rather than by
 * `ui/FaintInkSourceTest`: the same rule, "The faint ink never carries words" (`docs/DESIGN.md`).
 *
 * ## How it reads
 *
 * `export/PdfReportGenerator.kt` as code only, through [codeOnly], its palette read from its own
 * `private const val NAME = 0xAARRGGBB.toInt()` lines. Two rules, which never see the same line:
 *  - **Words.** The file makes every paint that draws words with its `paint(size, color, bold)`
 *    factory, which sets a text size. Each call's colour must be a palette constant measuring at
 *    least 4.5:1 on white.
 *  - **The faint ink elsewhere.** Anywhere outside that factory, `FAINT` may only colour a paint that
 *    draws marks: held in a `val`, and that `val` handed to nothing but `drawCircle`, `drawLine`,
 *    `drawRect` and their kind. A faint paint that reaches `drawText`, `measureText`, a note box or
 *    a paragraph, or `FAINT` put anywhere else, fails.
 *
 * ## What it will not catch
 *
 * A word drawn with a paint built by hand in some colour other than `FAINT`, or a colour that
 * reaches a paint through a parameter. The white page is the page itself: nothing in the report
 * fills behind its words. The printed pages are checked by hand, in greyscale.
 */
class ReportInkSourceTest {

    private companion object {
        const val GENERATOR = "app/src/main/java/com/daymark/app/export/PdfReportGenerator.kt"

        /** WCAG 2's floor for small text. */
        const val WORDS_FLOOR = 4.5

        /** The page. */
        const val WHITE = "FFFFFF"

        /** The report's palette, `private const val NAME = 0xAARRGGBB.toInt()`. */
        val CONSTANT = Regex("""\bconst\s+val\s+(\w+)\s*=\s*0[xX]([0-9a-fA-F]{8})\.toInt\(\)""")

        /** The text-paint factory's calls; its definition is not one. */
        val FACTORY_CALL = Regex("""(?<![\w.])paint\s*\(""")

        /** Calls that draw a mark and never a word. */
        val MARKS = setOf("drawCircle", "drawLine", "drawLines", "drawRect", "drawRoundRect", "drawOval", "drawArc", "drawPath", "drawPoint", "drawPoints")

        /** A `val` and where its initializer starts. */
        val VAL = Regex("""\bval\s+(\w+)\s*(?::\s*[\w.<>?]+\s*)?=\s*""")
    }

    private val source: String = repoFile(GENERATOR).readText()

    private fun lineOf(text: String, index: Int) = text.substring(0, index).count { it == '\n' } + 1

    /** WCAG 2 contrast between two RRGGBB colours. */
    private fun contrast(a: String, b: String): Double {
        fun channel(hex: String, at: Int): Double {
            val c = hex.substring(at, at + 2).toInt(16) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        fun luminance(hex: String) = 0.2126 * channel(hex, 0) + 0.7152 * channel(hex, 2) + 0.0722 * channel(hex, 4)
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun palette(code: String): Map<String, String> =
        CONSTANT.findAll(code).associate { it.groupValues[1] to it.groupValues[2].takeLast(6).uppercase() }

    /** The index of the bracket that closes the one at [open], or the end of [code]. */
    private fun closing(code: String, open: Int): Int {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return code.length
    }

    /** The innermost bracket still open at [index], or -1. */
    private fun enclosing(code: String, index: Int): Int {
        var depth = 0
        for (i in index - 1 downTo 0) {
            when (code[i]) {
                ')', ']', '}' -> depth++
                '(', '[', '{' -> if (depth == 0) return i else depth--
            }
        }
        return -1
    }

    /** The name called at the parenthesis [open], or "". */
    private fun calleeAt(code: String, open: Int): String {
        var i = open - 1
        while (i >= 0 && code[i].isWhitespace()) i--
        val end = i + 1
        while (i >= 0 && (code[i].isLetterOrDigit() || code[i] == '_')) i--
        return code.substring(i + 1, end)
    }

    /** [text] split on commas outside any brackets. */
    private fun topLevelSplit(text: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for (i in text.indices) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    parts += text.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += text.substring(start)
        return parts
    }

    /** The factory's calls in [code]: the open parenthesis of each. */
    private fun factoryCalls(code: String): List<Int> =
        FACTORY_CALL.findAll(code)
            .filter { !code.substring(0, it.range.first).trimEnd().endsWith("fun") }
            .map { it.range.last }
            .toList()

    /** Every word paint in [source] whose colour is under the floor on white, or cannot be measured. */
    private fun faintWords(source: String): List<String> {
        val code = codeOnly(source)
        val colours = palette(code)
        val found = ArrayList<String>()
        for (open in factoryCalls(code)) {
            val args = topLevelSplit(code.substring(open + 1, closing(code, open))).map { it.trim() }
            val colour = args.firstOrNull { it.startsWith("color") && it.substringAfter("color").trimStart().startsWith("=") }
                ?.substringAfter("=")?.trim()
                ?: args.getOrNull(1)
            val rgb = colour?.let { colours[it] }
            when {
                rgb == null -> found += "line ${lineOf(code, open)}: paint(...) in $colour, which is not a colour of the palette"
                contrast(rgb, WHITE) < WORDS_FLOOR ->
                    found += "line ${lineOf(code, open)}: paint(...) in $colour, ${"%.2f".format(contrast(rgb, WHITE))}:1 on white"
            }
        }
        return found
    }

    /** Every use of `FAINT` outside the factory that is not a paint for marks alone. */
    private fun faintOffMarks(source: String): List<String> {
        val code = codeOnly(source)
        val factory = factoryCalls(code).toSet()
        val vals = VAL.findAll(code).map { m ->
            // The initializer runs to the end of its line, or further while a bracket is open.
            var depth = 0
            var end = m.range.last + 1
            while (end < code.length && !(code[end] == '\n' && depth == 0)) {
                when (code[end]) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                }
                end++
            }
            Triple(m.groupValues[1], m.range.last + 1, end)
        }.toList()
        val found = ArrayList<String>()
        for (m in Regex("""\bFAINT\b""").findAll(code)) {
            val at = m.range.first
            if (Regex("""const\s+val\s+$""").containsMatchIn(code.substring(0, at))) continue
            if (enclosing(code, at) in factory) continue
            val holder = vals.lastOrNull { (_, start, end) -> at in start until end }
            if (holder == null) {
                found += "line ${lineOf(code, at)}: FAINT is not held in a paint for marks"
                continue
            }
            val (name, _, end) = holder
            for (use in Regex("""\b$name\b""").findAll(code, end)) {
                val open = enclosing(code, use.range.first)
                val callee = if (open >= 0 && code[open] == '(') calleeAt(code, open) else ""
                if (callee !in MARKS) {
                    found += "line ${lineOf(code, use.range.first)}: $name, in FAINT, reaches ${callee.ifEmpty { "something" }}"
                }
            }
        }
        return found
    }

    @Test
    fun `the report was read, and its palette with it`() {
        // Guards everything below: no palette or no factory calls read as "every word is dark
        // enough" for free.
        val code = codeOnly(source)
        val colours = palette(code)
        assertTrue("the palette was not read: $colours", colours.keys.containsAll(listOf("INK", "SOFT", "FAINT", "HAIR", "BAND")))
        assertTrue("implausibly few word paints: ${factoryCalls(code).size}", factoryCalls(code).size > 50)
        // The figures the palette's comment and the issue state, measured again, so a contrast
        // function wrong in either direction cannot pass.
        assertEquals(14.88, contrast(colours.getValue("INK"), WHITE), 0.005)
        assertEquals(5.77, contrast(colours.getValue("SOFT"), WHITE), 0.005)
        assertEquals(2.72, contrast(colours.getValue("FAINT"), WHITE), 0.005)
    }

    @Test
    fun `every word in the report clears the small-text floor on white`() {
        val found = faintWords(source)
        assertTrue(
            "Every word in the report takes INK or SOFT; FAINT measures 2.72:1 on the white page and is " +
                "for marks only (#409). These do not clear $WORDS_FLOOR:1:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `the faint ink draws marks only`() {
        val found = faintOffMarks(source)
        assertTrue(
            "FAINT may colour a paint for rules and marks, never one that reaches words (#409). These " +
                "do:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `the check sees the faint ink put back on a real label`() {
        // The section label's paint, planted in the real file whatever colour it holds today, and
        // looked for on its line, so this still means something while the test above is red.
        val label = source.indexOf("private fun sectionLabel(")
        assertTrue("the report no longer has sectionLabel", label >= 0)
        val call = Regex("""\bpaint\(\s*[\d.]+f\s*,\s*(\w+)""").find(source, label)
        assertTrue("sectionLabel no longer makes its paint with the factory", call != null)
        val colour = call!!.groups[1]!!
        val faint = if (colour.value == "FAINT") "HAIR" else "FAINT"
        val planted = source.replaceRange(colour.range, faint)
        assertTrue("nothing was planted", planted != source)
        val line = lineOf(source, call.range.first)
        assertTrue(
            "the check cannot see sectionLabel in $faint: ${faintWords(planted)}",
            faintWords(planted).any { it.startsWith("line $line: paint(...) in $faint") },
        )
    }

    @Test
    fun `the check sees a faint paint reaching words`() {
        // The prompts' bullet, the one faint paint, handed to drawText beside its circle.
        val bullet = Regex("""canvas\.drawCircle\(([^\n]*), bullet\)""").find(source)
        assertTrue("the prompts no longer draw their bullet with the faint paint", bullet != null)
        val planted = source.replaceRange(bullet!!.range, bullet.value + "\n                canvas.drawText(text, margin, y, bullet)")
        assertTrue("nothing was planted", planted != source)
        val found = faintOffMarks(planted)
        assertTrue("the check cannot see the bullet's paint drawing words: $found", found.any { "bullet, in FAINT, reaches drawText" in it })

        // And FAINT put straight onto a paint's colour, held by nothing.
        val loose = source.replaceFirst("val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }", "val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }\n            dot.color = FAINT")
        assertTrue("the scatter's dot paint has moved", loose != source)
        assertTrue("the check cannot see FAINT held by nothing: ${faintOffMarks(loose)}", faintOffMarks(loose).any { "FAINT is not held" in it })
    }

    @Test
    fun `the check does not rule on a mark or a dark word`() {
        val marks = """
            private const val FAINT = 0xFFA49C8E.toInt()
            private const val SOFT = 0xFF6B655B.toInt()
            val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FAINT }
            canvas.drawLine(0f, 0f, 1f, 1f, rule)
            canvas.drawRoundRect(0f, 0f, 1f, 1f, 3f, 3f, rule)
            canvas.drawText("words", 0f, 0f, paint(8f, SOFT, bold = true))
            private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint()
        """.trimIndent()
        assertEquals("a faint rule and a soft word were ruled on", emptyList<String>(), faintOffMarks(marks) + faintWords(marks))
    }
}
