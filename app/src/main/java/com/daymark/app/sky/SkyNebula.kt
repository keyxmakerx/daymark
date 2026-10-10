package com.daymark.app.sky

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nebulae: gas around weeks with a lot of writing (`DECISIONS.md` §D11).
 *
 * A rule over the person's own dates and kinds, and nothing else: a week with at least
 * [ENTRIES_PER_WEEK] journal entries in sight is a week with a lot of writing, and weeks like that
 * one after another share one nebula. It reads no words, no mood and no length; it counts entries,
 * the way a person looking back at their own journal would.
 *
 * A nebula is added light. A week without one is an ordinary week, never a dark one, and nothing
 * marks a stretch where the writing stopped. Put-away entries never count, so no nebula gives away
 * where they were.
 *
 * Its colours are the sky's own ([SkyColours.Look.nebulae]), which are blind to data; which of the
 * three looks a nebula takes is a hash of where it is, never of what is in it.
 *
 * Import-free, like the rest of `sky/`.
 */
object SkyNebula {

    /** Journal entries in one week, Monday to Sunday, that make it a week with a lot of writing. */
    const val ENTRIES_PER_WEEK = 4

    /**
     * One nebula.
     *
     * @property members every star in sight in its weeks, which the gas surrounds.
     * @property entries how many journal entries those weeks hold, for its card.
     * @property firstDay the first journal entry's day, and [lastDay] the last.
     * @property look which of the sky's three nebula gradients it takes.
     * @property turn how far round its gas is turned, in radians.
     */
    class Nebula(
        val members: IntArray,
        val entries: Int,
        val firstDay: Long,
        val lastDay: Long,
        val look: Int,
        val turn: Float,
    )

    /** Where a nebula sits now, in the sky's units: its middle and how far its gas reaches. */
    class Extent(val x: Float, val y: Float, val radius: Float)

    /** The Monday-to-Sunday week [epochDay] falls in. Day 0, 1 January 1970, was a Thursday. */
    fun weekOf(epochDay: Long): Long = (epochDay + 3).floorDiv(7L)

    /** Every nebula in [layout], oldest first. */
    fun find(layout: SkyLayout): List<Nebula> {
        val entriesByWeek = HashMap<Long, Int>()
        for (i in 0 until layout.starCount) {
            if (!layout.isShown(i) || layout.kindAt(i) != SkyKind.JOURNAL) continue
            val week = weekOf(layout.epochDay[i])
            entriesByWeek[week] = (entriesByWeek[week] ?: 0) + layout.recordCountAt(i)
        }
        val busy = entriesByWeek.filter { it.value >= ENTRIES_PER_WEEK }.keys.sorted()
        if (busy.isEmpty()) return emptyList()

        // Weeks one after another make one nebula.
        val runs = ArrayList<LongRange>()
        var from = busy[0]
        var to = busy[0]
        for (week in busy.drop(1)) {
            if (week == to + 1) {
                to = week
            } else {
                runs.add(from..to)
                from = week
                to = week
            }
        }
        runs.add(from..to)

        return runs.map { run ->
            val members = ArrayList<Int>()
            var entries = 0
            var firstDay = Long.MAX_VALUE
            var lastDay = Long.MIN_VALUE
            for (i in 0 until layout.starCount) {
                if (!layout.isShown(i) || weekOf(layout.epochDay[i]) !in run) continue
                members.add(i)
                if (layout.kindAt(i) == SkyKind.JOURNAL) {
                    entries += layout.recordCountAt(i)
                    firstDay = minOf(firstDay, layout.epochDay[i])
                    lastDay = maxOf(lastDay, layout.epochDay[i])
                }
            }
            val hash = SkyRandom.mix(NEBULA_SALT, run.first)
            Nebula(
                members = members.toIntArray(),
                entries = entries,
                firstDay = firstDay,
                lastDay = lastDay,
                look = (SkyRandom.unit(hash) * 3f).toInt().coerceAtMost(2),
                turn = SkyRandom.unit(SkyRandom.mix(hash)) * TAU,
            )
        }
    }

    /** Where [nebula] is among stars at [xs], [ys]: the middle of its members, and past the farthest. */
    fun extent(nebula: Nebula, xs: FloatArray, ys: FloatArray): Extent {
        var sx = 0f
        var sy = 0f
        for (i in nebula.members) {
            sx += xs[i]
            sy += ys[i]
        }
        val n = nebula.members.size.coerceAtLeast(1)
        val cx = sx / n
        val cy = sy / n
        var far = 0f
        for (i in nebula.members) {
            val dx = xs[i] - cx
            val dy = ys[i] - cy
            far = maxOf(far, sqrt(dx * dx + dy * dy))
        }
        return Extent(cx, cy, (far * REACH).coerceIn(MIN_RADIUS, MAX_RADIUS))
    }

