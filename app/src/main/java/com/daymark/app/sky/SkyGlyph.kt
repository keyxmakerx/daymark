package com.daymark.app.sky

/**
 * What a star looks like — every number the renderer would otherwise have invented.
 *
 * The Compose layer under `ui/sky/` is a renderer and makes no decisions; this is where the
 * decisions are, in a file a plain-JVM test can execute. Sizes are in density-independent pixels,
 * alphas in `0..1`.
 *
 * ## The question this file had to answer: what does a hard day look like?
 *
 * It is the only question on this surface that can do real damage, so the reasoning is written out
 * rather than summarised.
 *
 * **What a bad version does.** Size and brightness track mood. Good days are big bright glinting
 * stars; hard days are small dim dots. It is the obvious design, it looks good in a mockup, and
 * `ui/components/YearInStarsGrid.kt` ships it today — radius 1.7 dp at level 1 rising to 3.9 dp at
 * level 5, glow discs and cross-rays added only for levels 4 and 5. Its own docstring states the
 * intent: *"the amount of twinkle itself reads as how a stretch of life went."*
 *
 * **What that hands a person.** Someone opens this in a bad week, which is exactly when they will
 * open it, and is shown their worst months rendered as their faintest ones. It is a picture that
 * says the days you barely got through are the days that barely count. It is not a report they can
 * argue with, because it is not stated — it is a shape, and it is the shape of themselves. A person
 * who stopped for three weeks and came back must not be shown evidence of failure, and a person who
 * kept going through three bad weeks must not be shown their effort drawn as a whisper.
 *
 * **What is built instead.** Mood changes the *character* of a star's light and never its
 * *presence*:
 *
 * | Fixed for every mood, every kind | Varies with mood |
 * |---|---|
 * | [CORE_RADIUS_DP] | [haloRadiusDp] — how far the light spreads |
 * | [CORE_ALPHA] | [haloPeakAlpha] — how concentrated it is |
 * | colour — [SkyAge.tintFor], which is age | |
 * | brightness — [SkyAge.fadeFor], which is age | |
 * | [HALO_LIGHT] — total light emitted | |
 *
 * So a hard day is a soft wide warm star and a good day is a tight crisp one. **Neither is
 * brighter, neither is bigger, and neither emits more light** — [haloPeakAlpha] is defined as
 * [HALO_LIGHT] divided by the halo's area, so the product is a constant by construction and not by
 * a table someone has to keep balanced. Mood is a quality of light, which is true, rather than a
 * quantity of it, which would be a verdict.
 *
 * ## What changed in September 2026: colour left
 *
 * The right-hand column used to start with *hue — the person's own ramp colour*, and it does not
 * any more. `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §1 moves colour onto age
 * ([SkyAge]) and states the remainder flatly: *"Mood is the character of the light and nothing
 * else... Mood never touches brightness or colour."*
 *
 * The halo is therefore the **only** thing on a star that mood moves, and this file is where that
 * is enforced: [starTint] and [starBrightness] take a mood level and ignore it, the way
 * [coreRadiusDp] has always taken a kind and a mood and ignored both, so the sweep in
 * `SkyGlyphTest` has something to sweep. Nothing was lost in the move that the person can see —
 * their own mood colour is still on every row of the list and on the sheet when a star is tapped,
 * where it is a word with a colour beside it rather than a hue someone could read off the sky over
 * their shoulder.
 *
 * **What was rejected on the way.** Holding peak alpha constant and varying only the radius was the
 * first attempt: it makes hard days glow *more*, which is not shaming but is still a ranking, and
 * an inverted one is no better than the original. Making mood drive nothing at all was seriously
 * considered and is the safest option — it was not taken because handing a person their own
 * recorded answer back is `docs/DECISIONS_2026-08.md` §D1b's "reflect, never label", and dropping
 * it entirely would remove the one thing on the Sky that is the person's own answer rather than the
 * app's observation. The variation that remains is deliberately small (a 14% spread in radius) so
 * it reads as texture at a glance and resolves into meaning only up close.
 *
 * **What no glyph varies with.** Nothing here is a function of how much was logged. A star holding
 * three folded records ([SkyLayout.recordCountAt]) is drawn at exactly the same size as one holding
 * a single record — a bigger star for a busier day would rank days by output, which is the
 * productivity reading of a journal and is what §6.2 forbids.
 */
