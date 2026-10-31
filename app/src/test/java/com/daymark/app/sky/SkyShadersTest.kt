package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sky's shaders, as far as a plain JVM can hold them: which stars light the gas, that a crowd
 * of them lights it no more than one does, that the colours the space mixes are never green, and
 * that the shaders keep to what every Android 13 device compiles.
 *
 * What the shaders draw is checked by eye, on pictures rendered by a real AGSL compiler; that the
 * renderer sets exactly the uniforms they declare is `SkyShadingSourceTest`'s.
 */
class SkyShadersTest {

    // -------------------------------------------------------------------------------------------
    // The stars that light the gas.
    // -------------------------------------------------------------------------------------------

    /** Stars on a line out from the origin, one at each whole distance 1, 2, 3 and so on. */
    private fun line(count: Int): Pair<FloatArray, FloatArray> =
        FloatArray(count) { it + 1f } to FloatArray(count) { 0f }

    @Test
    fun `the gas is lit by the stars nearest the middle, the nearest at full strength`() {
        val (xs, ys) = line(20)
        // Shuffled, so the answer cannot come from the order the stars arrive in.
        val order = (0 until 20).shuffled(java.util.Random(7))
        val sx = FloatArray(20) { xs[order[it]] }
        val sy = FloatArray(20) { ys[order[it]] }
        val chosen = IntArray(SkyShaders.LIGHTS)
        val weights = FloatArray(SkyShaders.LIGHTS)
        val count = SkyShaders.gasLights(sx, sy, null, 0f, 0f, chosen, weights)
        assertEquals(SkyShaders.LIGHTS, count)
        assertEquals("the lights are not the nearest stars, nearest first", (1..SkyShaders.LIGHTS).map { it.toFloat() }, (0 until count).map { sx[chosen[it]] })
        assertEquals("the nearest star does not light the gas fully", 1f, weights[0], 0f)
        for (k in 1 until count) assertTrue("a farther light is stronger than a nearer one", weights[k] <= weights[k - 1])
        // The farthest is easing out toward the next in line, at 9, and none is at nothing yet.
        assertTrue("the farthest light is not easing out: ${weights[count - 1]}", weights[count - 1] < 1f)
        assertTrue("a light is already gone: ${weights.toList()}", weights.all { it > 0f })
    }

    @Test
    fun `a light hands over to the next one without a jump`() {
        // As the middle of the screen slides along the line, each star's weight changes a little
        // at a time: a light that popped in or out would flicker the gas as the person pans.
        val (xs, ys) = line(30)
        val chosen = IntArray(SkyShaders.LIGHTS)
        val weights = FloatArray(SkyShaders.LIGHTS)
        var previous = FloatArray(30)
        var largestStep = 0f
        var at = 0f
        while (at < 15f) {
            val now = FloatArray(30)
            val count = SkyShaders.gasLights(xs, ys, null, at, 0.3f, chosen, weights)
            for (k in 0 until count) now[chosen[k]] = weights[k]
            if (at > 0f) for (i in 0 until 30) largestStep = maxOf(largestStep, Math.abs(now[i] - previous[i]))
            previous = now
            at += 0.01f
        }
        assertTrue("a light jumps by ${largestStep} as the view moves", largestStep < 0.05f)

        // The control: the same walk with the easing taken away, every chosen light at full, does
        // jump, so the check above can see one.
        previous = FloatArray(30)
        largestStep = 0f
        at = 0f
        while (at < 15f) {
            val now = FloatArray(30)
            val count = SkyShaders.gasLights(xs, ys, null, at, 0.3f, chosen, weights)
            for (k in 0 until count) now[chosen[k]] = 1f
            if (at > 0f) for (i in 0 until 30) largestStep = maxOf(largestStep, Math.abs(now[i] - previous[i]))
            previous = now
            at += 0.01f
        }
        assertEquals("lights without easing do not pop, so the check proves nothing", 1f, largestStep, 0f)
    }

