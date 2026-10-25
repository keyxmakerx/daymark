package com.daymark.app.ui.sky

import com.daymark.app.backup.repoFile
import com.daymark.app.sky.SkyShaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names and widths the renderer sets on the sky's shaders, against the ones the shaders declare.
 *
 * `SkyShading.kt` sets each uniform by its name as a string, and Android checks that string only on
 * a device: a name the shader does not declare, or a value of the wrong width, throws there. A throw
 * inside `AgslSkyShading.create` is caught and the sky falls back to its flat glows, which is a sky
 * quietly missing its depth on every phone; a throw inside a draw is a crash. Neither can be seen
 * from here, because `SkyShading.kt` imports Android and only CI compiles it. So this reads it as
 * text, finds every uniform each of its three functions sets, and holds them to the declarations in
 * [SkyShaders.SPACE] and [SkyShaders.SUN] (#474):
 *
 *  - each draw sets exactly the uniforms its shader declares, no more and no fewer, so every frame
 *    is drawn with nothing left over from an earlier one;
 *  - each value is as wide as the uniform it lands in (`float3` takes three floats, a colour); and
 *  - `touchEveryUniform` does both for both shaders, so a disagreement throws once, at creation,
 *    where it is caught, rather than in the middle of a frame.
 */
class SkyShadingSourceTest {

    @Test
    fun `the space is drawn with exactly the uniforms it declares, each at its width`() {
        assertEquals(emptyList<String>(), mismatches(shading(), "drawSpace", "space", declaredIn(SkyShaders.SPACE)))
    }

    @Test
    fun `a sun is drawn with exactly the uniforms it declares, each at its width`() {
        assertEquals(emptyList<String>(), mismatches(shading(), "drawSun", "sun", declaredIn(SkyShaders.SUN)))
    }

    @Test
    fun `every uniform of both shaders is set once when they are made`() {
        val source = shading()
        assertEquals(emptyList<String>(), mismatches(source, "touchEveryUniform", "space", declaredIn(SkyShaders.SPACE)))
        assertEquals(emptyList<String>(), mismatches(source, "touchEveryUniform", "sun", declaredIn(SkyShaders.SUN)))
    }

    @Test
    fun `the declarations are all read, and so are the lights`() {
        // Positive control on the reader of the shader side: every `uniform` line is counted, and
        // the eight lights and their tints are among them at their own widths.
        for (shader in listOf(SkyShaders.SPACE, SkyShaders.SUN)) {
            val lines = shader.lines().count { it.trimStart().startsWith("uniform ") }
            assertTrue("no uniforms found at all", lines > 0)
            assertEquals("a uniform line the reader cannot parse", lines, declaredIn(shader).size)
        }
        val space = declaredIn(SkyShaders.SPACE)
        for (k in 0 until SkyShaders.LIGHTS) {
            assertEquals(4, space["uLight$k"])
            assertEquals(3, space["uLightTint$k"])
        }
        assertEquals(1, declaredIn(SkyShaders.SUN)["uRadius"])
    }

    @Test
    fun `the lights' names are built the way the check reads them`() {
        val source = shading()
        assertTrue("LIGHT is no longer built as namesOf reads it", "val LIGHT = Array(SkyShaders.LIGHTS) { \"uLight\$it\" }" in source)
        assertTrue("LIGHT_TINT is no longer built as namesOf reads it", "val LIGHT_TINT = Array(SkyShaders.LIGHTS) { \"uLightTint\$it\" }" in source)
    }

    @Test
    fun `the check sees a misspelt name, a wrong width and a uniform left unset`() {
        val source = shading()
        val space = declaredIn(SkyShaders.SPACE)
        val sun = declaredIn(SkyShaders.SUN)

        val misspelt = plant(source, "shader.setFloatUniform(\"uShow\", show)", "shader.setFloatUniform(\"uShoe\", show)")
        val missed = mismatches(misspelt, "drawSpace", "space", space)
        assertTrue("a misspelt name went unseen: $missed", missed.any { "uShoe" in it } && missed.any { "uShow" in it })

        val wide = plant(source, "shader.setFloatUniform(\"uScale\", scale)", "shader.setFloatUniform(\"uScale\", scale, scale)")
        assertTrue("a wrong width went unseen", mismatches(wide, "drawSpace", "space", space).any { "uScale" in it })

        val colour = plant(source, "shader.setFloatUniform(\"uLight\", light.coerceIn(0f, 1f))", "setColour(shader, \"uLight\", tint)")
        assertTrue("a colour set on a single float went unseen", mismatches(colour, "drawSun", "sun", sun).any { "uLight" in it })

        val unset = plant(source, "        sun.setFloatUniform(\"uReach\", 1f)\n", "")
        assertTrue("an unset uniform went unseen", mismatches(unset, "touchEveryUniform", "sun", sun).any { "uReach" in it })

        val oneLight = plant(source, "for (k in 0 until SkyShaders.LIGHTS) {\n            if (k < lightCount)", "for (k in 0 until 1) {\n            if (k < lightCount)")
        assertTrue("lights left unset went unseen", mismatches(oneLight, "drawSpace", "space", space).any { "uLight7" in it })
    }

    // -----------------------------------------------------------------------------------------

    private fun shading(): String = repoFile(SHADING).readText()

    private fun plant(source: String, from: String, to: String): String {
        assertEquals("the control's anchor is no longer in SkyShading.kt: $from", 1, source.split(from).size - 1)
        val planted = source.replace(from, to)
        assertNotEquals(source, planted)
        return planted
    }

    /** Every `uniform <type> <name>;` in [shader], as its name and its width in floats. */
    private fun declaredIn(shader: String): Map<String, Int> =
        Regex("""^\s*uniform\s+(float[234]?)\s+(\w+)\s*;""", RegexOption.MULTILINE).findAll(shader)
            .associate { it.groupValues[2] to widthOf(it.groupValues[1]) }

    private fun widthOf(type: String): Int = if (type == "float") 1 else type.last().digitToInt()

    /**
     * What is wrong with the uniforms [function] sets on the shader called [shader] (`space` or
     * `sun`), against [declared]: a name set that is not declared, a declared one never set, and a
     * value of the wrong width. Empty when they agree.
     */
    private fun mismatches(source: String, function: String, shader: String, declared: Map<String, Int>): List<String> {
        val body = bodyOf(source, function)
        // Inside a draw the shader is held as `shader`; inside `touchEveryUniform` it is named.
        val alias = Regex("""val shader = (?:this\.)?(\w+)""").find(body)?.groupValues?.get(1)
        val receivers = if (alias == shader) setOf(shader, "shader") else setOf(shader)
        val lights = loopBound(body)

        val set = HashMap<String, MutableList<Int>>()
        for (call in callsIn(body)) {
            if (call.receiver !in receivers) continue
            for (name in namesOf(call.name, lights)) set.getOrPut(name) { ArrayList() }.add(call.width)
        }
        val problems = ArrayList<String>()
        for (name in set.keys - declared.keys) problems.add("$function sets $name, which the $shader shader does not declare")
        for (name in declared.keys - set.keys) problems.add("$function never sets $name on the $shader shader")
        for ((name, widths) in set) {
            val want = declared[name] ?: continue
            for (width in widths.distinct()) {
                if (width != want) problems.add("$function sets $name with $width floats; it is $want wide")
            }
        }
        return problems.sorted()
    }

    /**
     * The text of [function]'s body. The last declaration of that name, because the interface
     * declares each draw before the class implements it.
     */
    private fun bodyOf(source: String, function: String): String {
        val start = source.lastIndexOf("fun $function(")
        if (start < 0) throw AssertionError("SkyShading.kt has no function $function")
        val open = source.indexOf('{', closeOf(source, source.indexOf('(', start)))
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start, i + 1)
            }
        }
        throw AssertionError("$function in SkyShading.kt never closes")
    }

    /** One uniform set: on what, by what name (a literal, or `LIGHT[k]`), and how many floats. */
    private class Call(val receiver: String, val name: String, val width: Int)

    /** Every `x.setFloatUniform(name, …)` and `setColour(x, name, rgb)` in [body]. */
    private fun callsIn(body: String): List<Call> {
        val calls = ArrayList<Call>()
        for (match in Regex("""(\w+)\.setFloatUniform\(|\bsetColour\(""").findAll(body)) {
            val open = match.range.last
            val arguments = topLevelArguments(body.substring(open + 1, closeOf(body, open)))
            if (match.value.startsWith("setColour")) {
                calls.add(Call(arguments[0], arguments[1], 3))
            } else {
                calls.add(Call(match.groupValues[1], arguments[0], arguments.size - 1))
            }
        }
        return calls
    }

    /**
     * The names a written name stands for: a literal is itself; `LIGHT[k]` and `LIGHT_TINT[k]` are
     * every light the loop round them reaches, by the arrays `SkyShading.kt` builds them from.
     */
    private fun namesOf(written: String, lights: Int): List<String> = when (written) {
        "LIGHT[k]" -> List(lights) { "uLight$it" }
        "LIGHT_TINT[k]" -> List(lights) { "uLightTint$it" }
        else -> {
            assertTrue("a uniform set by a name this check cannot read: $written", written.matches(Regex("\"u\\w+\"")))
            listOf(written.trim('"'))
        }
    }

    /** How many lights the loop in [body] sets: `SkyShaders.LIGHTS`, or a number written in. */
    private fun loopBound(body: String): Int {
        val bound = Regex("""for \(k in 0 until ([\w.]+)\)""").find(body)?.groupValues?.get(1) ?: return 0
        return if (bound == "SkyShaders.LIGHTS") SkyShaders.LIGHTS else bound.toInt()
    }

    private fun closeOf(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        throw AssertionError("an unclosed call in SkyShading.kt")
    }

    private fun topLevelArguments(text: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for (i in text.indices) {
            when (text[i]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                ',' -> if (depth == 0) {
                    parts.add(text.substring(start, i).trim())
                    start = i + 1
                }
            }
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        return parts
    }

    private companion object {
        const val SHADING = "app/src/main/java/com/daymark/app/ui/sky/SkyShading.kt"
    }
}
