package com.daymark.app.ui

import com.daymark.app.backup.repoFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No words on the phone are drawn in the faint ink (#396).
 *
 * The faint ink, `InkFaint`, is what `colorScheme.tertiary` carries. It measures 2.61:1 on the sheet,
 * 2.37:1 on the paper and 3.48:1 on the dark sheet: too faint for small text for many people, and in
 * bright light for everyone. It is for decoration, such as rules and empty marks, and never carries a
 * word. A word that steps back takes the soft ink, `onSurfaceVariant`: 5.53:1 on the sheet, 5.04:1 on
 * the paper, 7.29:1 on the dark sheet. The web consoles rule the same way on the same colour
 * (COMPANION_DESIGN_SYSTEM.md §2.3.6).
 *
 * ## What counts as giving it to words
 *
 * Three shapes, each shown a planted example below:
 *  - the faint ink named anywhere in the arguments of `Text`, `BasicText`, `ClickableText`,
 *    `TextStyle` or `SpanStyle`;
 *  - `LocalContentColor provides` the faint ink, which every Text beneath it inherits;
 *  - a named argument whose name says it colours words or content (`contentColor`,
 *    `titleContentColor`, `supportingColor`, `focusedLabelColor` and the like), which is how a
 *    Material component is told the colour of its words.
 * An icon's tint, a divider or a drawn mark is none of these, and this test does not rule on them.
 * Which roles carry the faint ink is read from `ui/theme/Theme.kt`, not assumed.
 *
 * ## What it will not catch
 *
 * The faint ink reaching a word through a name defined somewhere else: a `val` holding it, a function
 * returning it, a composable of our own taking it as a plain `color`. Nor text drawn on a Canvas.
 * Reading source is a weaker claim than what a person sees; the screens are checked by hand in both
 * themes.
 *
 * It reads every production Kotlin file outside `ui/theme/`, which defines the ink and maps it,
 * through [codeOnly], so neither a comment nor a string can hide a call or fake one. These run in
 * seconds through `tools/jvm-source-tests.sh`, and in CI with the rest.
 */
class FaintInkSourceTest {

    private companion object {
        const val THEME = "app/src/main/java/com/daymark/app/ui/theme/Theme.kt"
        const val HOME = "app/src/main/java/com/daymark/app/ui/home/HomeScreen.kt"

        /** A call that draws words or styles them; the match ends on its open parenthesis. */
        val TEXT_CALL = Regex("""(?<!\w)(?:Text|BasicText|ClickableText|TextStyle|SpanStyle)\s*\(""")

        /** A content colour handed to everything beneath; the match ends where the value starts. */
        val CONTENT_PROVIDED = Regex("""\bLocalContentColor\s+provides\s+""")

        /** An argument named for the colour of words or content; the match ends where the value starts. */
        val WORD_COLOUR_ARGUMENT = Regex(
            """(?<!\w)\w*(?:[Tt]ext|[Ll]abel|[Tt]itle|[Hh]eadline|[Ss]upporting|[Oo]verline|[Pp]laceholder|[Cc]ontent)Color\s*=(?!=)\s*""",
        )
    }

    /** The roles Theme.kt gives the faint ink, in either scheme. */
    private val faintRoles: Set<String> =
        Regex("""\b(\w+)\s*=\s*InkFaint\w*\b""").findAll(withoutComments(repoFile(THEME).readText()))
            .map { it.groupValues[1] }
            .toSet()

    /** The faint ink by role or by token. A role ends at a word boundary, so `tertiaryContainer` is not it. */
    private val faint: Regex =
        Regex(faintRoles.joinToString("|", prefix = """\.(?:""", postfix = """)\b|\bInkFaint\w*"""))

    /** `app/src`, found above a file known to be in it. */
    private val src: File = run {
        var dir: File? = repoFile(HOME).parentFile
        while (dir != null && !(dir.name == "src" && dir.parentFile?.name == "app")) dir = dir.parentFile
        checkNotNull(dir) { "app/src is not above $HOME" }
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

    private fun argumentsFrom(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        return code.substring(open + 1)
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

    /** The last character before [index] that is not whitespace, or null. */
    private fun charBefore(code: String, index: Int): Char? {
        var i = index - 1
        while (i >= 0 && code[i].isWhitespace()) i--
        return if (i >= 0) code[i] else null
    }

    /** Where Kotlin [source] gives the faint ink to words, one line each, by line number. */
    private fun faintWords(source: String): List<String> {
        val code = codeOnly(source)
        fun line(index: Int) = code.substring(0, index).count { it == '\n' } + 1
        val found = ArrayList<String>()
        for (m in TEXT_CALL.findAll(code)) {
            if (faint.containsMatchIn(argumentsFrom(code, m.range.last))) {
                found += "line ${line(m.range.first)}: ${m.value.substringBefore('(').trim()}(...)"
            }
        }
        for (m in CONTENT_PROVIDED.findAll(code)) {
            if (faint.containsMatchIn(expressionFrom(code, m.range.last + 1))) {
                found += "line ${line(m.range.first)}: LocalContentColor provides ..."
            }
        }
        for (m in WORD_COLOUR_ARGUMENT.findAll(code)) {
            if (charBefore(code, m.range.first) !in setOf('(', ',')) continue
            if (faint.containsMatchIn(expressionFrom(code, m.range.last + 1))) {
                found += "line ${line(m.range.first)}: ${m.value.substringBefore('=').trim()} = ..."
            }
        }
        return found
    }

    @Test
    fun `the tree was read, and the scanner sees code and only code`() {
        // Guards everything below: an empty walk, or a scanner that blanked the calls, reads as "no
        // words in the faint ink" for free.
        assertTrue("Theme.kt no longer gives tertiary the faint ink; re-read this test's premise", "tertiary" in faintRoles)
        assertTrue("the walk found implausibly few files: ${files.size}", files.size > 200)
        assertTrue("the walk missed HomeScreen.kt", files.any { it.invariantSeparatorsPath.endsWith(HOME) })
        assertTrue("the walk read ui/theme", files.none { "/ui/theme/" in it.invariantSeparatorsPath })
        assertTrue("the walk read a test", files.none { it.relativeTo(src).invariantSeparatorsPath.startsWith("test/") })
        val calls = files.sumOf { TEXT_CALL.findAll(codeOnly(it.readText())).count() }
        assertTrue("the scanner found implausibly few Text calls: $calls", calls > 300)

        val home = repoFile(HOME).readText()
        val homeCode = codeOnly(home)
        assertEquals("the scanner changed the length", home.length, homeCode.length)
        assertTrue("the scanner ate the code", homeCode.contains("PaperSurface(") && homeCode.contains("Text("))
        assertTrue("a word only HomeScreen's strings hold is not there to test", home.contains("HOW ARE YOU"))
        assertFalse("the scanner left a string's contents", homeCode.contains("HOW ARE YOU"))
        assertTrue("a word only HomeScreen's comments hold is not there to test", home.contains("faint stub"))
        assertFalse("the scanner left a comment", homeCode.contains("faint stub"))
    }

    @Test
    fun `the scanner keeps lines and follows nesting`() {
        val s = "a(\"x)\") /* b( /* nested */ still */ c // d(\n'\"' \"\"\"raw ) \"\"\"\" f \"t \${g(\")\")} u\" h"
        val code = codeOnly(s)
        assertEquals(s.length, code.length)
        assertEquals(s.indices.filter { s[it] == '\n' }, code.indices.filter { code[it] == '\n' })
        for (kept in listOf("a(", " c ", " f ", " h")) assertTrue("the scanner ate \"$kept\": $code", code.contains(kept))
        for (gone in listOf("x)", "b(", "nested", "still", "d(", "raw", "g(", " t ", " u")) {
            assertFalse("the scanner kept \"$gone\": $code", code.contains(gone))
        }
    }

    @Test
    fun `no words on the phone are drawn in the faint ink`() {
        val found = files.flatMap { f -> faintWords(f.readText()).map { "${f.relativeTo(src).invariantSeparatorsPath} $it" } }
        assertTrue(
            "The faint ink measures 2.61:1 on the sheet and is for decoration only; a word that steps " +
                "back takes onSurfaceVariant (#396). These give it to words:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    @Test
    fun `the check sees the faint ink planted back on a real label`() {
        // The label #396 was found on, put back as it was, in the real file.
        val home = repoFile(HOME).readText()
        val soft = "colorScheme.onSurfaceVariant"
        val label = home.indexOf("text = \"TODAY\"")
        val at = home.indexOf(soft, label)
        assertTrue("the TODAY label or its colour has moved", label >= 0 && at > label && at - label < 150)
        val role = faintRoles.first()
        val planted = home.substring(0, at) + "colorScheme.$role" + home.substring(at + soft.length)
        assertNotEquals("nothing was planted", home, planted)

        val found = faintWords(planted)
        assertEquals("the check cannot see the TODAY label in the faint ink: $found", 1, found.size)
    }

    @Test
    fun `the check sees every shape that gives words a colour`() {
        val afterMimeType = "launcher.launch(arrayOf(\"text/*\"))\nText(\"x\", color = MaterialTheme.colorScheme.tertiary)\n/** a doc */"
        val shapes = listOf(
            "Text(date, color = if (hasMood) Color.White else MaterialTheme.colorScheme.tertiary)",
            "androidx.compose.material3.Text(\n    \"x\",\n    color = MaterialTheme.colorScheme\n        .tertiary,\n)",
            "BasicText(\"x\", style = TextStyle(color = InkFaint))",
            "withStyle(SpanStyle(color = InkFaintDark)) { append(\"x\") }",
            "Text(\"x\", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.tertiary))",
            "CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.tertiary) { Text(\"x\") }",
            "ListItem(headlineContent = { Text(\"x\") }, colors = ListItemDefaults.colors(supportingColor = MaterialTheme.colorScheme.tertiary))",
            "TextButton(onClick = {}, colors = ButtonDefaults.textButtonColors(contentColor = InkFaint)) { Text(\"x\") }",
            "TopAppBar(title = { Text(\"x\") }, colors = TopAppBarDefaults.topAppBarColors(titleContentColor = MaterialTheme.colorScheme.tertiary))",
            // Behind a string holding a comment opener, a bracket, a template with a string of its
            // own, and a character literal holding a quote: none of them may hide the call.
            afterMimeType,
            "Text(\"Step 1) \", color = MaterialTheme.colorScheme.tertiary)",
            "Text(\"a \${f(\")\")} b\", color = MaterialTheme.colorScheme.tertiary)",
            "val q = '\"'\nText(\"x\", color = MaterialTheme.colorScheme.tertiary)",
        )
        for (shape in shapes) {
            assertTrue("the check cannot see:\n$shape", faintWords(shape).isNotEmpty())
        }

        // Why the scanner and not the regex stripper: the stripper reads the wildcard MIME type as a
        // comment opener and deletes the call that follows it.
        assertFalse("the regex stripper kept the call after all", withoutComments(afterMimeType).contains("tertiary"))
    }

    @Test
    fun `the check does not rule on what is not a word`() {
        val notWords = listOf(
            "Icon(Icons.Filled.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)",
            "HorizontalDivider(color = MaterialTheme.colorScheme.tertiary)",
            "Box(Modifier.background(InkFaint))",
            "Text(\"x\", color = MaterialTheme.colorScheme.tertiaryContainer)",
            "Text(\"colorScheme.tertiary and InkFaint\", color = MaterialTheme.colorScheme.onSurfaceVariant)",
            "Text(\"x\" /* .tertiary */, color = MaterialTheme.colorScheme.onSurfaceVariant) // .tertiary",
        )
        for (shape in notWords) {
            assertEquals("the check calls this a word in the faint ink:\n$shape", 0, faintWords(shape).size)
        }
    }
}
