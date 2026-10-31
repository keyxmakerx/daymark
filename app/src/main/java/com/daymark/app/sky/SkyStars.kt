package com.daymark.app.sky

/**
 * What the person chose for their own stars (`DECISIONS.md` §D11): a name (#517), a meteor shower
 * on a star's day each year (#516), and a place a memory belongs to (#518). Every one of them is the
 * person's own act; nothing here suggests, picks or infers any of them.
 *
 * Import-free, like the rest of `sky/`: the repository hands in plain rows and today's day, and the
 * screen asks this for each star.
 */
object SkyStars {

    /** One star's choices, keyed as the store keys them: the kind's key and the star's own record. */
    data class Choice(
        val kindKey: String,
        val recordId: Long,
        val name: String? = null,
        val shower: Boolean = false,
        val placeId: Long? = null,
    )

    /** A place the person named. Only what they typed: no location is ever read for one. */
    data class Place(val id: Long, val name: String)

    /** The longest name a star or a place keeps, the same as a constellation's. */
    const val NAME_MAX = SkyConstellation.NAME_MAX

    /** A name as the person typed it, made safe to keep, or null when nothing is left of it. */
    fun cleanName(raw: String): String? =
        if (raw.none { !it.isWhitespace() && !it.isISOControl() }) null else SkyConstellation.cleanName(raw)

    /** The choices for every star of [layout], by index: what [rows] say about each star's own record. */
    class Resolved(
        val names: Array<String?>,
        val shower: BooleanArray,
        /** The place's id, or [NO_PLACE]. */
        val place: LongArray,
    ) {
        fun nameAt(index: Int): String? = names.getOrNull(index)
        fun showerAt(index: Int): Boolean = index in shower.indices && shower[index]
        fun placeAt(index: Int): Long = if (index in place.indices) place[index] else NO_PLACE

        companion object {
            val EMPTY = Resolved(emptyArray(), BooleanArray(0), LongArray(0))
        }
    }

    const val NO_PLACE = 0L

    /**
     * Each star's choices, read from the row for its own first record. A row for a record that is no
     * longer a star matches nothing and is never drawn.
     */
    fun resolve(layout: SkyLayout, rows: List<Choice>): Resolved {
        if (rows.isEmpty() || layout.starCount == 0) return Resolved.EMPTY
        val byKey = rows.associateBy { it.kindKey to it.recordId }
        val n = layout.starCount
        val names = arrayOfNulls<String>(n)
        val shower = BooleanArray(n)
        val place = LongArray(n)
        for (i in 0 until n) {
            val row = byKey[layout.kindAt(i).key to layout.recordIds[layout.idStart[i]]] ?: continue
            names[i] = row.name
            shower[i] = row.shower
            place[i] = row.placeId ?: NO_PLACE
        }
        return Resolved(names, shower, place)
    }

    /**
     * Whether a shower can be chosen for this star at all: never for a life event marked hard
     * (`DECISIONS.md` §D11, #516), and never for one put away.
     */
    fun showerAllowed(layout: SkyLayout, index: Int): Boolean =
        index in 0 until layout.starCount && layout.isShown(index) && !layout.hard[index]

    /**
     * Whether [todayEpochDay] is the anniversary of [epochDay]: the same month and day in a later
     * year. A day chosen on 29 February falls on 28 February in a year without one, so it is never
     * skipped.
     */
    fun isAnniversary(epochDay: Long, todayEpochDay: Long): Boolean {
        if (todayEpochDay <= epochDay) return false
        val then = SkyCalendar.civilOf(epochDay)
        val now = SkyCalendar.civilOf(todayEpochDay)
        if (now.year <= then.year) return false
        if (then.month == now.month && then.day == now.day) return true
        val leapDay = then.month == 2 && then.day == 29
        return leapDay && now.month == 2 && now.day == 28 && !isLeap(now.year)
    }

    /**
     * The star a meteor shower falls from today, or -1: only a star the person chose a shower for,
     * on its anniversary, still in sight and not marked hard. When more than one falls today, the
     * oldest is the radiant, so the answer is the same every time the sky is opened that day.
     */
    fun showerToday(layout: SkyLayout, chosen: Resolved, todayEpochDay: Long): Int {
        for (i in 0 until layout.starCount) {
            if (chosen.showerAt(i) && showerAllowed(layout, i) && isAnniversary(layout.epochDay[i], todayEpochDay)) return i
        }
        return -1
    }

    private fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}
