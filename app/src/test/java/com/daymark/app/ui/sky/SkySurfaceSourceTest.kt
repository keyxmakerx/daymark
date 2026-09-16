package com.daymark.app.ui.sky

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a mood is allowed to reach in the renderer, and everywhere it is not.
 *
 * `docs/SKY.md` §0.3 finding 4 states the rule the whole Sky is built around, and states it as
 * arithmetic rather than as a promise: *"What is built holds **total light constant** —
 * `haloPeakAlpha` is defined as `HALO_LIGHT / radius²`, so the product is fixed by construction and
 * not by a table someone has to keep balanced. Mood redistributes light; it never changes the
 * amount."*
 *
 * So mood reaches exactly two quantities: a star's halo **radius** and its halo **peak alpha**.
 * Total emitted light is a function of age alone. A hard day is wider and softer, a good day is
 * gathered, and both emit the same light. Mood must never reach the core's alpha, the tint, the
 * twinkle, the position, or the base brightness — any of those turns "wider" into "dimmer", which
 * `SKY.md` §0.3 records as tried and rejected once already: *"the raw ramp ranks the moods by
 * visibility, with the hardest one faintest, which is precisely the cruelty §1 exists to prevent."*
 *
 * ## Why this file exists, and what it is worth
 *
 * `app/src/main/java/com/daymark/app/sky/` is import-free and its rules are executed on a plain JVM
 * — `SkyGlyphTest` holds every function that takes a mood level to the value it must return. What
 * had no test at all was the **renderer**: `SkySurface.kt`, `SkySprite.kt` and `SkyPresentation.kt`
 * are read by nothing. A review inserted `* (0.6f + 0.08f * moodLevel)` into `SkySurface.kt`'s
 * brightness expression and the whole suite still reported no failure — every hard day drawn
 * faintest, and it would have shipped.
 *
 * Compose needs a device or Robolectric and this module has neither, so the repository's answer for
 * that shape is a structural source test: read the `.kt` file as text and assert properties over
 * it. `ui/people/PeopleUiSourceTest` and `ui/debug/DebugScreenSourceTest` are the two files this
 * follows. Reading the source is the weaker claim and it is stated as such — what is actually
 * rendered is checked by hand and by `/walkthrough`, never here.
 *
 * ## Where the rule is defended, which is not where the numbers are
 *
 * `SkyGlyph.coreRadiusDp`, `coreAlpha`, `starTint` and `starBrightness` all **take a mood level and
 * ignore it**, deliberately, so that "mood moves the halo and nothing else" is a property of a
 * signature rather than a habit of callers. That is `SkyGlyphTest`'s business and it is pinned
 * there. What is pinned here is the other half: the renderer hands the mood **whole** to those
 * functions and never does arithmetic of its own with it, so the pure layer's refusal is the last
 * word rather than a suggestion the renderer can talk over.
 *
 * ## Every absence here is shown a planted example first
 *
 * `CLAUDE.md` §5: a grep that cannot see a planted example proves only that it is blind, and that
 * is this repository's most common bug shape. So every detector below is also run over a copy of
 * the real source with the violation spliced into it, and must report it. Each mutation is derived
 * from the text that was read and is asserted to have actually changed it, because a mutation that
 * might not be a mutation is the second most common bug shape here.
 */
class SkySurfaceSourceTest {