    @Test
    fun `a star put away or in a hole lights nothing, and a few stars all light fully`() {
        val (xs, ys) = line(12)
        val hidden = BooleanArray(12).also { it[0] = true; it[2] = true }
        val chosen = IntArray(SkyShaders.LIGHTS)
        val weights = FloatArray(SkyShaders.LIGHTS)
        val count = SkyShaders.gasLights(xs, ys, hidden, 0f, 0f, chosen, weights)
        assertFalse("a hidden star lights the gas", (0 until count).any { hidden[chosen[it]] })
        assertTrue("the hidden stars were not the nearest, so this proves nothing", chosen[0] == 1)

        val (few, fewY) = line(3)
        val n = SkyShaders.gasLights(few, fewY, null, 0f, 0f, chosen, weights)
        assertEquals(3, n)
        for (k in 0 until n) assertEquals("with no ninth star to ease toward, light $k is not full", 1f, weights[k], 0f)
        assertEquals(0, SkyShaders.gasLights(FloatArray(0), FloatArray(0), null, 0f, 0f, chosen, weights))
    }

    @Test
    fun `a crowd of stars lights the gas little more than one bright star does`() {
        // Every nearby star's light is added, so where one star's light hands over to the next
        // nothing jumps (#535), and the total is capped, so a busy stretch of the sky is not a much
        // brighter one: the cap is a bright star's light and a little more.
        val space = SkyShaders.SPACE
        fun capped(src: String) = Regex("""float amount = min\(la, LIGHT_CAP\);""").containsMatchIn(src) &&
            Regex("""tint \* amount \*""").containsMatchIn(src) && !Regex("""\bla \* lum""").containsMatchIn(src)
        assertTrue(capped(space))
        val cap = Regex("""const float LIGHT_CAP = ([0-9.]+);""").find(space)!!.groupValues[1].toFloat()
        assertTrue("the cap lets a crowd far outshine one star", cap <= 1.5f)
        // The control: the light used uncapped is seen.
        val uncapped = space.replaceFirst("tint * amount *", "tint * la * lum * 0.0 + tint * amount *").replaceFirst("float amount = min(la, LIGHT_CAP);", "float amount = la;")
        assertNotEquals(space, uncapped)
        assertFalse(capped(uncapped))
    }

    // -------------------------------------------------------------------------------------------
    // The colours the space mixes.
    // -------------------------------------------------------------------------------------------

    private fun linear(rgb: Int, shift: Int) = SkyShaders.linear(rgb, shift)

    private fun linearOf(rgb: Int) = floatArrayOf(linear(rgb, 16), linear(rgb, 8), linear(rgb, 0))

