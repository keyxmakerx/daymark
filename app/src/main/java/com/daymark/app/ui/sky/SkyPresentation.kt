package com.daymark.app.ui.sky

import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyCalendar
import com.daymark.app.sky.SkyDetail
import com.daymark.app.sky.SkyGlyph
import com.daymark.app.sky.SkyKind
import com.daymark.app.sky.SkyLayout
import com.daymark.app.sky.SkyListItem
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Everything the Sky's renderer would otherwise decide in a `Canvas` block where no test can reach
 * it: the viewport transform, hit testing, and the words a screen reader is given.
 *
 * `sky/` is import-free so its arithmetic runs on a plain JVM. This file cannot be — it formats
 * dates, and a date needs `java.time` — but it keeps the other half of that discipline: **no
 * Compose, no Android, no `Context`**. So the whole of the renderer's arithmetic compiles and runs
 * under JUnit, and `ui/sky/SkySurface.kt` is left holding draw calls and gesture plumbing only.
 * That split is the point. There is no Android SDK in this authoring environment, so anything
 * living in the Compose file is unexecutable until CI; anything living here is checked.
 *
 * Nothing here decides *what the sky is*. Every constant that carries meaning — core radius, halo
 * geometry, the mood ramp, the zoom thresholds — is read from `sky/`. What this file owns is the
 * mapping from that layout onto a rectangle of pixels, which `sky/` deliberately knows nothing
 * about.
 */
object SkyPresentation {

    // -------------------------------------------------------------------------------------------
    // Zoom. One parameter, a plain scale factor over the whole sky: at 1 all of it fits the screen.
    // -------------------------------------------------------------------------------------------

    /**
     * The whole sky in the viewport, which is [SkyDetail.FAR]. The opening ends somewhere closer,
     * on the newest star; this is where "Fit the whole sky" goes back to.
     */
    const val DEFAULT_ZOOM = 1f

    /**
     * The zoom-out stop: the sky exactly fits the viewport and never shrinks inside it.
     *
     * Letting it shrink would put a border of nothing around the sky, and a hard edge with empty
     * space beyond it is the one shape §1 spends four pages ruling out.
     */
    const val MIN_ZOOM = 1f

    /**
     * The zoom-in stop: far enough in that a star stops being a point of light and is drawn as a
     * sun of its own (`DECISIONS.md` §D11). Overlap is accepted and resolved by zoom, never by
     * moving a star (`docs/SKY.md` §3.1), and long before this every pair is apart.
     */
    const val MAX_ZOOM = 400f

    /**
     * What "maximum contrast" is, arithmetically.
     *
     * §7.1's quiet sky draws the mood colours at maximum contrast, which needed a number or it
     * would have become a guess inside a draw call. Every colour still goes through
     * [com.daymark.app.sky.SkyPalette.equalised] — the same transform, a higher target — rather than
     * through a second code path, so the ramp stays **level** at this setting too. Lifting the ramp
     * to a ceiling instead would re-introduce the ranking equalisation exists to remove, only
     * brighter: the mid-ramp would pin against white while the worst mood sat below it, which is the
     * original cruelty with the contrast turned up.
     *
     * It was 9.0 on the old `#16150F` ground and is 10.0 on `#07070A`, re-measured with everything
     * else in that pass. The number is fixed by the same cliff `SkyPalette`'s header describes: at
     * 9.0 on the old ground exactly one shipped mood — level 1, the darkest — had to be blended
     * toward the ink to reach the target, and on this ground 10.0 is the brightest value that keeps
     * that shape. At 10.5 level 2 joins it, and the quiet sky would start washing out a second of
     * the person's own colours.
     *
     * 10.0 is most of the way to the sky's own ink (16.0:1 on the night ground) and leaves the
     * ground still legibly a night ground rather than a grey one.
     */
    const val HIGH_CONTRAST_TARGET = 10.0

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /**
     * The narrowest stretch of sky one screen width can show, in the sky's own units. A memory's
     * step along the sky's guide is 0.005, so at the closest a single star fills much of the screen
     * and is drawn as a sun ([sunRadius]).
     */
    const val CLOSEST_SPAN = 0.004f

    /**
     * How much sky is across the screen on Today, and where the opening ends: about eight
     * memories' steps, so the newest star is a place with its neighbours round it.
     */
    const val TODAY_SPAN = 0.04f

