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
    // Zoom. One parameter, a plain scale factor over the normalised field — there are no month rows
    // to count any more, and having two zoom numbers would let the level shown disagree with the
    // level drawn.
    // -------------------------------------------------------------------------------------------

    /**
     * Where the Sky opens: the whole field in the viewport, which is [SkyDetail.FAR].
     *
     * §4 calls it "the view you leave open", and it is also the honest first frame — the shape of a
     * sky, with nothing singled out. It is deliberately not "today, close up": the Sky has no start
     * (§4.2), and now that position carries no time at all there is no "the present" to open on.
     */
    const val DEFAULT_ZOOM = 1f

    /**
     * The zoom-out stop: the field exactly fills the viewport and never shrinks inside it.
     *
     * Letting it shrink would put a border of nothing around the sky, and a hard edge with empty
     * space beyond it is the one shape §1 spends four pages ruling out.
     */
    const val MIN_ZOOM = 1f

    /**
     * The zoom-in stop.
     *
     * At 32 the viewport holds about a thousandth of the field, which separates all but the very
     * closest pairs — overlap is accepted and resolved by zoom, never by moving a star
     * (`docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §1.0). Past this there is nothing further to
     * resolve and the surface only gets emptier, which on this surface reads as absence.
     */
    const val MAX_ZOOM = 32f

    /**
     * What "maximum contrast" is, arithmetically.
     *
     * §7.1's quiet sky "raises every glyph to maximum contrast", which needed a number or it would
     * have become a guess inside a draw call. Every colour still goes through
     * [com.daymark.app.sky.SkyPalette.equalised] — the same transform, a higher target — rather than
     * through a second code path, so the ramp stays **level** at this setting too. Lifting the ramp
     * to a ceiling instead would re-introduce the ranking equalisation exists to remove, only
     * brighter: the mid-ramp would pin against white while the worst mood sat below it, which is the
     * original cruelty with the contrast turned up.
     *
     * 9.0 is most of the way to the sky's own ink (14.6:1 on the night ground) and leaves the ground
     * still legibly a night ground rather than a grey one.
     */
    const val HIGH_CONTRAST_TARGET = 9.0

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun detailFor(zoom: Float): SkyDetail = SkyDetail.forZoom(clampZoom(zoom))

    /**
     * How wide the whole field is drawn, in pixels.
     *
     * At [MIN_ZOOM] it is exactly the viewport, so a sky with five stars in it spreads across the
     * screen and a sky with fifteen thousand is dense — **without any star's position changing**.
     * That is the whole benefit of normalised coordinates, and it is why the layout has no idea how
     * big anything is.
     */
    fun contentWidthPx(viewportWidthPx: Float, zoom: Float): Float =
        viewportWidthPx * clampZoom(zoom)

    /** How tall the whole field is drawn, in pixels. The counterpart of [contentWidthPx]. */
    fun contentHeightPx(viewportHeightPx: Float, zoom: Float): Float =
        viewportHeightPx * clampZoom(zoom)

    // -------------------------------------------------------------------------------------------
    // Pan. `pan` is where the content's top-left corner sits on screen, so screen = content + pan.
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

    fun screenX(layout: SkyLayout, index: Int, contentWidthPx: Float, panXPx: Float): Float =
        layout.x[index] * contentWidthPx + panXPx

    fun screenY(layout: SkyLayout, index: Int, contentHeightPx: Float, panYPx: Float): Float =
        layout.y[index] * contentHeightPx + panYPx

    /**
     * Whether a star is close enough to the viewport to be worth drawing.
     *
     * Culling used to be a contiguous slice of the packed arrays, because x was monotonic in the
     * date and every star of a month row sat next to the rest of its row. With the rows gone
     * (`docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §1.0) there is no order in the arrays that
     * corresponds to any order on the screen, so the renderer walks all of them and asks this. That
     * is a bounds check per star per frame over a primitive array with no allocation — a few tens of
     * microseconds for ten years of daily use, which the layout's own timing test bounds.
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
     * [SkyGlyph] says it means. Overlap is not resolved by moving anything: §1.0 accepts it, and a
     * star nudged away from a neighbour would be a star whose position depended on other records.
     *
     * Ties go to the lower index, which is the earlier record: the arrays are in time order, so two
     * stars exactly equidistant resolve the same way on every device and every open.
     */
    fun nearestStar(
        layout: SkyLayout,
        indices: IntRange,
        contentWidthPx: Float,
        contentHeightPx: Float,
        panXPx: Float,
        panYPx: Float,
        tapXPx: Float,
        tapYPx: Float,
        maxDistancePx: Float,
    ): Int {
        var best = -1
        var bestDistanceSquared = maxDistancePx * maxDistancePx
        for (i in indices) {
            val dx = screenX(layout, i, contentWidthPx, panXPx) - tapXPx
            val dy = screenY(layout, i, contentHeightPx, panYPx) - tapYPx
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
    // (`docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §1.0) the top of the viewport is not a date and no
    // part of the screen is: a label there would be a claim about where you are that is not true.
    // The way to reach a particular date is the text list, which keeps its month headings.

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
