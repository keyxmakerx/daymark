package com.daymark.app.calm

import kotlin.math.hypot

/**
 * "Sand to rake", the calm activity in Take a moment with nothing in it that can be done wrong
 * (#170). A finger drawn across the sand leaves [TINES] parallel grooves; "Smooth" wipes it flat.
 *
 * Import-free like [CalmBlocks], so `tools/jvm-tests.sh calm` runs it. The Compose screen keeps the
 * strokes and draws them; the geometry, and the limit on how much is kept, live here.
 *
 * ## What it must keep true
 *
 *  - **Nothing moves on its own.** A groove appears under the finger and stays until "Smooth". No
 *    groove fades, drifts or refills by itself.
 *  - **Nothing is kept.** The strokes live only in memory for the visit; nothing is saved or
 *    counted, so the sand cannot become something to keep up (`docs/FEATURES.md` §7.2).
 *  - **Bounded.** At most [MAX_POINTS] points are held. Past that the oldest stroke is let go
 *    whole, so a long visit costs the same as a short one and no stroke is ever cut in half.
 */
object SandRake {

    data class Point(val x: Float, val y: Float)

    /** How many grooves one finger leaves, and how far apart, in density-independent pixels. */
    const val TINES = 4
    const val TINE_SPACING_DP = 9f

    /** Points closer than this to the last kept one are not kept: they add nothing a person sees. */
    const val MIN_STEP_DP = 3f

    const val MAX_POINTS = 6000

    /**
     * The [TINES] grooves for one step of the finger from [from] to [to], each a segment offset
     * along the step's normal and centred on the finger. A step of zero length has no direction,
     * so it leaves nothing.
     */
    fun tines(from: Point, to: Point, spacing: Float): List<Pair<Point, Point>> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = hypot(dx, dy)
        if (length == 0f) return emptyList()
        val nx = -dy / length
        val ny = dx / length
        val middle = (TINES - 1) / 2f
        return (0 until TINES).map { i ->
            val k = (i - middle) * spacing
            Point(from.x + nx * k, from.y + ny * k) to Point(to.x + nx * k, to.y + ny * k)
        }
    }

    /** Whether [next] is far enough from [last] to be worth keeping. */
    fun worthKeeping(last: Point?, next: Point, minStep: Float): Boolean =
        last == null || hypot(next.x - last.x, next.y - last.y) >= minStep

    /** [strokes] with whole strokes let go from the oldest end until at most [max] points remain. */
    fun bounded(strokes: List<List<Point>>, max: Int = MAX_POINTS): List<List<Point>> {
        var total = strokes.sumOf { it.size }
        var from = 0
        while (total > max && from < strokes.size - 1) {
            total -= strokes[from].size
            from++
        }
        return strokes.drop(from)
    }
}

/** What every calm activity shares: when its one fixed line appears, and what that line says. */
object CalmSession {

    /** About ten minutes in, one fixed line appears. It never counts down and never counts up. */
    const val NATURAL_END_MS = 10L * 60L * 1000L

    /** The line. It says what the person can do, never how they feel (`docs/FEATURES.md` §7.2). */
    const val NATURAL_END_LINE = "You can keep going, or go back to the menu."

    /** The way out, on screen at all times. */
    const val BACK_TO_MENU = "Back to the menu"
}
