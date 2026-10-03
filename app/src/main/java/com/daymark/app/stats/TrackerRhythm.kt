package com.daymark.app.stats

/**
 * When a tracker asks to be logged: the person's choice of rhythm, and the times it produces.
 *
 * Pure and Android-free, like the rest of `stats/`: no clock, no time zone, no Room type. Times are
 * minutes from the start of a calendar day the caller names by its epoch day; the caller turns them
 * into instants in the person's own zone. `tools/jvm-tests.sh stats` runs `TrackerRhythmTest`.
 *
 * ## What it decides, and what it leaves to the engine
 *
 * This file says when a tracker's check-ins are due, from the person's settings alone. Whether a
 * due check-in is actually posted is [CheckInEngine]'s decision, exactly as for the reminders: it
 * eases off when check-ins go unanswered and never asks more often than these times. So everything
 * here is the ceiling the person set, and nothing here reads how anyone answered.
 *
 * ## The rhythms
 *
 * A new tracker starts at [Rhythm.WHEN_IT_HAPPENS]: no check-ins at all, with the quick-log button
 * one tap away. Asking is something the person switches on per tracker (§D1a: only the person
 * escalates). A rhythm key this version does not know reads as [Rhythm.WHEN_IT_HAPPENS], so a
 * backup from a later version asks nothing rather than something nobody chose here.
 *
 * ## A few times a day, varied
 *
 * [Rhythm.FEW_A_DAY] spreads its check-ins between hours the person sets, at times that vary from
 * day to day, because fixed times catch the same moments every day. The window is cut into equal
 * slots, one check-in per slot, each placed in the middle half of its slot. That keeps any two
 * check-ins at least half a slot apart and keeps every one inside the person's hours. The draw is
 * seeded by the tracker and the day, so the same day always gives the same times: re-arming after
 * a restart cannot move a check-in or add one.
 */
object TrackerRhythm {

    const val MINUTES_PER_DAY = 24 * 60

    /** The person's choice for one tracker. Stored as [key], so an old row always reads back. */
    enum class Rhythm(val key: String) {
        /** No check-ins. The quick-log button is the way to log. The default. */
        WHEN_IT_HAPPENS("when"),

        /** One check-in a day, at the time the person picked. */
        ONCE_A_DAY("daily"),

        /** Several check-ins a day, at varied times inside the hours the person set. */
        FEW_A_DAY("few"),

        /** No check-ins and no prompt of any kind: the person logs from the tracker. */
        DONT_ASK("none"),
        ;

        /** Whether this rhythm ever posts a check-in. */
        val asks: Boolean get() = this == ONCE_A_DAY || this == FEW_A_DAY

        companion object {
            val DEFAULT = WHEN_IT_HAPPENS

            /** Unknown or missing keys read as [DEFAULT], which asks nothing. */
            fun fromKey(key: String?): Rhythm = entries.firstOrNull { it.key == key } ?: DEFAULT
        }
    }

    /**
     * The hours a few-times-a-day tracker may ask in: from [startMinute] for [lengthMinutes].
     * An end at or before the start runs past midnight; an end equal to the start is the whole day.
     */
    data class Window(val startMinute: Int, val endMinute: Int) {
        val start: Int get() = Math.floorMod(startMinute, MINUTES_PER_DAY)

        val lengthMinutes: Int
            get() {
                val length = Math.floorMod(endMinute - startMinute, MINUTES_PER_DAY)
                return if (length == 0) MINUTES_PER_DAY else length
            }
    }

    /**
     * A tracker's few-times-a-day check-ins for one day, as minutes from that day's midnight,
     * ascending. A window that runs past midnight gives values of [MINUTES_PER_DAY] or more, which
     * belong to the early hours of the next calendar day.
     *
     * [count] below one is read as one, and above the window's length in minutes as that length,
     * only so the arithmetic stays sound; the settings screen offers far fewer.
     */
    fun timesForDay(seed: Long, epochDay: Long, count: Int, window: Window): List<Int> {
        val length = window.lengthMinutes
        val n = count.coerceIn(1, length)
        val slot = length / n
        val spread = slot / 2
        return (0 until n).map { i ->
            val jitter = if (spread == 0) 0 else Math.floorMod(mix(seed, epochDay, i), spread.toLong()).toInt()
            window.start + i * slot + slot / 4 + jitter
        }
    }

    /** A check-in that is due: the calendar day it falls on and its minute within that day. */
    data class Due(val epochDay: Long, val minuteOfDay: Int)

    /**
     * The first check-in strictly after [nowMinuteOfDay] on [nowEpochDay], or null for a rhythm that
     * never asks. [onceAtMinute] is the time a once-a-day tracker asks; [count] and [window] shape a
     * few-times-a-day one.
     */
    fun nextDue(
        rhythm: Rhythm,
        seed: Long,
        nowEpochDay: Long,
        nowMinuteOfDay: Int,
        onceAtMinute: Int,
        count: Int,
        window: Window,
    ): Due? {
        if (!rhythm.asks) return null
        val now = nowEpochDay * MINUTES_PER_DAY + nowMinuteOfDay
        // Yesterday's window can run into today, so its late check-ins are candidates too.
        val candidates = (nowEpochDay - 1..nowEpochDay + 1).flatMap { day ->
            val times = when (rhythm) {
                Rhythm.ONCE_A_DAY -> listOf(Math.floorMod(onceAtMinute, MINUTES_PER_DAY))
                else -> timesForDay(seed, day, count, window)
            }
            times.map { day * MINUTES_PER_DAY + it }
        }
        val next = candidates.filter { it > now }.minOrNull() ?: return null
        return Due(Math.floorDiv(next, MINUTES_PER_DAY.toLong()), Math.floorMod(next, MINUTES_PER_DAY.toLong()).toInt())
    }

    /**
     * The spacing the person's own settings already keep, for [CheckInEngine.paceOf]'s
     * `setSpacingMillis`: a day for once a day, a slot for a few times a day.
     */
    fun setSpacingMillis(rhythm: Rhythm, count: Int, window: Window): Long = when (rhythm) {
        Rhythm.ONCE_A_DAY -> MINUTES_PER_DAY * MINUTE_MILLIS
        Rhythm.FEW_A_DAY -> (window.lengthMinutes / count.coerceIn(1, window.lengthMinutes)) * MINUTE_MILLIS
        else -> 0L
    }

    private const val MINUTE_MILLIS = 60_000L

    /** SplitMix64 over the tracker, the day and the slot: stable, and different for each. */
    private fun mix(seed: Long, epochDay: Long, slot: Int): Long {
        var z = seed * -0x61c8864680b583ebL + epochDay * 0x2545F4914F6CDD1DL + slot.toLong()
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