    private companion object {

        const val SURFACE = "app/src/main/java/com/daymark/app/ui/sky/SkySurface.kt"
        const val SPRITE = "app/src/main/java/com/daymark/app/ui/sky/SkySprite.kt"
        const val PRESENTATION = "app/src/main/java/com/daymark/app/ui/sky/SkyPresentation.kt"

        /** Where [com.daymark.app.sky.SkyKind.introduction] is written — the first words of every
         * star description, so the vocabulary check has to read it too. */
        const val KINDS = "app/src/main/java/com/daymark/app/sky/Sky.kt"

        /** What the field is called. Everything below is a claim about this token. */
        const val MOOD = "moodLevel"

        /** The call that rasterises one radial gradient, matched with its opening parenthesis. */
        const val GRADIENT = "RadialGradientShader("

        /**
         * Every call in `SkySurface.kt` that a mood level is allowed to arrive at.
         *
         * Named here, one per line, so that a sixth sink is a deliberate edit somebody reviews
         * rather than a line that slid in with a feature. The set is asserted to be exactly this —
         * gaining one fails, losing one fails — because both directions are worth a second look.
         *
         *  - `DrawScope.drawStar` / `drawStar`: the declaration the mood arrives on, and the one
         *    call that passes it, read straight off `SkyLayout` with nothing done to it.
         *  - `sprites.star`: the sprite cache key. `SkySprite.kt`'s header is explicit that a
         *    sprite is keyed on mood, age bucket and temperature and on nothing else.
         *  - the three `SkyGlyph` calls: colour, brightness and the core's radius. All three take a
         *    mood level and **ignore** it; that is `SkyGlyphTest`'s claim, and what matters here is
         *    that the renderer hands the value over whole instead of pre-chewing it.
         *
         * The two functions mood genuinely moves — `haloRadiusDp` and `haloPeakAlpha` — are not on
         * this list because the surface never calls them. They are reached from `SkySprite.kt`,
         * which is where the halo is drawn, and `the halo is the one place a mood is spent` pins
         * that so this list's absences are not absences everywhere.
         */
        val MOOD_SINKS = listOf(
            "DrawScope.drawStar",
            "drawStar",
            "sprites.star",
            "SkyGlyph.starBrightness",
            "SkyGlyph.coreRadiusDp",
            "SkyGlyph.starTint",
        )

        /**
         * Anything that would mean the renderer computed with a mood rather than passed one on.
         *
         * A mood inside a permitted call is still a defect if it arrives scaled, offset or
         * compared: `coreRadiusDp(kind, moodLevel * 2)` reaches a function that ignores its mood,
         * and `elapsedMillis + moodLevel` reaches one that does not.
         */
        val ARITHMETIC = listOf("*", "+", "-", "/", "<", ">", "?")

        /**
         * Words a star description may not contain, in any form.
         *
         * `docs/SKY.md` uses every one of these about the *drawing* — "hardest one faintest",
         * "one step from dimmer", "a landmark is the one bright star" — and that is where they
         * belong. Said to a person about their own day they are a ranking: a description that
         * called a mark faint would tell someone their worst week showed up smallest, which is the
         * reading the constant-light halo exists to make impossible. §7.2 wants mood available
         * outside colour; §D1b wants it reflected back in the person's own word, not translated
         * into one about light.
         */
        val BRIGHTNESS_WORDS = listOf(
            "dim", "dimmer", "dimmest", "dimly",
            "bright", "brighter", "brightest", "brightly", "brightness",
            "faint", "fainter", "faintest", "faintly",
            "dark", "darker", "darkest",
            "pale", "paler", "dull", "duller", "vivid", "luminous", "radiant",
            "glow", "glows", "glowing", "shine", "shines", "shining",
            "sparkle", "sparkles", "sparkling", "twinkle", "twinkles", "twinkling",
            "lit", "unlit",
        )
    }

    private val surface = strip(read(SURFACE))
    private val sprite = strip(read(SPRITE))
    private val presentation = strip(read(PRESENTATION))
    private val kinds = strip(read(KINDS))

    private fun read(rel: String) = repoFile(rel).readText()

    // ---------------------------------------------------------------------------------------------
    // The guards in front of every assertion below.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the three sources were found and the stripper neither ate them nor did nothing`() {
        assertTrue("SkySurface.kt lost its composable", surface.contains("fun SkySurface("))
        assertTrue("SkySurface.kt lost the star draw", surface.contains("fun DrawScope.drawStar("))
        assertTrue("SkySprite.kt lost its cache", sprite.contains("internal class SkySprites("))
        assertTrue("SkySprite.kt lost its rasteriser", sprite.contains("fun rasterise("))
        assertTrue("SkyPresentation.kt lost its object", presentation.contains("object SkyPresentation"))
        assertTrue("Sky.kt lost the kinds", kinds.contains("enum class SkyKind("))

