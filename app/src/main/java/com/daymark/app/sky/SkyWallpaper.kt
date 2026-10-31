package com.daymark.app.sky

/**
 * A background for the phone's lock or home screen, made from the sky (#534, `DECISIONS.md` §D11).
 *
 * What it must keep true:
 * - **Space only is the seed and nothing else.** [spaceLights] and [spaceView] take the sky's seed,
 *   a view number and the screen's shape, and nothing about any memory: no star, date or count can
 *   reach a background of space alone, so a lock screen seen by anyone says nothing about the
 *   person. The few points that light its gas are placed by the seed and stand for nothing.
 * - **With my stars is the sky as it is**, and before the first one is set the person reads
 *   [STARS_NOTE] once (`docs/SKY.md` §6.4, §6.5).
 * - It is drawn by the sky's own space shader; nothing is generated.
 *
 * Import-free, like the rest of `sky/`.
 */
object SkyWallpaper {

    enum class Mode { SPACE_ONLY, WITH_STARS }

    /**
     * Said once, before the first background with stars is set: the sky's own privacy rule (§6.5)
     * in the words its export rule asks for (§6.4).
     */
    const val STARS_NOTE = "Your sky is a picture of your own record. Anyone who sees your screen " +
        "sees its shape, and its shape can say what its stars do not to someone who knows you. " +
        "Space only shows your sky's colours and none of your stars."

    /** How many points light a background of space alone. */
    const val SPACE_LIGHTS = 5

    /** How far a view of space alone is zoomed in, least and most, in screen widths across the sky. */
    const val SPACE_LEAST_ZOOM = 1.6f
    const val SPACE_MOST_ZOOM = 5f

    /** A view of space alone: its middle, in the sky's units, and how many screen widths across. */
    class View(val centreX: Float, val centreY: Float, val across: Float)

    /**
     * The [n]th view of space alone. "Another view" asks for the next one; each is the same every
     * time for the same seed.
     */
    fun spaceView(seed: Long, n: Int): View {
        val random = SkyStream(SkyRandom.mix(seed xor VIEW_SALT, n.toLong()))
        return View(
            centreX = random.nextBetween(0.15f, 0.85f),
            centreY = random.nextBetween(0.15f, 1.6f),
            across = random.nextBetween(SPACE_LEAST_ZOOM, SPACE_MOST_ZOOM),
        )
    }

    /**
     * The star the [n]th view with stars is framed on: one in sight, by the seed and the view
     * number, and never chosen for its day, kind or mood. -1 for a sky with nothing in sight.
     */
    fun starView(layout: SkyLayout, seed: Long, n: Int): Int {
        val shown = layout.shownCount
        if (shown <= 0) return -1
        var k = (SkyRandom.unit(SkyRandom.mix(seed xor STAR_SALT, n.toLong())) * shown).toInt().coerceIn(0, shown - 1)
        for (i in 0 until layout.starCount) {
            if (!layout.isShown(i)) continue
            if (k-- == 0) return i
        }
        return -1
    }

    /**
     * The points that light space alone, written to [out] four floats each, as the screen's share
     * across and down (`0..1`), how far the light reaches as a share of the screen's width, and how
     * strongly (`0..1`); [tints] gets each one's colour, packed `0xRRGGBB`, a white a little warm or
     * a little blue. From the seed and the view number alone. Returns how many, [SPACE_LIGHTS].
     */
    fun spaceLights(seed: Long, n: Int, out: FloatArray, tints: IntArray): Int {
        val count = minOf(SPACE_LIGHTS, out.size / 4, tints.size)
        val random = SkyStream(SkyRandom.mix(seed xor LIGHT_SALT, n.toLong()))
        for (k in 0 until count) {
            out[k * 4] = random.nextBetween(0.08f, 0.92f)
            out[k * 4 + 1] = random.nextBetween(0.08f, 0.92f)
            out[k * 4 + 2] = random.nextBetween(0.06f, 0.22f)
            out[k * 4 + 3] = random.nextBetween(0.45f, 1f)
            tints[k] = WHITES[(random.nextUnit() * WHITES.size).toInt().coerceAtMost(WHITES.size - 1)]
        }
        return count
    }

    /** The whites a lighting point may take: none of them a memory's colour, since none is one. */
    private val WHITES = intArrayOf(0xFFF4E8, 0xF2F4FF, 0xFFFFFF, 0xE8EEFF)

    private const val VIEW_SALT = 0x3A11_9A9EL
    private const val LIGHT_SALT = 0x3A11_11C7L
    private const val STAR_SALT = 0x3A11_57A2L
}
