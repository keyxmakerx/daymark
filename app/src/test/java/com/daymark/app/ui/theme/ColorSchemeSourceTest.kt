package com.daymark.app.ui.theme

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No role in the phone's colour scheme is a mood colour (#395).
 *
 * A mood colour is the value a person logged, never a status (`docs/DESIGN.md`, "Colour tokens"),
 * and a person can recolour their moods. So every Material role names a token of its own. The alarm
 * roles above all: they draw a Delete label, a destructive menu item, the crisis button's fill and
 * the lock screen's error, and they take the clay tokens, which are the web consoles' `--clay` and
 * `--clay-wash` value for value, so the two registers draw one alarm (COMPANION_DESIGN_SYSTEM.md
 * §2.3.4).
 *
 * ## How it reads
 *
 * `ui/theme/Theme.kt` and `ui/theme/Color.kt`, as text, comments removed. Every named argument of a
 * call that builds a scheme (`lightColorScheme`, `darkColorScheme`) or copies one (`.copy`) is a role
 * assignment, and each is followed through Color.kt's declarations to the colour it lands on. It fails
 * if anything on the way says "mood" — a `Mood…` token, or a person's own colours arriving as
 * `moods.awful` or `MaterialTheme.moodColors` — or if it lands on a mood token's value under any
 * other name. Each of those is shown a planted example below, derived from the file rather than
 * written out, and has to see it.
 *
 * ## Why a source test
 *
 * Theme.kt imports Compose and Android, so nothing on a plain JVM can build a scheme and ask it. The
 * claim is what the file says, and reading it answers that. These run in seconds through
 * `tools/jvm-source-tests.sh`, and in CI with the rest.
 */
class ColorSchemeSourceTest {

    private companion object {
        const val THEME = "app/src/main/java/com/daymark/app/ui/theme/Theme.kt"
        const val COLORS = "app/src/main/java/com/daymark/app/ui/theme/Color.kt"
        const val WEB_TOKENS = "companion/web/src/app.css"

        /**
         * Values a token shares with a mood token by decision, as (token, mood token). The dark alarm
         * wash and the dark Awful wash are both a warm tint on the same dark ground, and the web keeps
         * the same pair as two primitives for the same reason (`--c-clay-wash-night`). Any other role
         * that lands on a mood token's value fails.
         */
        val SHARED_BY_DESIGN = setOf("ClayWashDark" to "MoodAwfulWashDark")

        /** The four alarm roles, and the web primitive each must equal in (light, dark). */
        val ALARM_ROLES = mapOf(
            "error" to ("--c-clay-day" to "--c-clay-night"),
            "errorContainer" to ("--c-clay-wash-day" to "--c-clay-wash-night"),
        )

        /** A Color.kt literal, `Color(0xAARRGGBB)`. */
        val COLOR_LITERAL = Regex("""^Color\(\s*0[xX]([0-9a-fA-F]{8})\s*\)$""")

        /** A call that builds a colour scheme or copies one; the match ends on its open parenthesis. */
        val SCHEME_CALL = Regex("""\b(lightColorScheme|darkColorScheme)\s*\(|\.(copy)\s*\(""")
    }

    private val themeSource: String = repoFile(THEME).readText()
    private val colorsSource: String = repoFile(COLORS).readText()
    private val theme: String = withoutComments(themeSource)
    private val colors: String = withoutComments(colorsSource)

    /** One named argument of a scheme call: which call, which role, and the expression it is given. */
    private data class Assignment(val call: String, val role: String, val expression: String)

    /** Where an expression lands: the Color.kt names it passes through, and the RRGGBB it ends on. */
    private data class Landing(val via: List<String>, val rgb: String?)

    /** Color.kt's top-level `val Name = expression` declarations. */
    private fun declarations(colorsCode: String): Map<String, String> =
        Regex("""(?m)^val\s+(\w+)\s*=\s*(.+?)\s*$""").findAll(colorsCode)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun land(expression: String, decls: Map<String, String>): Landing {
        val via = mutableListOf<String>()
        var e = expression.trim()
        while (true) {
            COLOR_LITERAL.find(e)?.let { return Landing(via, it.groupValues[1].takeLast(6).uppercase()) }
            val next = decls[e] ?: return Landing(via, null)
            if (e in via) return Landing(via, null)
            via += e
            e = next.trim()
        }
    }

