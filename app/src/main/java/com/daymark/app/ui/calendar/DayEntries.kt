package com.daymark.app.ui.calendar

import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.util.DateUtils

/**
 * One entry as the day model ([CalendarDays]) needs it: the day it falls on in the phone's time zone,
 * when it was logged, its row id and its own mood, and nothing else.
 *
 * The month, Insights → Week and Home's week strip all turn entries into days through this, so the
 * three cannot disagree about which day an entry belongs to or what it logged (#411).
 */
fun EntryWithActivities.toDayEntry(): DayEntry = DayEntry(
    date = DateUtils.toLocalDate(entry.dateTime),
    epochMillis = entry.dateTime,
    id = entry.id,
    moodLevel = entry.moodLevel,
)
