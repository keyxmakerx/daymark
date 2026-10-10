package com.daymark.app.sky

import kotlin.math.max

/**
 * Putting memories away and bringing them back, as passing events (`DECISIONS.md` §D11).
 *
 * **Put away.** A small black hole forms beside the memories, draws them in on a slow spiral while
 * they still shine, fading as they reach it, then closes and is gone. Nothing in the sky marks
 * where they were afterwards: no hole, no gap, no point.
 *
 * **Brought back.** A white hole forms where they were put away, sends each one home to where it
 * was, one after another, and fades away.
 *
 * Neither is framed as loss: nothing is eaten, nothing is lost, and both are over in seconds. With
 * motion off neither plays; the memories go, or come back, where they stand.
 *
 * Everything here is a pure function of the seconds since the event began, so the event can be
 * checked to end, and to end with nothing left behind, on a plain JVM.
 */
object SkyHoles {

    /** How long the hole takes to form. */
    const val FORMS = 1.2f

    /** Each memory after the first starts this much later than the one before. */
    const val STAGGER = 0.45f

    /** How long one memory takes to be drawn in, or sent home. */
    const val TRAVEL = 3.6f

    /** How long the hole takes to close, or fade, once the last memory has arrived. */
    const val CLOSES = 1.6f

    /** The most memories one event moves one at a time; past it they move together with the last. */
    const val MOST_STAGGERED = 8

    /** How long the event lasts for [count] memories, in seconds. */
    fun duration(count: Int): Float = arrivedBy(count) + CLOSES

    /** When the last of [count] memories has arrived. */
    private fun arrivedBy(count: Int): Float = FORMS + startOf(max(count, 1) - 1) + TRAVEL

    private fun startOf(order: Int): Float = STAGGER * order.coerceAtMost(MOST_STAGGERED)

    /** How strongly the hole is there at [seconds], 0..1: forming, there, then gone. */
    fun holeAt(seconds: Float, count: Int): Float {
        val closeFrom = arrivedBy(count)
        return smoothstep(0f, FORMS, seconds) * (1f - smoothstep(closeFrom, closeFrom + CLOSES, seconds))
    }

    /** How far memory [order] has travelled at [seconds], 0 not yet to 1 arrived. */
    fun travelAt(seconds: Float, order: Int): Float {
        val from = FORMS + startOf(order)
        return smoothstep(from, from + TRAVEL, seconds)
    }

    /**
     * A memory being drawn in, at [progress] 0..1: how far from the hole it still is, as a share of
     * where it started, and how far it has turned round it, in radians. It turns faster the closer
     * it gets, the way anything falling in does.
     */
    fun inwardRadius(progress: Float): Float {
        val e = ease(progress)
        return (1f - e) + HORIZON * e
    }

    fun inwardTurn(progress: Float): Float = ease(progress) * INWARD_TURNS

    /** How brightly a memory being drawn in still shines: fully, then fading as it reaches the hole. */
    fun inwardLight(progress: Float): Float = 1f - smoothstep(0.65f, 1f, progress)

    /** A memory being sent home, at [progress] 0..1: how far along its way home it is, 0..1. */
    fun outwardShare(progress: Float): Float = 1f - (1f - progress) * (1f - progress)

    /** How far it is turned on its way home, in radians, unwinding to none as it arrives. */
    fun outwardTurn(progress: Float): Float = (1f - outwardShare(progress)) * OUTWARD_TURNS

    /** How brightly a memory being sent home shines: coming up as it leaves the white hole. */
    fun outwardLight(progress: Float): Float = smoothstep(0f, 0.3f, progress)

    /** Where a memory being drawn in ends, as a share of its starting distance: at the hole's edge. */
    private const val HORIZON = 0.12f
    private const val INWARD_TURNS = 4.5f
    private const val OUTWARD_TURNS = 3.2f

    private fun ease(progress: Float): Float = progress.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
