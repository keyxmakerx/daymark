package com.daymark.app.sky

/**
 * The lumps in the sky: a fixed, smooth distortion of the unit square that makes a scattered sky
 * clump the way a real one does.
 *
 * Import-free, like the rest of `sky/`. No clock, no random source, no mutable state.
 *
 * ## Why a warp at all
 *
 * There are no month rows (`docs/SKY.md` §3.1): a star is placed by a hash of its own identity,
 * and *when* it is from is carried by [SkyAge]'s redshift and fade instead of by where it sits.
 * That fixes the thing the rows got wrong — a hard month drawn as a visibly empty band — but a hash
 * is *uniform*, and a uniform scatter of points does not read as a sky. It reads as a texture
 * swatch, evenly grey at a distance, with no constellations and nothing to find.
 *
 * So the hashed position is pushed through a slowly-varying displacement field. Where the field
 * converges, stars crowd; where it spreads, they thin. The result has clumps and lanes and looks
 * like something rather than like output.
 *
 * ## The trap this signature exists to close, which is [SkyField]'s trap exactly
 *
 * The tempting version makes the lumps mean something: cluster a person's practices here and their
 * journal entries there, or pull a busy month together so it reads as a constellation. Every one of
 * those is the surface measuring somebody. Density would then encode how much was logged, and a
 * sparse patch would be a sparse *period* — which is the reading the rows were deleted to prevent,
 * reintroduced through the back door.
 *
 * So these functions **take a position and a seed, and nothing else**. There is no record here, no
 * kind, no date, no mood, no index and no count — not as a parameter and not in the file. The
 * clumping cannot correlate with anything about the person because there is nothing about the
 * person in scope, which makes that a fact about the signature rather than a claim a reviewer has
 * to verify by reading the body. `SkyWarpTest` scans this file's own source and fails if a data
 * word appears in it, because the change that would make the clusters mean something is a visible
 * change to a public signature and should have to be made in the open.
 *
 * ## Why the seed, and why a star still never moves
 *
 * The field is seeded from the sky's own seed ([SkySeed]) — derived once, from the person's first
 * record, and persisted forever. Two people's skies therefore clump differently, and one person's
 * sky clumps the same way on every device and every open, for ever. The seed is a constant of the
 * sky and never a property of a record, so `position = warp(hash(identity), seed)` is still fixed
 * by the star's own identity: nothing a person logs afterwards can move it.
 *
 * ## The field
 *
 * Value noise on a [LUMPS] × [LUMPS] lattice, smoothstep-interpolated, evaluated twice from two
 * independently salted channels to give a displacement in x and one in y. The lattice **wraps**, so
 * the field is seamless across the edges of the unit square and the displaced position can be taken
 * modulo 1 rather than clamped. Clamping would pile every star that fell off an edge onto that
 * edge, and a bright line down one side of the sky is a structure that invites reading.
 *
 * [WARP] is chosen so the displacement's slope stays below 1 everywhere (the interpolant's steepest
 * gradient is `1.5 × LUMPS` per unit, so the bound is `WARP × 1.5 × LUMPS < 1`). At that limit the
 * map still compresses hard enough to clump plainly, and never folds the field back over itself.
 */
object SkyWarp {

    /** Lumps across the sky, in each axis. Four is constellation-sized rather than grain-sized. */
    const val LUMPS = 4

    /**
     * How far a star may be pushed, as a fraction of the field.
     *
     * `0.13 × 1.5 × 4 = 0.78`, comfortably under the 1 at which the displacement would fold. A
     * larger value clumps harder and eventually turns the sky into blobs with gaps between them —
     * and a gap that is *always* there, in the same place, on everybody's sky, is sky; but one big
     * enough to look like a hole would be a new way to read absence into the surface.
     */
    const val WARP = 0.13f

    /** Two channels, so the x displacement and the y displacement are independent draws. */
    private const val CHANNEL_X = 0x1D2C3B4AL
    private const val CHANNEL_Y = 0x2E3D4C5BL

    /** The warped x of a point in the unit square. Pure in all three arguments. */
    fun warpedX(x: Float, y: Float, seed: Long): Float =
        wrap01(x + WARP * (noise(x, y, seed, CHANNEL_X) - 0.5f))

    /** The warped y of a point in the unit square. Pure in all three arguments. */
    fun warpedY(x: Float, y: Float, seed: Long): Float =
        wrap01(y + WARP * (noise(x, y, seed, CHANNEL_Y) - 0.5f))

    /**
     * One channel of the field at `(x, y)`, in `[0, 1]`.
     *
     * Smoothstep rather than straight bilinear: a linear interpolant has a kink at every lattice
     * line, and a kink in the displacement is a straight seam in the star density. Nothing on this
     * surface should have a straight edge that someone can notice their own data sitting against.
     */
    private fun noise(x: Float, y: Float, seed: Long, channel: Long): Float {
        val fx = x * LUMPS
        val fy = y * LUMPS
        val ix = floorToInt(fx)
        val iy = floorToInt(fy)
        val tx = smoothstep(fx - ix)
        val ty = smoothstep(fy - iy)
        val v00 = lattice(seed, channel, ix, iy)
        val v10 = lattice(seed, channel, ix + 1, iy)
        val v01 = lattice(seed, channel, ix, iy + 1)
        val v11 = lattice(seed, channel, ix + 1, iy + 1)
        val top = v00 + (v10 - v00) * tx
        val bottom = v01 + (v11 - v01) * tx
        return top + (bottom - top) * ty
    }

    /**
     * The value at one lattice point, wrapped so the field is periodic in both axes.
     *
     * `Math.floorMod` and not `%`: Kotlin's remainder is negative for a negative left operand, and
     * a lattice that indexed backwards past the origin would repeat itself mirrored.
     */
    private fun lattice(seed: Long, channel: Long, gx: Int, gy: Int): Float {
        val wx = Math.floorMod(gx, LUMPS).toLong()
        val wy = Math.floorMod(gy, LUMPS).toLong()
        return SkyRandom.unit(SkyRandom.mix(seed + channel, wy * LUMPS + wx))
    }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    private fun floorToInt(v: Float): Int {
        val truncated = v.toInt()
        return if (v < 0f && v != truncated.toFloat()) truncated - 1 else truncated
    }

    /**
     * The fractional part, in `[0, 1)`.
     *
     * The second branch is not defensive clutter: a value a hair under zero gives a fraction that
     * rounds up to exactly `1f` in `Float`, and a coordinate of `1` is outside the contract every
     * consumer of this layout relies on.
     */
    private fun wrap01(v: Float): Float {
        val fraction = (v - Math.floor(v.toDouble())).toFloat()
        return if (fraction < 0f || fraction >= 1f) 0f else fraction
    }
}
