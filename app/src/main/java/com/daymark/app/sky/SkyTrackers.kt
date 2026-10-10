package com.daymark.app.sky

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Trackers in the sky: each tracker the person chose to show is an object of its own, beside their
 * memories and never among them (`DECISIONS.md` §D11).
 *
 * Tracker logs are not part of the rhythm a stretch is read from ([SkyForm]), so a tracker logged a
 * few times a day never floods the sky or turns into a band, and how often someone logs never
 * shapes the rest of their sky. Each object sits beside the day the tracker was started.
 *
 * ## Three looks
 *
 * - a **globular cluster**: a round, dense ball of stars, thickest at the middle, turning slowly;
 * - an **asteroid belt**: a tilted belt of fine grains round one star, the tracker's first log,
 *   inner grains a little faster than outer ones;
 * - a **ring**: a ring of stars round a small knot of the first few logs.
 *
 * Which look a tracker gets comes from the sky's seed and the tracker, so two trackers in one sky
 * differ and the same tracker looks different in someone else's.
 *
 * ## Denser, never brighter
 *
 * Every log is one grain, and grains are spread by their order, never their dates, so a week
 * without logs leaves no gap. Past the first few logs the object's total light stays the same: each
 * grain is fainter the more there are ([grainAlpha]). A busy tracker is a denser object, never a
 * brighter one, and nothing here counts logs out loud, ranks them or says "in a row".
 *
 * Import-free, like the rest of `sky/`.
 */
object SkyTrackers {

    enum class Look(val title: String) {
        GLOBULAR("Globular cluster"),
        BELT("Asteroid belt"),
        RING("Ring"),
    }

    /** A tracker the person shows in their sky: how many logs it has and the day of its first. */
    class Source(val trackerId: Long, val logs: Int, val firstEpochDay: Long)

    /**
     * A tracker's object, placed.
     *
     * @property x where its middle is, in the sky's units, and [y].
     * @property radius how far it reaches.
     * @property tilt how its belt or ring is turned, in radians, and [open] how round it is seen.
     * @property spin which way and how fast it turns, in turns per minute at its middle.
     */
    class Placed(
        val trackerId: Long,
        val look: Look,
        val logs: Int,
        val x: Float,
        val y: Float,
        val radius: Float,
        val tilt: Float,
        val open: Float,
        val spin: Float,
    )

    /** At most this many grains are drawn for one tracker; past it, a grain stands for more logs. */
    const val MAX_GRAINS = 1500

    /** The light an object holds in all, shared out among its grains. */
    const val TOTAL_LIGHT = 6f

    /** How bright one grain of an object with [logs] grains is: never brighter as logs are added. */
    fun grainAlpha(logs: Int): Float = (TOTAL_LIGHT / logs.coerceIn(1, MAX_GRAINS)).coerceAtMost(1f)

    /** How many grains an object with [logs] logs draws. */
    fun grains(logs: Int): Int = logs.coerceIn(0, MAX_GRAINS)

    /**
     * Places each of [sources] beside the star in sight nearest its first log's day, at [xs], [ys]
     * today. A tracker with no logs has nothing to draw and is left out.
     */
    fun place(
        sources: List<Source>,
        layout: SkyLayout,
        xs: FloatArray,
        ys: FloatArray,
        seed: Long,
    ): List<Placed> {
        val out = ArrayList<Placed>()
        for (source in sources.sortedBy { it.trackerId }) {
            if (source.logs <= 0) continue
            val near = nearestByDay(layout, source.firstEpochDay)
            val hash = SkyRandom.mix(seed xor TRACKER_SALT, source.trackerId)
            val random = SkyStream(hash)
            val look = Look.entries[(random.nextUnit() * Look.entries.size).toInt().coerceAtMost(Look.entries.size - 1)]
            val radius = random.nextBetween(MIN_RADIUS, MAX_RADIUS)
            val angle = random.nextUnit() * TAU
            val away = radius * 2.2f + random.nextBetween(0.004f, 0.012f)
            val baseX = if (near >= 0) xs[near] else 0.5f
            val baseY = if (near >= 0) ys[near] else layout.height / 2f
            out.add(
                Placed(
                    trackerId = source.trackerId,
                    look = look,
                    logs = source.logs,
                    x = (baseX + cos(angle) * away).coerceIn(radius, 1f - radius),
                    y = (baseY + sin(angle) * away).coerceIn(radius, layout.height - radius),
                    radius = radius,
                    tilt = (random.nextUnit() - 0.5f) * 1.6f,
                    open = random.nextBetween(0.25f, 0.55f),
                    spin = (if (random.nextUnit() < 0.5f) -1f else 1f) * random.nextBetween(0.15f, 0.35f),
                ),
            )
        }
        return out
    }