    private fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }

    /**
     * Green as the product draws the line everywhere it is checked: hue 75 to 165 degrees,
     * saturation at least 0.1, lightness strictly between 0.06 and 0.94 (`TickAndGreenSourceTest`,
     * after the web consoles' window). SkyColours keeps its own colours out of a wider window,
     * 45 to 195, so that a colour at that window's edge mixing a degree into it is not a green.
     */
    private fun isGreen(linear: FloatArray): Boolean {
        val c = linear.map { Math.pow(it.coerceIn(0f, 1f).toDouble(), 1 / 2.2) }
        val max = c.max()
        val min = c.min()
        val d = max - min
        if (d <= 0.0) return false
        val l = (max + min) / 2
        val s = d / (1 - Math.abs(2 * l - 1))
        var hue = when (max) {
            c[0] -> 60 * (((c[1] - c[2]) / d) % 6)
            c[1] -> 60 * ((c[2] - c[0]) / d + 2)
            else -> 60 * ((c[0] - c[1]) / d + 4)
        }
        if (hue < 0) hue += 360
        return hue >= 75 && hue <= 165 && s >= 0.1 && l > 0.06 && l < 0.94
    }

    /** The shaders' last word on colour: no more green in a pixel than red or blue. */
    private fun neverGreen(c: FloatArray) = floatArrayOf(c[0], minOf(c[1], maxOf(c[0], c[2])), c[2])

    /** Every colour the space's mixing reaches over many skies, [clamp]ed as the shader does or not. */
    private fun mixesToGreen(clamp: Boolean): Pair<Int, Int> {
        val steps = (0..10).map { it / 10f }
        var checked = 0
        var green = 0
        for (seed in 1L..300L) {
            for (choice in 0..2) {
                val look = SkyColours.of(seed * 7919L, choice)
                for (nebula in look.nebulae) {
                    val gas = nebula.map { linearOf(it) }
                    for (s in steps) for (u in steps) {
                        val c = mix(mix(gas[0], gas[1], s), gas[2], u)
                        checked++
                        if (isGreen(if (clamp) neverGreen(c) else c)) green++
                    }
                }
                val space = look.space.map { linearOf(it) }
                for (s in steps) for (u in steps) {
                    val base = mix(mix(space[0], space[1], s * 0.6f), space[2], u * 0.25f)
                    val lit = FloatArray(3) { base[it] + space[3][it] * 0.03f }
                    val grey = lit[0] * 0.3f + lit[1] * 0.4f + lit[2] * 0.3f
                    val c = mix(floatArrayOf(grey, grey, grey), lit, 0.6f)
                    checked++
                    if (isGreen(if (clamp) neverGreen(c) else c)) green++
                }
            }
        }
        return checked to green
    }

    @Test
    fun `the gas and the space never mix to a green`() {
        // The shader mixes the gas's three colours in linear light and lays the accent over the
        // space's tones. Two hues that are each allowed can mix to one that is not, a sky blue and
        // a gold to a grey-green between them, so the shaders end on a rule rather than a hope: no
        // pixel has more green in it than red or blue. Every look of every seed is mixed the way
        // the shader mixes it, through that rule, and held to the window.
        val (checked, green) = mixesToGreen(clamp = true)
        assertTrue(checked > 30_000)
        assertEquals("the space mixes to a green $green times", 0, green)
        // The control: without the rule some sky does mix to a green, so the rule is needed and the
        // sweep can see one; and the window sees a green and passes a grey.
        assertTrue("no sky mixes to a green even without the rule, so this proves nothing", mixesToGreen(clamp = false).second > 0)
        assertTrue("the window cannot see green", isGreen(linearOf(0x40A050)))
        assertFalse("the window calls grey green", isGreen(linearOf(0x808080)))
    }

    @Test
    fun `both shaders end on the rule that nothing is green`() {
        val rule = "c.g = min(c.g, max(c.r, c.b));"
        for ((name, source) in listOf("SUN" to SkyShaders.SUN, "SPACE" to SkyShaders.SPACE)) {
            val main = source.substringAfter("half4 main(float2 p)")
            assertTrue("$name lost the rule that nothing is green", main.contains(rule))
            // After every other change to the colour, so nothing after it can bring green back.
            val after = main.substringAfter(rule)
            assertFalse("$name changes a colour channel after the rule", Regex("""\bc\.[rgb]\s*=|c\s*\+=""").containsMatchIn(after))
        }
        // The control: a shader that adds light after the rule is seen.
        val late = SkyShaders.SPACE.replace("return half4(half3(c), 1.0);", "c += uAccent;\n    return half4(half3(c), 1.0);")
        assertNotEquals(SkyShaders.SPACE, late)
        assertTrue(Regex("""\bc\.[rgb]\s*=|c\s*\+=""").containsMatchIn(late.substringAfter("half4 main(float2 p)").substringAfter("c.g = min(c.g, max(c.r, c.b));")))
    }

    // -------------------------------------------------------------------------------------------
    // The text every Android 13 device has to compile.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `both shaders keep to what every Android 13 device compiles`() {
        for ((name, source) in listOf("SUN" to SkyShaders.SUN, "SPACE" to SkyShaders.SPACE)) {
            assertTrue("$name has no main", source.contains("half4 main(float2 p)"))
            assertTrue("$name lost the shared noise", source.startsWith(SkyShaders.NOISE))
            val uniforms = Regex("""(?m)^uniform\s+(\w+)\s+(\w+)\s*(\[)?""").findAll(source).toList()
            assertTrue("$name declares no uniforms", uniforms.isNotEmpty())
            for (u in uniforms) {
                assertEquals("$name declares an array of uniforms: ${u.value}", "", u.groupValues[3])
                assertTrue("$name declares a ${u.groupValues[1]}", u.groupValues[1] in setOf("float", "float2", "float3", "float4"))
            }
            // Every loop counts to a constant.
            for (loop in Regex("""for \(int \w+ = (-?\d+); \w+ <=? (-?\d+); \w+\+\+\)""").findAll(source)) {
                assertTrue(loop.value, loop.groupValues[1].toInt() <= loop.groupValues[2].toInt())
            }
            val loops = Regex("""\bfor \(""").findAll(source).count()
            val counted = Regex("""for \(int \w+ = -?\d+; \w+ <=? -?\d+; \w+\+\+\)""").findAll(source).count()
            assertEquals("$name has a loop that does not count to a constant", loops, counted)
            assertTrue("$name has no loops to check, so the check proves nothing", loops > 0)
            assertFalse("$name samples a texture", source.contains("eval("))
        }
        // The control: an array of uniforms, which Android 13 cannot be handed, is seen.
        val arrayed = Regex("""(?m)^uniform\s+(\w+)\s+(\w+)\s*(\[)?""").find("uniform float4 uLight[8];")!!
        assertEquals("[", arrayed.groupValues[3])
    }

    @Test
    fun `a star lights only the gas near it, by its own size and brightness`() {
        val width = 270f
        val dp = 0.75f
        // A point of light reaches a few dozen of its own widths, a bright one a little further.
        val small = SkyShaders.gasLightReach(2f, 1f, dp, width)
        assertEquals(2f * SkyShaders.GAS_LIGHT_WIDTHS, small, 1e-4f)
        assertTrue(SkyShaders.gasLightReach(2f, 2f, dp, width) > small)
        assertTrue(SkyShaders.gasLightReach(4f, 1f, dp, width) > small)
        // The faintest still reaches a wisp; none reaches across the screen, however close the view.
        assertEquals(SkyShaders.GAS_LIGHT_LEAST_DP * dp, SkyShaders.gasLightReach(0.01f, 0f, dp, width), 1e-4f)
        for (radius in listOf(100f, 1_000f, 100_000f)) {
            assertEquals(SkyShaders.GAS_LIGHT_MOST * width, SkyShaders.gasLightReach(radius, 2f, dp, width), 1e-4f)
        }
        // Control: unheld, the biggest sun's light would be far wider than the screen.
        assertTrue(100_000f * SkyShaders.GAS_LIGHT_WIDTHS > 10f * width)
    }

    /**
     * pow of a negative base is undefined, and phone GPUs answer it with holes in the gas (#535).
     * Every pow in both shaders takes a base that is a constant, a clamp, a max, or a value its own
     * line shows cannot be negative.
     */
    @Test
    fun `no pow is handed a base that can go negative`() {
        val safe = Regex("""^\s*(max\(|clamp\(|mu\b|1\.0 - mu\b)""")
        fun unsafe(source: String): List<String> = Regex("""pow\(""").findAll(source).map { m ->
            source.substring(m.range.last + 1).takeWhile { it != '\n' }
        }.filterNot { safe.containsMatchIn(it) }.toList()
        for ((name, source) in listOf("SPACE" to SkyShaders.SPACE, "SUN" to SkyShaders.SUN)) {
            assertEquals("$name", emptyList<String>(), unsafe(source))
        }
        assertEquals(1, unsafe("float f = pow(1.0 - abs(n * 2.0 - 1.0), 10.0);").size)
    }

    /** Every nearby star's light is added, never the brightest picked, so no hand-over draws an edge. */
    @Test
    fun `the gas adds its stars' light together`() {
        val space = SkyShaders.SPACE
        assertEquals(SkyShaders.LIGHTS, Regex("""addLight\(p, uLight\d, uLightTint\d, lc, la, lw\);""").findAll(space).count())
        assertFalse(space.contains("if (l.a > best.a)"))
        assertTrue(space.contains("min(la, LIGHT_CAP)"))
    }

    @Test
    fun `a sky's space and a sun's surface are its own, every time`() {
        val a = SkyShaders.spaceSeed(41L)
        assertEquals(a.toList(), SkyShaders.spaceSeed(41L).toList())
        assertNotEquals(a.toList(), SkyShaders.spaceSeed(42L).toList())
        for (v in a) assertTrue(v >= 0f && v < 50f)

        val identities = (1L..500L).map { Sky.identityOf(SkyKind.CHECK_IN, it) }
        val seeds = identities.map { SkyShaders.sunSeed(it) }
        assertTrue(seeds.all { it >= 0f && it < 1f })
        assertEquals(seeds, identities.map { SkyShaders.sunSeed(it) })
        assertTrue("suns share their surfaces: ${seeds.distinct().size}", seeds.distinct().size > 490)
    }
}
