package com.daymark.app.ui

/**
 * Reading Kotlin source as text, for the checks that have to be made about UI this module cannot
 * run.
 *
 * ## Why these live apart from the tests that use them
 *
 * Two source tests need the same two operations and they are both easy to get subtly wrong in a
 * way that reports green forever. A stripper that eats the whole file makes every absence check
 * pass; a literal extractor that matches nothing makes every presence check fail loudly — which is
 * the safe direction — but makes every *forbidden phrase* check pass silently. So they are written
 * once, and every caller is required to show them a planted example before believing a result.
 * [plantAfter] and [replaceOnce] are how a caller plants one, and each refuses a plant that changed
 * nothing.
 *
 * ## The gap this does not close
 *
 * Reading the source is a weaker claim than "a person sees this on screen". This module has no
 * Robolectric and no Compose test runner, so a composition test would be written, never run, and
 * quietly believed — `LockDisclosureSourceTest` makes the same trade and says so in the same
 * words. What is actually rendered is checked by hand and by `/walkthrough`, never here.
 */

/**
 * The source with its comments removed, so a rule can be stated in KDoc using the very words the
 * code is forbidden to contain.
 *
 * Block comments first (`.` matching newlines), then line comments. Two known limits, and neither
 * is reached by the files this is pointed at: a `//` **inside** a string literal would truncate
 * that literal, and a block-comment opener inside one would swallow from there to the next
 * block-comment closer. Callers assert
 * that the stripped text still contains a known declaration, which catches a stripper that has run
 * away, and assert that a word present only in the comments is gone, which catches one that did
 * nothing.
 */
internal fun withoutComments(source: String): String =
    source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\n]*"), " ")

/**
 * [source] with every comment removed and every string and character literal emptied: each removed
 * character becomes a space and every newline stays, so what is left is code alone, and an index into
 * it is an index into [source] on the same line. The quotes stay; what was between them does not.
 *
 * A scanner and not a regex, for the two ways [withoutComments] fails once a check reads the whole
 * tree rather than one chosen file: a comment opener inside a string (a wildcard MIME type) swallows
 * the code after it, and a bracket inside a string ("Step 1)") unbalances the call that holds it.
 * Block comments nest, as Kotlin's do. A string template's `${…}` is followed to its own closing
 * brace, strings inside it included, so a quote in a template cannot end the outer string early.
 * `FaintInkSourceTest` shows it a planted example of each.
 */
internal fun codeOnly(source: String): String {
    val out = StringBuilder(source)
    fun blank(from: Int, until: Int) {
        for (k in from until minOf(until, source.length)) if (source[k] != '\n') out.setCharAt(k, ' ')
    }
    // What is open at this point, innermost last: the file's own code at the bottom, which never
    // closes; a literal (STRING or RAW); or code inside a template, held as its own open-brace count.
    val open = ArrayDeque<Int>().apply { addLast(FILE_CODE) }
    var i = 0
    while (i < source.length) {
        val top = open.last()
        val c = source[i]
        if (top == STRING || top == RAW) {
            when {
                top == STRING && c == '\\' -> { blank(i, i + 2); i += 2 }
                top == STRING && c == '"' -> { open.removeLast(); i++ }
                top == RAW && source.startsWith("\"\"\"", i) -> {
                    // A run of more than three quotes closes on its last three; the rest is content.
                    var end = i + 3
                    while (end < source.length && source[end] == '"') end++
                    blank(i, end - 3)
                    open.removeLast()
                    i = end
                }
                c == '$' && source.startsWith("{", i + 1) -> { blank(i, i + 2); open.addLast(0); i += 2 }
                else -> { blank(i, i + 1); i++ }
            }
            continue
        }
        // Code: the file's own, or a template's, which belongs to a string and is emptied with it.
        val inTemplate = top != FILE_CODE
        when {
            source.startsWith("//", i) -> {
                val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                blank(i, end)
                i = end
            }
            source.startsWith("/*", i) -> {
                var depth = 0
                var j = i
                while (j < source.length) {
                    when {
                        source.startsWith("/*", j) -> { depth++; j += 2 }
                        source.startsWith("*/", j) -> { depth--; j += 2; if (depth == 0) break }
                        else -> j++
                    }
                }
                blank(i, j)
                i = j
            }
            source.startsWith("\"\"\"", i) -> { if (inTemplate) blank(i, i + 3); open.addLast(RAW); i += 3 }
            c == '"' -> { if (inTemplate) blank(i, i + 1); open.addLast(STRING); i++ }
            c == '\'' -> {
                var j = i + 1
                if (j < source.length && source[j] == '\\') j += 2 else j++
                while (j < source.length && source[j] != '\'' && source[j] != '\n') j++
                if (inTemplate) blank(i, j + 1) else blank(i + 1, j)
                i = j + 1
            }
            c == '`' -> {
                val end = source.indexOf('`', i + 1).let { if (it < 0) source.length else it }
                if (inTemplate) blank(i, end + 1)
                i = end + 1
            }
            inTemplate && c == '{' -> { blank(i, i + 1); open[open.lastIndex] = top + 1; i++ }
            inTemplate && c == '}' -> {
                blank(i, i + 1)
                if (top == 0) open.removeLast() else open[open.lastIndex] = top - 1
                i++
            }
            else -> { if (inTemplate) blank(i, i + 1); i++ }
        }
    }
    return out.toString()
}

