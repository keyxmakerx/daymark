package com.daylie.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Date/time helpers working in the device's default zone. */
object DateUtils {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val monthYearFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMMM yyyy")

    fun toLocalDateTime(epochMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    fun toLocalDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun toEpochMillis(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    /** Inclusive start (00:00) of the given day in epoch millis. */
    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Exclusive end (next day 00:00) of the given day in epoch millis. */
    fun endOfDay(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun formatTime(epochMillis: Long): String =
        toLocalDateTime(epochMillis).format(timeFormatter)

    fun formatDate(epochMillis: Long): String =
        toLocalDateTime(epochMillis).format(dateFormatter)

    fun formatMonthYear(date: LocalDate): String = date.format(monthYearFormatter)

    fun formatMonthYear(epochMillis: Long): String =
        toLocalDate(epochMillis).format(monthYearFormatter)
}