    /** The text between the parenthesis at [open] and the one that closes it. */
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

    private fun assignments(themeCode: String): List<Assignment> =
        SCHEME_CALL.findAll(themeCode).flatMap { call ->
            val name = call.groups[1]?.value ?: call.groups[2]!!.value
            topLevelSplit(argumentsFrom(themeCode, call.range.last)).mapNotNull { argument ->
                Regex("""^\s*(\w+)\s*=\s*([\s\S]+?)\s*$""").find(argument)
                    ?.let { Assignment(name, it.groupValues[1], it.groupValues[2]) }
            }
        }.toList()

    /** Every role in [themeCode] that is a mood colour by name or by value, said in a line each. */
    private fun moodRoles(themeCode: String, colorsCode: String): List<String> {
        val decls = declarations(colorsCode)
        val moodValues = decls.keys.filter { it.startsWith("Mood") }
            .mapNotNull { token -> land(token, decls).rgb?.let { token to it } }
        return assignments(themeCode).mapNotNull { (call, role, expression) ->
            val landing = land(expression, decls)
            val byName = (listOf(expression) + landing.via).firstOrNull { it.contains("mood", ignoreCase = true) }
            val byValue = moodValues.firstOrNull { (mood, rgb) ->
                rgb == landing.rgb && landing.via.none { (it to mood) in SHARED_BY_DESIGN }
            }
            when {
                byName != null -> "$call: $role = $expression names a mood colour ($byName)"
                byValue != null -> "$call: $role = $expression is ${byValue.first}'s value, #${byValue.second}"
                else -> null
            }
        }
    }

    /** The RRGGBB of `--name: #rrggbb;` in the web's token sheet, or null. */
    private fun webValue(css: String, name: String): String? =
        Regex("""(?m)^\s*""" + Regex.escape(name) + """:\s*#([0-9a-fA-F]{6})\s*;""").find(css)
            ?.groupValues?.get(1)?.uppercase()

    @Test
    fun `the theme and the palette were read, and the stripper left the code`() {
        // Guards everything below: a file that was not found, or a stripper that ran away, reads as
        // "no role is a mood colour" for free.
        assertTrue("Theme.kt is implausibly short", themeSource.length > 1500)
        assertTrue("Color.kt is implausibly short", colorsSource.length > 1000)
        assertTrue("the stripper ate the light scheme", theme.contains("lightColorScheme("))
        assertTrue("the stripper ate the dark scheme", theme.contains("darkColorScheme("))
        assertTrue("the stripper ate a declaration", colors.contains("val PaperBg"))
        assertTrue("a word only Color.kt's comments hold is not there to test", colorsSource.contains("Measured"))
        assertFalse("the stripper left Color.kt's comments in", colors.contains("Measured"))
        assertTrue("a word only Theme.kt's comments hold is not there to test", themeSource.contains("holds it to that"))
        assertFalse("the stripper left Theme.kt's comments in", theme.contains("holds it to that"))

        val decls = declarations(colors)
        assertTrue("Color.kt's declarations were not read", decls.size >= 30)
        val moodTokens = decls.keys.filter { it.startsWith("Mood") }
        assertEquals("the five moods, their washes and their dark washes", 15, moodTokens.size)
        assertTrue("a mood token lands on no colour", moodTokens.all { land(it, decls).rgb != null })

        val all = assignments(theme)
        assertTrue(
            "both schemes were not found",
            all.map { it.call }.containsAll(listOf("lightColorScheme", "darkColorScheme")),
        )
        assertTrue("the schemes' roles were not read", all.size >= 40)
        for (call in listOf("lightColorScheme", "darkColorScheme")) {
            val roles = all.filter { it.call == call }.map { it.role }
            for (role in listOf("error", "onError", "errorContainer", "onErrorContainer")) {
                assertEquals("$call does not set $role exactly once", 1, roles.count { it == role })
            }
        }
    }