private const val FILE_CODE = Int.MAX_VALUE
private const val STRING = -1
private const val RAW = -2

/**
 * Every double-quoted string literal in [text], with `"a" + "b"` joined first so a sentence broken
 * across lines is matched as the one sentence it renders as. Lifted from
 * `ui/settings/LockDisclosureSourceTest`, which needed exactly this.
 */
internal fun stringLiteralsIn(text: String): List<String> {
    val joined = text.replace(Regex("\"\\s*\\+\\s*\""), "")
    return Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        .findAll(joined)
        .map { it.groupValues[1] }
        .toList()
}

/** The first phrase in [phrases] that appears, case-insensitively, in any of [strings]. */
internal fun firstPhraseIn(strings: List<String>, phrases: List<String>): String? {
    for (s in strings) {
        val lower = s.lowercase()
        for (phrase in phrases) if (lower.contains(phrase)) return phrase
    }
    return null
}

/**
 * Where the body of `fun [name](…) { … }` lies in [code], braces balanced: from just inside its
 * opening brace to just before its closing one. Null when there is no such function, or when it has
 * an expression body, which is not how the functions these checks read are written; saying so is
 * better than reading the next function by mistake. [code] must be code only ([codeOnly]), so a
 * bracket in a string or a comment cannot unbalance it.
 */
internal fun functionBodyRange(code: String, name: String): IntRange? {
    val start = Regex("""\bfun\s+$name\s*\(""").find(code) ?: return null
    var i = start.range.last
    var depth = 0
    while (i < code.length) {
        when (code[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) break
            }
        }
        i++
    }
    val open = code.indexOf('{', i)
    if (open < 0 || code.substring(i + 1, open).contains('=')) return null
    depth = 0
    for (j in open until code.length) {
        when (code[j]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return (open + 1) until j
            }
        }
    }
    return null
}

/**
 * [source] with [addition] written straight after [anchor], which must be there exactly once, for a
 * control that plants a counter-example in a copy of a real file. Fails when nothing changed, so a
 * control can never pass on a plant that did not happen (CLAUDE.md §5).
 */
internal fun plantAfter(source: String, anchor: String, addition: String): String {
    check(source.split(anchor).size - 1 == 1) { "the anchor \"$anchor\" is not there exactly once" }
    val planted = source.replace(anchor, anchor + addition)
    check(planted != source) { "nothing was planted after \"$anchor\"" }
    return planted
}

/** [source] with [old], which must be there exactly once, replaced by [new]; fails when nothing changed. */
internal fun replaceOnce(source: String, old: String, new: String): String {
    check(source.split(old).size - 1 == 1) { "\"$old\" is not there exactly once" }
    val changed = source.replace(old, new)
    check(changed != source) { "replacing \"$old\" changed nothing" }
    return changed
}

/** The findings a plant added: those in [planted], less one of each already in [real]. */
internal fun findingsAdded(planted: List<String>, real: List<String>): List<String> =
    planted.toMutableList().apply { real.forEach { remove(it) } }

/**
 * The argument text of the first call to [callee] in [source], parentheses balanced, or `null`
 * when there is no such call.
 *
 * Balanced rather than "up to the next `)`", because every call this is pointed at contains nested
 * calls, and a naive scan would stop inside one and then report that the outer call does not
 * mention something it plainly does — an absence check that passes for the wrong reason.
 */
internal fun argumentsOfCall(source: String, callee: String): String? {
    val start = source.indexOf("$callee(")
    if (start < 0) return null
    var depth = 0
    var i = start + callee.length
    val open = i
    while (i < source.length) {
        when (source[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return source.substring(open + 1, i)
            }
        }
        i++
    }
    return null
}
