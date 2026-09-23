package com.daymark.app.sky

/**
 * How far away in time a star is: its colour and how brightly it still burns.
 *
 * Import-free, the discipline [Sky], [SkyRandom], [SkyGlyph] and [SkyPalette] already keep. No
 * Android types, no `LocalDate`, no clock, no random source. Age arrives as a number of years the
 * caller worked out ([ageYears] does the arithmetic from two epoch days), so this file cannot know
 * what day it is and cannot draw a different sky tomorrow for any reason other than the date the
 * caller handed it.
 *
 * ## Why colour is age
 *
 * `docs/SKY.md` §3.2: *"A star's colour is how long ago it was: blue-white when new, through white,
 * gold and amber, to a deep red after five and a half years. Red means old, never bad."*
 *
 * That last clause is the whole argument. On the old design a star took its hue from the mood the
 * person recorded, so the warm end of the ramp was the hard end, and a bad month was a red month —
 * a verdict drawn in colour on a surface people screenshot. Age has the same visual range and none
 * of the meaning: **every** star reddens, at the same rate, and the only thing a red star says is
 * that it happened a while ago. Nobody's worst week is their reddest.
 *
 * Nothing here takes a mood level, in any function, and nothing here takes a count. Age and kind
 * are the only inputs, and kind only ever answers one question — *is this a landmark* — which is
 * about who authored the mark and never about what the app measured.
 *
 * ## The two curves
 *
 * Both are the ones signed off in `docs/prototypes/your-sky.html`, which opens in a browser and is
 * the thing the maintainer actually looked at. The numbers are transcribed rather than re-derived,
 * so what ships is what was agreed:
 *
 * | Age | Tint | Why |
 * |---|---|---|
 * | new | `#C4DAFF` blue-white | a hot, close star |
 * | 7 months | `#FFFAEC` white | |
 * | 1 year 7 months | `#FFE296` gold | |
 * | 3 years 2 months | `#FFAC64` amber | |
 * | 5 years 6 months and beyond | `#FF6E58` deep red | the far end; it stops here and stays |
 *
 * [fadeFor] falls from full brightness toward [FADE_FLOOR] with a time constant of [FADE_YEARS].
 * **It never reaches zero and it is never allowed to** — `docs/SKY.md` §3.5: *"Old stars recede but
 * never vanish"*, so an old year is far sky rather than a void; nothing is ever dropped from the
 * surface. A person who logged for a year and stopped must not open this and find that year gone,
 * and someone who comes back after five years must find everything they left. The floor is what
 * makes fading a sense of distance rather than a deletion on a timer.
 *
 * `0.22` and `2.1` years are the prototype's. The shape they give: half the fall has happened by
 * about 1.5 years, three quarters by about 3 years, and the last quarter never quite finishes.
 * That puts the fade's visible action inside the first few years, which is the span an ordinary
 * history covers, and leaves everything older sitting together on the floor instead of trailing
 * off toward nothing.
 */
object SkyAge {

    // -------------------------------------------------------------------------------------------
    // Age itself.
    // -------------------------------------------------------------------------------------------

    /**
     * Julian years, matching the prototype's `31557600000` ms.
     *
     * The exactness does not matter — nothing here is a calendar calculation and no boundary falls
     * on a date — but picking one number and naming it stops the renderer inventing `365f` and
     * putting a quarter-day-a-year drift between the sprite cache and the ramp.
     */
    const val DAYS_PER_YEAR = 365.25f

    /**
     * How old a star is, in years, from its date and the caller's idea of today.
     *
     * `todayEpochDay` is a parameter and not a clock read, which is the only reason this file can
     * be tested and the only reason two devices in different timezones draw the same sky from the
     * same day.
     *
     * A star is never newer than new: a record dated after [todayEpochDay] — a device clock that
     * moved backwards, a timezone that puts a late-evening entry on tomorrow — returns `0`, so it
     * is drawn as brand new rather than blue-shifted past the end of the ramp into whatever the
     * arithmetic happened to produce.
     */
    fun ageYears(epochDay: Long, todayEpochDay: Long): Float {
        val days = todayEpochDay - epochDay
        if (days <= 0L) return 0f
        return days.toFloat() / DAYS_PER_YEAR
    }

