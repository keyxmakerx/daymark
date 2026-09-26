package com.daymark.app.ui

import com.daymark.app.backup.repoFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No word on the hairline fill is drawn in the soft or the faint ink (#408).
 *
 * The hairline, #E7DFD1 light and #34312A dark, is a fill as well as a rule: the provenance badge and
 * its note, the swipe row's "Keep swiping", the safety plan's lines. The soft ink measures 4.36:1 on
 * it in the light theme, under the 4.5:1 small text needs, and the faint ink far less. The full ink
 * measures 11.24:1 light and 10.34:1 dark. `FaintInkSourceTest` keeps the faint ink off words
 * everywhere; this keeps the soft ink off this one ground, where it falls short.
 *
 * ## How it reads
 *
 * Every production Kotlin file outside `ui/theme/`, through [codeOnly]. Which roles carry the
 * hairline, and which the soft and faint inks, is read from `ui/theme/Theme.kt`. A ground is a
 * `Surface` whose `color` is the hairline, or a call whose own modifier chain draws `.background(…)`
 * in it, and its words are what its trailing lambda holds. A `val` in the same file is followed to
 * what it holds, so `background(backgroundColor)` and `color = foregroundColor` are seen for what they
 * are. A word takes the soft or faint ink through any of the shapes `FaintInkSourceTest` names, or
 * through the `Surface`'s own `contentColor`. Each is shown a planted example below.
 *
 * ## What it will not catch
 *
 * Words drawn by a composable of our own called on the fill, which draw wherever they are defined;
 * a fill reaching a ground through a parameter or another file; and the colour Material picks when a
 * `Surface` on the fill names none. That last one lands on the full ink in both paper schemes,
 * because Material matches the fill's value to `primaryContainer`, which is the hairline too, before
 * `surfaceVariant`; the safety plan's lines rely on it. The screens are checked by hand in both themes.
 */
class HairlineFillSourceTest {

    private companion object {
        const val THEME = "app/src/main/java/com/daymark/app/ui/theme/Theme.kt"
        const val SWIPE = "app/src/main/java/com/daymark/app/ui/components/SwipeToDeleteRow.kt"
        const val BADGE = "app/src/main/java/com/daymark/app/ui/components/ProvenanceBadge.kt"
        const val SAFETY = "app/src/main/java/com/daymark/app/ui/safety/SafetyPlanScreen.kt"

        /** A call that draws words or styles them; the match ends on its open parenthesis. */
        val TEXT_CALL = Regex("""(?<!\w)(?:Text|BasicText|ClickableText|TextStyle|SpanStyle)\s*\(""")

        /** A content colour handed to everything beneath; the match ends where the value starts. */
        val CONTENT_PROVIDED = Regex("""\bLocalContentColor\s+provides\s+""")

        /** An argument named for the colour of words or content; the match ends where the value starts. */
        val WORD_COLOUR_ARGUMENT = Regex(
            """(?<!\w)\w*(?:[Tt]ext|[Ll]abel|[Tt]itle|[Hh]eadline|[Ss]upporting|[Oo]verline|[Pp]laceholder|[Cc]ontent)Color\s*=(?!=)\s*""",
        )

        /** A Material surface, which names its fill `color` and its words' colour `contentColor`. */
        val SURFACE_CALL = Regex("""(?<!\w)Surface\s*\(""")

        /** A fill drawn by a modifier. */
        val BACKGROUND = Regex("""\.background\s*\(""")

        /** A `val` and where its initializer starts. */
        val VAL = Regex("""\bval\s+(\w+)\s*(?::\s*[\w.<>?]+\s*)?(?:=|by)\s*""")

        /** A name that is not a member of something else. */
        val NAME = Regex("""(?<![.\w])[A-Za-z_]\w*""")
    }

    private val themeCode: String = withoutComments(repoFile(THEME).readText())

    /** `role = Token` pairs in Theme.kt whose token's name matches [token]. */
    private fun rolesOf(token: String): Pair<Set<String>, Set<String>> {
        val pairs = Regex("""\b(\w+)\s*=\s*($token)\b""").findAll(themeCode).map { it.groupValues[1] to it.groupValues[2] }.toList()
        return pairs.map { it.first }.toSet() to pairs.map { it.second }.toSet()
    }

    /**
     * The light hairline only. The soft ink clears 4.5:1 on the dark one (5.95:1), so a role that is
     * the hairline in the dark scheme alone, as the dark `surfaceContainerHighest` is, is no ground.
     */
    private val hairline: Pair<Set<String>, Set<String>> = rolesOf("""Hairline""")
    private val softOrFaint: Pair<Set<String>, Set<String>> = rolesOf("""Ink(?:Soft|Faint)\w*""")

    /** A role by name, after a dot and whole, or a token by name, whole. */
    private fun pattern(of: Pair<Set<String>, Set<String>>): Regex =
        Regex(of.first.joinToString("|", prefix = """\.(?:""", postfix = """)\b""") + of.second.joinToString("|", prefix = """|\b(?:""", postfix = """)\b"""))

    private val hairlinePattern: Regex = pattern(hairline)
    private val softPattern: Regex = pattern(softOrFaint)

    /** `app/src`, found above a file known to be in it. */
    private val src: File = run {
        var dir: File? = repoFile(SWIPE).parentFile
        while (dir != null && !(dir.name == "src" && dir.parentFile?.name == "app")) dir = dir.parentFile
        checkNotNull(dir) { "app/src is not above $SWIPE" }
    }

    /** Every production Kotlin file: under `app/src`, in no test source set, and not in `ui/theme/`. */
    private val files: List<File> =
        src.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val rel = f.relativeTo(src).invariantSeparatorsPath
                !rel.startsWith("test/") && !rel.startsWith("androidTest/") && "/ui/theme/" !in rel
            }
            .sortedBy { it.path }
            .toList()

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

    /** From [start], the expression up to the first comma or closing bracket not nested inside it. */
    private fun expressionFrom(code: String, start: Int): String {
        var depth = 0
        for (i in start until code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) return code.substring(start, i) else depth--
                ',' -> if (depth == 0) return code.substring(start, i)
            }
        }
        return code.substring(start)
    }

    /**
     * A `val`'s initializer from [start]: to the end of its statement, which is a line break outside
     * any bracket, unless the next line carries on with `.`, `?`, `else`, `&&` or `||`.
     */
    private fun initializerFrom(code: String, start: Int): String {
        var depth = 0
        var i = start
        while (i < code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) return code.substring(start, i) else depth--
                ';' -> if (depth == 0) return code.substring(start, i)
                '\n' -> if (depth == 0) {
                    var j = i + 1
                    while (j < code.length && code[j].isWhitespace()) j++
                    val carriesOn = listOf(".", "?", "else", "&&", "||").any { code.startsWith(it, j) }
                    if (!carriesOn) return code.substring(start, i)
                }
            }
            i++
        }
        return code.substring(start)
    }

    /** What each `val` in [code] is initialised to, by name; one name may be declared more than once. */
    private fun vals(code: String): Map<String, List<String>> =
        VAL.findAll(code).groupBy({ it.groupValues[1] }, { initializerFrom(code, it.range.last + 1) })

    /** [expression] and everything the vals it names hold, followed through. */
    private fun followed(expression: String, decls: Map<String, List<String>>): String {
        val out = StringBuilder(expression)
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(expression))
        while (queue.isNotEmpty()) {
            for (m in NAME.findAll(queue.removeFirst())) {
                val held = decls[m.value] ?: continue
                if (!seen.add(m.value)) continue
                for (h in held) {
                    out.append('\n').append(h)
                    queue.addLast(h)
                }
            }
        }
        return out.toString()
    }

    /** [text] split on commas outside any brackets: a call's own arguments, one each. */
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

    private class Scan(val grounds: List<String>, val found: List<String>)

    /** The hairline grounds in [source], and every word on one in the soft or faint ink, by line. */
    private fun scan(source: String): Scan {
        val code = codeOnly(source)
        val decls = vals(code)
        fun line(index: Int) = code.substring(0, index).count { it == '\n' } + 1
        fun hairlineIn(expression: String) = hairlinePattern.containsMatchIn(followed(expression, decls))
        fun softIn(expression: String) = softPattern.containsMatchIn(followed(expression, decls))

        // (open paren of the ground's call, what the words beneath it inherit from the ground itself)
        val grounds = LinkedHashMap<Int, String?>()
        for (m in SURFACE_CALL.findAll(code)) {
            val open = m.range.last
            // The surface's own arguments only, so a `color =` inside its BorderStroke is not its fill.
            val named = topLevelSplit(code.substring(open + 1, closing(code, open)))
                .mapNotNull { Regex("""^\s*(color|contentColor)\s*=(?!=)\s*([\s\S]*)$""").find(it) }
                .associate { it.groupValues[1] to it.groupValues[2] }
            if (named["color"]?.let(::hairlineIn) == true) grounds[open] = named["contentColor"]
        }
        for (m in BACKGROUND.findAll(code)) {
            val fill = expressionFrom(code, m.range.last + 1)
            if (!hairlineIn(fill)) continue
            val open = enclosing(code, m.range.first)
            if (open >= 0 && code[open] == '(' && open !in grounds) grounds[open] = null
        }

        val found = ArrayList<String>()
        for ((open, contentColor) in grounds) {
            val at = "the hairline fill at line ${line(open)}"
            if (contentColor != null && softIn(contentColor)) found += "line ${line(open)}: contentColor = ... on $at"
            var brace = closing(code, open) + 1
            while (brace < code.length && code[brace].isWhitespace()) brace++
            if (brace >= code.length || code[brace] != '{') continue
            val end = closing(code, brace)
            val content = code.substring(0, end) // indices stay the file's
            for (t in TEXT_CALL.findAll(content, brace)) {
                val args = code.substring(t.range.last + 1, closing(code, t.range.last))
                if (softIn(args)) found += "line ${line(t.range.first)}: ${t.value.substringBefore('(').trim()}(...) on $at"
            }
            for (p in CONTENT_PROVIDED.findAll(content, brace)) {
                if (softIn(expressionFrom(code, p.range.last + 1))) found += "line ${line(p.range.first)}: LocalContentColor provides ... on $at"
            }
            for (w in WORD_COLOUR_ARGUMENT.findAll(content, brace)) {
                if (softIn(expressionFrom(code, w.range.last + 1))) {
                    found += "line ${line(w.range.first)}: ${w.value.substringBefore('=').trim()} = ... on $at"
                }
            }
        }
        return Scan(grounds.keys.map { "line ${line(it)}" }, found)
    }

    @Test
    fun `the theme and the tree were read, and the grounds are found`() {
        // Guards everything below: no roles, no files or no grounds all read as "no soft words on the
        // fill" for free.
        assertTrue("Theme.kt no longer puts the hairline on surfaceVariant: $hairline", "surfaceVariant" in hairline.first)
        assertTrue("Theme.kt no longer gives onSurfaceVariant the soft ink: $softOrFaint", "onSurfaceVariant" in softOrFaint.first)
        assertTrue("Theme.kt no longer gives tertiary the faint ink: $softOrFaint", "tertiary" in softOrFaint.first)
        assertTrue("the walk found implausibly few files: ${files.size}", files.size > 200)

        // The real grounds: the badge and its note, the swipe row, the safety plan's chips.
        assertEquals("the badge's two grounds", 2, scan(repoFile(BADGE).readText()).grounds.size)
        assertEquals("the swipe row's ground", 1, scan(repoFile(SWIPE).readText()).grounds.size)
        assertEquals("the safety plan's chip", 1, scan(repoFile(SAFETY).readText()).grounds.size)
    }

    @Test
    fun `no word on the hairline fill is in the soft or faint ink`() {
        val found = files.flatMap { f -> scan(f.readText()).found.map { "${f.relativeTo(src).invariantSeparatorsPath} $it" } }
        assertTrue(
            "The soft ink measures 4.36:1 on the hairline fill in the light theme, under the 4.5:1 small " +
                "text needs; words there take the full ink, onSurface (#408). These do not:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `the check sees the soft ink put back on the real labels`() {
        // "Keep swiping", through the val that colours it, and the badge's own words: the soft ink
        // planted in the real file, whatever ink it holds today, and looked for on the Text's line,
        // so this still means something while the test above is red.
        fun lineOf(text: String, index: Int) = text.substring(0, index).count { it == '\n' } + 1

        val swipe = repoFile(SWIPE).readText()
        val foreground = Regex("""val foregroundColor = if \(armed\) \{[^}]*\} else \{\s*MaterialTheme\.colorScheme\.(\w+)""").find(swipe)
        assertTrue("the swipe row no longer colours its words through foregroundColor", foreground != null)
        val swipeInk = foreground!!.groups[1]!!
        val swipePlanted = swipe.replaceRange(swipeInk.range, "onSurfaceVariant")
        assertTrue("nothing was planted in the swipe row", swipePlanted != swipe || swipeInk.value == "onSurfaceVariant")
        // After the val, so the words its KDoc quotes are not taken for the label.
        val keepSwiping = swipe.indexOf("\"Keep swiping\"", foreground.range.last)
        assertTrue("the swipe row no longer says \"Keep swiping\" after foregroundColor", keepSwiping >= 0)
        val swipeText = lineOf(swipe, swipe.lastIndexOf("Text(", keepSwiping))
        assertTrue(
            "the check cannot see \"Keep swiping\" in the soft ink: ${scan(swipePlanted).found}",
            scan(swipePlanted).found.any { it.startsWith("line $swipeText: Text(") },
        )

        val badge = repoFile(BADGE).readText()
        val label = Regex("""labelSmall,\s*color = MaterialTheme\.colorScheme\.(\w+)""").find(badge)
        assertTrue("the badge no longer colours its words beside labelSmall", label != null)
        val badgeInk = label!!.groups[1]!!
        val badgePlanted = badge.replaceRange(badgeInk.range, "onSurfaceVariant")
        assertTrue("nothing was planted in the badge", badgePlanted != badge || badgeInk.value == "onSurfaceVariant")
        val badgeText = lineOf(badge, badge.lastIndexOf("Text(", label.range.first))
        assertTrue(
            "the check cannot see the badge's words in the soft ink: ${scan(badgePlanted).found}",
            scan(badgePlanted).found.any { it.startsWith("line $badgeText: Text(") },
        )
    }

    @Test
    fun `the check sees every way a word takes the soft ink on the fill`() {
        val shapes = listOf(
            "Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Text(\"x\", color = MaterialTheme.colorScheme.onSurfaceVariant) }",
            "Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSurfaceVariant) { Text(\"x\") }",
            "Box(Modifier.background(Hairline)) { Text(\"x\", style = TextStyle(color = InkSoft)) }",
            "val fill = MaterialTheme.colorScheme.surfaceVariant\nval ink = MaterialTheme.colorScheme.onSurfaceVariant\n" +
                "Row(Modifier.fillMaxWidth().background(fill)) { Text(\"x\", color = ink) }",
            "val fill by animateColorAsState(\n    targetValue = if (on) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,\n    label = \"f\",\n)\n" +
                "Row(modifier = Modifier.background(fill).padding(4.dp)) { Text(\"x\", color = MaterialTheme.colorScheme.tertiary) }",
            "val c = if (armed) MaterialTheme.colorScheme.onErrorContainer\n    else MaterialTheme.colorScheme.onSurfaceVariant\n" +
                "Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) { Text(\"x\", color = c) }",
            "Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {\n    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { Text(\"x\") }\n}",
            "Surface(color = MaterialTheme.colorScheme.surfaceVariant) {\n    ListItem(headlineContent = { Text(\"x\") }, colors = ListItemDefaults.colors(supportingColor = MaterialTheme.colorScheme.onSurfaceVariant))\n}",
        )
        for (shape in shapes) assertTrue("the check cannot see:\n$shape", scan(shape).found.isNotEmpty())
    }

    @Test
    fun `the check does not rule on what is not a soft word on the fill`() {
        val notSoftWords = listOf(
            "Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Text(\"x\", color = MaterialTheme.colorScheme.onSurface) }",
            "Surface(color = MaterialTheme.colorScheme.surface) { Text(\"x\", color = MaterialTheme.colorScheme.onSurfaceVariant) }",
            "Column {\n    Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant))\n    Text(\"x\", color = MaterialTheme.colorScheme.onSurfaceVariant)\n}",
            "Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }",
            "Surface(border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outline)) { Text(\"x\", color = MaterialTheme.colorScheme.onSurfaceVariant) }",
            "SwipeToDismissBox(state, backgroundContent = { Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {} }) {\n    Text(\"x\", color = MaterialTheme.colorScheme.onSurfaceVariant)\n}",
            "Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Text(\"onSurfaceVariant\", color = MaterialTheme.colorScheme.onSurface) }",
        )
        for (shape in notSoftWords) assertEquals("the check rules on this:\n$shape", 0, scan(shape).found.size)
    }
}
