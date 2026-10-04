package com.daymark.app.sky

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sky's form: where each star sits (`DECISIONS.md` §D11, #449).
 *
 * Every memory is placed in time order along a guide that the sky's seed fixes, and distance along
 * it is counted in memories, never days: star `i` is the `i`th place along it. A quiet stretch
 * therefore takes almost no room and is never drawn as an empty reach.
 *
 * ## No two skies take the same form
 *
 * The seed picks the form ([formOf]) and its size ([sizeOf]), and neither ever changes:
 *
 *  - **River.** The memories run along one river, on one of six courses ([Course]).
 *  - **Galaxies.** The memories fill one galaxy and then the next, down the sky. Each galaxy has a
 *    size and a type of its own: spiral, barred, elliptical, ring or irregular.
 *  - **Open sky.** The memories spread across the whole sky with no line to follow, and bands,
 *    clusters and streams stand out of it.
 *
 * A river is one form among three and never the default. Size scales a river's width, an open
 * sky's spread, every cluster and every galaxy, and galaxies in one sky differ from each other too.
 *
 * Import-free, like the rest of `sky/`. No clock, no random source: every guide is built from the
 * seed, and every star's own scatter is a hash of its identity ([SkyRandom.mix]), so:
 *
 *  - **Appending moves nothing.** A new star takes the next place along a guide that already
 *    exists, and nothing before it reads how many stars come after. `SkyTest` adds a thousand newer
 *    stars to a sky of every form and checks every older position bit for bit.
 *  - **A back-dated memory shifts later stars one place**, because their count along the guide
 *    changes. That is the accepted cost of a sky measured in memories.
 *  - **The newest stretch may still change shape** as days arrive (a run becomes a stream, a long
 *    dense stretch becomes a band). A stretch that is over keeps its shape.
 *
 * ## Shape follows rhythm, read from dates alone
 *
 * A star's place near its guide comes from which days around it have something on them. Never
 * mood, never kind, never content. No shape is the good one; none is described as kept up or
 * broken (§D11).
 *
 *  - **Band**: a stretch of at least [BAND_MIN_DAYS] days in which at least six of every seven days
 *    have a memory. Rare by design, and at most [MAX_BANDS] in a sky.
 *  - **Cluster**: a run of consecutive days outside a band, set off to one side of the guide.
 *  - **Stream**: a run at least as long as this sky's own stream length (6 to 11 days, by seed).
 *  - **Single**: a day on its own, scattered loosely near the guide.
 *
 * ## Coordinates
 *
 * The world is [WORLD_WIDTH] wide and grows downward as memories arrive. Output is normalised by
 * the width alone, so `x` is in 0..1 and `y` runs from 0 to [Placement.height]; one scale serves
 * both axes and shapes are never stretched.
 *
 * ## Drift
 *
 * Older stars drift outward and loosen. The drift is a fixed vector per star, per year of age,
 * worked out here; the renderer adds `drift × age`. It is decoration, never a relayout, and it is
 * what makes every drawn constellation fall out of the live sky over the years (§D11).
 */
object SkyForm {

    /** The world's width. One phone screen at the whole-sky view. */
    const val WORLD_WIDTH = 6f

    /** Guide length per star along a river or an open sky, in world units. */
    const val STEP = 0.03f

    /** A band needs this many days, at six in seven or better. Set high on purpose: bands are rare. */
    const val BAND_MIN_DAYS = 56

    /** The most bands one sky can have; the first ones in time keep the shape. */
    const val MAX_BANDS = 2

    const val SHAPE_SINGLE = 0
    const val SHAPE_BAND = 1
    const val SHAPE_CLUSTER = 2
    const val SHAPE_STREAM = 3

    /** The three forms a sky can take. Which one is the seed's, and it never changes. */
    enum class Form { RIVER, GALAXIES, OPEN }

    fun formOf(seed: Long): Form {
        val u = SkyRandom.unit(SkyRandom.mix(seed, FORM_SALT))
        return when {
            u < 0.34f -> Form.RIVER
            u < 0.75f -> Form.GALAXIES
            else -> Form.OPEN
        }
    }

    /** How big this sky's shapes are drawn: 0.7 to 1.35, by seed. */
    fun sizeOf(seed: Long): Float = 0.7f + 0.65f * SkyRandom.unit(SkyRandom.mix(seed, SIZE_SALT))

    /** The six courses a river, or an open sky's unseen guide, can take. */
    enum class Course { SPIRAL, SERPENTINE, ROWS, WANDER, LOOPS, ZIGZAG }

    fun courseOf(seed: Long): Course {
        val pick = (SkyRandom.unit(SkyRandom.mix(seed, COURSE_SALT)) * Course.entries.size).toInt()
        return Course.entries[pick.coerceIn(0, Course.entries.size - 1)]
    }

