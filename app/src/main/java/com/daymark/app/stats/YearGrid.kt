package com.daymark.app.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * What the year grid draws (Insights → Year → Grid, and Year in Pixels under More): twelve small
 * month calendars, Monday first, so every day sits under its weekday.
 *
 * A day with entries keeps the range of what it held, never an average: [Day.Moods] carries its
 * lowest and its highest level, and a day whose entries were more than one mood is drawn split, its
 * highest above its lowest. An average turns an Awful morning and a Rad night into a Meh day, which
 * is the one thing that day was not.
 *
 * A day with no entry is [Day.Nothing] and a day after [today] is [Day.ToCome]; the two are kept
 * apart so a gap that has not happened yet is never drawn as one that has.
 *
 * Pure and Android-free: no Room types, no Compose, no clock. Today is passed in.
 */
object YearGrid {

    sealed interface Day {
        /** A place before the first of the month, so the first falls under its weekday. */
        data object Pad : Day

        /** A day after today. */
        data object ToCome : Day

        /** A day up to today with no entry. */
        data object Nothing : Day

        /** A day with entries: the lowest and the highest mood level it held, 1..5. */
        data class Moods(val low: Int, val high: Int) : Day {
            init { require(low in 1..5 && high in 1..5 && low <= high) }

            /** True when the day held more than one mood, and is drawn split. */
            val isSplit: Boolean get() = low != high
        }
    }

    /** One month: its number (1..12) and its places, leading [Day.Pad]s first, then each day. */
    data class Month(val month: Int, val days: List<Day>)

    /** The places for [year], from each date's entry mood levels. */
    fun months(year: Int, dayLevels: Map<LocalDate, List<Int>>, today: LocalDate): List<Month> =
        (1..12).map { m ->
            val ym = YearMonth.of(year, m)
            val lead = ym.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
            val dates = (1..ym.lengthOfMonth()).map { ym.atDay(it) }
            Month(m, List(lead) { Day.Pad } + dates.map { day(it, dayLevels[it], today) })
        }

    /** One date's place. An entry is always drawn, even one dated after [today] by a clock change. */
    fun day(date: LocalDate, levels: List<Int>?, today: LocalDate): Day {
        val known = levels.orEmpty().filter { it in 1..5 }
        return when {
            known.isNotEmpty() -> Day.Moods(known.min(), known.max())
            date.isAfter(today) -> Day.ToCome
            else -> Day.Nothing
        }
    }
}
