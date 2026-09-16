package com.daymark.app.sky

/**
 * Every star's own rhythm: a slow breathe, a faint shimmer on some, and a rare quarter-second
 * glint. All decoration, all derived from the star's identity, none of it a reading of anyone.
 *
 * Import-free, like the rest of `sky/`. **No clock and no random source**: every function here is
 * pure, and time arrives as an elapsed-millisecond count the renderer passes in. That is what makes
 * a twinkle testable, and it is the same discipline that keeps [Sky.layout] deterministic —
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §1 asks for twinkle to live *"in `sky/` so it is
 * unit-tested"*, and this is why.
 *
 * ## What a rhythm is allowed to know
 *
 * §1: *"Each star's rhythm is a hash of its own identity, the way its position is: every star
 * twinkles, each to its own beat, the same beat forever. **Never a function of mood, kind or
 * count.**"*
 *
 * So the inputs are the star's kind and its anchor record id — the same pair [Sky.layout] hashes
 * for position ([identityIdAt] hands the renderer the right id so it cannot pick a different one)
 * — and the elapsed time. Kind enters twice and only twice: as part of the identity hash, where it
 * is an ordinal being mixed and carries no ranking; and in [glints], where a landmark always
 * glints because the person placed it to be found. **No function here takes a mood level or a
 * record count, and none can be given one.** A star that twinkled faster on a good day would be a
 * score with a heartbeat.
 *
 * ## The motion-safety rules, as arithmetic
 *
 * §1 lists four, and each is a property of this file rather than a promise about the renderer:
 *
 *  - **Everything stops under the motion switch.** [alphaAt] and [scaleAt] return exactly `1`, and
 *    [glintEnvelopeAt] exactly `0`, whenever `options.motionEnabled` is false. The switch is a
 *    parameter of every time-dependent function, so a renderer cannot forget to consult it without
 *    deleting an argument. Motion carries no meaning, so a still sky loses nothing.
 *  - **At any moment only a few stars are glinting.** About one star in five ever glints
 *    ([GLINT_SHARE]), and each of those is lit for [GLINT_MS] out of a [GLINT_PERIOD_MIN_MS] to
 *    [GLINT_PERIOD_MAX_MS] cycle — a duty cycle near 2%, so roughly four stars in a thousand are
 *    glinting at any instant. `SkyTwinkleTest` counts them over a realistic population.
 *  - **A glint is under a third of a second.** [GLINT_MS] is 280.
 *  - **Nothing is ever in step with anything else.** Every period and every phase is drawn from its
 *    own salted hash, so two stars share a beat only by a 1-in-16-million coincidence in each of
 *    four independent values.
 *
 * The amplitudes are deliberately small — the breathe moves brightness by at most [BREATHE_DEPTH]
 * and size by [BREATHE_SCALE], the shimmer by [SHIMMER_DEPTH] — because §1 asks for *"a night sky,
 * not an instrument"*. A star's tint never changes at all: the glint is a coloured fringe added
 * over the star and taken away again, which is why its colours live here as separate values rather
 * than as a shift applied to [SkyAge.tintFor].
 *
 * ## A note on the salts, which is a real trap and not a style preference
 *
 * Each property mixes a **different first argument**, so each gets an independently mixed hash.
 * Salting *after* the mix — `mix(kind, id) xor SALT` — looks equivalent and is not: [SkyRandom.unit]
 * keeps only the top 24 bits of the hash, so a salt whose own top 24 bits are zero changes nothing
 * at all, and one whose top 24 bits are set produces the exact mirror, `1 - x`, of the unsalted
 * value. Two properties salted that way are the same number twice, or the same number and its
 * complement — never two draws. The decorrelation test in `SkyTwinkleTest` is the guard: it fails
 * if anyone "simplifies" these back into one hash with xors on the end.
 */
object SkyTwinkle {

    // -------------------------------------------------------------------------------------------
    // Identity.
    // -------------------------------------------------------------------------------------------