    /** Runs at least this long are streams. Six to eleven days, per sky. */
    fun streamDaysOf(seed: Long): Int =
        6 + (SkyRandom.unit(SkyRandom.mix(seed, STREAM_SALT)) * 6f).toInt().coerceIn(0, 5)

    /**
     * Where every star sits.
     *
     * @param epochDay each star's day, in time order (the order [Sky.layout] produces).
     * @param identity each star's stable identity: a hash of its kind and anchor record id. Its own
     *   scatter is drawn from this and from nothing else.
     */
    class Placement(
        val x: FloatArray,
        val y: FloatArray,
        val driftX: FloatArray,
        val driftY: FloatArray,
        val shape: IntArray,
        /** How far down the sky reaches, in the same units as [y]. Never less than [MIN_HEIGHT]. */
        val height: Float,
        val form: Form,
    )

    /** The smallest sky: a world as tall as it is wide, so a first star is not drawn at a huge scale. */
    const val MIN_HEIGHT = 1f

    fun place(epochDay: LongArray, identity: LongArray, seed: Long): Placement {
        val n = epochDay.size
        val x = FloatArray(n)
        val y = FloatArray(n)
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        val shapeOut = IntArray(n)
        val form = formOf(seed)
        if (n == 0) return Placement(x, y, dx, dy, shapeOut, MIN_HEIGHT, form)

        val size = sizeOf(seed)
        val guide: Guide = when (form) {
            Form.RIVER -> RiverGuide(seed, spread = size, reach = size)
            Form.OPEN -> RiverGuide(seed, spread = size * OPEN_SPREAD, reach = size * OPEN_REACH)
            Form.GALAXIES -> GalaxyGuide(seed, size)
        }
        val rhythm = Rhythm.of(epochDay, streamDaysOf(seed))

        var maxY = 0f
        var i = 0
        while (i < n) {
            val shape = rhythm.shapeOf(epochDay[i])
            if (shape == SHAPE_CLUSTER || shape == SHAPE_STREAM) {
                // A run of days: its stars are consecutive in time order.
                val runStart = rhythm.runStartOf(epochDay[i])
                var end = i
                while (end < n && rhythm.runStartOf(epochDay[end]) == runStart) end++
                placeCluster(guide, seed, size, i, end, shape, identity, x, y, dx, dy)
                for (k in i until end) shapeOut[k] = shape
                i = end
            } else {
                placeLoose(guide, seed, i, shape, identity[i], x, y, dx, dy)
                shapeOut[i] = shape
                i++
            }
        }

        for (k in 0 until n) {
            // Normalise by the width alone; one scale for both axes.
            x[k] = (x[k] + HALF_WIDTH) / WORLD_WIDTH
            y[k] = (y[k] + TOP_MARGIN) / WORLD_WIDTH
            dx[k] = dx[k] / WORLD_WIDTH
            dy[k] = dy[k] / WORLD_WIDTH
            if (y[k] > maxY) maxY = y[k]
        }
        return Placement(x, y, dx, dy, shapeOut, max(MIN_HEIGHT, maxY + BOTTOM_MARGIN / WORLD_WIDTH), form)
    }

    // ---------------------------------------------------------------------------------------------
    // Guides: where the i-th place is, which way the guide runs there, and how loosely stars sit.
    // ---------------------------------------------------------------------------------------------

    private abstract class Guide {
        /** The last place [at] found, and the unit direction the guide runs there. */
        var px = 0f
        var py = 0f
        var tx = 0f
        var ty = 1f

        /** How far loose stars scatter here, as a multiple of a river's own. */
        var spread = 1f

        /** How far a cluster stands off the guide here, as a multiple of a river's own. */
        var reach = 1f

        /** Finds place [index]. [id] is the star's or the run's identity; a galaxy picks an arm by it. */
        abstract fun at(index: Int, id: Long)
    }

    /** A river, or an open sky's unseen guide: a seeded [Path], [STEP] per star. */
    private class RiverGuide(seed: Long, spread: Float, reach: Float) : Guide() {
        private val path = Path(seed)

        init {
            this.spread = spread
            this.reach = reach
        }

        override fun at(index: Int, id: Long) {
            path.at(index * STEP)
            px = path.px
            py = path.py
            tx = path.tx
            ty = path.ty
        }
    }

    /**
     * Galaxies, one after another down the sky.
     *
     * Each galaxy holds a fixed number of places, set by its size, and fills from its core outward,
     * so the newest stars of an unfinished galaxy are at its edge. Its size, type and position come
     * from a hash of the seed and the galaxy's number, and its position also from the galaxies above
     * it, so that none overlaps another. None of it reads how many stars there are.
     */
    private class GalaxyGuide(private val seed: Long, private val size: Float) : Guide() {

