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
import kotlin.math.floor
import kotlin.math.pow

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
    // Zoom. One parameter — how many month rows the viewport is asked to hold — because that is
    // what SkyDetail.forVisibleMonths reads, and having two zoom numbers would let the level shown
    // disagree with the level drawn.
    // -------------------------------------------------------------------------------------------

    /**
     * Where the Sky opens: a bit over a year, which is [SkyDetail.DRIFT].
     *
     * §4 calls DRIFT "the view you leave open", and it is also the honest first frame — it shows
     * the shape of a history without inviting anyone to read a single month as a verdict. It is
     * deliberately not "today, close up": the Sky has no start (§4.2) and opening on the present
     * would make the most recent weeks the subject every time, which is the reading most likely to
     * hurt on a bad week.
     */
    const val DEFAULT_VISIBLE_MONTHS = 14f

    /**
     * The zoom-in stop.
     *
     * Below [ROW_FILLS_VIEWPORT] the row is already as tall as the screen and further zoom is
     * horizontal only ([widthFactor]) — this value is chosen so the widest the month can get is one
     * day roughly filling the viewport, which is [SkyDetail.NIGHT]. Zoom bottoms out there and
     * never at a single star: a star's detail is reached by activating it, not by pushing further
     * into empty sky.
     */
    const val MIN_VISIBLE_MONTHS = 0.13f

    /** Ten years at once. Past this, rows are thinner than a glyph and the surface is noise. */
    const val MAX_VISIBLE_MONTHS = 120f

    /**
     * The zoom at which one month row fills the height, and the hinge the whole transform turns on.
     *
     * It is [SkyDetail]'s own MONTH/NIGHT threshold rather than a number this file picked. Above
     * it, zooming stacks fewer rows on screen; at it, one row fills the viewport and the month runs
     * edge to edge, which is §4's L2 exactly; below it the row stops growing and the *month* widens
     * instead, which is how a day comes close without a star's vertical scatter being stretched
     * across eight screens.
     */
    const val ROW_FILLS_VIEWPORT = 1.2f

    /**
     * How far a month may be stretched horizontally: 31 viewports, so a day is about a screen.
     *
     * Not larger. Past one-day-per-screen there is nothing further to resolve — a day's stars are
     * already spread apart — and the surface would only get emptier, which on this surface reads as
     * absence and is the one reading the Sky is built to avoid.
     */
    const val MAX_WIDTH_FACTOR = 31f

    private const val WIDTH_EXPONENT = 1.6

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

    fun clampVisibleMonths(visibleMonths: Float): Float =
        visibleMonths.coerceIn(MIN_VISIBLE_MONTHS, MAX_VISIBLE_MONTHS)

    fun detailFor(visibleMonths: Float): SkyDetail =
        SkyDetail.forVisibleMonths(clampVisibleMonths(visibleMonths))

    /**
     * A month row's height in pixels.
     *
     * Capped at [ROW_FILLS_VIEWPORT] rows: a row never grows past the viewport, so `y` — which is
     * a fraction *down the row* — never scatters a day's stars across more screen than a person can
     * see at once.
     */
    fun rowHeightPx(viewportHeightPx: Float, visibleMonths: Float): Float =
        viewportHeightPx / maxOf(clampVisibleMonths(visibleMonths), ROW_FILLS_VIEWPORT)

    /**
     * How many viewports wide one month is drawn.
     *
     * `1` at every zoom from DRIFT down to MONTH — so the month is the width of the screen and the
     * horizontal axis means the same thing at all three of those levels, which is what lets a star
     * be followed from the overview to its own month by eye. It only leaves 1 once the row can no
     * longer grow vertically, and then it is the only thing still moving.
     */
    fun widthFactor(visibleMonths: Float): Float {
        val months = clampVisibleMonths(visibleMonths)
        if (months >= ROW_FILLS_VIEWPORT) return 1f
        val grown = (ROW_FILLS_VIEWPORT / months).toDouble().pow(WIDTH_EXPONENT).toFloat()
        return grown.coerceIn(1f, MAX_WIDTH_FACTOR)
    }

    fun rowWidthPx(viewportWidthPx: Float, visibleMonths: Float): Float =
        viewportWidthPx * widthFactor(visibleMonths)

    // -------------------------------------------------------------------------------------------
    // Pan. `pan` is where the content's top-left corner sits on screen, so screen = content + pan.
    // -------------------------------------------------------------------------------------------

    /**
     * Keeps the sky reachable without letting it be dragged off the screen.
     *
     * When the content is smaller than the viewport it is centred rather than pinned to a corner —
     * a person with two months of history should find their sky in the middle of the screen, not
     * hugging the top-left with a void under it. "Void" is not a metaphor here: empty space on this
     * surface is the thing §1 spends four pages ruling out, and a layout that parks a short history
     * against one edge manufactures exactly that.
     */
    fun clampPan(pan: Float, contentPx: Float, viewportPx: Float): Float =
        if (contentPx <= viewportPx) (viewportPx - contentPx) / 2f
        else pan.coerceIn(viewportPx - contentPx, 0f)

    fun contentHeightPx(layout: SkyLayout, rowHeightPx: Float): Float =
        layout.rowCount * rowHeightPx

    fun screenX(layout: SkyLayout, index: Int, rowWidthPx: Float, panXPx: Float): Float =
        layout.x[index] * rowWidthPx + panXPx

    fun screenY(layout: SkyLayout, index: Int, rowHeightPx: Float, panYPx: Float): Float =
        (layout.row[index] + layout.y[index]) * rowHeightPx + panYPx

    /**
     * The rows overlapping the viewport, as an inclusive range, or `null` when the layout is empty.
     *
     * Widened by one row on each side so a star sitting near a row's edge is drawn while it is
     * still partly on screen instead of popping in. The result feeds [com.daymark.app.sky.Sky.rowRange],
     * which turns it into one contiguous slice of the packed arrays — culling is an array slice and
     * never a scan.
     */
    fun visibleRows(
        layout: SkyLayout,
        rowHeightPx: Float,
        panYPx: Float,
        viewportHeightPx: Float,
    ): IntPair? {
        if (layout.rowCount == 0 || rowHeightPx <= 0f) return null
        val first = floor((-panYPx) / rowHeightPx).toInt() - 1
        val last = floor((viewportHeightPx - panYPx) / rowHeightPx).toInt() + 1
        return IntPair(first.coerceAtLeast(0), last.coerceAtMost(layout.rowCount - 1))
    }

    /** A first/last row pair. A `data class` rather than `IntRange` so `first > last` stays sayable. */
    data class IntPair(val first: Int, val last: Int)

    /**
     * The star nearest to a tap, or `-1`.
     *
     * §7.1: every star is a 48 dp target however small it is drawn, and where targets overlap the
     * tap resolves to the **nearest core** — it does not grow the star underneath, because a star
     * that swells when you reach for it is a star whose size means something other than what
     * [SkyGlyph] says it means.
     *
     * Ties go to the lower index, which is the earlier record: the arrays are in time order, so two
     * stars exactly equidistant resolve the same way on every device and every open.
     */
    fun nearestStar(
        layout: SkyLayout,
        indices: IntRange,
        rowWidthPx: Float,
        rowHeightPx: Float,
        panXPx: Float,
        panYPx: Float,
        tapXPx: Float,
        tapYPx: Float,
        maxDistancePx: Float,
    ): Int {
        var best = -1
        var bestDistanceSquared = maxDistancePx * maxDistancePx
        for (i in indices) {
            val dx = screenX(layout, i, rowWidthPx, panXPx) - tapXPx
            val dy = screenY(layout, i, rowHeightPx, panYPx) - tapYPx
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
     * §7.3: at DRIFT and SEASON the canvas is a **single node described by what it is, not by what
     * it contains**. It does not enumerate its stars and it announces no total — a screen-reader
     * user who wants the contents gets the list (§7.5), which is a peer surface, not a summary read
     * out of this one.
     *
     * The span is the two years the sky covers. That is a property of the axis, the way "January to
     * December" is a property of a calendar, and not a count of anything the person did.
     */
    fun canvasDescription(layout: SkyLayout, locale: Locale): String {
        if (layout.starCount == 0) return SkyLayout.EMPTY_LINE
        val firstYear = SkyCalendar.yearOfMonth(layout.firstEpochMonth)
        val lastYear = SkyCalendar.yearOfMonth(layout.firstEpochMonth + layout.rowCount - 1)
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

    /** The "where you are" label over the sky. A date, because navigation targets are dates (§4.2). */
    fun monthLabel(epochMonth: Int, locale: Locale): String {
        val month = Month.of(SkyCalendar.monthOfMonth(epochMonth))
            .getDisplayName(TextStyle.FULL_STANDALONE, locale)
        return "$month ${SkyCalendar.yearOfMonth(epochMonth)}"
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