    /**
     * How far in this viewport can zoom: until [CLOSEST_SPAN] fills its width. Measured against the
     * screen rather than as a fixed factor, so a tall sky, which fits the screen narrower, can still
     * be followed in as far as a short one.
     */
    fun zoomLimit(fitPx: Float, viewportWidthPx: Float): Float =
        if (fitPx <= 0f) MIN_ZOOM else max(MIN_ZOOM, viewportWidthPx / CLOSEST_SPAN / fitPx)

    /** The zoom that puts [span] of the sky across a viewport [viewportWidthPx] wide. */
    fun zoomForSpan(span: Float, fitPx: Float, viewportWidthPx: Float): Float =
        if (fitPx <= 0f) MIN_ZOOM else viewportWidthPx / span / fitPx

    /**
     * The zoom that frames a constellation [width] by [height] in a little under half the screen
     * each way, with a floor so two stars drawn close together are not flown into as suns.
     */
    fun zoomToFrame(
        width: Float,
        height: Float,
        fitPx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
    ): Float {
        if (fitPx <= 0f) return MIN_ZOOM
        val scale = min(
            viewportWidthPx * FRAME / max(width, FRAME_MIN_SPAN),
            viewportHeightPx * FRAME / max(height, FRAME_MIN_SPAN),
        )
        return scale / fitPx
    }

    private const val FRAME = 0.45f
    private const val FRAME_MIN_SPAN = 0.012f

    /**
     * A star's radius as a sun, in pixels at [scalePx]. Real size in the sky's own units, so it
     * grows with zoom like a place does; a life event is a bigger star, as the person's own marks
     * are (`DECISIONS.md` §D11). Drawn only once it is bigger than the star's point of light.
     */
    fun sunRadius(kind: SkyKind, scalePx: Float): Float =
        scalePx * if (kind == SkyKind.LIFE_EVENT) SUN_RADIUS * 1.7f else SUN_RADIUS

    private const val SUN_RADIUS = 0.00012f

    /**
     * How strongly constellation lines and names are drawn: not until the opening has nearly
     * finished, and fading away as the sky is followed in past a few dozen screens across, where
     * a constellation is too big to read as one.
     */
    fun constellationAlpha(reveal: Float, screenWidthsAcross: Float): Float =
        smoothstep(0.85f, 1f, reveal) * (1f - smoothstep(40f, 160f, screenWidthsAcross))

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val u = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /**
     * How much detail to draw, from how many screen widths the sky's full width would span. A tall
     * sky fits the screen narrower than a short one, so detail follows what is on screen rather
     * than the zoom factor alone.
     */
    fun detailFor(screenWidthsAcross: Float): SkyDetail = SkyDetail.forZoom(clampZoom(screenWidthsAcross))

    /**
     * The sky's extent in its own units: the world it was laid out in (one wide, [height] tall),
     * and wherever a star has drifted to beyond it, with a little room round the edge.
     */
    class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    fun boundsOf(xs: FloatArray, ys: FloatArray, height: Float): Bounds {
        var left = 0f
        var top = 0f
        var right = 1f
        var bottom = height.coerceAtLeast(1f)
        for (i in xs.indices) {
            if (xs[i] < left) left = xs[i]
            if (xs[i] > right) right = xs[i]
            if (ys[i] < top) top = ys[i]
            if (ys[i] > bottom) bottom = ys[i]
        }
        val pad = 0.03f
        return Bounds(left - pad, top - pad, right + pad, bottom + pad)
    }

    /** Pixels per unit of the sky at zoom 1: the whole of [bounds] fits the viewport. */
    fun fitPx(viewportWidthPx: Float, viewportHeightPx: Float, bounds: Bounds): Float =
        minOf(viewportWidthPx / bounds.width, viewportHeightPx / bounds.height)

    /**
     * Pixels per unit of the sky at [zoom].
     *
     * At [MIN_ZOOM] it is exactly the fit, so a sky with five stars in it spreads across the screen
     * and a sky with fifteen thousand is dense, **without any star's position changing**.
     */
    fun scalePx(fitPx: Float, zoom: Float): Float = fitPx * clampZoom(zoom)

    // -------------------------------------------------------------------------------------------
    // Pan. `pan` is where the sky's top-left corner sits on screen, so screen = content + pan.
    // -------------------------------------------------------------------------------------------