        private class Galaxy(
            val first: Int,
            val count: Int,
            val type: Int,
            val cx: Float,
            val cy: Float,
            val r: Float,
            val key: Long,
            val arms: Int,
            val phase: Float,
            val twist: Float,
            val tilt: Float,
            val cosRot: Float,
            val sinRot: Float,
        )

        private val galaxies = ArrayList<Galaxy>()

        private fun add() {
            val k = galaxies.size
            val key = SkyRandom.mix(seed, k.toLong() + GALAXY_SALT)
            val roll = unit(key, 60)
            val type = when {
                roll < 0.34f -> GALAXY_SPIRAL
                roll < 0.52f -> GALAXY_BARRED
                roll < 0.70f -> GALAXY_ELLIPTICAL
                roll < 0.82f -> GALAXY_RING
                else -> GALAXY_IRREGULAR
            }
            val r = (size * (0.75f + 1.65f * unit(key, 61).pow(1.4f))).coerceAtMost(MAX_GALAXY_RADIUS)
            val count = max(MIN_GALAXY_STARS, (GALAXY_DENSITY * PI.toFloat() * r * r).toInt())
            val first = if (k == 0) 0 else galaxies[k - 1].first + galaxies[k - 1].count

            // Across: anywhere the whole disc fits. Down: below everything it would overlap.
            val mx = HALF_WIDTH - 0.25f - r * 1.05f
            val cx = (unit(key, 62) * 2f - 1f) * mx
            var cy = if (k == 0) -TOP_MARGIN + 0.35f + r else galaxies[k - 1].cy + 0.25f
            for (prev in galaxies) {
                val need = (prev.r + r) * 0.9f + 0.15f
                val across = abs(cx - prev.cx)
                if (across < need) cy = max(cy, prev.cy + sqrt(need * need - across * across))
            }
            if (k > 0) cy += unit(key, 63) * 0.3f * r

            val rot = unit(key, 64) * TWO_PI
            galaxies.add(
                Galaxy(
                    first = first,
                    count = count,
                    type = type,
                    cx = cx,
                    cy = cy,
                    r = r,
                    key = key,
                    arms = 2 + (unit(key, 65) * 3f).toInt().coerceIn(0, 2),
                    phase = unit(key, 66) * TWO_PI,
                    twist = (if (unit(key, 67) < 0.5f) -1f else 1f) * (3.2f + 3f * unit(key, 68)),
                    tilt = if (type == GALAXY_RING) 0.55f + 0.45f * unit(key, 69) else 0.45f + 0.55f * unit(key, 69),
                    cosRot = cos(rot),
                    sinRot = sin(rot),
                ),
            )
        }

        private fun galaxyOf(index: Int): Galaxy {
            while (galaxies.isEmpty() || galaxies[galaxies.size - 1].let { it.first + it.count } <= index) add()
            var lo = 0
            var hi = galaxies.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (galaxies[mid].first <= index) lo = mid else hi = mid - 1
            }
            return galaxies[lo]
        }

        // The place within the galaxy, in units of its radius, before tilt and rotation.
        private var lx = 0f
        private var ly = 0f
        private var ltx = 0f
        private var lty = 1f
        private var lspread = 0.1f

        private fun local(x: Float, y: Float, tx: Float, ty: Float, spread: Float) {
            lx = x
            ly = y
            ltx = tx
            lty = ty
            lspread = spread
        }