    /**
     * A nebula's gas as a square of [size] pixels, packed non-premultiplied `0xAARRGGBB`, row by
     * row, ready to be drawn stretched over its [Extent].
     *
     * The gas is folded noise in the [gradient]'s three colours, thickest towards the middle and
     * gone well before the edge, so stretched over any size it has no edge at all. [seed] makes
     * each nebula's folds its own. At its thickest it is still faint ([PEAK_ALPHA]): the stars in
     * front of it stay the brightest things there.
     */
    fun texture(size: Int, gradient: IntArray, seed: Long): IntArray {
        val out = IntArray(size * size)
        val ox = SkyRandom.unit(SkyRandom.mix(seed, 1L)) * 100f
        val oy = SkyRandom.unit(SkyRandom.mix(seed, 2L)) * 100f
        val scale = 1.6f + 1.4f * SkyRandom.unit(SkyRandom.mix(seed, 3L))
        for (py in 0 until size) {
            val v = (py + 0.5f) / size * 2f - 1f
            for (px in 0 until size) {
                val u = (px + 0.5f) / size * 2f - 1f
                val r = sqrt(u * u + v * v)
                val falloff = 1f - smoothstep(0.25f, 0.95f, r)
                if (falloff <= 0f) continue
                // Domain-warped noise: the folds of the gas.
                val wx = fbm(u * scale + ox, v * scale + oy)
                val wy = fbm(u * scale + oy + 5.2f, v * scale + ox + 1.3f)
                val density = fbm(u * scale + 1.7f * wx + ox, v * scale + 1.7f * wy + oy)
                val gas = smoothstep(0.35f, 0.8f, density)
                val alpha = gas * falloff * PEAK_ALPHA
                if (alpha <= 0.004f) continue
                val colour = mix3(gradient, (density * 1.4f - 0.2f).coerceIn(0f, 1f))
                out[py * size + px] = ((alpha * 255f + 0.5f).toInt() shl 24) or colour
            }
        }
        return out
    }

    /** How faint a nebula's gas is at its thickest, as alpha. */
    const val PEAK_ALPHA = 0.32f

    /** The gas reaches this far past the farthest star it surrounds. */
    private const val REACH = 1.6f
    private const val MIN_RADIUS = 0.004f
    private const val MAX_RADIUS = 0.12f
    private const val TAU = 6.2831855f
    private const val NEBULA_SALT = 0x4E_EB01AL

    /** Fractal noise, about 0..1. */
    fun fbm(x: Float, y: Float): Float {
        var amplitude = 0.5f
        var sum = 0f
        var norm = 0f
        var px = x
        var py = y
        repeat(OCTAVES) {
            sum += amplitude * noise(px, py)
            norm += amplitude
            // Turned and doubled each octave, so the folds do not line up with the grid.
            val nx = 0.8f * px - 0.6f * py
            val ny = 0.6f * px + 0.8f * py
            px = nx * 2.03f + 17.1f
            py = ny * 2.03f + 17.1f
            amplitude *= 0.5f
        }
        return sum / norm
    }

    private const val OCTAVES = 5

    /** Gradient noise, about 0..1. */
    private fun noise(x: Float, y: Float): Float {
        val ix = floor(x)
        val iy = floor(y)
        val fx = x - ix
        val fy = y - iy
        val ux = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f)
        val uy = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f)
        val a = dot(ix, iy, fx, fy)
        val b = dot(ix + 1f, iy, fx - 1f, fy)
        val c = dot(ix, iy + 1f, fx, fy - 1f)
        val d = dot(ix + 1f, iy + 1f, fx - 1f, fy - 1f)
        val top = a + (b - a) * ux
        val bottom = c + (d - c) * ux
        return (top + (bottom - top) * uy) * 0.7f + 0.5f
    }

    private fun dot(ix: Float, iy: Float, fx: Float, fy: Float): Float {
        val angle = SkyRandom.unit(SkyRandom.mix(ix.toLong() * 73_856_093L, iy.toLong() * 19_349_663L)) * TAU
        return cos(angle) * fx + sin(angle) * fy
    }

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** A colour along three, at [t] 0..1, packed `0xRRGGBB`. */
    private fun mix3(colours: IntArray, t: Float): Int {
        val scaled = t * 2f
        val i = scaled.toInt().coerceIn(0, 1)
        val f = scaled - i
        val a = colours[i]
        val b = colours[i + 1]
        fun channel(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
