package com.daymark.app.sky

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * How the sky opens, and how the camera travels (`DECISIONS.md` §D11, #449).
 *
 * The stars twinkle in slowly, the pace builds gently into a burst, and the last ones settle; then
 * the camera flies to the newest star. Every star has its own moment to appear, a hash of its
 * identity, so the opening is the same sky every time and never a random one. Any tap skips the
 * whole of it, and with reduced motion there is no opening at all: the sky opens still, on today.
 *
 * Import-free, like the rest of `sky/`. Time is passed in; nothing here reads a clock.
 */
object SkyOpening {

    /** The opening's length, in seconds. */
    const val SECONDS = 8.4f

    /** When the camera sets off for the newest star, in seconds from the start. */
    const val FLY_AT = SECONDS + 0.3f

    /**
     * How far through the stars the opening is at [seconds], 0 to 1.
     *
     * The rate is one continuous curve, never a switch from slow to fast: a faint trickle for the
     * first two and a half seconds, a burst that builds over about three and a half more, then a
     * settle of about two as the last ones arrive. A star appears once this passes its [moment].
     */
    fun revealAt(seconds: Float): Float {
        val f = (seconds / SECONDS).coerceIn(0f, 1f) * STEPS
        val i = f.toInt()
        if (i >= STEPS) return 1f
        return table[i] + (table[i + 1] - table[i]) * (f - i)
    }

    /** When, through the opening, the star with this identity appears: 0 to 1. */
    fun moment(identity: Long): Float = SkyRandom.unit(SkyRandom.mix(identity, MOMENT_SALT))

    /**
     * How bright a star is drawn while the opening reaches [reveal], as a multiple of its own
     * brightness: nothing before its [moment], a brief soft flare as it arrives, then itself.
     */
    fun brightness(reveal: Float, moment: Float): Float {
        if (reveal >= 1f) return 1f
        val since = reveal - moment
        if (since < 0f) return 0f
        return (since / 0.03f).coerceIn(0f, 1f) * (1f + 1.4f * exp(-since * 35f))
    }

    /**
     * How long a new star takes to be born, in milliseconds: a cloud spins in and collapses, a core
     * warms and ignites, and a ring of light runs out. It plays once per newest star, after the
     * camera arrives, and only for a star the person has not yet watched arrive.
     */
    const val BIRTH_MILLIS = 6000L

    /**
     * How bright the star being born is drawn at [progress] through its birth, 0 to 1, as a
     * multiple of its own brightness: nothing until it ignites four fifths of the way through, the
     * same soft flare every star gets as it arrives, then itself.
     */
    fun bornBrightness(progress: Float): Float = when {
        progress >= 1f -> 1f
        progress < IGNITES -> 0f
        else -> brightness((progress - IGNITES) * 0.3f, 0f)
    }

    /** Where through a birth the new star ignites. */
    const val IGNITES = 0.8f

    private const val STEPS = 600
    private const val BURST_FROM = 2.6f
    private const val MOMENT_SALT = 0x0BE1_1A5EL

    private val table: FloatArray = run {
        fun smooth(a: Float, b: Float, x: Float): Float {
            val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return u * u * (3f - 2f * u)
        }
        fun trickle(t: Float) = smooth(0f, 1.2f, t) * (1f - smooth(SECONDS - 1.5f, SECONDS, t))
        fun burst(t: Float): Float {
            val x = (t - BURST_FROM) / (SECONDS - BURST_FROM)
            return if (x > 0f && x < 1f) x.pow(1.8f) * (1f - x) else 0f
        }
        var sumTrickle = 0f
        var sumBurst = 0f
        for (i in 0 until STEPS) {
            val t = (i + 0.5f) / STEPS * SECONDS
            sumTrickle += trickle(t)
            sumBurst += burst(t)
        }
        val c = FloatArray(STEPS + 1)
        for (i in 0 until STEPS) {
            val t = (i + 0.5f) / STEPS * SECONDS
            c[i + 1] = c[i] + 0.05f * trickle(t) / sumTrickle + 0.95f * burst(t) / sumBurst
        }
        c[STEPS] = 1f
        c
    }

    /**
     * One camera journey, from one view to another.
     *
     * Positions are the sky's own units; zoom is relative to the whole-sky view. A long way at a
     * close zoom first draws back, so the person sees where they are going, then closes in again.
     * While the zoom changes and there is no draw-back, the target point holds still on the screen.
     *
     * @param viewsApart how far apart the two places are, in screen widths at the wider of the two
     *   zooms. One screen or less needs no draw-back.
     */
    class Flight(
        private val fromX: Float,
        private val fromY: Float,
        private val fromZoom: Float,
        private val toX: Float,
        private val toY: Float,
        private val toZoom: Float,
        viewsApart: Float,
    ) {
        private val logFrom = ln(fromZoom.coerceAtLeast(1e-6f))
        private val logTo = ln(toZoom.coerceAtLeast(1e-6f))
        private val bump: Float = min(
            max(0f, ln(max(1e-6f, viewsApart / 0.6f))),
            max(0f, max(logFrom, logTo)),
        )

        /** How long the journey takes, in milliseconds. */
        val millis: Long = (1800f + 260f * min(6f, abs(logTo - logFrom) + bump)).toLong()

        var x = fromX; private set
        var y = fromY; private set
        var zoom = fromZoom; private set

        /** Moves the camera to where it is [elapsedMillis] into the journey. Returns true at the end. */
        fun at(elapsedMillis: Long): Boolean {
            val u = (elapsedMillis.toFloat() / millis).coerceIn(0f, 1f)
            val e = if (u < 0.5f) 4f * u * u * u else 1f - (-2f * u + 2f).pow(3) / 2f
            val logZoom = logFrom + (logTo - logFrom) * e - bump * sin(PI_F * e)
            zoom = exp(logZoom)
            val w0 = 1f / fromZoom
            val w1 = 1f / toZoom
            val k = if (abs(w1 - w0) > 1e-9f && bump == 0f) ((1f / zoom - w0) / (w1 - w0)).coerceIn(0f, 1f) else e
            x = fromX + (toX - fromX) * k
            y = fromY + (toY - fromY) * k
            if (u >= 1f) {
                x = toX
                y = toY
                zoom = toZoom
                return true
            }
            return false
        }
    }

    private const val PI_F = 3.1415927f
}