    /**
     * The id a laid-out star's rhythm is derived from: its **anchor** record id, which is the same
     * id [Sky.layout] hashed for the star's position.
     *
     * A folded star covers several records ([SkyLayout.recordCountAt]); it has one position and it
     * has one rhythm, and both come from the first id it covers. Exposed so the renderer cannot
     * reach for a different one — "the last id" or "the id of the record the sheet is showing"
     * would make a star's beat change when a record was added to its day.
     */
    fun identityIdAt(layout: SkyLayout, index: Int): Long = layout.recordIds[layout.idStart[index]]

    /**
     * Kinds are spaced [KIND_SALT] apart in the mixer's input and the per-property salts below are
     * all smaller than that gap, so no (kind, property) pair can land on another's hash input.
     */
    private const val KIND_SALT = 0x7E3A91C5L

    private const val BREATHE_PERIOD_SALT = 0x1F3B5D79L
    private const val BREATHE_PHASE_SALT = 0x2A4C6E80L
    private const val SHIMMER_SALT = 0x35576981L
    private const val SHIMMER_PHASE_SALT = 0x40628A9CL
    private const val GLINT_SALT = 0x4B6D8FA1L
    private const val GLINT_PERIOD_SALT = 0x5678A0B2L
    private const val GLINT_OFFSET_SALT = 0x6183B1C3L

    private fun hash(kind: SkyKind, id: Long, salt: Long): Float =
        SkyRandom.unit(SkyRandom.mix(kind.ordinal.toLong() * KIND_SALT + salt, id))

    // -------------------------------------------------------------------------------------------
    // The slow breathe. Every star has one.
    // -------------------------------------------------------------------------------------------

    /** Slowest and fastest breathe, in milliseconds per cycle. The prototype's 3.5 to 8 seconds. */
    const val BREATHE_PERIOD_MIN_MS = 3500f
    const val BREATHE_PERIOD_MAX_MS = 8000f

    /** How much of a star's brightness the breathe may take away at its lowest. */
    const val BREATHE_DEPTH = 0.22f

    /** How much a star grows and shrinks with the breathe: ±2%, which reads as air and not as size. */
    const val BREATHE_SCALE = 0.04f

    /** This star's breathe, in milliseconds per cycle. Fixed for the life of the record. */
    fun breathePeriodMs(kind: SkyKind, id: Long): Float =
        BREATHE_PERIOD_MIN_MS +
            hash(kind, id, BREATHE_PERIOD_SALT) * (BREATHE_PERIOD_MAX_MS - BREATHE_PERIOD_MIN_MS)

    /** Where in its own cycle this star starts, in radians. What keeps the sky out of step. */
    fun breathePhase(kind: SkyKind, id: Long): Float = hash(kind, id, BREATHE_PHASE_SALT) * TAU_F

    // -------------------------------------------------------------------------------------------
    // The shimmer. A third of stars carry one as well.
    // -------------------------------------------------------------------------------------------

    /**
     * How many stars shimmer. A third, as in the prototype.
     *
     * Drawn from its own hash rather than from the breathe phase — the prototype reuses the phase
     * (`phase > 4.2`), which is fine on a canvas of five hundred but puts every shimmering star
     * into the same third of the breathe cycle. §1 asks that *"nothing is ever in step with
     * anything else"*, so the two are independent here.
     */
    const val SHIMMER_SHARE = 1f / 3f

    /** About 1.6 seconds, from the prototype's `sin(t / 260)`. */
    const val SHIMMER_PERIOD_MS = 1634f

    /** A quarter of the breathe's depth. Faint on purpose: it is texture, not a second pulse. */
    const val SHIMMER_DEPTH = 0.05f

    /** Whether this star carries the faint quick shimmer as well as the breathe. */
    fun shimmers(kind: SkyKind, id: Long): Boolean = hash(kind, id, SHIMMER_SALT) < SHIMMER_SHARE

    /** Where in the shimmer's cycle this star starts, in radians. */
    fun shimmerPhase(kind: SkyKind, id: Long): Float = hash(kind, id, SHIMMER_PHASE_SALT) * TAU_F

