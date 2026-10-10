package com.daymark.app.sky

/**
 * Every star's own rhythm: a scintillation that comes and goes, and on some a rare quarter-second
 * glint. All decoration, all derived from the star's identity, none of it a reading of anyone.
 *
 * Import-free, like the rest of `sky/`. **No clock and no random source**: every function here is
 * pure, and time arrives as an elapsed-millisecond count the renderer passes in. That is what makes
 * a twinkle testable, and it is the same discipline that keeps [Sky.layout] deterministic —
 * `docs/SKY.md` §0.1 puts everything the Sky decides, twinkle included (§3.6), in `sky/` so that
 * every rule is a plain-JVM test, and this is why.
 *
 * ## What a rhythm is allowed to know
 *
 * `docs/SKY.md` §3.6: *"Every value comes from the star's identity and the time, never from its
 * mood or any count, so each star has its own beat, forever. Kind matters only in that every life
 * event glints."*
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
 * `docs/SKY.md` §7.4 lists four, and each is a property of this file rather than a promise about
 * the renderer:
 *
 *  - **Everything stops under the motion switch.** [lightAt] and [alphaAt] return exactly `1`, and
 *    [glintEnvelopeAt] exactly `0`, whenever `options.motionEnabled` is false. The switch is a
 *    parameter of every time-dependent function, so a renderer cannot forget to consult it without
 *    deleting an argument. Motion carries no meaning, so a still sky loses nothing.
 *  - **At any moment only a few stars are glinting.** About one star in five ever glints
 *    ([GLINT_SHARE]), and each of those is lit for [GLINT_MS] out of a [GLINT_PERIOD_MIN_MS] to
 *    [GLINT_PERIOD_MAX_MS] cycle — a duty cycle near 2%, so roughly four stars in a thousand are
 *    glinting at any instant. `SkyTwinkleTest` counts them over a realistic population.
 *  - **A glint is under a third of a second.** [GLINT_MS] is 280.
 *  - **Nothing is ever in step with anything else.** Every rate and every phase is drawn from its
 *    own salted hash, so two stars share a beat only by a 1-in-16-million coincidence in each of
 *    four independent values.
 *
 * The scintillation is the approved phone sky's (`docs/prototypes/sky-phone.html`, `tw` in its star
 * shader), number for number (#460). Three sines at different speeds add up to a flicker that never
 * quite repeats, the way starlight wavers through air, and a slow envelope lets each star flicker
 * for a while and then hold still. Its limits are numbers, not taste: the fastest sine runs under
 * 2.3 cycles a second, well under the three a second at which flicker starts to trouble people, and
 * the light never moves by more than [FLICKER_DEPTH] × [FLICKER_SWING], under a quarter either way.
 * It is centred on the star's own light and gives back as much as it takes, so over any few
 * seconds every star is as bright as its age makes it. `docs/SKY.md` §3.6 makes twinkle
 * *"decoration only"*. A star's tint never changes at all: the glint is a coloured fringe added
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

    private const val FLICKER_SPEED_SALT = 0x1F3B5D79L
    private const val FLICKER_PHASE_SALT = 0x2A4C6E80L
    private const val EPISODE_RATE_SALT = 0x35576981L
    private const val EPISODE_PHASE_SALT = 0x40628A9CL
    private const val GLINT_SALT = 0x4B6D8FA1L
    private const val GLINT_PERIOD_SALT = 0x5678A0B2L
    private const val GLINT_OFFSET_SALT = 0x6183B1C3L

    private fun hash(kind: SkyKind, id: Long, salt: Long): Float =
        SkyRandom.unit(SkyRandom.mix(kind.ordinal.toLong() * KIND_SALT + salt, id))

    // -------------------------------------------------------------------------------------------
    // The scintillation. Every star has one.
    // -------------------------------------------------------------------------------------------

    /**
     * How much of the flicker reaches the star at the height of an episode. The approved sky's
     * `0.6 + 0.4 × smoothstep(0.6, 2, lum)` at its ordinary star's light, which is [SkyStarLight.LIGHT]
     * for every star here, so one depth for every star.
     */
    const val FLICKER_DEPTH = 0.6f

    /** Each of the three sines' share of the flicker, slowest first. They add up to [FLICKER_SWING]. */
    private val FLICKER_AMPLITUDE = doubleArrayOf(0.2, 0.12, 0.08)

    /** The most the three sines can add up to, either way. */
    const val FLICKER_SWING = 0.4f

    /**
     * Each sine's speed for the slowest star, in radians a second, and how much faster the fastest
     * star runs it. The approved sky's `3.1 + 4s`, `5.7 + 3s` and `9.3 + 5s`.
     */
    private val FLICKER_RATE_RAD_PER_S = doubleArrayOf(3.1, 5.7, 9.3)
    private val FLICKER_RATE_SPREAD_RAD_PER_S = doubleArrayOf(4.0, 3.0, 5.0)

    /** How each sine's starting point is turned from the star's one phase, so the three never line up. */
    private val FLICKER_PHASE_TURN = doubleArrayOf(1.0, 1.7, 2.3)

    /** The fastest any sine runs, in cycles a second. Under 2.3, and the tests hold it under 3. */
    val FLICKER_FASTEST_HZ: Float =
        (FLICKER_RATE_RAD_PER_S.indices.maxOf {
            FLICKER_RATE_RAD_PER_S[it] + FLICKER_RATE_SPREAD_RAD_PER_S[it]
        } / TAU).toFloat()

    /**
     * How slowly a star's flicker comes and goes, in radians a second: the approved sky's
     * `0.04 + 0.06s`, so an episode comes round every one to two and a half minutes.
     */
    const val EPISODE_RATE_MIN_RAD_PER_S = 0.04f
    const val EPISODE_RATE_SPREAD_RAD_PER_S = 0.06f

    /** Where on the slow wave the flicker starts to show, and where it is fully on. */
    private const val EPISODE_ON = 0.55
    private const val EPISODE_FULL = 0.95

    /** Where in the range of flicker speeds this star sits, `0` slowest to `1` fastest. */
    fun flickerSpeed(kind: SkyKind, id: Long): Float = hash(kind, id, FLICKER_SPEED_SALT)

    /** Where in its flicker this star starts, in radians. */
    fun flickerPhase(kind: SkyKind, id: Long): Float = hash(kind, id, FLICKER_PHASE_SALT) * TAU_F

    /** How fast this star's episodes come round, in radians a second. Fixed for the life of the record. */
    fun episodeRate(kind: SkyKind, id: Long): Float =
        EPISODE_RATE_MIN_RAD_PER_S + hash(kind, id, EPISODE_RATE_SALT) * EPISODE_RATE_SPREAD_RAD_PER_S

    /** Where in its episodes this star starts, in radians. What keeps the sky's flickers out of step. */
    fun episodePhase(kind: SkyKind, id: Long): Float = hash(kind, id, EPISODE_PHASE_SALT) * TAU_F

    // -------------------------------------------------------------------------------------------
    // The glint. About one star in five, plus every landmark.
    // -------------------------------------------------------------------------------------------

    /** About one in five, as `docs/SKY.md` §3.6 asks and the prototype draws. */
    const val GLINT_SHARE = 0.22f

    /** Seconds between one star's glints. Wide and irregular, so no two stars beat together. */
    const val GLINT_PERIOD_MIN_MS = 7000f
    const val GLINT_PERIOD_MAX_MS = 22000f

    /** How long one glint lasts. `docs/SKY.md` §7.4's rule is *"under a third of a second"*. */
    const val GLINT_MS = 280f

    /**
     * Whether this star ever glints at all.
     *
     * Every landmark does, because `docs/SKY.md` §3.4 gives the mark the person placed the one
     * prominence on this surface, and about one star in five otherwise. This is the only place kind
     * is anything other than an ordinal in a hash, and the question it answers is *who authored
     * this*, never *how much is it worth*: a goal the app watched being reached does not glint more
     * than a journal page.
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
     * How much of its own light this star gives at this instant, between `1 - FLICKER_DEPTH ×
     * FLICKER_SWING` and `1 + FLICKER_DEPTH × FLICKER_SWING`: the approved sky's `tw`.
     *
     * Light, not pixels: the approved sky multiplies a star's light by this before it is tone-mapped
     * and encoded. The renderer can only scale an already-encoded sprite, so it asks for [alphaAt].
     *
     * Returns exactly `1` when motion is off. Not "approximately": a reduced-motion sky is the
     * ordinary sky with time removed, and there is no second design to keep in step.
     */
    fun lightAt(kind: SkyKind, id: Long, elapsedMs: Long, options: SkyOptions): Float {
        if (!options.motionEnabled) return 1f
        val seconds = elapsedMs / 1000.0
        val episode = smoothstep(
            EPISODE_ON,
            EPISODE_FULL,
            0.5 + 0.5 * Math.sin(seconds * episodeRate(kind, id) + episodePhase(kind, id)),
        )
        if (episode == 0.0) return 1f
        val speed = flickerSpeed(kind, id)
        val phase = flickerPhase(kind, id).toDouble()
        var flicker = 0.0
        for (i in FLICKER_AMPLITUDE.indices) {
            val rate = FLICKER_RATE_RAD_PER_S[i] + FLICKER_RATE_SPREAD_RAD_PER_S[i] * speed
            flicker += FLICKER_AMPLITUDE[i] * Math.sin(seconds * rate + phase * FLICKER_PHASE_TURN[i])
        }
        return (1.0 + FLICKER_DEPTH * episode * flicker).toFloat()
    }

    /**
     * The alpha multiplier the renderer stamps this star's sprite with at this instant: [lightAt]
     * carried into encoded pixels, `light^(1/2.2)`. That is exactly what a change of light does to
     * an encoded pixel wherever the star is faint, which is all of it but the bead's heart, so the
     * phone flickers as much as the approved sky does and no more. Between about `0.88` and `1.10`.
     *
     * Multiplied by [SkyAge.fadeFor] at the call site, so an old star's flicker is a proportion of
     * the old star's light. Returns exactly `1` when motion is off, as [lightAt] does.
     */
    fun alphaAt(kind: SkyKind, id: Long, elapsedMs: Long, options: SkyOptions): Float {
        val light = lightAt(kind, id, elapsedMs, options)
        if (light == 1f) return 1f
        return Math.pow(light.toDouble(), 1.0 / GAMMA).toFloat()
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
     * Added and not blended, which is the reason a glint never changes what colour a star is.
     * `docs/SKY.md` §3.6: *"The star's own tint never changes."* A star that shifted hue as
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

    private const val GAMMA = 2.2

    /** `0` below [edge0], `1` above [edge1], and a smooth S between: GLSL's `smoothstep`. */
    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