object SkyGlyph {

    // ---------------------------------------------------------------------------------------
    // The core. One radius, one alpha, for every kind and every mood. This is mechanism M4.
    // ---------------------------------------------------------------------------------------

    const val CORE_RADIUS_DP = 1.9f

    const val CORE_ALPHA = 1.0f

    /**
     * The core, as a function of everything it is *not* allowed to depend on.
     *
     * Both arguments are ignored, and that is the point: the renderer asks for a core by kind and
     * mood, gets the same answer every time, and `SkyGlyphTest` sweeps both axes and fails the
     * moment either one starts to matter. Exposing the constant alone would leave the rule
     * unenforceable — a future change would add a branch at the call site, where no test is looking.
     * The signature is the place the rule can be defended.
     */
    @Suppress("UNUSED_PARAMETER")
    fun coreRadiusDp(kind: SkyKind, moodLevel: Int): Float = CORE_RADIUS_DP

    /** As [coreRadiusDp], and for the same reason. */
    @Suppress("UNUSED_PARAMETER")
    fun coreAlpha(kind: SkyKind, moodLevel: Int): Float = CORE_ALPHA

    /**
     * Every star is a 48 dp target however small it is drawn — the platform minimum, applied to the
     * drawn size rather than derived from it. Where targets overlap the renderer resolves to the
     * nearest core, and never by growing a star.
     */
    const val TOUCH_TARGET_DP = 48f

    // ---------------------------------------------------------------------------------------
    // The halo. The only thing mood moves.
    // ---------------------------------------------------------------------------------------

    /** `1..5`, matching `model/Mood.level`. */
    const val MOOD_NONE = 0
    const val MOOD_MIN = 1
    const val MOOD_MAX = 5

    private const val HALO_RADIUS_HARDEST_DP = 4.8f
    private const val HALO_RADIUS_STEP_DP = 0.3f

    /** The halo radius of a star with no mood attached — the middle of the range. */
    const val HALO_RADIUS_NEUTRAL_DP = 4.2f

    /**
     * Total halo light, as `peak alpha × radius²`, held constant across the whole ramp.
     *
     * The value is fixed by the neutral point: a mid-ramp star has a halo of
     * [HALO_RADIUS_NEUTRAL_DP] at [HALO_PEAK_ALPHA_NEUTRAL], and every other level is that same
     * product redistributed.
     */
    const val HALO_PEAK_ALPHA_NEUTRAL = 0.30f
    const val HALO_LIGHT = HALO_PEAK_ALPHA_NEUTRAL * HALO_RADIUS_NEUTRAL_DP * HALO_RADIUS_NEUTRAL_DP

    /**
     * Widest at level 1, tightest at level 5. An unattached mood ([MOOD_NONE]) and any level
     * outside `1..5` sit at the neutral radius: a star with no mood is not a star at the bad end of
     * the scale, and a level this version does not recognise must not be guessed at.
     */
    fun haloRadiusDp(moodLevel: Int): Float {
        if (moodLevel < MOOD_MIN || moodLevel > MOOD_MAX) return HALO_RADIUS_NEUTRAL_DP
        return HALO_RADIUS_HARDEST_DP - (moodLevel - MOOD_MIN) * HALO_RADIUS_STEP_DP
    }

    /** [HALO_LIGHT] spread over [haloRadiusDp]. Never called with a radius of zero. */
    fun haloPeakAlpha(moodLevel: Int): Float {
        val r = haloRadiusDp(moodLevel)
        return HALO_LIGHT / (r * r)
    }

    // ---------------------------------------------------------------------------------------
    // Temperature. A star's own warmth, from its identity — variety that says nothing.
    //
    // `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §1: "Each star also has its own temperature from
    // its identity, icy, white, pale gold or peach, mixed about a third into its age tint, so the
    // sky is varied and the redshift still reads." A real field of stars is not one hue at one
    // distance, and a sky that were would look printed.
    //
    // It is drawn from the hash of the star's own identity, like its position and its rhythm, so
    // it is FIXED FOREVER AND MEANS NOTHING. There are exactly four and they are close together:
    // the mix is a third, so an old star stays plainly old whichever one it drew. Anything
    // stronger and temperature would start to compete with age, which is the one thing colour is
    // allowed to say.
    // ---------------------------------------------------------------------------------------