    /**
     * Keeps the sky reachable without letting it be dragged off the screen.
     *
     * When the content is smaller than the viewport it is centred rather than pinned to a corner —
     * a person's sky belongs in the middle of the screen, not hugging the top-left with a void
     * under it. "Void" is not a metaphor here: empty space on this surface is the thing §1 spends
     * four pages ruling out, and a layout that parks the field against one edge manufactures
     * exactly that.
     */
    fun clampPan(pan: Float, contentPx: Float, viewportPx: Float): Float =
        if (contentPx <= viewportPx) (viewportPx - contentPx) / 2f
        else pan.coerceIn(viewportPx - contentPx, 0f)

    /**
     * Where the content's edge has to move so that the point under somebody's fingers stays there.
     *
     * A pinch used to scale the field about its own top-left corner, because the gesture's centroid
     * was discarded. The effect is the one the maintainer described as "pinch to widen": the sky
     * grows, but whatever you were pinching slides away from between your fingers, and on a surface
     * with no landmarks and no labels there is nothing to navigate back by. A pinch has to be a
     * magnifying glass held over a place.
     *
     * The arithmetic, on one axis. Screen is `content * u + pan`, so the point under the focus is
     * `u = (focus - pan) / content`. Holding it under the focus after the zoom means
     * `focus = u * content' + pan'`, and since both content sizes are the fit times their zoom,
     * `content' / content` is just `to / from`:
     *
     *     pan' = focus - (focus - pan) * (to / from)
     *
     * [to] is the zoom AFTER clamping, deliberately. At either stop a further pinch changes nothing,
     * so the ratio is 1 and the sky holds still rather than drifting under fingers that are still
     * moving — the shape of bug where a control keeps responding after it has stopped having an
     * effect. When [from] is not positive there is no meaningful previous scale, so the pan is
     * returned untouched rather than divided by zero.
     *
     * The result is not clamped here. Clamping needs the viewport and the new content size, the
     * caller has both, and it must happen after the drag part of the same gesture is added — a
     * pinch and a drag are one movement and clamping between them would fight the finger.
     */
    fun panForZoomAbout(focusPx: Float, panPx: Float, from: Float, to: Float): Float =
        if (from <= 0f) panPx else focusPx - (focusPx - panPx) * (to / from)

    /** Where a point of the sky is on screen, on one axis: [origin] is the sky's edge on that axis. */
    fun toScreen(coordinate: Float, origin: Float, scalePx: Float, panPx: Float): Float =
        (coordinate - origin) * scalePx + panPx

    /** The point of the sky on screen at [screenPx], on one axis. The inverse of [toScreen]. */
    fun fromScreen(screenPx: Float, origin: Float, scalePx: Float, panPx: Float): Float =
        (screenPx - panPx) / scalePx + origin

    /** The pan that puts [coordinate] at the middle of a viewport [viewportPx] across. */
    fun panToCentre(coordinate: Float, origin: Float, scalePx: Float, viewportPx: Float): Float =
        viewportPx / 2f - (coordinate - origin) * scalePx

    /**
     * Whether a star is close enough to the viewport to be worth drawing.
     *
     * Culling used to be a contiguous slice of the packed arrays, because x was monotonic in the
     * date and every star of a month row sat next to the rest of its row. With no rows
     * (`docs/SKY.md` §3.1) there is no order in the arrays that corresponds to any order on the
     * screen, so the renderer walks all of them and asks this (§8.2 rule 4). That is a bounds check
     * per star per frame over a primitive array with no allocation — a few tens of microseconds for
     * ten years of daily use, which the layout's own timing test bounds.
     *
     * [marginPx] keeps a star that is half off the edge drawn instead of popping in.
     */
    fun isOnScreen(
        screenXPx: Float,
        screenYPx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        marginPx: Float,
    ): Boolean =
        screenXPx >= -marginPx && screenXPx <= viewportWidthPx + marginPx &&
            screenYPx >= -marginPx && screenYPx <= viewportHeightPx + marginPx