    // -------------------------------------------------------------------------------------------
    // The redshift ramp.
    // -------------------------------------------------------------------------------------------

    /** Where each stop of the ramp sits, in years. Ascending, and the first is `0`. */
    private val STOP_AGE_YEARS = floatArrayOf(0f, 0.6f, 1.6f, 3.2f, 5.5f)

    /** The tint at each stop, packed `0xRRGGBB` the way [SkyPalette] packs colour. */
    private val STOP_TINT = intArrayOf(0xC4DAFF, 0xFFFAEC, 0xFFE296, 0xFFAC64, 0xFF6E58)

    /** The tint of a brand-new star. */
    const val NEWEST_TINT = 0xC4DAFF

    /** The far end. Nothing reddens past this, however old it gets. */
    const val OLDEST_TINT = 0xFF6E58

    /**
     * The tint of a star [ageYears] old, on one continuous ramp.
     *
     * Continuous and monotonic, which `SkyAgeTest` pins: the blue channel never rises with age and
     * the red-minus-blue warmth never falls, so there is no age at which a star suddenly jumps a
     * colour and no age at which reddening reverses. A stepped ramp would put visible bands across
     * the sky at fixed ages, and a band is a boundary, and a boundary on this surface invites
     * someone to read a meaning into which side of it their month fell on.
     *
     * Ages at or below zero take the first stop, which also catches `NaN` — `!(x > 0f)` is true for
     * `NaN` where `x <= 0f` is false — so a broken age can never produce a colour out of nowhere.
     */
    fun tintFor(ageYears: Float): Int {
        val last = STOP_AGE_YEARS.size - 1
        if (!(ageYears > STOP_AGE_YEARS[0])) return STOP_TINT[0]
        if (ageYears >= STOP_AGE_YEARS[last]) return STOP_TINT[last]
        var i = 1
        while (i <= last) {
            if (ageYears <= STOP_AGE_YEARS[i]) {
                val from = STOP_AGE_YEARS[i - 1]
                val to = STOP_AGE_YEARS[i]
                return lerpTint(STOP_TINT[i - 1], STOP_TINT[i], (ageYears - from) / (to - from))
            }
            i++
        }
        return STOP_TINT[last]
    }

    /**
     * The tint of a star of this [kind] and this age.
     *
     * A **landmark is exempt**: `docs/SKY.md` §3.4 says a life event alone is bigger, brighter,
     * spiked, never redshifted and never faded, because it is a mark the person placed in order to
     * find it again, and something placed to be found must not recede. It is drawn in
     * [LANDMARK_TINT], the sky's own white, at every age.
     *
     * Kind is used here for exactly one thing — *did the person author this* — and never as a rank
     * between the other five. A journal page and a check-in and a step all redshift identically.
     */
    fun tintFor(kind: SkyKind, ageYears: Float): Int =
        if (kind == SkyKind.LIFE_EVENT) LANDMARK_TINT else tintFor(ageYears)

    /** A landmark's colour, at any age. White, and the only colour on the Sky that never moves. */
    const val LANDMARK_TINT = 0xFFFFFF

    // -------------------------------------------------------------------------------------------
    // The fade.
    // -------------------------------------------------------------------------------------------

    /**
     * How faint an old star is allowed to get. **Never zero, and never near it.**
     *
     * At 0.22 the oldest star on the surface is still a fifth as bright as today's — plainly there,
     * plainly further away. The number is the prototype's, and the thing it is chosen against is
     * the reading *"the app is deleting my past"*: anything low enough to be missed at a glance
     * turns a person's first year into a gap they have to take on trust.
     */
    const val FADE_FLOOR = 0.22f

    /** A brand-new star. The top of the curve, stated so the renderer never assumes it. */
    const val FADE_NEW = 1.0f

