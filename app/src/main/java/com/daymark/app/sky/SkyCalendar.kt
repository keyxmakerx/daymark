package com.daymark.app.sky

/**
 * Proleptic-Gregorian date arithmetic, written out so the Sky's layout stays import-free.
 *
 * ## Why the Sky does its own calendar
 *
 * `java.time` would do this in one line, and using it would cost more than it saves. The layout has
 * to answer three questions — which month row a day belongs to, where that row starts, how long it
 * is — and every one of them is integer arithmetic. Pulling in `LocalDate` to answer them would put
 * a `ZoneId` inside the layout, and a zone is a decision about *whose day this is* that belongs at
 * the edge of the app where the person's locale is known, not in the middle of a geometry pass.
 *
 * So the boundary is drawn at the epoch day. The caller converts a stored `epochMillis` to a local
 * date once, with the zone it already has, and hands the layout a `LocalDate.toEpochDay()` — the
 * same currency `data/entity/SleepLog.night` already uses. Everything downstream is `Long`
 * arithmetic that cannot drift with a timezone database update.
 *
 * ## The algorithm
 *
 * Howard Hinnant's `civil_from_days` / `days_from_civil`, which shift the year to start in March so
 * the leap day lands at the end of the cycle and the month-length table disappears. They are exact
 * for the whole `Long` range and use no division that Kotlin's truncate-toward-zero semantics would
 * get wrong, because the era terms handle the negative side explicitly.
 *
 * The range is not clamped, and that is deliberate. A life event (`docs/SKY.md` §2.2) is a date the
 * person chose, and people have events in 1974. A layout that silently refused dates before some
 * arbitrary floor would be the app deciding which parts of a life are in scope.
 */
object SkyCalendar {

    /** A calendar date, as three plain integers. Month is `1..12`, day is `1..31`. */
    data class Civil(val year: Int, val month: Int, val day: Int)

    /** Days from 1970-01-01 to 0000-03-01, the shifted-year epoch the algorithm counts from. */
    private const val ERA_SHIFT = 719_468L

    fun civilOf(epochDay: Long): Civil {
        val z = epochDay + ERA_SHIFT
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L // [0, 146096]
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L // [0, 399]
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L) // [0, 365]
        val mp = (5L * doy + 2L) / 153L // [0, 11], March = 0
        val d = doy - (153L * mp + 2L) / 5L + 1L // [1, 31]
        val m = mp + (if (mp < 10L) 3L else -9L) // [1, 12]
        return Civil(
            year = (y + (if (m <= 2L) 1L else 0L)).toInt(),
            month = m.toInt(),
            day = d.toInt(),
        )
    }

    fun epochDayOf(year: Int, month: Int, day: Int): Long {
        val y0 = year.toLong() - (if (month <= 2) 1L else 0L)
        val era = (if (y0 >= 0) y0 else y0 - 399L) / 400L
        val yoe = y0 - era * 400L // [0, 399]
        val mp = month.toLong() + (if (month > 2) -3L else 9L) // March = 0
        val doy = (153L * mp + 2L) / 5L + day.toLong() - 1L // [0, 365]
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy // [0, 146096]
        return era * 146_097L + doe - ERA_SHIFT
    }

    /**
     * Months since year 0, month 1 — the row index the layout stacks on.
     *
     * An `Int` rather than a `Long` because it is used as an array index and as a subtraction, and
     * because ±178 million years is enough headroom for a journal. Returning a `Long` here and
     * subtracting it from an `Int` row count is exactly the mistake this comment exists to stop.
     */
    fun epochMonth(epochDay: Long): Int {
        val civil = civilOf(epochDay)
        return civil.year * 12 + (civil.month - 1)
    }

    fun yearOfMonth(epochMonth: Int): Int = Math.floorDiv(epochMonth, 12)

    /** `1..12`. */
    fun monthOfMonth(epochMonth: Int): Int = Math.floorMod(epochMonth, 12) + 1

    fun firstEpochDayOfMonth(epochMonth: Int): Long =
        epochDayOf(yearOfMonth(epochMonth), monthOfMonth(epochMonth), 1)

    /**
     * `28..31`. Derived by subtracting two month starts rather than from a table, so February in a
     * leap year is correct without the layout knowing what a leap year is.
     */
    fun lengthOfMonth(epochMonth: Int): Int =
        (firstEpochDayOfMonth(epochMonth + 1) - firstEpochDayOfMonth(epochMonth)).toInt()
}
