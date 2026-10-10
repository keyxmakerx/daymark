package com.daymark.app.sky

import kotlin.math.sqrt

/**
 * Constellations the person draws between their own stars (`DECISIONS.md` §D11, #449).
 *
 * The software never groups stars; only the person does, by tapping them in order. A constellation
 * keeps two things: which memories it joins, and where those stars were on the day it was drawn.
 *
 *  - **In the live sky** its lines are measured against those drawn lengths. As the stars drift
 *    apart over the years a line fades ([lineAlpha]), so in time every constellation falls out of
 *    the sky, by itself and at its own pace.
 *  - **The photo** ("See it as you drew it") is drawn from the stored positions alone, with the
 *    stars that already existed that day around it. It is the only way the sky goes back in time.
 *  - **A deleted memory leaves nothing anywhere**: its point no longer resolves, so neither the
 *    live sky nor the photo draws it or any line to it. A constellation with fewer than two points
 *    left is not shown at all.
 *
 * Import-free, like the rest of `sky/`.
 */
object SkyConstellation {

    /** The longest name, in characters. */
    const val NAME_MAX = 28

    /** The name a constellation gets when the person leaves it blank. */
    const val UNTITLED = "Untitled"

    /** The fewest points that make a constellation. */
    const val MIN_POINTS = 2

    /** The most points one constellation can join. */
    const val MAX_POINTS = 40

    /** One point: a memory, and where its star was on the day the constellation was drawn. */
    data class Point(val kind: SkyKind, val recordId: Long, val x: Float, val y: Float)

    /** A name as the person typed it, made safe to keep: trimmed, on one line, at most [NAME_MAX]. */
    fun cleanName(raw: String): String {
        val sb = StringBuilder()
        var space = false
        for (ch in raw.trim()) {
            if (ch.isWhitespace() || ch.isISOControl()) {
                space = sb.isNotEmpty()
            } else {
                if (space) sb.append(' ')
                space = false
                sb.append(ch)
            }
        }
        var name = sb.toString()
        if (name.length > NAME_MAX) {
            var end = NAME_MAX
            // Never split a pair of surrogates.
            if (name[end - 1].isHighSurrogate()) end--
            name = name.substring(0, end).trimEnd()
        }
        return name.ifEmpty { UNTITLED }
    }

    /** The stored form of [points]: `kind,recordId,x,y` per point, joined by `;`. */
    fun encode(points: List<Point>): String =
        points.joinToString(";") { "${it.kind.key},${it.recordId},${it.x},${it.y}" }

    /** The points [text] holds. A malformed point, or one of a kind this version lacks, is skipped. */
    fun decode(text: String): List<Point> {
        if (text.isBlank()) return emptyList()
        return text.split(';').mapNotNull { part ->
            val f = part.split(',')
            if (f.size != 4) return@mapNotNull null
            val kind = SkyKind.fromKey(f[0]) ?: return@mapNotNull null
            val id = f[1].toLongOrNull() ?: return@mapNotNull null
            val x = f[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            val y = f[3].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            Point(kind, id, x, y)
        }
    }

    /** Where each point's star is in [layout]: its index, or -1 when the memory is gone. */
    fun resolve(points: List<Point>, layout: SkyLayout): IntArray =
        IntArray(points.size) { layout.indexOf(points[it].kind, points[it].recordId) }

    /** Whether a constellation still has anything to draw. */
    fun isShown(resolved: IntArray): Boolean = resolved.count { it >= 0 } >= MIN_POINTS

    /**
     * [resolved] as the live sky draws it: a point whose memory is put away is -1 there, so no
     * line reaches a star that is not drawn. Its photo still has it (`DECISIONS.md` §D11).
     */
    fun inSight(resolved: IntArray, layout: SkyLayout): IntArray =
        IntArray(resolved.size) { val s = resolved[it]; if (s >= 0 && layout.isShown(s)) s else -1 }

    /**
     * How strongly a line is drawn today: 1 while its stars stay about as far apart as when it was
     * drawn, fading out as they drift to between 1.25 and 1.6 times that, and gone beyond.
     */
    fun lineAlpha(drawnLength: Float, currentLength: Float): Float {
        val ratio = currentLength / drawnLength.coerceAtLeast(1e-6f)
        val u = ((ratio - 1.25f) / (1.6f - 1.25f)).coerceIn(0f, 1f)
        return 1f - u * u * (3f - 2f * u)
    }

    /**
     * Every line's strength on [onEpochDay]: one per pair of consecutive points, 0 where either
     * point is gone.
     */
    fun lineAlphas(points: List<Point>, resolved: IntArray, layout: SkyLayout, onEpochDay: Long): FloatArray {
        if (points.size < 2) return FloatArray(0)
        return FloatArray(points.size - 1) { i ->
            val a = resolved[i]
            val b = resolved[i + 1]
            if (a < 0 || b < 0) {
                0f
            } else {
                val drawn = distance(points[i].x, points[i].y, points[i + 1].x, points[i + 1].y)
                val now = distance(
                    layout.xOn(a, onEpochDay), layout.yOn(a, onEpochDay),
                    layout.xOn(b, onEpochDay), layout.yOn(b, onEpochDay),
                )
                lineAlpha(drawn, now)
            }
        }
    }

    /**
     * The stars around a constellation in its photo: those that already existed on [madeEpochDay]
     * and stood within [reach] of its middle that day, apart from its own. In time order.
     */
    fun neighbours(
        points: List<Point>,
        resolved: IntArray,
        layout: SkyLayout,
        madeEpochDay: Long,
        reach: Float,
    ): IntArray {
        val kept = points.indices.filter { resolved[it] >= 0 }
        if (kept.isEmpty()) return IntArray(0)
        val cx = (kept.minOf { points[it].x } + kept.maxOf { points[it].x }) / 2f
        val cy = (kept.minOf { points[it].y } + kept.maxOf { points[it].y }) / 2f
        val own = resolved.filter { it >= 0 }.toSet()
        val out = ArrayList<Int>()
        for (i in 0 until layout.starCount) {
            if (layout.epochDay[i] > madeEpochDay || i in own || !layout.isShown(i)) continue
            if (distance(layout.xOn(i, madeEpochDay), layout.yOn(i, madeEpochDay), cx, cy) <= reach) out.add(i)
        }
        return out.toIntArray()
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }
}
