package com.daymark.app.sky

/**
 * The Sky's only source of scatter, and the reason none of it is random.
 *
 * Import-free, like `stats/InterruptionBudget.kt`, `export/ReportLayout.kt`, `goals/GoalBoard.kt`
 * and `data/ImageStrip.kt`, so every rule the Sky follows is decided in a file a plain-JVM unit
 * test can reach.
 *
 * ## Why not `java.util.Random`
 *
 * The pitch for this surface is that it is *the person's*: the same history draws the same sky,
 * every time, on every device, forever. That is not an aesthetic preference — it is what makes the
 * sky an artefact of a life rather than a screensaver. Three things follow, and each rules out a
 * platform RNG:
 *
 *  - **`Math.random()` and `java.util.Random()` are seeded from the clock.** A sky drawn from them
 *    is different every time it is opened, which means it is a picture of nothing.
 *  - **A seeded `java.util.Random` is deterministic but is a *stream*.** Position would depend on
 *    how many stars were drawn before, so inserting one record in 2019 would move every star after
 *    it. `docs/SKY.md` §3.1 calls that out as the property the whole "place" framing rests on: a
 *    star never moves. So positions come from a *hash of the star's own identity* ([mix]), not from
 *    a stream, and the arithmetic is written out here rather than delegated so it cannot change
 *    under a platform update.
 *  - **`Random.nextInt` and friends are not specified to be stable across JDK versions** in the way
 *    an explicitly written mixer is. The mixer below is fixed arithmetic on `Long`, which Kotlin
 *    defines as wrapping two's complement, so it produces the same bits everywhere.
 *
 * ## The algorithm
 *
 * SplitMix64 — the finalising mixer from Steele/Lea/Flood, as used by `SplittableRandom`. Chosen
 * because it is four lines, has no state to get wrong, passes the usual avalanche tests, and can be
 * used two ways from the same core: as a stateless hash ([mix]) for star placement, and as a
 * stream ([SkyStream]) for the decorative field, which genuinely wants a sequence.
 *
 * The three constants are written as negated hex because Kotlin has no unsigned `Long` literal;
 * `-0x61C8864680B583EBL` is the two's-complement of `0x9E3779B97F4A7C15`, and so on.
 * `SkyRandomTest` checks the output against the published SplitMix64 vectors, so a typo in one of
 * them is a test failure and not a subtly worse sky.
 */
object SkyRandom {

    private const val GAMMA = -0x61C8864680B583EBL // 0x9E3779B97F4A7C15
    private const val MIX_A = -0x40A7B892E31B1A47L // 0xBF58476D1CE4E5B9
    private const val MIX_B = -0x6B2FB644ECCEEE15L // 0x94D049BB133111EB

    /** SplitMix64's finaliser: a bijection on 64 bits with good avalanche. Stateless on purpose. */
    fun mix(value: Long): Long {
        var z = value
        z = (z xor (z ushr 30)) * MIX_A
        z = (z xor (z ushr 27)) * MIX_B
        return z xor (z ushr 31)
    }

    /**
     * A hash of two values. [b] is multiplied by the golden-ratio gamma before folding so that the
     * common case — a small ordinal and a small row id — does not collide with the equally common
     * case of the two swapped.
     */
    fun mix(a: Long, b: Long): Long = mix(a xor (b * GAMMA))

    /**
     * The top 24 bits of a hash as a `Float` in `[0, 1)`.
     *
     * 24 bits and not 32: `Float` has a 24-bit significand, so any more would be discarded on the
     * conversion and two hashes that differ only in the discarded bits would compare equal after
     * rounding. Taking the *top* bits and not the bottom ones matters too — the low bits of a
     * multiply-based mixer are the weakest.
     */
    fun unit(hash: Long): Float = (hash ushr 40).toFloat() / UNIT_DIVISOR

    /** [unit] mapped onto `[lo, hi)`. */
    fun between(hash: Long, lo: Float, hi: Float): Float = lo + unit(hash) * (hi - lo)

    private const val UNIT_DIVISOR = 16_777_216f // 2^24
}

/**
 * SplitMix64 as a sequence, for the one thing on the Sky that wants a sequence: the decorative
 * field ([SkyField]), which is generated once per tile and never per star.
 *
 * Deliberately *not* used for star placement — see the second bullet in [SkyRandom]'s header. A
 * class rather than a function so the field generator cannot accidentally be handed a shared,
 * already-advanced instance: every tile makes its own from its own seed.
 */
class SkyStream(seed: Long) {

    private var state: Long = seed

    fun nextLong(): Long {
        state += GAMMA
        return SkyRandom.mix(state)
    }

    fun nextUnit(): Float = SkyRandom.unit(nextLong())

    fun nextBetween(lo: Float, hi: Float): Float = lo + nextUnit() * (hi - lo)

    private companion object {
        const val GAMMA = -0x61C8864680B583EBL
    }
}

/**
 * Where the Sky's seed comes from.
 *
 * The brief is that the sky is seeded from the person's own data. Star *positions* already are —
 * they are a hash of each record's kind and row id ([SkyRandom.mix]), so two people with different
 * histories get visibly different skies with no seed involved at all, and that is the part of the
 * uniqueness that matters.
 *
 * The decorative field is the part that needs a seed, and it needs one with an awkward property:
 * derived from the person, but **never changing afterwards**. A field re-derived from the whole
 * history would redraw the entire background every time the person logged anything, and a place
 * whose walls move is not a place (`docs/SKY.md` §3.1). So [forFirstRecord] is a *one-time*
 * derivation: the caller computes it once, when the first record exists, persists it, and passes
 * the persisted value from then on. This object never re-derives it, and nothing here can, because
 * [Sky.layout] does not take a seed at all.
 *
 * Deleting that first record must not reseed the field — deletion "leaves no shape" (§2.1), and a
 * background that changes is a shape. That is a rule about the persistence the caller owns, stated
 * here because this is where someone would look for it.
 */
object SkySeed {

    /**
     * The field seed before the person has made anything. A fixed value, not a random one: a new
     * install has no data to be unique from, and inventing uniqueness for someone with no history
     * would be a small lie told by the surface whose whole claim is that it does not invent.
     */
    const val EMPTY_SKY = 0x5B1E5EEDL

    /** The one-time derivation. Call once, persist the result, never call again. */
    fun forFirstRecord(record: SkyRecord): Long =
        SkyRandom.mix(record.id, record.epochDay * 6L + record.kind.ordinal.toLong())
}