    /**
     * Where grain [index] of [placed] is at [seconds], as an offset from its middle in the sky's
     * units, written into [out] as (x, y). Still when [seconds] is 0, which is the sky with motion
     * off. Grain 0 is the first log.
     */
    fun grain(placed: Placed, index: Int, seconds: Float, out: FloatArray) {
        val n = grains(placed.logs).coerceAtLeast(1)
        val h = SkyRandom.mix(placed.trackerId * GRAIN_SALT, index.toLong())
        val u = SkyRandom.unit(h)
        val w = SkyRandom.unit(SkyRandom.mix(h))
        val turnsPerSecond = placed.spin / 60f
        when (placed.look) {
            Look.GLOBULAR -> {
                // Thickest at the middle; the middle turns fastest.
                val r = placed.radius * u.pow(1.8f)
                val a = w * TAU + turnsPerSecond * TAU * seconds / (1f + 3f * r / placed.radius)
                out[0] = cos(a) * r
                out[1] = sin(a) * r
            }
            Look.BELT -> {
                if (index == 0) {
                    out[0] = 0f
                    out[1] = 0f
                    return
                }
                // Spread evenly by order, never by date, so a quiet week leaves no gap.
                val r = placed.radius * (0.55f + 0.45f * u)
                val faster = (placed.radius / r).pow(1.5f)
                val a = TAU * index / n + (w - 0.5f) * 0.08f + turnsPerSecond * TAU * seconds * faster
                tilted(placed, cos(a) * r, sin(a) * r * placed.open + (w - 0.5f) * 0.08f * placed.radius, out)
            }
            Look.RING -> {
                val knot = minOf(KNOT, n)
                if (index < knot) {
                    val r = placed.radius * 0.14f * sqrt(u)
                    val a = w * TAU
                    out[0] = cos(a) * r
                    out[1] = sin(a) * r
                    return
                }
                val r = placed.radius * (0.86f + 0.14f * u)
                val a = TAU * (index - knot) / (n - knot).coerceAtLeast(1) + turnsPerSecond * TAU * seconds
                tilted(placed, cos(a) * r, sin(a) * r * placed.open, out)
            }
        }
    }

    private fun tilted(placed: Placed, x: Float, y: Float, out: FloatArray) {
        val c = cos(placed.tilt)
        val s = sin(placed.tilt)
        out[0] = x * c - y * s
        out[1] = x * s + y * c
    }

    /** The star in sight whose day is nearest [epochDay]; -1 in a sky with none in sight. */
    fun nearestByDay(layout: SkyLayout, epochDay: Long): Int {
        var best = -1
        var bestGap = Long.MAX_VALUE
        for (i in 0 until layout.starCount) {
            if (!layout.isShown(i)) continue
            val gap = kotlin.math.abs(layout.epochDay[i] - epochDay)
            if (gap < bestGap) {
                bestGap = gap
                best = i
            }
        }
        return best
    }

    /** How many of a ring's first logs make its knot. */
    private const val KNOT = 5

    private const val MIN_RADIUS = 0.004f
    private const val MAX_RADIUS = 0.008f
    private const val TAU = (2.0 * PI).toFloat()
    private const val TRACKER_SALT = 0x7_2AC4E25L
    private const val GRAIN_SALT = 0x6_7A1BL
}
