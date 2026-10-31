package com.daymark.app.sky

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Two objects in the sky that are not memories (`DECISIONS.md` §D11): a pulsar for each habit the
 * person gave a set time (#519), and a ringed planet for each place they named (#518).
 *
 * What both must keep true, and why each function takes only what it does:
 * - **A pulsar marks its habit's time and never its record.** Its beat is one fixed rate for every
 *   pulsar, and nothing here is handed whether the habit was done, how often, or a streak: the
 *   functions take a habit's id, the day it was made and its set time, and nothing else, so the
 *   pulse cannot speed, slow, dim or brighten with the record (`DECISIONS.md` §D6).
 * - **No count or mood changes a planet.** It sits among the memories the person put in its place
 *   and its size, ring and light come from the seed alone; how many memories it has is never read.
 * - **No location is read for a place.** A place is a name the person typed and nothing more.
 *
 * Import-free, like the rest of `sky/`.
 */
object SkyObjects {

    // ---------------------------------------------------------------------------------------
    // Pulsars
    // ---------------------------------------------------------------------------------------

    /** A habit with a set time: its id, the day it was made, and its time, minutes after midnight. */
    class Habit(val goalId: Long, val createdEpochDay: Long, val atMinute: Int)

    /**
     * A pulsar, placed.
     *
     * @property slope how its two jets lean as they turn, in radians, like a lighthouse on a slope.
     * @property phase where in its turn it starts, `0..1`, from the seed.
     */
    class Pulsar(val goalId: Long, val atMinute: Int, val x: Float, val y: Float, val slope: Float, val phase: Float)

    /** One turn of every pulsar's jets, in seconds: one fixed beat, the same for every habit. */
    const val PULSAR_BEAT_SECONDS = 2.4f

    /** How much brighter a pulsar is at the peak of its flash from the whole sky, and from close in. */
    const val PULSAR_FAR_PEAK = 1.2f
    const val PULSAR_NEAR_PEAK = 2f

    /** How far in the full flash and full jets arrive, in screen widths across. */
    const val PULSAR_FULL_ZOOM = 14f

    /**
     * Places each habit beside the star in sight nearest the day it was made. Only a habit with a
     * set time is ever handed in ([Habit.atMinute]); one without has no pulsar (`DECISIONS.md` §D11).
     */
    fun placePulsars(habits: List<Habit>, layout: SkyLayout, xs: FloatArray, ys: FloatArray, seed: Long): List<Pulsar> =
        habits.sortedBy { it.goalId }.map { habit ->
            val near = SkyTrackers.nearestByDay(layout, habit.createdEpochDay)
            val random = SkyStream(SkyRandom.mix(seed xor PULSAR_SALT, habit.goalId))
            val angle = random.nextUnit() * TAU
            val away = random.nextBetween(0.006f, 0.014f)
            val baseX = if (near >= 0) xs[near] else 0.5f
            val baseY = if (near >= 0) ys[near] else layout.height / 2f
            Pulsar(
                goalId = habit.goalId,
                atMinute = habit.atMinute,
                x = (baseX + cos(angle) * away).coerceIn(0f, 1f),
                y = (baseY + sin(angle) * away).coerceIn(0f, max(layout.height, 0f)),
                slope = random.nextBetween(0.35f, 0.75f) * (if (random.nextUnit() < 0.5f) -1f else 1f),
                phase = random.nextUnit(),
            )
        }

    /**
     * Where a pulsar's jets point at [seconds], as an angle round its turn in radians. Turning at
     * [PULSAR_BEAT_SECONDS] and nothing else; still at its starting angle when motion is off
     * ([seconds] held at 0).
     */
    fun jetAngle(pulsar: Pulsar, seconds: Float): Float =
        ((pulsar.phase + seconds / PULSAR_BEAT_SECONDS) % 1f) * TAU

    /**
     * How strongly a pulsar flashes at [angle]: brightest as a jet sweeps toward the viewer, which
     * happens twice a turn, once for each jet. `0..1`.
     */
    fun flash(angle: Float): Float {
        val c = cos(angle.toDouble()).toFloat()
        return Math.pow(c.toDouble() * c, 6.0).toFloat()
    }

    /**
     * How far in the view is toward a pulsar's full flash and jets: 0 at the whole sky, 1 from
     * [PULSAR_FULL_ZOOM] screens across, so from far away it beats quietly and its jets are short.
     */
    fun nearness(screenWidthsAcross: Float): Float {
        if (!(screenWidthsAcross > 1f)) return 0f
        val t = (Math.log(screenWidthsAcross.toDouble()) / Math.log(PULSAR_FULL_ZOOM.toDouble())).toFloat()
        return t.coerceIn(0f, 1f)
    }

    /** A pulsar's brightness against its steady light at [flash] and [nearness]. */
    fun pulsarLight(flash: Float, nearness: Float): Float {
        val peak = PULSAR_FAR_PEAK + (PULSAR_NEAR_PEAK - PULSAR_FAR_PEAK) * nearness.coerceIn(0f, 1f)
        return 1f + (peak - 1f) * flash.coerceIn(0f, 1f)
    }

    /** How long its jets are drawn against their full length, short and faint from far away. */
    fun jetLength(nearness: Float): Float = 0.3f + 0.7f * nearness.coerceIn(0f, 1f)

    // ---------------------------------------------------------------------------------------
    // Planets
    // ---------------------------------------------------------------------------------------

    /**
     * A ringed planet, placed.
     *
     * @property radius its body, in the sky's units, from the seed alone.
     * @property tilt how its ring is turned, and [open] how round the ring is seen.
     * @property tone which of the sky's own colours it takes, `0..2`.
     */
    class Planet(
        val placeId: Long,
        val name: String,
        val x: Float,
        val y: Float,
        val radius: Float,
        val tilt: Float,
        val open: Float,
        val tone: Int,
    )

    /**
     * One planet for each of [places] that has a memory in sight in it, among those memories: at
     * the middle of where they sit today, nudged a little by the seed so it covers none of them.
     * A place with no memory in sight has nothing to gather and is not drawn. No star moves.
     */
    fun placePlanets(
        places: List<SkyStars.Place>,
        chosen: SkyStars.Resolved,
        layout: SkyLayout,
        xs: FloatArray,
        ys: FloatArray,
        seed: Long,
    ): List<Planet> {
        if (places.isEmpty()) return emptyList()
        val out = ArrayList<Planet>()
        for (place in places.sortedBy { it.id }) {
            var sx = 0.0
            var sy = 0.0
            var n = 0
            for (i in 0 until layout.starCount) {
                if (chosen.placeAt(i) != place.id || !layout.isShown(i)) continue
                sx += xs[i]
                sy += ys[i]
                n++
            }
            if (n == 0) continue
            val random = SkyStream(SkyRandom.mix(seed xor PLANET_SALT, place.id))
            val radius = random.nextBetween(PLANET_MIN_RADIUS, PLANET_MAX_RADIUS)
            val angle = random.nextUnit() * TAU
            val away = radius * 3f
            out.add(
                Planet(
                    placeId = place.id,
                    name = place.name,
                    x = ((sx / n).toFloat() + cos(angle) * away).coerceIn(0f, 1f),
                    y = ((sy / n).toFloat() + sin(angle) * away).coerceIn(0f, max(layout.height, 0f)),
                    radius = radius,
                    tilt = (random.nextUnit() - 0.5f) * 1.2f,
                    open = random.nextBetween(0.22f, 0.4f),
                    tone = (random.nextUnit() * 3f).toInt().coerceAtMost(2),
                ),
            )
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Meteors
    // ---------------------------------------------------------------------------------------

    /** A new meteor leaves the shower's star this often, in seconds, and each lasts [METEOR_LIFE_SECONDS]. */
    const val METEOR_EVERY_SECONDS = 1.7f
    const val METEOR_LIFE_SECONDS = 0.9f

    /**
     * The meteor in flight at [seconds], if any, written to [out] as its direction in radians, how
     * far through its flight it is (`0..1`) and its brightness (`0..1`); false between meteors.
     * Each one's direction comes from the seed and its own number, so the shower is the same
     * every time it is watched; there is no meteor at all with motion off, where the screen hands
     * no star in.
     */
    fun meteor(seconds: Float, seed: Long, out: FloatArray): Boolean {
        if (!(seconds >= 0f)) return false
        val k = (seconds / METEOR_EVERY_SECONDS).toLong()
        val t = (seconds - k * METEOR_EVERY_SECONDS) / METEOR_LIFE_SECONDS
        if (t !in 0f..1f) return false
        val random = SkyStream(SkyRandom.mix(seed xor METEOR_SALT, k))
        out[0] = random.nextUnit() * TAU
        out[1] = t
        // In fast, out slow: bright at once, fading as it goes.
        out[2] = (1f - t) * (1f - t) * kotlin.math.min(1f, t * 8f)
        return true
    }

    const val PLANET_MIN_RADIUS = 0.0018f
    const val PLANET_MAX_RADIUS = 0.0028f

    private const val TAU = (2.0 * PI).toFloat()
    private const val PULSAR_SALT = 0x9_0154_ABL
    private const val PLANET_SALT = 0x9_1A2E7L
    private const val METEOR_SALT = 0x3_E7E0_25L
}