    /** Icy, white, pale gold, peach. */
    const val TEMPERATURE_COUNT = 4

    private val TEMPERATURE_TINT = intArrayOf(0xC8D7FF, 0xFFFFFF, 0xFFEEC8, 0xFFD6BE)

    /** How much of the temperature is mixed into the age tint. The prototype's 0.35. */
    const val TEMPERATURE_MIX = 0.35f

    private const val TEMPERATURE_KIND_SALT = 0x6B2D7F19L
    private const val TEMPERATURE_SALT = 0x11A3C5E7L

    /**
     * Which temperature this star drew, `0..TEMPERATURE_COUNT - 1`: icy, white, pale gold, peach.
     *
     * Weighted the prototype's way — white is the commonest, peach the rarest — because an evenly
     * split field reads as four groups rather than as one field with variety in it.
     */
    fun temperatureIndex(kind: SkyKind, id: Long): Int {
        val seed = kind.ordinal.toLong() * TEMPERATURE_KIND_SALT + TEMPERATURE_SALT
        val h = SkyRandom.unit(SkyRandom.mix(seed, id))
        return when {
            h < 0.30f -> 1
            h < 0.55f -> 0
            h < 0.80f -> 2
            else -> 3
        }
    }

    /** The tint of a temperature, packed `0xRRGGBB`. Out-of-range indices clamp rather than throw. */
    fun temperatureTint(index: Int): Int =
        TEMPERATURE_TINT[index.coerceIn(0, TEMPERATURE_COUNT - 1)]

    // ---------------------------------------------------------------------------------------
    // What a star is actually drawn in. Both of these take a mood and both of them ignore it.
    // ---------------------------------------------------------------------------------------

    /**
     * A star's colour: its age tint, warmed a third of the way toward its own temperature.
     *
     * [moodLevel] is accepted and **ignored**, exactly as in [coreRadiusDp], and for exactly the
     * same reason: this is the signature the renderer calls, so this is where the rule *colour is
     * never a function of mood* can be defended. `SkyGlyphTest` sweeps the whole ramp through it
     * and fails the moment the answer moves.
     *
     * A landmark is white at every age and takes no temperature at all — [SkyAge.tintFor] exempts
     * it from the redshift, and mixing a temperature into white would tint the one star that is
     * meant to be the sky's plainest.
     */
    @Suppress("UNUSED_PARAMETER")
    fun starTint(kind: SkyKind, id: Long, ageYears: Float, moodLevel: Int): Int {
        val base = SkyAge.tintFor(kind, ageYears)
        if (kind == SkyKind.LIFE_EVENT) return base
        return mix(base, temperatureTint(temperatureIndex(kind, id)), TEMPERATURE_MIX)
    }

    /**
     * How brightly a star burns before the twinkle and the glyph's own alphas: [SkyAge.fadeFor].
     *
     * [moodLevel] is accepted and ignored, as in [starTint]. **This is the assertion the whole
     * surface rests on** — a person who logged through a bad month must not find that month drawn
     * fainter than any other — and it is now true by construction, because the only input here is
     * how long ago the act was.
     */
    @Suppress("UNUSED_PARAMETER")
    fun starBrightness(kind: SkyKind, ageYears: Float, moodLevel: Int): Float =
        SkyAge.fadeFor(kind, ageYears)

    // ---------------------------------------------------------------------------------------
    // The landmark. The one exception, and it follows authorship rather than measurement.
    // ---------------------------------------------------------------------------------------

    /**
     * How much bigger a landmark's core is drawn than every other star's.
     *
     * §1: *"A landmark is the one bright star. A life event is a mark the person placed to be
     * found, so it alone is bigger, brighter, spiked, never redshifted and never faded. Brightness
     * may follow a mark the person placed, never anything the app measured: a reached goal keeps
     * its glint at lean-in and a journal page stays the size of everything else."*
     *
     * So this is a scale on [CORE_RADIUS_DP] and not a second core radius, and it is a function of
     * kind alone — a life event's core does not know what mood or what date it is, and no other
     * kind can reach this branch. [coreRadiusDp] stays constant across every mood and every kind
     * and keeps its own tests; a landmark is louder *around* that constant, never by moving it.
     */
    const val LANDMARK_CORE_SCALE = 1.9f