    // -------------------------------------------------------------------------------------------
    // The glint. About one star in five, plus every landmark.
    // -------------------------------------------------------------------------------------------

    /** About one in five, as §1 asks and the prototype draws. */
    const val GLINT_SHARE = 0.22f

    /** Seconds between one star's glints. Wide and irregular, so no two stars beat together. */
    const val GLINT_PERIOD_MIN_MS = 7000f
    const val GLINT_PERIOD_MAX_MS = 22000f

    /** How long one glint lasts. §1's motion rule is *"under a third of a second"*. */
    const val GLINT_MS = 280f

    /**
     * Whether this star ever glints at all.
     *
     * Every landmark does, because §1 gives the mark the person placed the one prominence on this
     * surface, and about one star in five otherwise. This is the only place kind is anything other
     * than an ordinal in a hash, and the question it answers is *who authored this*, never *how
     * much is it worth*: a goal the app watched being reached does not glint more than a journal
     * page.
     */
    fun glints(kind: SkyKind, id: Long): Boolean =
        kind == SkyKind.LIFE_EVENT || hash(kind, id, GLINT_SALT) < GLINT_SHARE

    /** Milliseconds between this star's glints. */
    fun glintPeriodMs(kind: SkyKind, id: Long): Float =
        GLINT_PERIOD_MIN_MS +
            hash(kind, id, GLINT_PERIOD_SALT) * (GLINT_PERIOD_MAX_MS - GLINT_PERIOD_MIN_MS)

    /** Where in that cycle this star starts, so the glints are scattered rather than synchronised. */
    fun glintOffsetMs(kind: SkyKind, id: Long): Float =
        hash(kind, id, GLINT_OFFSET_SALT) * glintPeriodMs(kind, id)

    // -------------------------------------------------------------------------------------------
    // What the renderer asks for.
    // -------------------------------------------------------------------------------------------

    /**
     * The alpha multiplier for this star at this instant, in
     * `[1 - BREATHE_DEPTH - SHIMMER_DEPTH, 1]`.
     *
     * Multiplied by [SkyAge.fadeFor] and the glyph's own alpha at the call site — the twinkle is a
     * proportion of whatever the star's brightness already is, so an old star's breathe is as faint
     * as the old star, and a star can never be twinkled *up* past a younger one.
     *
     * Returns exactly `1` when motion is off. Not "approximately": a reduced-motion sky is the
     * ordinary sky with time removed, and there is no second design to keep in step.
     */
    fun alphaAt(kind: SkyKind, id: Long, elapsedMs: Long, options: SkyOptions): Float {
        if (!options.motionEnabled) return 1f
        val slow = wave(elapsedMs, breathePeriodMs(kind, id), breathePhase(kind, id))
        val quick =
            if (shimmers(kind, id)) wave(elapsedMs, SHIMMER_PERIOD_MS, shimmerPhase(kind, id))
            else 0.5f
        return 1f - (BREATHE_DEPTH * (1f - slow) + SHIMMER_DEPTH * (1f - quick))
    }

    /**
     * The size multiplier for this star at this instant, in
     * `[1 - BREATHE_SCALE / 2, 1 + BREATHE_SCALE / 2]`.
     *
     * The star breathes; it does not grow. Two percent either way is under a tenth of a pixel on a
     * core this size, and it exists to keep the glow from looking pasted on. A bigger swing would
     * make size mean something, and on this surface size means authorship and nothing else.
     */
    fun scaleAt(kind: SkyKind, id: Long, elapsedMs: Long, options: SkyOptions): Float {
        if (!options.motionEnabled) return 1f
        val slow = wave(elapsedMs, breathePeriodMs(kind, id), breathePhase(kind, id))
        return 1f + BREATHE_SCALE * (slow - 0.5f)
    }