        // Did nothing? All three files explain the mood rule at length in prose, using the very
        // words the code is checked for. A stripper that is a no-op turns every check below into a
        // check on a comment.
        val ruleInProse = "mood moves the halo's spread"
        assertTrue("SkySurface.kt no longer states the rule in its header", read(SURFACE).contains(ruleInProse))
        assertFalse("the stripper left KDoc behind in SkySurface.kt", surface.contains(ruleInProse))
        assertTrue("SkySprite.kt no longer states the rule", read(SPRITE).contains("spread is the mood"))
        assertFalse("the stripper left KDoc behind in SkySprite.kt", sprite.contains("spread is the mood"))
        assertTrue(
            "SkyPresentation.kt no longer states the rule",
            read(PRESENTATION).contains("rather than interpreted"),
        )
        assertFalse(
            "the stripper left KDoc behind in SkyPresentation.kt",
            presentation.contains("rather than interpreted"),
        )
        for ((name, code) in sources()) assertFalse("$name still holds a doc comment", code.contains("/**"))

        // Ate it? A stripper that returned "" would satisfy every absence check in this file.
        for ((name, code) in sources()) {
            assertTrue(
                "$name is empty after stripping, so nothing below is checking anything",
                code.contains("package com.daymark.app."),
            )
        }
    }

    private fun sources() = listOf(
        "SkySurface.kt" to surface,
        "SkySprite.kt" to sprite,
        "SkyPresentation.kt" to presentation,
    )

    /**
     * The stripper itself, because everything above is a claim about what it returned.
     *
     * A scanner and not the regex in `ui/SourceText.kt`, for the reason `DebugScreenSourceTest`
     * gives: a comment opener inside a string literal makes the regex swallow the rest of the file.
     * It is written out here rather than imported so that this file's only Daymark import is
     * `repoFile`, which is what lets `tools/jvm-tests.sh` compile it without the Compose graph.
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

        val nested = strip("val a = 1 /* outer /* inner */ still comment */ val b = 2")
        assertTrue(nested.contains("val a = 1"))
        assertTrue(nested.contains("val b = 2"))
        assertFalse(nested.contains("still comment"))

        assertFalse("a block comment survived", strip("/** doc */ val c = 3").contains("doc"))
        assertTrue(strip("/** doc */ val c = 3").contains("val c = 3"))
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Where a mood may go in the surface.
    // ---------------------------------------------------------------------------------------------

    /**
     * Every mood token in `SkySurface.kt` sits in the argument list of a call on [MOOD_SINKS], and
     * arrives there whole.
     *
     * The enclosing call is found by matching parentheses backwards rather than by reading the
     * line, because the two hand-offs that matter are written one argument per line: `moodLevel =
     * moodLevel` on its own line names no sink at all, and a line-based check would have to either
     * refuse it or wave through anything else written the same way.
     */
    @Test
    fun `a mood reaches nothing in the surface but the calls named here`() {
        val sinks = moodSinksIn(surface)
        assertTrue(
            "SkySurface.kt names \"$MOOD\" fewer times than the draw path needs it, so the field " +
                "has probably been renamed and every check below is now looking for nothing",
            sinks.size >= 6,
        )

        assertEquals(
            "a mood level now reaches a call this file does not sanction. Mood moves a star's halo " +
                "radius and its halo peak alpha, and those two only; anything else it touches turns " +
                "\"wider and softer\" into \"dimmer\", which is the ranking of hard days that " +
                "SKY.md §0.3 records as already tried and rejected. If the new sink is deliberate, " +
                "add it to MOOD_SINKS and say in the commit what a mood now does there.",
            MOOD_SINKS.sorted(),
            sinks.map { it.callee }.distinct().sorted(),
        )

        for (sink in sinks) {
            val operator = ARITHMETIC.firstOrNull { sink.argument.contains(it) }
            assertNull(
                "the surface computes with a mood before handing it over: \"${squeeze(sink.argument)}\" " +
                    "into ${sink.callee}(). SkyGlyph's mood-taking functions ignore the level they " +
                    "are given, which is what makes the rule structural — a renderer that scales, " +
                    "offsets or compares the level first has taken the decision back. Operator: " +
                    "\"$operator\".",
                operator,
            )
        }
    }

    @Test
    fun `the sink list sees a mood smuggled into the brightness, into the twinkle and into an argument`() {
        // The mutation that went unnoticed: an extra factor on the brightness, mood in it.
        val statement = statementFrom(surface, "val brightness =")
        assertTrue("the brightness statement was not found, so this control proves nothing", statement.isNotEmpty())
        val dimmed = surface.replace(statement, "$statement * (0.6f + 0.08f * $MOOD)")
        assertNotEquals("the planted factor did not change the source", surface, dimmed)
        assertFalse(
            "the sink list cannot see a mood in a bare parenthesised factor, which is the exact " +
                "shape the review used to draw every hard day faintest",
            moodSinksIn(dimmed).all { it.callee in MOOD_SINKS },
        )

        // A mood reaching the star's own rhythm, inside a call that is not on the list.
        val twinkled = surface.replace(statement, statement.replace("elapsedMillis", "elapsedMillis + $MOOD"))
        assertNotEquals("the planted twinkle argument did not change the source", surface, twinkled)
        assertFalse(
            "the sink list cannot see a mood handed to SkyTwinkle",
            moodSinksIn(twinkled).all { it.callee in MOOD_SINKS },
        )

        // And a mood arriving pre-chewed at a call that IS on the list: the sink set still reads
        // clean, so the shape check is the only thing standing between this and the sky.
        val scaledCall = "SkyGlyph.coreRadiusDp(kind, $MOOD)"
        assertTrue("the core radius call has moved", surface.contains(scaledCall))
        val scaled = surface.replace(scaledCall, "SkyGlyph.coreRadiusDp(kind, $MOOD * 2)")
        assertNotEquals("the planted factor did not change the source", surface, scaled)
        val sinks = moodSinksIn(scaled)
        assertTrue(
            "this control is meant to pass the sink list and fail the shape check; it no longer " +
                "passes the sink list, so it is proving something else",
            sinks.all { it.callee in MOOD_SINKS },
        )
        assertTrue(
            "the shape check cannot see arithmetic done on a mood before it is handed over",
            sinks.any { sink -> ARITHMETIC.any { sink.argument.contains(it) } },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 2. How brightly a star burns.
    // ---------------------------------------------------------------------------------------------

    /**
     * `val brightness = fade * SkyTwinkle.alphaAt(...)` — two factors, and the mood is in neither.
     *
     * Pinned as *"exactly two top-level factors, one of them the age fade and one of them the
     * star's own beat"* rather than as the literal line, so wrapping it, renaming nothing and
     * reordering the twinkle's arguments all still pass, and a third factor does not.
     */
    @Test
    fun `a star's brightness is its age fade and its own beat, and nothing else`() {
        val fade = squeeze(statementFrom(surface, "val fade ="))
        assertTrue(
            "the age fade is no longer read from the pure layer: \"$fade\". SkyGlyph.starBrightness " +
                "is a function of age, and SkyGlyphTest is what holds it to ignoring the mood level " +
                "it is handed.",
            fade.startsWith("val fade = SkyGlyph.starBrightness("),
        )

        val statement = statementFrom(surface, "val brightness =")
        val factors = topLevelSplit(statement.substringAfter("="), '*').map { squeeze(it) }

        assertEquals(
            "a star's brightness is no longer exactly two things: \"${squeeze(statement)}\". Total " +
                "emitted light is a function of age alone — a third factor here is how a mood " +
                "reaches the amount of light rather than its distribution, and the person would see " +
                "every hard day drawn faintest.",
            2,
            factors.size,
        )
        assertEquals("the first factor is no longer the age fade", "fade", factors[0])
        assertTrue(
            "the second factor is no longer the star's own beat: \"${factors[1]}\"",
            factors[1].startsWith("SkyTwinkle.alphaAt("),
        )
        assertFalse(
            "the brightness expression names a mood. It may not: mood redistributes a star's fixed " +
                "light between width and centre, and never changes the amount.",
            statement.lowercase().contains("mood"),
        )
        for (operator in listOf('+', '-')) {
            assertEquals(
                "the brightness expression now adds or subtracts a term: \"${squeeze(statement)}\"",
                1,
                topLevelSplit(statement.substringAfter("="), operator).size,
            )
        }
    }

    @Test
    fun `the brightness check sees a mood spliced into the expression`() {
        val statement = statementFrom(surface, "val brightness =")
        assertTrue("the brightness statement was not found", statement.isNotEmpty())

        // Exactly the review's mutation.
        val dimmed = "$statement * (0.6f + 0.08f * $MOOD)"
        assertNotEquals("the planted factor did not change the statement", statement, dimmed)
        assertEquals(
            "the factor count cannot see a third factor",
            3,
            topLevelSplit(dimmed.substringAfter("="), '*').size,
        )
        assertTrue("the mood check cannot see the planted mood", dimmed.lowercase().contains("mood"))

        // And the quieter shape: the fade replaced by something the mood does move.
        val haloed = statement.replace("fade *", "SkyGlyph.haloPeakAlpha(kind, $MOOD) *")
        assertNotEquals("the planted replacement did not change the statement", statement, haloed)
        assertNotEquals(
            "the first-factor check cannot see the age fade swapped for the halo's alpha",
            "fade",
            topLevelSplit(haloed.substringAfter("="), '*').map { squeeze(it) }.first(),
        )

        // A term added rather than a factor.
        val offset = "$statement + 0.08f * $MOOD"
        assertEquals(
            "the additive check cannot see a term added to the brightness",
            2,
            topLevelSplit(offset.substringAfter("="), '+').size,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 3. The sprite: a white heart, a tight glow, and only then the halo.
    // ---------------------------------------------------------------------------------------------

    /**
     * The core's alpha comes from the pure layer whole, and the inner glow is two fixed constants.
     *
     * §1, quoted in `SkySprite.kt` at the line that draws it: *"Every star has a white heart. The
     * core is the same near-white for every star; the mood is the colour of the glow and how far it
     * spreads."* Equal presence is exactly this: whatever the day was, the point at the centre of
     * the mark is the same point.
     */
    @Test
    fun `the core is the pure layer's alpha and the inner glow is two fixed constants`() {
        val core = "paint.color = CORE_TINT.copy(alpha = SkyGlyph.coreAlpha(kind, $MOOD))"
        assertTrue(
            "the core's alpha is no longer SkyGlyph.coreAlpha handed the mood whole. " +
                "SkyGlyph.coreAlpha returns CORE_ALPHA for every mood and every kind and " +
                "SkyGlyphTest holds it there; anything multiplied onto it here is a mood reaching " +
                "the white heart, and the hardest days would have the faintest centres.",
            squeeze(sprite).contains(core),
        )

        val tint = squeeze(statementFrom(sprite, "val CORE_TINT ="))
        assertTrue(
            "the white heart is no longer a fixed colour: \"$tint\"",
            Regex("""^val CORE_TINT = Color\(0[xX][0-9a-fA-F]{8}\)$""").matches(tint),
        )

        val inner = gradientOn(sprite, "innerRadius")
        assertFalse(
            "the inner glow names a mood. It is the \"then a glow\" in \"a point, then a glow\" " +
                "and it is the same glow on every star; the halo outside it is the only part a " +
                "mood may move.",
            inner.lowercase().contains("mood"),
        )
        assertEquals(
            "the inner glow's alphas are no longer the two constants and a zero",
            listOf("0f", "INNER_GLOW_ALPHA", "INNER_GLOW_MID_ALPHA"),
            alphasIn(inner).sorted(),
        )
        for (name in listOf("INNER_GLOW_ALPHA", "INNER_GLOW_MID_ALPHA")) {
            val declared = squeeze(statementFrom(sprite, "const val $name ="))
            assertTrue(
                "$name is no longer a plain number: \"$declared\". A constant that becomes a call " +
                    "is a constant that can take a mood.",
                Regex("""^const val \w+ = \d+(\.\d+)?f$""").matches(declared),
            )
        }
    }

    @Test
    fun `the core and inner-glow checks see the halo's alpha spliced into them`() {
        val coreCall = "SkyGlyph.coreAlpha(kind, $MOOD)"
        assertTrue("the core alpha call has moved", sprite.contains(coreCall))
        val dimmedCore = sprite.replace(coreCall, "$coreCall * SkyGlyph.haloPeakAlpha(kind, $MOOD)")
        assertNotEquals("the planted factor did not change the source", sprite, dimmedCore)
        assertFalse(
            "the core check cannot see the halo's alpha multiplied onto the white heart",
            squeeze(dimmedCore).contains("paint.color = CORE_TINT.copy(alpha = $coreCall)"),
        )

        val innerAlpha = "alpha = INNER_GLOW_ALPHA"
        assertTrue("the inner glow's first stop has moved", sprite.contains(innerAlpha))
        val moodyInner = sprite.replace(innerAlpha, "alpha = SkyGlyph.haloPeakAlpha(kind, $MOOD)")
        assertNotEquals("the planted alpha did not change the source", sprite, moodyInner)
        val planted = gradientOn(moodyInner, "innerRadius")
        assertTrue("the inner-glow mood check is blind", planted.lowercase().contains("mood"))
        assertNotEquals(
            "the inner-glow alpha check is blind",
            listOf("0f", "INNER_GLOW_ALPHA", "INNER_GLOW_MID_ALPHA"),
            alphasIn(planted).sorted(),
        )

        val declaration = statementFrom(sprite, "const val INNER_GLOW_ALPHA =")
        val loosened = declaration.substringBefore("=") + "= SkyGlyph.haloPeakAlpha(kind, $MOOD)"
        assertNotEquals("the planted declaration did not change", declaration, loosened)
        assertFalse(
            "the constant check cannot see a constant turned into a call",
            Regex("""^const val \w+ = \d+(\.\d+)?f$""").matches(squeeze(loosened)),
        )
    }

    /**
     * The other half, so none of the absences above is an absence everywhere.
     *
     * A mood that reached nothing at all would pass every check in this file and draw a sky in
     * which no day looked like any other day. It is spent here, on the halo, and on the two
     * quantities `SkyGlyph` derives from `HALO_LIGHT` — `outerGlowRadiusDp` is the halo radius
     * carried out to where the fade ends, and `haloStopAlpha` is each stop's weight on the halo's
     * own peak.
     */
    @Test
    fun `the halo is the one place a mood is spent`() {
        val outer = gradientOn(sprite, "outerRadius")
        assertTrue(
            "the outer glow's stops no longer come from SkyGlyph.haloStopAlpha handed this star's " +
                "own mood, and that is the only alpha in the renderer a mood is allowed to move",
            outer.contains("SkyGlyph.haloStopAlpha(kind, $MOOD"),
        )
        assertTrue(
            "the outer glow's radius is no longer SkyGlyph.outerGlowRadiusDp handed this star's " +
                "own mood. A halo drawn at one width for every day is a sky in which a hard day " +
                "and a good one are the same mark, and the mood has gone somewhere else or " +
                "nowhere.",
            squeeze(sprite).contains("val outerRadius = SkyGlyph.outerGlowRadiusDp(kind, $MOOD)"),
        )
        assertTrue(
            "the sprite is no longer keyed on the mood, so two days that draw differently could " +
                "share one bitmap",
            squeeze(sprite).contains("val key = keyOf(landmark, quiet, $MOOD, bucket, temperature)"),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 4. What a star is called, for someone who will never see it.
    // ---------------------------------------------------------------------------------------------

    /**
     * `SkyPresentation.starDescription` says what a mark was, when, and the person's own mood word.
     * It says nothing about light.
     *
     * The screen-reader description is where the drawing's vocabulary would arrive in words, and in
     * words it is no longer a distribution of light — it is the app telling somebody their worst
     * day showed up faintest. `SkyPresentationTest` holds what the sentence *contains*; this holds
     * what it may never contain.
     *
     * What this cannot see, stated rather than glossed: the mood label itself is a lambda the caller
     * supplies from the person's own words, so it is not in this file to read.
     */
    @Test
    fun `a star in words names no brightness and no dimness`() {
        val slice = starDescriptionSource()
        val word = firstBrightnessWordIn(stringLiteralsIn(slice))
        assertNull(
            "starDescription says \"$word\". A description may name the act, the date and the " +
                "person's own mood word; it may not describe the star's light, because the light " +
                "is where a mood lives and saying it out loud turns a redistribution into a " +
                "ranking of days.",
            word,
        )

        val introductions = introductionsIn(kinds)
        assertEquals("the kind introductions could not be read, so this check is vacuous", 6, introductions.size)
        assertTrue(
            "the introduction extractor no longer finds the copy it is meant to read",
            introductions.contains("A check-in you logged."),
        )
        val leading = firstBrightnessWordIn(introductions)
        assertNull(
            "a kind's introduction says \"$leading\", and the introduction leads every star " +
                "description this surface reads out",
            leading,
        )
    }

    @Test
    fun `the vocabulary sees a planted word in a description and in an introduction`() {
        val slice = starDescriptionSource()
        assertNull(
            "these controls plant words the real source already contains, so they prove nothing",
            firstBrightnessWordIn(stringLiteralsIn(slice)),
        )

        for (sentence in listOf(
            "The brightest mark of that week.",
            "A dimmer star than the ones around it.",
            "Drawn faint, because the day was hard.",
            "It glows a little less than the others.",
        )) {
            val plantedSlice = slice + "\nparts.append(\" " + sentence + "\")\n"
            assertNotEquals("the planted sentence did not change the slice", slice, plantedSlice)
            assertTrue(
                "the vocabulary did not see the planted sentence: \"$sentence\"",
                firstBrightnessWordIn(stringLiteralsIn(plantedSlice)) != null,
            )
        }

        // And the extractor that reads the kinds, shown a seventh kind with a word in it.
        val plantedKinds = kinds + "\nSPARK(\"spark\", \"The brightest thing you did.\")\n"
        assertNotEquals("the planted kind did not change the source", kinds, plantedKinds)
        assertEquals("the introduction extractor cannot see a seventh kind", 7, introductionsIn(plantedKinds).size)
        assertTrue(
            "the vocabulary did not see the planted introduction",
            firstBrightnessWordIn(introductionsIn(plantedKinds)) != null,
        )
    }

    /**
     * Both `starDescription` overloads and nothing else, bounded by the next declaration.
     *
     * A check over the whole of `SkyPresentation.kt` would read `canvasDescription` and
     * `monthHeading` too, and the first word of a vocabulary check that covers more than it claims
     * is the first word of a vocabulary check somebody loosens.
     */
    private fun starDescriptionSource(): String {
        val marker = "fun starDescription("
        val first = presentation.indexOf(marker)
        assertTrue("SkyPresentation.kt no longer declares starDescription", first >= 0)
        val last = presentation.lastIndexOf(marker)
        assertTrue("starDescription is no longer a pair of overloads", last > first)
        val after = presentation.indexOf("\n    fun ", last + marker.length)
        val slice = presentation.substring(first, if (after < 0) presentation.length else after)

        assertFalse("the slice ran on into the rest of the file", slice.contains("fun monthHeading("))
        assertFalse("the slice ran on into the rest of the file", slice.contains("fun canvasDescription("))
        assertTrue("the slice lost the copy it is meant to read", slice.contains("Covers "))
        return slice
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the source.
    // ---------------------------------------------------------------------------------------------

    /** A mood token, the call it sits inside, and the one argument it is part of. */
    private data class Sink(val callee: String, val argument: String)

    /**
     * Every occurrence of [MOOD] in [source], with the call whose argument list it sits in.
     *
     * Parentheses are matched backwards from the token rather than the line being read, because a
     * hand-off written one argument per line — `moodLevel = moodLevel` — names no call on its own
     * line at all. A token that sits inside no call at all comes back with an empty callee, which
     * is what an ordinary expression like `brightness * moodLevel` produces and what no entry in
     * [MOOD_SINKS] matches.
     */
    private fun moodSinksIn(source: String): List<Sink> =
        Regex("""\b$MOOD\b""").findAll(source).map { match ->
            val open = enclosingOpenParen(source, match.range.first)
            if (open < 0) {
                Sink("", source.substring(match.range.first, match.range.last + 1))
            } else {
                var start = open - 1
                while (start >= 0 && isNamePart(source[start])) start--
                val callee = source.substring(start + 1, open)
                val close = matchingCloseParen(source, open)
                val inside = source.substring(open + 1, close)
                Sink(callee, argumentAt(inside, match.range.first - open - 1))
            }
        }.toList()

    /** Letters, digits, underscores and dots: what a qualified callee is spelled with. */
    private fun isNamePart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.'

    /** The index of the innermost unclosed `(` before [at], or `-1` when there is none. */
    private fun enclosingOpenParen(source: String, at: Int): Int {
        var depth = 0
        var i = at - 1
        while (i >= 0) {
            when (source[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) return i
                    depth--
                }
            }
            i--
        }
        return -1
    }

    private fun matchingCloseParen(source: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return source.length - 1
    }

    /** The comma-separated argument of [inside] that covers [offset], commas counted at depth 0. */
    private fun argumentAt(inside: String, offset: Int): String {
        var depth = 0
        var start = 0
        for (i in inside.indices) {
            when (inside[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    if (offset in start until i) return inside.substring(start, i)
                    start = i + 1
                }
            }
        }
        return inside.substring(start)
    }

    /**
     * One statement of [source] starting at [marker], run on across newlines while the parentheses
     * are unbalanced or the line ends on an operator.
     *
     * Both continuations are ordinary Kotlin formatting, and a reader that stopped at the first
     * newline would report a rewrapped expression as a shorter one — an assertion that passes
     * because it read half of what it was pointed at.
     */
    private fun statementFrom(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) return ""
        var depth = 0
        var i = start
        while (i < source.length) {
            when (source[i]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                '\n' -> if (depth == 0 && !endsOpen(source.substring(start, i))) return source.substring(start, i)
            }
            i++
        }
        return source.substring(start)
    }

    private fun endsOpen(text: String): Boolean {
        val trimmed = text.trimEnd()
        return trimmed.isEmpty() || trimmed.last() in "*+-/=(,&|."
    }

    /** [text] split on [delimiter] at parenthesis depth zero. */
    private fun topLevelSplit(text: String, delimiter: Char): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for (i in text.indices) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                delimiter -> if (depth == 0) {
                    parts.add(text.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(text.substring(start))
        return parts
    }

    /**
     * The arguments of the one `RadialGradientShader(...)` in [source] drawn at [radiusName].
     *
     * The three gradients in `SkySprite.kt` are told apart by the radius they are given — the
     * halo's, the inner glow's and a prism fringe's — which is a property of the call rather than
     * of the comment above it or the order they happen to be written in.
     */
    private fun gradientOn(source: String, radiusName: String): String {
        val found = ArrayList<String>()
        var index = source.indexOf(GRADIENT)
        while (index >= 0) {
            val open = index + GRADIENT.length - 1
            val arguments = source.substring(open + 1, matchingCloseParen(source, open))
            if (squeeze(arguments).contains("radius = $radiusName,")) found.add(arguments)
            index = source.indexOf(GRADIENT, index + 1)
        }
        assertEquals("SkySprite.kt no longer draws exactly one gradient at $radiusName", 1, found.size)
        return found.first()
    }

    /** Every `alpha = x` in [text], as the text of `x`. */
    private fun alphasIn(text: String): List<String> =
        Regex("""alpha\s*=\s*([A-Za-z0-9_.]+(?:\([^)]*\))?)""").findAll(text).map { it.groupValues[1] }.toList()

    /** Every `SkyKind` introduction, read off the constructor call of each constant. */
    private fun introductionsIn(source: String): List<String> =
        Regex("""\w+\("[a-z_]+", "([^"]*)"\)""").findAll(source).map { it.groupValues[1] }.toList()

    /**
     * Every double-quoted string literal in [text], with `"a" + "b"` joined first so a sentence
     * broken across lines is read as the one sentence it renders as. `ui/SourceText.kt`'s, written
     * out here for the reason the stripper is.
     */
    private fun stringLiteralsIn(text: String): List<String> {
        val joined = text.replace(Regex("\"\\s*\\+\\s*\""), "")
        return Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
            .findAll(joined)
            .map { it.groupValues[1] }
            .toList()
    }

    /** The first word of [BRIGHTNESS_WORDS] in any of [strings], matched whole. */
    private fun firstBrightnessWordIn(strings: List<String>): String? {
        for (text in strings) {
            val lower = text.lowercase()
            for (word in BRIGHTNESS_WORDS) {
                if (Regex("""\b""" + Regex.escape(word) + """\b""").containsMatchIn(lower)) return word
            }
        }
        return null
    }

    private fun squeeze(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    /**
     * Source with comments removed and string literals kept, character by character.
     *
     * A scanner and not a regex: a comment opener inside a string literal makes the regex version
     * delete everything to the next comment close, and string contents are exactly what the
     * vocabulary check needs to read. Kotlin's block comments nest, so the depth is counted.
     * `ui/debug/DebugScreenSourceTest` carries the same scanner and the same reasoning.
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
}