    /** [LANDMARK_CORE_SCALE] for a life event, `1` for everything else. Never a function of mood. */
    fun coreScale(kind: SkyKind): Float =
        if (kind == SkyKind.LIFE_EVENT) LANDMARK_CORE_SCALE else 1f

    /** A landmark's halo: wider than the widest mood, and the one halo mood does not size. */
    const val LANDMARK_HALO_RADIUS_DP = 6.5f

    /**
     * A landmark's halo peak — brighter than any other star's, which is the *"brighter"* in §1.
     *
     * Not [HALO_LIGHT] redistributed: a landmark genuinely emits more light than the stars around
     * it, which is the whole of its job. That is allowed here and nowhere else because it follows
     * a mark the person placed by hand.
     */
    const val LANDMARK_HALO_PEAK_ALPHA = 0.5f

    /** [haloRadiusDp], with the landmark exception applied. Mood still sizes every other star. */
    fun haloRadiusDp(kind: SkyKind, moodLevel: Int): Float =
        if (kind == SkyKind.LIFE_EVENT) LANDMARK_HALO_RADIUS_DP else haloRadiusDp(moodLevel)

    /** [haloPeakAlpha], with the landmark exception applied. */
    fun haloPeakAlpha(kind: SkyKind, moodLevel: Int): Float =
        if (kind == SkyKind.LIFE_EVENT) LANDMARK_HALO_PEAK_ALPHA else haloPeakAlpha(moodLevel)

    /** A straight-line blend of two packed colours, `t` of the way from [from] to [to]. */
    private fun mix(from: Int, to: Int, t: Float): Int {
        val r = mixChannel((from shr 16) and 0xFF, (to shr 16) and 0xFF, t)
        val g = mixChannel((from shr 8) and 0xFF, (to shr 8) and 0xFF, t)
        val b = mixChannel(from and 0xFF, to and 0xFF, t)
        return (r shl 16) or (g shl 8) or b
    }

    private fun mixChannel(from: Int, to: Int, t: Float): Int {
        val value = Math.round(from + (to - from) * t)
        return if (value < 0) 0 else if (value > 255) 255 else value
    }

    // ---------------------------------------------------------------------------------------
    // Kind. Carried by form, never by colour and never by motion.
    // ---------------------------------------------------------------------------------------

    /**
     * Cross-rays, and how many.
     *
     * A function of [SkyKind] alone, and it is written to take a kind so that it *cannot* become a
     * function of mood without changing the signature. `YearInStarsGrid` gives rays to levels 4 and
     * 5 only; rays here are the mark of *a goal reached* and *a life event the person placed*, which
     * are acts, not moods. A reward glint on good days is a score, and this surface does not score.
     */
    fun rayCount(kind: SkyKind): Int = when (kind) {
        SkyKind.GOAL_REACHED -> 4
        SkyKind.LIFE_EVENT -> 4
        else -> 0
    }

    /** The open ring around a practice star, and the outer ring on a life event. `0` = no ring. */
    fun ringRadiusDp(kind: SkyKind): Float = when (kind) {
        SkyKind.PRACTICE -> 4.4f
        SkyKind.LIFE_EVENT -> 7.0f
        else -> 0f
    }

    /** The short rule under a journal star. `0` = none. */
    fun underlineWidthDp(kind: SkyKind): Float = if (kind == SkyKind.JOURNAL) 5.2f else 0f

    /**
     * The stub a project step carries whether or not it has a thread to draw. `0` = none.
     *
     * Added because the silhouette sweep in `SkyGlyphTest` caught a hole: the design distinguishes
     * a project step from a check-in by the hairline **thread** back to the previous step, and a
     * thread is a property of a *pair* of stars, not of a star. The first step of any project has
     * nothing to connect to, so under that design it was drawn as a plain core — identical to a
     * check-in. Every project's first step, mislabelled, on a surface where kind is the only thing
     * form carries.
     *
     * So the link is part of the glyph. The stub points the way a thread would go and is drawn
     * alone when there is no previous step, which makes "a link" readable from one star.
     */
    fun threadStubDp(kind: SkyKind): Float = if (kind == SkyKind.PROJECT_STEP) 3.0f else 0f