    /**
     * The exponential's time constant, in years: the fall is `e`-folded once per [FADE_YEARS].
     *
     * An exponential rather than a straight line because a straight line has an end — a date on
     * which a star reaches the floor — and a date is a boundary someone can notice their history
     * crossing. This approaches the floor and never arrives, so there is no anniversary of
     * anything.
     */
    const val FADE_YEARS = 2.1f

    /**
     * How brightly a star [ageYears] old still burns, in `[FADE_FLOOR, FADE_NEW]`.
     *
     * Total by construction: negative and `NaN` ages return [FADE_NEW], and an age so large it
     * saturates the exponential returns exactly [FADE_FLOOR]. There is no input — none, including
     * `Float.MAX_VALUE` and `Float.POSITIVE_INFINITY` — for which this returns zero, and
     * `SkyAgeTest` sweeps for one.
     */
    fun fadeFor(ageYears: Float): Float {
        if (!(ageYears > 0f)) return FADE_NEW
        val decay = Math.exp((-ageYears / FADE_YEARS).toDouble()).toFloat()
        return FADE_FLOOR + (FADE_NEW - FADE_FLOOR) * decay
    }

    /**
     * How brightly a star of this [kind] and this age burns.
     *
     * A landmark never fades, for the reason given on [tintFor]: the one star placed to be found
     * again is the one star that must still be there at the far end of the sky.
     */
    fun fadeFor(kind: SkyKind, ageYears: Float): Float =
        if (kind == SkyKind.LIFE_EVENT) FADE_NEW else fadeFor(ageYears)

    // -------------------------------------------------------------------------------------------
    // Sprite buckets — a rendering convenience, stated here so the renderer does not invent it.
    // -------------------------------------------------------------------------------------------

    /**
     * How finely age is quantised when a star is drawn from a cached sprite.
     *
     * The renderer cannot rasterise a separate glow for every star, so it caches one per
     * (mood, age bucket, temperature) and reuses it. A quarter of a year per bucket is the
     * prototype's, and it is far below what the eye resolves on this ramp — the steepest stretch
     * moves a colour channel by about 25 of 255 across a whole bucket.
     *
     * The bucket is a *drawing* approximation and never the ramp itself: [tintFor] stays
     * continuous, so anything that reasons about age (a tap, a description, a test of the ramp)
     * gets the exact value and only the sprite cache rounds.
     */
    const val BUCKETS_PER_YEAR = 4

    /** The last bucket. Seven years: past the end of the ramp, so the far sky is one sprite. */
    const val MAX_AGE_BUCKET = 28

    /** Which sprite bucket an age falls in, `0..MAX_AGE_BUCKET`. Never negative, never `NaN`. */
    fun ageBucket(ageYears: Float): Int {
        if (!(ageYears > 0f)) return 0
        val bucket = Math.round(ageYears * BUCKETS_PER_YEAR)
        return if (bucket > MAX_AGE_BUCKET) MAX_AGE_BUCKET else bucket
    }

    /** The age a bucket stands for, so a sprite's tint is [tintFor] of a real age. */
    fun bucketAgeYears(bucket: Int): Float {
        val clamped = if (bucket < 0) 0 else if (bucket > MAX_AGE_BUCKET) MAX_AGE_BUCKET else bucket
        return clamped.toFloat() / BUCKETS_PER_YEAR
    }

    // -------------------------------------------------------------------------------------------

    private fun lerpTint(from: Int, to: Int, t: Float): Int {
        val r = lerpChannel((from shr 16) and 0xFF, (to shr 16) and 0xFF, t)
        val g = lerpChannel((from shr 8) and 0xFF, (to shr 8) and 0xFF, t)
        val b = lerpChannel(from and 0xFF, to and 0xFF, t)
        return (r shl 16) or (g shl 8) or b
    }

    private fun lerpChannel(from: Int, to: Int, t: Float): Int {
        val value = Math.round(from + (to - from) * t)
        return if (value < 0) 0 else if (value > 255) 255 else value
    }
}
