package com.daymark.app.sky

/**
 * The sky itself — the faint specks that are *not* anyone's data.
 *
 * ## Why a decorative field is load-bearing rather than decoration
 *
 * `docs/SKY.md` §1 sets out the argument in full; the short form is that logging drops when things
 * are hard, so a surface that draws only data draws an inverted map of suffering. Without a field,
 * three stars in a month reads as a broken render, and a person looking at their own worst year
 * sees a blank band with a date on it. With a uniform field, a sparse month is *sky* — the same sky
 * as everywhere else — and the person's stars are the ones they made.
 *
 * ## The trap this signature exists to close
 *
 * The tempting version fills in the gaps: fewer stars here, so put more specks here, and the
 * surface looks evenly busy. That is worse than no field at all, because **compensation is a form
 * of measurement**. The density would then encode exactly the thing it was meant to stop encoding,
 * and anyone who worked out the rule could read their own lapses off the background.
 *
 * So the generator **takes a seed and a tile coordinate, and nothing else**. There is no parameter
 * of any data type — no records, no date range, no count of anything — which makes §10's property
 * P3 ("the field cannot see data") a fact about the signature rather than a claim a reviewer has to
 * verify by reading. Adding a data argument here is the change that would have to be made, in the
 * open, for the field to become measurement.
 *
 * ## Uniform by construction, not by tuning
 *
 * A speck per cell of a fixed grid, jittered inside its cell. Density is then exactly
 * [CELLS_PER_TILE]² per tile everywhere, so property P4 holds structurally: the count in any region
 * is its area times a constant, up to the boundary effect of the jitter. Poisson sampling would
 * look marginally more natural and would put the density within a tolerance rather than on the
 * nose, which is the wrong trade for the one property this surface cannot get wrong.
 *
 * ## Tiles, not one field
 *
 * The field is generated per tile from `(seed, tileX, tileY)`, so a sky spanning forty years is not
 * one enormous array and there is no visible repeat. The renderer generates the tiles it can see
 * and drops them; nothing is retained, and cost is proportional to screen area rather than to how
 * much history the person has. A person with ten years of data does not pay more for their sky than
 * a person with one.
 */
object SkyField {

    /** Specks per tile edge. 12 × 12 = 144 per tile is dense enough to read as sky at arm's length. */
    const val CELLS_PER_TILE = 12

    const val SPECKS_PER_TILE = CELLS_PER_TILE * CELLS_PER_TILE

    /**
     * How far into its own cell a speck may sit. Kept off the cell edges so two specks from
     * neighbouring cells cannot land on top of each other and read as one brighter mark — the field
     * must have no bright points in it, or the eye starts counting them as stars.
     */
    private const val JITTER_LO = 0.12f
    private const val JITTER_HI = 0.88f

    /**
     * The field's alpha range, against [SkyPalette.NIGHT_INK].
     *
     * Deliberately far below anything a data star is drawn at. The field is the single biggest
     * obstacle to finding real stars for someone with low vision (§7.1), which is why the renderer
     * must be able to switch it off entirely — see [SkyOptions.fieldEnabled]. A field bright enough
     * to be mistaken for data is not a kinder sky, it is a noisier one.
     */
    const val ALPHA_MIN = 0.05f
    const val ALPHA_MAX = 0.16f

    /**
     * One tile's specks, in tile-local coordinates in `[0, 1)`.
     *
     * Structure of arrays and not a `List<Speck>`: this is regenerated as the person scrolls, and
     * 144 short-lived objects per tile per fling is avoidable garbage on the one surface whose job
     * is to be smooth.
     */
    class Tile(val x: FloatArray, val y: FloatArray, val alpha: FloatArray) {
        val size: Int get() = x.size
    }

    /**
     * Generate the tile at `(tileX, tileY)`.
     *
     * Pure in all three arguments: the same coordinates always give the same specks, so scrolling
     * away and back does not redraw the sky differently, and two devices restoring the same backup
     * show the same background.
     */
    fun tile(seed: Long, tileX: Int, tileY: Int): Tile {
        val n = CELLS_PER_TILE
        val count = SPECKS_PER_TILE
        val x = FloatArray(count)
        val y = FloatArray(count)
        val alpha = FloatArray(count)
        // One stream per tile, started from a hash of the tile's own address, so tiles are
        // independent and can be generated in any order or discarded and regenerated.
        val stream = SkyStream(SkyRandom.mix(seed, tileX.toLong() * 0x9E37L + tileY.toLong()))
        var i = 0
        for (cellY in 0 until n) {
            for (cellX in 0 until n) {
                x[i] = (cellX + stream.nextBetween(JITTER_LO, JITTER_HI)) / n
                y[i] = (cellY + stream.nextBetween(JITTER_LO, JITTER_HI)) / n
                alpha[i] = stream.nextBetween(ALPHA_MIN, ALPHA_MAX)
                i++
            }
        }
        return Tile(x, y, alpha)
    }
}