        override fun at(index: Int, id: Long) {
            val g = galaxyOf(index)
            // How far through filling this galaxy the place is: the core first, the edge last.
            val f = (index - g.first + 0.5f) / g.count
            when (g.type) {
                GALAXY_SPIRAL -> if (f < 0.14f) {
                    val q = sqrt(f / 0.14f) * 0.2f
                    val a = unit(id, 70) * TWO_PI
                    local(q * cos(a), q * sin(a), -sin(a), cos(a), 0.06f)
                } else {
                    val rr = 0.18f + 0.82f * (f - 0.14f) / 0.86f
                    val arm = (unit(id, 71) * g.arms).toInt().coerceIn(0, g.arms - 1)
                    val a = g.phase + arm * TWO_PI / g.arms + g.twist * rr
                    arm(rr, a, g.twist, 0.05f + 0.07f * rr)
                }
                GALAXY_BARRED -> if (f < 0.3f) {
                    local((unit(id, 72) * 2f - 1f) * 0.42f, 0f, 1f, 0f, 0.045f)
                } else {
                    val rr = 0.42f + 0.58f * (f - 0.3f) / 0.7f
                    val arm = if (unit(id, 71) < 0.5f) 0f else PI.toFloat()
                    val twist = g.twist * 0.7f
                    arm(rr, arm + twist * (rr - 0.42f), twist, 0.05f + 0.06f * rr)
                }
                GALAXY_ELLIPTICAL -> {
                    val q = f.pow(0.8f) * 0.85f
                    val a = unit(id, 70) * TWO_PI
                    local(q * cos(a), q * sin(a), -sin(a), cos(a), 0.1f + 0.1f * q)
                }
                GALAXY_RING -> if (f < 0.22f) {
                    val q = sqrt(f / 0.22f) * 0.16f
                    val a = unit(id, 70) * TWO_PI
                    local(q * cos(a), q * sin(a), -sin(a), cos(a), 0.05f)
                } else {
                    // The ring is drawn round, a little further with every memory.
                    val a = g.phase + (f - 0.22f) / 0.78f * TWO_PI
                    local(0.8f * cos(a), 0.8f * sin(a), -sin(a), cos(a), 0.045f)
                }
                else -> {
                    // Irregular: a few clouds, filled one after another.
                    val clouds = 3 + (unit(g.key, 73) * 2f).toInt().coerceIn(0, 1)
                    val c = (f * clouds).toInt().coerceIn(0, clouds - 1)
                    val bx = (unit(g.key, 80 + c * 3) * 2f - 1f) * 0.55f
                    val by = (unit(g.key, 81 + c * 3) * 2f - 1f) * 0.55f
                    val br = 0.18f + 0.22f * unit(g.key, 82 + c * 3)
                    val a = unit(id, 70) * TWO_PI
                    val q = sqrt(unit(id, 74)) * br
                    local(bx + q * cos(a), by + q * sin(a), -sin(a), cos(a), 0.07f)
                }
            }
            // Tilt, turn and scale the galaxy into the world.
            val sy = ly * g.tilt
            val sty = lty * g.tilt
            px = g.cx + (lx * g.cosRot - sy * g.sinRot) * g.r
            py = g.cy + (lx * g.sinRot + sy * g.cosRot) * g.r
            val rtx = ltx * g.cosRot - sty * g.sinRot
            val rty = ltx * g.sinRot + sty * g.cosRot
            val len = sqrt(rtx * rtx + rty * rty).coerceAtLeast(1e-6f)
            tx = rtx / len
            ty = rty / len
            spread = g.r * lspread / SINGLE_SPREAD
            reach = g.r * GALAXY_REACH
        }