    /**
     * The star nearest to a tap, or `-1`.
     *
     * §7.1: every star is a 48 dp target however small it is drawn, and where targets overlap the
     * tap resolves to the **nearest core** — it does not grow the star underneath, because a star
     * that swells when you reach for it is a star whose size means something other than what
     * [SkyGlyph] says it means. Overlap is not resolved by moving anything: `docs/SKY.md` §3.1
     * accepts it, and a star nudged away from a neighbour would be a star whose position depended
     * on other records.
     *
     * [xs] and [ys] are where the stars are now, drift included. Ties go to the lower index, which
     * is the earlier record: the arrays are in time order, so two stars exactly equidistant resolve
     * the same way on every device and every open.
     */
    fun nearestStar(
        xs: FloatArray,
        ys: FloatArray,
        bounds: Bounds,
        scalePx: Float,
        panXPx: Float,
        panYPx: Float,
        tapXPx: Float,
        tapYPx: Float,
        maxDistancePx: Float,
    ): Int {
        var best = -1
        var bestDistanceSquared = maxDistancePx * maxDistancePx
        for (i in xs.indices) {
            val dx = toScreen(xs[i], bounds.left, scalePx, panXPx) - tapXPx
            val dy = toScreen(ys[i], bounds.top, scalePx, panYPx) - tapYPx
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared
                best = i
            }
        }
        return best
    }

    // -------------------------------------------------------------------------------------------
    // Words. Everything a screen reader is given comes from here, so §7.3's rules are testable.
    // -------------------------------------------------------------------------------------------

    /**
     * What the canvas is, for someone who will never see it.
     *
     * §7.3: at [SkyDetail.FAR] the canvas is a **single node described by what it is, not by what
     * it contains**. It does not enumerate its stars and it announces no total — a screen-reader
     * user who wants the contents gets the list (§7.5), which is a peer surface, not a summary read
     * out of this one.
     *
     * The span is the years the sky covers, read off the earliest and latest star rather than off
     * any geometry — there is none any more. It is a property of the person's history the way
     * "January to December" is a property of a calendar, and not a count of anything they did.
     */
    fun canvasDescription(layout: SkyLayout, locale: Locale): String {
        if (layout.starCount == 0) return SkyLayout.EMPTY_LINE
        // The arrays are in time order, so the ends are the span. No scan, and no min/max that
        // could quietly become a "busiest year".
        val firstYear = SkyCalendar.civilOf(layout.epochDay[0]).year
        val lastYear = SkyCalendar.civilOf(layout.epochDay[layout.starCount - 1]).year
        return if (firstYear == lastYear) {
            "Your sky, $firstYear."
        } else {
            "Your sky, $firstYear to $lastYear."
        }
    }

    /**
     * One star, in words: what it was, when, and its mood if it had one.
     *
     * The order is deliberate. [SkyKind.introduction] leads because kind is what the glyph carries
     * and it is the one thing a person cannot recover from the position. The mood comes last and
     * **as the person's own label**, never as a number and never as a word this file chose — §7.2
     * requires mood to be available outside colour, and §D1b requires it to be reflected back
     * rather than interpreted.
     *
     * There is no prose here and there cannot be: [SkyLayout] has no text field to read one from.
     * A journal star says that something was written on a date, which is the whole of what §4.1
     * allows anyone standing behind the person to learn.
     */
    fun starDescription(
        kind: SkyKind,
        epochDay: Long,
        moodLevel: Int,
        recordCount: Int,
        moodLabel: (Int) -> String,
        locale: Locale,
    ): String {
        val parts = StringBuilder(kind.introduction)
        parts.append(' ').append(dateLabel(epochDay, locale)).append('.')
        if (moodLevel in SkyGlyph.MOOD_MIN..SkyGlyph.MOOD_MAX) {
            parts.append(' ').append(moodLabel(moodLevel)).append('.')
        }
        // Only when the star is folded, and phrased as what it opens rather than as a tally. A
        // number attached to a day the person can compare against another day is a ranking of days
        // (§6.2); a number telling someone that activating this mark reaches three records is how
        // they find the other two.
        if (recordCount > 1) parts.append(" Covers ").append(recordCount).append(" records.")
        return parts.toString()
    }

    /** [starDescription] for a star already in a laid-out sky. */
    fun starDescription(
        layout: SkyLayout,
        index: Int,
        moodLabel: (Int) -> String,
        locale: Locale,
    ): String = starDescription(
        kind = layout.kindAt(index),
        epochDay = layout.epochDay[index],
        moodLevel = layout.moodLevel[index],
        recordCount = layout.recordCountAt(index),
        moodLabel = moodLabel,
        locale = locale,
    )

    /**
     * A month heading in the text equivalent — "March 2024, 6 items".
     *
     * The count is list structure at the point of navigation and the only number the Sky produces
     * (see [Sky.list]). It is never summed with another heading's, never compared, and never drawn
     * on the sky itself.
     */
    fun monthHeading(heading: SkyListItem.MonthHeading, locale: Locale): String {
        val month = Month.of(heading.month).getDisplayName(TextStyle.FULL_STANDALONE, locale)
        val items = if (heading.itemCount == 1) "1 item" else "${heading.itemCount} items"
        return "$month ${heading.year}, $items"
    }

    // There is no "where you are" label any more, and there is no function here that could produce
    // one. `monthLabel(epochMonth, locale)` named the month at the top of the viewport, which was
    // meaningful only while the sky was a stack of month rows. With placement random
    // (`docs/SKY.md` §3.1) the top of the viewport is not a date and no part of the screen is: a
    // label there would be a claim about where you are that is not true.
    // The way to reach a particular date is the text list, which keeps its month headings.

    /**
     * A constellation's card: when it was drawn, and the memories it joins. A count of the stars
     * the person chose to join, never of anything they did.
     */
    fun constellationSummary(madeEpochDay: Long, starDays: List<Long>, locale: Locale): String {
        val drawn = "Drawn ${dateLabel(madeEpochDay, locale)}."
        if (starDays.isEmpty()) return drawn
        val first = starDays.min()
        val last = starDays.max()
        val span = if (first == last) {
            "all from ${dateLabel(first, locale)}"
        } else {
            "${dateLabel(first, locale)} to ${dateLabel(last, locale)}"
        }
        val memories = if (starDays.size == 1) "1 memory" else "${starDays.size} memories"
        return "$drawn $memories, $span."
    }

    /** A constellation's row in the list. */
    fun constellationRow(madeEpochDay: Long, stars: Int, locale: Locale): String =
        "Drawn ${dateLabel(madeEpochDay, locale)} · " + if (stars == 1) "1 star" else "$stars stars"

    /** What the draw bar says as the person joins stars. */
    fun drawingPrompt(joined: Int): String = when (joined) {
        0 -> "Tap a star to start"
        1 -> "1 star. Tap the next one"
        else -> "$joined stars joined"
    }

    fun dateLabel(epochDay: Long, locale: Locale): String =
        LocalDate.ofEpochDay(epochDay)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    /**
     * Whether activating a star of this kind can hand off to the record behind it.
     *
     * Four kinds can: the star's id is the row id of something with a screen of its own, and §4.1's
     * "the Sky names the act and hands off to the feature that owns the content" is satisfied by
     * navigating there.
     *
     * Two cannot, and both are honest gaps rather than decisions:
     *
     *  - **A project step** is identified by its own row id, and the screen that shows it is the
     *    *goal* editor, keyed by `goalId`. [SkyLayout] carries no goal id and must not start
     *    carrying one for this alone — a star's payload is `(kind, id, day, mood)` and every field
     *    added to it is a field the Sky could later draw.
     *  - **A life event** has a list, not a per-row screen. The list is one tap away on the Sky's
     *    own control, which is where §2.2 puts the affordance.
     *
     * A kind that cannot hand off shows no action at all, rather than a disabled one: a control
     * that is present and refuses is worse than a control that was never offered.
     */
    fun opensRecord(kind: SkyKind): Boolean = when (kind) {
        SkyKind.CHECK_IN, SkyKind.JOURNAL, SkyKind.PRACTICE, SkyKind.GOAL_REACHED -> true
        SkyKind.PROJECT_STEP, SkyKind.LIFE_EVENT -> false
    }

    /**
     * Whether activating this star does anything at all.
     *
     * Wider than [opensRecord] by one kind: a life event has no per-row screen but it does have the
     * list, so activating one is not a dead end. A project step is the only kind where nothing
     * happens, and the surface shows it no action rather than an inert one.
     *
     * This exists as a function because the sky and the list have to agree about it. Two copies of
     * `opensRecord(kind) || kind == LIFE_EVENT` would be two places for them to drift apart, and a
     * row that is tappable in the list but not on the sky breaks §7.5's "same actions" — which is
     * the whole basis for calling the list a peer surface rather than a fallback.
     */
    fun hasAction(kind: SkyKind): Boolean = opensRecord(kind) || kind == SkyKind.LIFE_EVENT
}
