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
 * | [CORE_RADIUS_DP] | hue — the person's own ramp colour |
 * | [CORE_ALPHA] | [haloRadiusDp] — how far the light spreads |
 * | contrast, equalised by [SkyPalette.equalised] | [haloPeakAlpha] — how concentrated it is |
 * | [HALO_LIGHT] — total light emitted | |
 *
 * So a hard day is a soft wide warm star and a good day is a tight crisp one. **Neither is
 * brighter, neither is bigger, and neither emits more light** — [haloPeakAlpha] is defined as
 * [HALO_LIGHT] divided by the halo's area, so the product is a constant by construction and not by
 * a table someone has to keep balanced. Mood is a quality of light, which is true, rather than a
 * quantity of it, which would be a verdict.
 *
 * **What was rejected on the way.** Holding peak alpha constant and varying only the radius was the
 * first attempt: it makes hard days glow *more*, which is not shaming but is still a ranking, and
 * an inverted one is no better than the original. Making mood drive nothing at all was seriously
 * considered and is the safest option — it was not taken because the mood ramp handing a person
 * their own recorded colour back is `docs/DECISIONS_2026-08.md` §D1b's "reflect, never label", and
 * dropping it would remove the one thing on the Sky that is the person's own answer rather than the
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
 * Zoom, as five named thresholds on one continuous scale.
 *
 * Nothing reflows across a zoom: every level is the same coordinates under a different transform,
 * so a star can be followed from [DRIFT] to [STAR] by eye and culling stays a contiguous index
 * range. The levels decide only what *resolves* — how much of a glyph is drawn and whether stars
 * are individually focusable.
 */
enum class SkyDetail {
    /** Years at once. Points only, no glyphs, no threads. The view you leave open. */
    DRIFT,

    /** About a season. Kind glyphs begin to resolve. */
    SEASON,

    /** One month row across the width. Full glyphs, project threads, focusable stars. */
    MONTH,

    /** One day, spread apart with leader lines. The sky continues at the edges — not a modal. */
    NIGHT,

    /** One star's detail. */
    STAR,
    ;

    companion object {
        /**
         * The level implied by how many month rows fit on screen.
         *
         * Stated as a function of *rows visible* rather than of a zoom factor so the thresholds do
         * not have to be re-tuned per screen size, and so the small-screen case (§11.6) is a number
         * this file owns rather than a surprise in the renderer.
         *
         * It never returns [STAR]: a star's detail is reached by activating a star, not by zooming
         * further into empty sky. Zoom bottoms out at [NIGHT], which is a place, not a sheet.
         */
        fun forVisibleMonths(visibleMonths: Float): SkyDetail = when {
            visibleMonths > 12f -> DRIFT
            visibleMonths > 4f -> SEASON
            visibleMonths > 1.2f -> MONTH
            else -> NIGHT
        }

        /** Glyph shape only resolves from [MONTH] inward; below that a star is a point. */
        fun drawsGlyphs(detail: SkyDetail): Boolean = detail != DRIFT

        /** Project threads are the only line on the Sky, and they vanish at [DRIFT]. */
        fun drawsThreads(detail: SkyDetail): Boolean = detail == MONTH || detail == NIGHT

        /** Stars become individually focusable at [MONTH]; above that the canvas is one node. */
        fun starsAreFocusable(detail: SkyDetail): Boolean = detail == MONTH || detail == NIGHT
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