    /**
     * How far into a glint this star is, `0` for not glinting, rising to `1` at the middle.
     *
     * Zero for a star that never glints, zero under the motion switch, and zero in the high-contrast
     * sky — a brief coloured flash over a small mark is exactly what someone who turned that switch
     * on is trying to get away from, and the prototype suppresses it there too.
     */
    fun glintEnvelopeAt(kind: SkyKind, id: Long, elapsedMs: Long, options: SkyOptions): Float {
        if (!options.motionEnabled || options.highContrast) return 0f
        if (!glints(kind, id)) return 0f
        val period = glintPeriodMs(kind, id)
        val shifted = elapsedMs.toDouble() + glintOffsetMs(kind, id)
        val cycles = shifted / period
        val within = (cycles - Math.floor(cycles)) * period
        if (within >= GLINT_MS) return 0f
        return Math.sin(Math.PI * within / GLINT_MS).toFloat()
    }

    // -------------------------------------------------------------------------------------------
    // The prism, as amounts rather than as decisions the renderer makes.
    // -------------------------------------------------------------------------------------------

    /**
     * The two halves of the prism flash: a red fringe and a blue one, drawn *added* on either side
     * of the star and gone again.
     *
     * Added and not blended, which is the reason a glint never changes what colour a star is. §1:
     * *"The star's own tint never changes; the glint passes over it."* A star that shifted hue as
     * it glinted would put a second, moving colour dimension on a surface whose one colour
     * dimension is age.
     */
    const val GLINT_RED = 0xFF463C
    const val GLINT_BLUE = 0x5A96FF

    private const val FRINGE_ALPHA = 0.34f
    private const val FRINGE_ALPHA_LANDMARK = 0.5f
    private const val FLARE_ALPHA = 0.16f
    private const val FLARE_ALPHA_LANDMARK = 0.3f
    private const val FRINGE_OFFSET_DP = 0.8f
    private const val FRINGE_OFFSET_DP_LANDMARK = 1.6f

    /** How wide a landmark's fringe is drawn against everything else's. */
    const val FRINGE_SCALE_LANDMARK = 1.8f

    /** The alpha of each coloured fringe at this point in a glint. */
    fun glintFringeAlpha(kind: SkyKind, envelope: Float): Float =
        (if (kind == SkyKind.LIFE_EVENT) FRINGE_ALPHA_LANDMARK else FRINGE_ALPHA) * envelope

    /** The alpha of the extra pass of the star itself, which is what makes a glint read as light. */
    fun glintFlareAlpha(kind: SkyKind, envelope: Float): Float =
        (if (kind == SkyKind.LIFE_EVENT) FLARE_ALPHA_LANDMARK else FLARE_ALPHA) * envelope

    /** How far apart the red and blue halves are pulled, in dp, at this point in a glint. */
    fun glintFringeOffsetDp(kind: SkyKind, envelope: Float): Float =
        (if (kind == SkyKind.LIFE_EVENT) FRINGE_OFFSET_DP_LANDMARK else FRINGE_OFFSET_DP) * envelope

    /** How much wider a landmark's fringe sprite is. */
    fun glintFringeScale(kind: SkyKind): Float =
        if (kind == SkyKind.LIFE_EVENT) FRINGE_SCALE_LANDMARK else 1f

    // -------------------------------------------------------------------------------------------

    private const val TAU_F = 6.2831855f
    private const val TAU = 6.283185307179586

    /**
     * One cycle of a sine, in `[0, 1]`.
     *
     * The elapsed time is reduced to a fraction of a cycle **in `Double`** before it becomes an
     * angle. A `Float` would be exact only for about the first four hours of an app's life —
     * `Float` has a 24-bit significand, so past 16.7 million milliseconds it starts rounding whole
     * milliseconds away and the breathe would visibly judder on a device left awake.
     */
    private fun wave(elapsedMs: Long, periodMs: Float, phase: Float): Float {
        val cycles = elapsedMs.toDouble() / periodMs
        val angle = TAU * (cycles - Math.floor(cycles)) + phase
        return (0.5 + 0.5 * Math.sin(angle)).toFloat()
    }
}