    /**
     * The one kind allowed to be visually louder, and the ratio by which.
     *
     * Prominence follows **authorship**, not value (§3.4). A life event is louder because the person
     * decided it mattered and typed it; nothing the software creates outranks anything else the
     * software creates. The ray length is the only thing that grows — the core does not, so M4 still
     * holds across every star on the surface.
     */
    fun rayLengthDp(kind: SkyKind): Float = if (kind == SkyKind.LIFE_EVENT) 9.5f else 5.0f
}

/**
 * Zoom, as three named thresholds on one continuous scale.
 *
 * Nothing reflows across a zoom: every level is the same coordinates under a different transform,
 * so a star can be followed from [FAR] to [CLOSE] by eye. The levels decide only what *resolves* —
 * how much of a glyph is drawn and whether stars are individually focusable.
 *
 * ## Why these are distances and no longer spans of time
 *
 * They were `DRIFT`, `SEASON`, `MONTH`, `NIGHT` and `STAR`, and each named how much *time* the
 * viewport held, because the sky was a timeline with a row per month.
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §1.0 removed the rows: position carries no time at all
 * any more, and a level called `MONTH` would be naming something that is not on the screen. So a
 * level is how close someone is leaning in, which is the only thing zoom still means.
 *
 * **A level is a function of zoom alone and never of how many stars are on screen.** Deriving it
 * from density would make what the surface draws a function of how much somebody logged — the same
 * mistake, one layer up, that deleting the rows was meant to fix.
 */
enum class SkyDetail {
    /** The whole sky at once. Points only, no glyphs, no threads. The view you leave open. */
    FAR,

    /** Leaning in. Kind marks begin to resolve and stars become individually focusable. */
    NEAR,

    /** Close. Full glyphs and project threads. */
    CLOSE,
    ;

    companion object {
        /** Where kind marks start to resolve, as a zoom factor. */
        const val NEAR_ZOOM = 2.5f

        /** Where the full glyph resolves. */
        const val CLOSE_ZOOM = 7f

        /**
         * The level implied by the zoom factor, where `1` is the whole field in the viewport.
         *
         * Stated as a function of *zoom* rather than of anything measured in pixels so the
         * thresholds do not have to be re-tuned per screen size, and so the small-screen case
         * (§11.6) is a number this file owns rather than a surprise in the renderer.
         *
         * There is no level for one star: a star's detail is reached by activating it, not by
         * zooming further into empty sky. Zoom bottoms out at [CLOSE], which is a place, not a
         * sheet.
         */
        fun forZoom(zoom: Float): SkyDetail = when {
            zoom < NEAR_ZOOM -> FAR
            zoom < CLOSE_ZOOM -> NEAR
            else -> CLOSE
        }

        /** Glyph shape only resolves from [NEAR] inward; above that a star is a point. */
        fun drawsGlyphs(detail: SkyDetail): Boolean = detail != FAR

        /** Project threads are the only line on the Sky, and they resolve last. */
        fun drawsThreads(detail: SkyDetail): Boolean = detail == CLOSE

        /** Stars become individually focusable at [NEAR]; above that the canvas is one node. */
        fun starsAreFocusable(detail: SkyDetail): Boolean = detail != FAR
    }
}

/**
 * The switches the Sky's own controls own, as data rather than as scattered `if`s in the renderer.
 *
 * Every default is the ordinary sky, and every one of them is one tap from the surface itself
 * rather than buried in Settings, because the person who needs them needs them *here*.
 */
data class SkyOptions(
    /**
     * The decorative field. Off is the low-vision presentation ("quiet sky", §7.1): the field is
     * the single biggest impediment to finding real stars, and switching it off is the one change
     * that helps most. It follows the platform contrast preference as a *default* and stays
     * independently toggleable, because the platform signal is coarse.
     */
    val fieldEnabled: Boolean = true,
    /**
     * When false: no drift, no parallax, no twinkle, and zoom transitions are instant cuts. Motion
     * never carries meaning (§3.3), so nothing is lost by removing it — that is the property which
     * makes reduced motion a rendering switch rather than a second design.
     */
    val motionEnabled: Boolean = true,
    /** Maximum contrast, thicker strokes, no halos. Pairs with `fieldEnabled = false`. */
    val highContrast: Boolean = false,
)
