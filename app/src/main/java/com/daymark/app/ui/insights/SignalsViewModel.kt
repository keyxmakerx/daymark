package com.daymark.app.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.stats.MoodCorrelations
import com.daymark.app.stats.MoodPatterns
import com.daymark.app.stats.MoodStats
import com.daymark.app.stats.Signals
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Derives [Signals.Inputs] from locally-stored entries and runs the [Signals] rules engine, exposing
 * the ranked list of cards. This is the first place the engine becomes visible: the Insights screen
 * reads the [Signals.Surface.Insights]-eligible signals from here.
 *
 * Like the other view-models it owns Room and the time zone, and hands the pure engine
 * already-computed facts. Achievement/due-check-in inputs are wired in a later step (passed as null
 * for now — the engine degrades gracefully).
 */
@HiltViewModel
class SignalsViewModel @Inject constructor(
    entryRepository: EntryRepository,
) : ViewModel() {

    val signals: StateFlow<List<Signals.Signal>> = entryRepository.observeAll()
        .map { entries -> Signals.build(buildInputs(entries)) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun buildInputs(entries: List<EntryWithActivities>): Signals.Inputs {
        if (entries.isEmpty()) {
            return Signals.Inputs(
                totalEntries = 0, avgMood = null, moodTodayLevel = null, loggedToday = false,
                currentStreak = 0, longestStreak = 0, daysLoggedThisWeek = 0,
                topLift = null, topDrag = null, monthDeltaPct = null,
                newlyUnlockedAchievement = null, dueCheckin = null, onThisDayNote = null,
            )
        }
        val today = LocalDate.now()
        val levels = entries.map { it.entry.moodLevel }
        val byDay = entries.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
        val days = byDay.keys

        val todayLevels = byDay[today]?.map { it.entry.moodLevel }
        val moodTodayLevel = todayLevels?.average()?.roundToInt()?.coerceIn(1, 5)

        val daysThisWeek = days.count { !it.isBefore(today.minusDays(6)) && !it.isAfter(today) }

        // Strongest positive / negative factor associations (past the sample gate).
        val pairs = entries.map { it.entry.moodLevel to it.activities.map { a -> a.id } }
        val nameById = entries.flatMap { it.activities }.associate { it.id to it.name }
        val (up, down) = MoodCorrelations.rankLifts(
            MoodCorrelations.factorDeltas(pairs, MIN_OCCURRENCES), topN = 1,
        )
        fun lift(d: MoodCorrelations.FactorDelta?) =
            d?.let { nameById[it.id]?.let { name -> Signals.FactorLift(name, it.delta, it.n) } }

        // This 30-day window vs the previous 30 days.
        val monthDeltaPct = periodDeltaPct(entries, today, 30)

        // A note written on this date a year ago (looks a day either side for a near match).
        val onThisDay = onThisDayNote(byDay, today)

        return Signals.Inputs(
            totalEntries = entries.size,
            avgMood = levels.average(),
            moodTodayLevel = moodTodayLevel,
            loggedToday = days.contains(today),
            currentStreak = MoodStats.currentStreak(days, today),
            longestStreak = MoodStats.longestStreak(days),
            daysLoggedThisWeek = daysThisWeek,
            topLift = lift(up.firstOrNull()),
            topDrag = lift(down.firstOrNull()),
            monthDeltaPct = monthDeltaPct,
            newlyUnlockedAchievement = null,
            dueCheckin = null,
            onThisDayNote = onThisDay,
        )
    }

    /** Percent change of the last [days]-day average mood vs the [days] days before it, or null. */
    private fun periodDeltaPct(entries: List<EntryWithActivities>, today: LocalDate, days: Long): Double? {
        val curStart = today.minusDays(days - 1)
        val prevStart = today.minusDays(days * 2 - 1)
        val cur = ArrayList<Int>()
        val prev = ArrayList<Int>()
        for (e in entries) {
            val d = DateUtils.toLocalDate(e.entry.dateTime)
            when {
                !d.isBefore(curStart) && !d.isAfter(today) -> cur.add(e.entry.moodLevel)
                !d.isBefore(prevStart) && d.isBefore(curStart) -> prev.add(e.entry.moodLevel)
            }
        }
        return MoodPatterns.periodCompare(cur, prev).deltaPct
    }

    private fun onThisDayNote(byDay: Map<LocalDate, List<EntryWithActivities>>, today: LocalDate): String? {
        val target = today.minusYears(1)
        for (day in listOf(target, target.minusDays(1), target.plusDays(1))) {
            byDay[day]?.mapNotNull { it.entry.note.trim().takeIf { n -> n.isNotEmpty() } }
                ?.lastOrNull()?.let { return it }
        }
        return null
    }

    private companion object {
        const val MIN_OCCURRENCES = 5
    }
}
