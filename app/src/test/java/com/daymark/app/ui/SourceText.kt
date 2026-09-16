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