    @Test
    fun `no colour-scheme role is a mood colour`() {
        val found = moodRoles(theme, colors)
        assertTrue(
            "A mood colour is the value a person logged, and they can recolour it, so no role in the " +
                "scheme may be one (#395). These are:\n" + found.joinToString("\n"),
            found.isEmpty(),
        )

        // Every decided coincidence still holds, so the list says only what is true.
        val decls = declarations(colors)
        for ((token, mood) in SHARED_BY_DESIGN) {
            val tokenRgb = land(token, decls).rgb
            assertNotNull("$token is gone; drop it from SHARED_BY_DESIGN", tokenRgb)
            assertEquals("$token no longer shares $mood's value; drop the pair", land(mood, decls).rgb, tokenRgb)
        }
    }

    @Test
    fun `the check sees a role pointed at a mood token by name`() {
        val current = Regex("""\berror\s*=\s*(\w+)""").find(themeSource)!!.groupValues[1]
        val mood = if (current == "MoodAwful") "MoodBad" else "MoodAwful"
        val planted = themeSource.replaceFirst("error = $current", "error = $mood")
        assertNotEquals("nothing was planted", themeSource, planted)

        val found = moodRoles(withoutComments(planted), colors)
        assertTrue("the check cannot see error = $mood: $found", found.any { it.contains(": error = $mood ") })
    }

    @Test
    fun `the check sees a person's own mood colour arriving in the scheme`() {
        // The way a person's recoloured moods would reach the alarm: a copy of the scheme handed
        // the holder that carries their overrides.
        val planted = themeSource.replace("colorScheme = colorScheme,", "colorScheme = colorScheme.copy(error = moods.awful),")
        assertNotEquals("nothing was planted", themeSource, planted)

        val found = moodRoles(withoutComments(planted), colors)
        assertTrue("the check cannot see a copy handed moods.awful: $found", found.any { it.startsWith("copy: error = moods.awful") })
    }

    @Test
    fun `the check sees a mood colour's value under another name`() {
        // A token named for nothing, carrying the Awful mood's value, and the light scheme's error
        // pointed at it. Planted whatever error is today, so this shows the check works even while
        // the test above is red.
        val current = Regex("""\berror\s*=\s*(\w+)""").find(themeSource)!!.groupValues[1]
        val pointed = themeSource.replaceFirst("error = $current", "error = PlantedAlarm")
        assertNotEquals("nothing was planted in Theme.kt", themeSource, pointed)
        val awful = declarations(colors).getValue("MoodAwful")

        val byValue = moodRoles(withoutComments(pointed), colors + "\nval PlantedAlarm = $awful\n")
        assertTrue(
            "the check cannot see a token carrying MoodAwful's value: $byValue",
            byValue.any { it.contains(": error = PlantedAlarm is MoodAwful's value") },
        )

        // The same token as an alias of the Awful mood.
        val byAlias = moodRoles(withoutComments(pointed), colors + "\nval PlantedAlarm = MoodAwful\n")
        assertTrue(
            "the check cannot see a token that is an alias of MoodAwful: $byAlias",
            byAlias.any { it.contains(": error = PlantedAlarm names a mood colour (MoodAwful)") },
        )
    }

    @Test
    fun `the alarm is the consoles' clay, value for value`() {
        val css = repoFile(WEB_TOKENS).readText()
        val decls = declarations(colors)
        val all = assignments(theme)
        for ((role, web) in ALARM_ROLES) {
            for ((call, token) in listOf("lightColorScheme" to web.first, "darkColorScheme" to web.second)) {
                val expected = webValue(css, token)
                assertNotNull("$token is not in $WEB_TOKENS any more", expected)
                val expression = all.single { it.call == call && it.role == role }.expression
                assertEquals(
                    "$call's $role is not the consoles' $token. The phone and the consoles draw one " +
                        "alarm, so change both (COMPANION_DESIGN_SYSTEM.md §2.3.6 has the measurements)",
                    expected,
                    land(expression, decls).rgb,
                )
            }
        }

        // The web parser is shown a planted difference, so the equality cannot hold by reading nothing.
        val day = webValue(css, "--c-clay-day")!!
        val other = if (day == "000000") "FFFFFF" else "000000"
        val planted = css.replaceFirst(Regex("""--c-clay-day:\s*#$day""", RegexOption.IGNORE_CASE), "--c-clay-day: #$other")
        assertNotEquals("nothing was planted", css, planted)
        assertEquals("the web parser cannot see a changed --c-clay-day", other, webValue(planted, "--c-clay-day"))
    }
}