        /** A place on an arm at radius [rr] and angle [a], with the arm's own direction there. */
        private fun arm(rr: Float, a: Float, twist: Float, spread: Float) {
            val c = cos(a)
            val s = sin(a)
            local(rr * c, rr * s, c - rr * twist * s, s + rr * twist * c, spread)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Loose stars: bands and singles.
    // ---------------------------------------------------------------------------------------------

    private fun placeLoose(
        guide: Guide, seed: Long, index: Int, shape: Int, id: Long,
        x: FloatArray, y: FloatArray, dx: FloatArray, dy: FloatArray,
    ) {
        guide.at(index, id)
        val px = guide.px
        val py = guide.py
        val nx = -guide.ty
        val ny = guide.tx
        val w = guide.spread
        var v: Float
        var u: Float
        if (shape == SHAPE_BAND) {
            // A band breathes a little along its length, and bunches into knots.
            val s = index * STEP
            val sig = 0.2f * w * (0.65f + 0.7f * (0.5f + 0.5f * sin(s * 1.35f + sin(s * 0.36f) * 2f)))
            val roll = unit(id, 1)
            if (roll < 0.35f) {
                val knot = SkyRandom.mix(seed, (index / KNOT_STARS).toLong())
                val kv = gauss(knot, 2) * sig * 0.9f
                val kz = (0.025f + 0.05f * unit(knot, 3)) * w
                v = kv + gauss(id, 4) * kz
                u = gauss(id, 5) * kz
            } else if (roll < 0.95f) {
                v = gauss(id, 6) * sig
                u = gauss(id, 7) * 0.12f * w
            } else {
                v = gauss(id, 8) * 1.1f * w * (if (unit(id, 9) < 0.3f) 1.8f else 1f)
                u = gauss(id, 10) * 0.5f * w
            }
        } else {
            v = gauss(id, 11) * SINGLE_SPREAD * w
            u = gauss(id, 12) * SINGLE_SPREAD * w
        }
        val ox = nx * v + guide.tx * u
        val oy = ny * v + guide.ty * u
        x[index] = reflectX(px + ox)
        y[index] = reflectTop(py + oy)
        // Older stars drift outward from their guide, and wander a little.
        dx[index] = ox * 0.25f + gauss(id, 13) * 0.05f
        dy[index] = oy * 0.25f + gauss(id, 14) * 0.05f
    }

    // ---------------------------------------------------------------------------------------------
    // Clusters and streams: one per run of days.
    // ---------------------------------------------------------------------------------------------

    private fun placeCluster(
        guide: Guide, seed: Long, size: Float, from: Int, to: Int, shape: Int,
        identity: LongArray,
        x: FloatArray, y: FloatArray, dx: FloatArray, dy: FloatArray,
    ) {
        // Keyed on the run's first star, not its date: a sky moved wholesale in time is the same sky.
        val run = SkyRandom.mix(seed, from.toLong() + RUN_SALT)
        guide.at(from, run)
        val nx = -guide.ty
        val ny = guide.tx
        val side = if (unit(run, 20) < 0.5f) -1f else 1f
        val off = side * (0.5f + 1.6f * unit(run, 21)) * guide.reach
        val along = gauss(run, 22) * 0.6f * guide.reach
        val cx = reflectX(guide.px + nx * off + guide.tx * along)
        val cy = reflectTop(guide.py + ny * off + guide.ty * along)
        val centreDriftX = gauss(run, 23) * 0.08f
        val centreDriftY = gauss(run, 24) * 0.08f

        val count = to - from
        val rc = (0.05f + 0.022f * sqrt(count.toFloat())) * size
        val rot = unit(run, 25) * TWO_PI
        val cr = cos(rot)
        val sr = sin(rot)
        val ax = 0.45f + 0.55f * unit(run, 26)
        val type = when {
            shape == SHAPE_STREAM -> TYPE_STREAM
            unit(run, 27) < 0.45f -> TYPE_OVAL
            unit(run, 27) < 0.75f -> TYPE_SPIRAL
            else -> TYPE_OPEN
        }
        val arms = if (unit(run, 28) < 0.3f) 3 else 2
        val armPhase = unit(run, 29) * TWO_PI
        val twist = 3.2f + 2.4f * unit(run, 30)
        val bend = (if (unit(run, 31) < 0.5f) -1f else 1f) * (0.15f + 0.3f * unit(run, 32))

        for (j in 0 until count) {
            val k = from + j
            val id = identity[k]
            var lx: Float
            var ly: Float
            when (type) {
                TYPE_STREAM -> {
                    val len = (0.3f + 0.0075f * count) * size
                    val wd = (0.02f + 0.0005f * count) * size
                    val t = (j.toFloat() / max(1, count - 1) + gauss(id, 33) * 0.03f).coerceIn(0f, 1f)
                    val taper = sin(PI.toFloat() * (0.04f + 0.92f * t)).coerceAtLeast(0f).pow(0.55f) *
                        (0.3f + 1.1f * (1f - t))
                    lx = (t - 0.5f) * len
                    ly = gauss(id, 34) * wd * taper + bend * len * (t - 0.5f) * (t - 0.5f)
                }
                TYPE_SPIRAL -> if (j % 4 != 0) {
                    val t = unit(id, 35)
                    val a = (j % arms) * TWO_PI / arms + armPhase + t * twist + gauss(id, 36) * 0.22f
                    val rr = rc * (0.15f + t * 0.95f)
                    lx = cos(a) * rr
                    ly = sin(a) * rr * ax
                } else {
                    lx = gauss(id, 37) * rc * 0.18f
                    ly = gauss(id, 38) * rc * 0.18f * ax
                }
                TYPE_OPEN -> {
                    lx = gauss(id, 39) * rc * 0.7f
                    ly = gauss(id, 40) * rc * 0.7f
                }
                else -> {
                    val spread = if (unit(id, 41) < 0.5f) 0.18f else 0.5f
                    lx = gauss(id, 42) * rc * spread
                    ly = gauss(id, 43) * rc * spread * ax
                }
            }
            val ox = lx * cr - ly * sr
            val oy = lx * sr + ly * cr
            x[k] = cx + ox
            y[k] = cy + oy
            // The cluster wanders a little and loosens as it ages.
            dx[k] = centreDriftX + ox * 0.12f
            dy[k] = centreDriftY + oy * 0.12f
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Rhythm.
    // ---------------------------------------------------------------------------------------------

    /**
     * Which shape each day's stars take, worked out from which days have something on them.
     *
     * Days only: the input is the stars' days, and nothing about any record but its date reaches
     * this class.
     */
    class Rhythm private constructor(
        private val days: LongArray,
        private val shapes: IntArray,
        private val runStarts: LongArray,
    ) {
        fun shapeOf(day: Long): Int {
            val i = days.binarySearch(day)
            return if (i >= 0) shapes[i] else SHAPE_SINGLE
        }

        /** The first day of the run [day] belongs to; the day itself when it stands alone. */
        fun runStartOf(day: Long): Long {
            val i = days.binarySearch(day)
            return if (i >= 0) runStarts[i] else day
        }

        companion object {
            fun of(epochDay: LongArray, streamDays: Int): Rhythm {
                val days = epochDay.distinct().sorted().toLongArray()
                val m = days.size
                val shapes = IntArray(m) { SHAPE_SINGLE }
                val runStarts = LongArray(m)

                // Bands: stretches with no gap longer than one day, long enough and dense enough.
                var bands = 0
                val inBand = BooleanArray(m)
                var a = 0
                while (a < m) {
                    var b = a
                    while (b + 1 < m && days[b + 1] - days[b] <= 2) b++
                    val spanDays = days[b] - days[a] + 1
                    val covered = (b - a + 1).toLong()
                    if (bands < MAX_BANDS && spanDays >= BAND_MIN_DAYS && covered * 7 >= spanDays * 6) {
                        for (k in a..b) inBand[k] = true
                        bands++
                    }
                    a = b + 1
                }

                // Runs: consecutive days, outside a band.
                var r = 0
                while (r < m) {
                    var e = r
                    if (!inBand[r]) {
                        while (e + 1 < m && !inBand[e + 1] && days[e + 1] - days[e] == 1L) e++
                    }
                    val length = e - r + 1
                    for (k in r..e) {
                        runStarts[k] = days[r]
                        shapes[k] = when {
                            inBand[k] -> SHAPE_BAND
                            length >= streamDays -> SHAPE_STREAM
                            length >= 2 -> SHAPE_CLUSTER
                            else -> SHAPE_SINGLE
                        }
                    }
                    r = e + 1
                }
                return Rhythm(days, shapes, runStarts)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The course: a seeded line that grows downward, block after block, for as long as needed.
    // ---------------------------------------------------------------------------------------------

    /**
     * A river's centre line, or an open sky's unseen one.
     *
     * Control points come from a [SkyStream] over the seed, one block of the world at a time, so the
     * course for the first thousand stars is the same whether there are a thousand or ten thousand.
     * The line through them is a Catmull-Rom curve sampled into a polyline and measured by arc length.
     */
    class Path(seed: Long) {

        val course: Course = courseOf(seed)
        private val stream = SkyStream(SkyRandom.mix(seed, PATH_SALT))
        private val flipX = if (stream.nextUnit() < 0.5f) -1f else 1f
        private val loopRadiusX = HALF_WIDTH * (0.55f + 0.3f * stream.nextUnit())
        private val cpx = ArrayList<Float>()
        private val cpy = ArrayList<Float>()
        private val polyX = ArrayList<Float>()
        private val polyY = ArrayList<Float>()
        private val cumulative = ArrayList<Float>()
        private var finalised = 0
        private var block = 0
        private var endX = 0f

        /** The last point [at] found, and the unit tangent there. */
        var px = 0f; private set
        var py = 0f; private set
        var tx = 0f; private set
        var ty = 1f; private set

        init {
            endX = -flipX * (HALF_WIDTH - EDGE)
        }

        val length: Float get() = if (cumulative.isEmpty()) 0f else cumulative[cumulative.size - 1]

        fun ensure(arcLength: Float) {
            while (length < arcLength) {
                addBlock()
                finalise()
            }
        }

        fun at(s: Float) {
            ensure(s + STEP)
            val target = s.coerceAtLeast(0f)
            var lo = 0
            var hi = cumulative.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (cumulative[mid] < target) lo = mid else hi = mid
            }
            val span = (cumulative[hi] - cumulative[lo]).coerceAtLeast(1e-6f)
            val f = ((target - cumulative[lo]) / span).coerceIn(0f, 1f)
            val ax = polyX[lo]
            val ay = polyY[lo]
            val bx = polyX[hi]
            val by = polyY[hi]
            px = ax + (bx - ax) * f
            py = ay + (by - ay) * f
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            if (len > 1e-6f) {
                tx = (bx - ax) / len
                ty = (by - ay) / len
            }
        }

        private fun jitter(scale: Float) = gaussOf(stream) * scale

        private fun point(x: Float, y: Float) {
            cpx.add(x.coerceIn(-HALF_WIDTH + EDGE, HALF_WIDTH - EDGE))
            cpy.add(y)
        }

        private fun addBlock() {
            val y0 = blockTop
            val mx = HALF_WIDTH - EDGE
            if (cpx.isEmpty()) point(endX, y0)
            val startX = cpx[cpx.size - 1]
            val goRight = startX < 0f
            when (course) {
                Course.ROWS -> {
                    val rows = 6 + (stream.nextUnit() * 3f).toInt()
                    val h = 13f
                    var right = goRight
                    for (r in 1..rows) {
                        val yr = y0 + h * r / rows
                        val from = if (right) -mx else mx
                        val to = -from
                        point(from * 0.98f + jitter(0.2f), yr + jitter(0.25f))
                        point((from + to) / 2f + jitter(0.35f), yr + jitter(0.4f))
                        point(to + jitter(0.2f), yr + jitter(0.25f))
                        right = !right
                    }
                    blockTop = y0 + h
                }
                Course.SERPENTINE -> {
                    val columns = if (stream.nextUnit() < 0.5f) 3 else 5
                    val h = 9f + 4f * stream.nextUnit()
                    for (c in 0 until columns) {
                        val t = c.toFloat() / (columns - 1)
                        val xc = if (goRight) -mx + 2f * mx * t else mx - 2f * mx * t
                        val down = c % 2 == 0
                        for (k in 1..4) {
                            val f = k / 4f
                            val yy = if (down) y0 + h * f else y0 + h * (1f - f)
                            point(xc + jitter(0.3f), yy + jitter(0.3f))
                        }
                    }
                    blockTop = y0 + h
                }
                Course.ZIGZAG -> {
                    val legs = 4 + (stream.nextUnit() * 3f).toInt()
                    val h = 13f
                    var side = if (goRight) 1f else -1f
                    var px0 = startX
                    var py0 = y0
                    for (r in 1..legs) {
                        val xr = side * mx * (0.75f + 0.25f * stream.nextUnit())
                        val yr = y0 + h * r / legs
                        point(px0 + (xr - px0) * 0.33f + jitter(0.35f), py0 + (yr - py0) * 0.33f + jitter(0.35f))
                        point(px0 + (xr - px0) * 0.67f + jitter(0.35f), py0 + (yr - py0) * 0.67f + jitter(0.35f))
                        point(xr, yr)
                        px0 = xr
                        py0 = yr
                        side = -side
                    }
                    blockTop = y0 + h
                }
                Course.LOOPS -> {
                    val loops = 4 + (stream.nextUnit() * 3f).toInt()
                    val h = 13f
                    val total = TWO_PI * loops
                    val a = h / total
                    val ry = a * (1.8f + 0.8f * stream.nextUnit())
                    val sway = mx * 0.3f * stream.nextUnit()
                    val phase = stream.nextUnit() * TWO_PI
                    val sign = if (goRight) -1f else 1f
                    var th = PI.toFloat() / 4f
                    while (th <= total + 1e-4f) {
                        val xx = sign * loopRadiusX * cos(th) + sway * sin(th / loops + phase) * (1f - cos(th)) / 2f
                        val yy = y0 + a * th - ry * sin(th)
                        point(xx + jitter(0.15f), yy + jitter(0.15f))
                        th += PI.toFloat() / 4f
                    }
                    blockTop = y0 + h
                }
                Course.SPIRAL -> {
                    // A double spiral: in along one arm to the middle, out along the other. The
                    // arms interleave, so the river never crosses itself.
                    val r = min(mx, 3.2f) * (0.85f + 0.15f * stream.nextUnit())
                    val turns = 2f + stream.nextUnit()
                    val thMax = turns * TWO_PI
                    val cyy = y0 + 0.6f + r
                    val phi = -PI.toFloat() / 2f - thMax
                    val steps = (turns * 10f).toInt()
                    point(0f, cyy - r - 0.5f)
                    for (k in steps downTo 1) {
                        val th = thMax * k / steps
                        val rr = r * th / thMax
                        point(rr * cos(th + phi) + jitter(0.08f), cyy + rr * sin(th + phi) + jitter(0.08f))
                    }
                    for (k in 1..steps) {
                        val th = thMax * k / steps
                        val rr = r * th / thMax
                        point(-rr * cos(th + phi) + jitter(0.08f), cyy - rr * sin(th + phi) + jitter(0.08f))
                    }
                    blockTop = cyy + r + 0.6f
                }
                Course.WANDER -> {
                    // A wandering river that keeps away from where it has just been, and works its
                    // way down the sky.
                    val h = 13f
                    var xx = startX
                    var yy = y0
                    var heading = PI.toFloat() / 2f + jitter(0.4f)
                    val swing = stream.nextUnit() * TWO_PI
                    val start = cpx.size
                    var guard = 0
                    while (yy < y0 + h && guard < 80) {
                        guard++
                        var bestX = xx
                        var bestY = yy
                        var bestH = heading
                        var bestScore = -1e9f
                        for (c in 0 until 14) {
                            val hh = heading + jitter(0.7f)
                            val nx = xx + cos(hh) * 1.6f
                            val ny = yy + sin(hh) * 1.6f
                            var score = -max(0f, abs(nx) - mx) * 6f - max(0f, y0 - 1f - ny) * 6f
                            score += (ny - yy) * 0.12f
                            // Meanders: a slow swing from side to side that the river leans toward.
                            score -= abs(nx - mx * 0.8f * sin(swing + ny * 0.45f)) * 0.5f
                            for (q in start until cpx.size - 2) {
                                val d = sqrt((cpx[q] - nx) * (cpx[q] - nx) + (cpy[q] - ny) * (cpy[q] - ny))
                                if (d < 1.6f) score -= (1.6f - d) * 3f
                            }
                            score += stream.nextUnit() * 0.3f
                            if (score > bestScore) {
                                bestScore = score
                                bestX = nx
                                bestY = ny
                                bestH = hh
                            }
                        }
                        xx = bestX.coerceIn(-mx, mx)
                        yy = bestY
                        heading = bestH
                        point(xx, yy)
                    }
                    blockTop = max(yy, y0 + 1f)
                }
            }
            block++
        }

        private var blockTop = 0f

        /** Turns control points into polyline samples, as far as the points ahead allow. */
        private fun finalise() {
            val count = cpx.size
            // Segment i runs from point i to i+1 and needs i+2 to be known, except at the very end.
            while (finalised < count - 2) {
                val i = finalised
                val ax = cpx[max(0, i - 1)]
                val ay = cpy[max(0, i - 1)]
                val bx = cpx[i]
                val by = cpy[i]
                val cx = cpx[i + 1]
                val cy = cpy[i + 1]
                val dx = cpx[i + 2]
                val dy = cpy[i + 2]
                for (k in 0 until SAMPLES) {
                    val t = k.toFloat() / SAMPLES
                    val sx = catmullRom(ax, bx, cx, dx, t)
                    val sy = catmullRom(ay, by, cy, dy, t)
                    if (polyX.isEmpty()) {
                        cumulative.add(0f)
                    } else {
                        val lx = polyX[polyX.size - 1]
                        val ly = polyY[polyY.size - 1]
                        val step = sqrt((sx - lx) * (sx - lx) + (sy - ly) * (sy - ly))
                        cumulative.add(cumulative[cumulative.size - 1] + step)
                    }
                    polyX.add(sx)
                    polyY.add(sy)
                }
                finalised++
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun catmullRom(a: Float, b: Float, c: Float, d: Float, t: Float): Float =
        0.5f * (2f * b + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t * t + (-a + 3f * b - 3f * c + d) * t * t * t)

    /** A uniform value in [0, 1) from a star's or a run's identity and a salt. */
    internal fun unit(id: Long, salt: Int): Float = SkyRandom.unit(SkyRandom.mix(id, SALT_BASE + salt))

    /** A standard normal value from an identity and a salt (Box-Muller over two hashes). */
    internal fun gauss(id: Long, salt: Int): Float {
        val u1 = unit(id, salt * 2 + 1000).coerceAtLeast(1e-7f)
        val u2 = unit(id, salt * 2 + 1001)
        return sqrt(-2f * ln(u1)) * cos(TWO_PI * u2)
    }

    private fun gaussOf(stream: SkyStream): Float {
        val u1 = stream.nextUnit().coerceAtLeast(1e-7f)
        val u2 = stream.nextUnit()
        return sqrt(-2f * ln(u1)) * cos(TWO_PI * u2)
    }

    private fun reflectX(x: Float): Float {
        val m = HALF_WIDTH - 0.2f
        return when {
            x > m -> (2f * m - x).coerceAtLeast(-m)
            x < -m -> (-2f * m - x).coerceAtMost(m)
            else -> x
        }
    }

    private fun reflectTop(y: Float): Float {
        val top = -TOP_MARGIN + 0.2f
        return if (y < top) 2f * top - y else y
    }

    private const val HALF_WIDTH = WORLD_WIDTH / 2f
    private const val EDGE = 0.9f
    private const val TOP_MARGIN = 1.2f
    private const val BOTTOM_MARGIN = 1.2f
    private const val SINGLE_SPREAD = 0.9f
    private const val KNOT_STARS = 30
    private const val SAMPLES = 16
    private const val TWO_PI = (2.0 * PI).toFloat()
    private const val TYPE_OVAL = 0
    private const val TYPE_SPIRAL = 1
    private const val TYPE_OPEN = 2
    private const val TYPE_STREAM = 3
    private const val OPEN_SPREAD = 2.4f
    private const val OPEN_REACH = 1.5f
    private const val GALAXY_DENSITY = 26f
    private const val MIN_GALAXY_STARS = 36
    private const val MAX_GALAXY_RADIUS = 2.2f
    private const val GALAXY_REACH = 0.07f
    private const val GALAXY_SPIRAL = 0
    private const val GALAXY_BARRED = 1
    private const val GALAXY_ELLIPTICAL = 2
    private const val GALAXY_RING = 3
    private const val GALAXY_IRREGULAR = 4
    private const val FORM_SALT = 0x0F0E_4D11L
    private const val SIZE_SALT = 0x512E_0B5DL
    private const val GALAXY_SALT = 0x6A1A_0000_0000L
    private const val COURSE_SALT = 0x7C0FF5E1L
    private const val STREAM_SALT = 0x5712EA4DL
    private const val PATH_SALT = 0x2A7BC0DEL
    private const val SALT_BASE = 0x51DE_0000L
    private const val RUN_SALT = 0x3C1A_5000_0000L
}
