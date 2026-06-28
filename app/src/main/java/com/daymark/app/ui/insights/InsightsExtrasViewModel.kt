package com.daymark.app.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.TrackerRepository
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.data.entity.Tracker
import com.daymark.app.data.entity.TrackerLog
import com.daymark.app.stats.MoodCorrelations
import com.daymark.app.stats.MoodPatterns
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject

/** A named factor (activity or tracker) and how it relates to mood, ready for display. */
data class FactorRow(val name: String, val delta: Double, val n: Int)

/** A numeric tracker's correlation with daily mood. */
data class TrackerCorrelationRow(val name: String, val r: Double, val n: Int)

data class InsightsExtrasState(
    val topUp: List<FactorRow> = emptyList(),
    val topDown: List<FactorRow> = emptyList(),
    val trackerCorrelations: List<TrackerCorrelationRow> = emptyList(),
    val dayOfWeek: Map<DayOfWeek, Double> = emptyMap(),
    val timeOfDay: Map<MoodPatterns.TimeBucket, Double> = emptyMap(),
    val weekCompare: MoodPatterns.PeriodComparison? = null,
    val monthCompare: MoodPatterns.PeriodComparison? = null,
    val yearCompare: MoodPatterns.PeriodComparison? = null,
)

/**
 * Computes the richer Insights sections (correlations, weekday/time patterns, period comparisons)
 * from data already stored locally. All outputs are associations, never causes — the UI labels
 * them as such and the min-sample gates below avoid showing noise from too little data.
 */
@HiltViewModel
class InsightsExtrasViewModel @Inject constructor(
    entryRepository: EntryRepository,
    trackerRepository: TrackerRepository,
) : ViewModel() {

    val uiState: StateFlow<InsightsExtrasState> = combine(
        entryRepository.observeAll(),
        trackerRepository.observeActive(),
        trackerRepository.observeAllLogs(),
    ) { entries, trackers, logs ->
        compute(entries, trackers, logs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsExtrasState())

    private fun compute(
        entries: List<EntryWithActivities>,
        trackers: List<Tracker>,
        logs: List<TrackerLog>,
    ): InsightsExtrasState {
        if (entries.isEmpty()) return InsightsExtrasState()

        // --- Activity correlations ---
        val moodActivityPairs = entries.map { it.entry.moodLevel to it.activities.map { a -> a.id } }
        val nameById = entries.flatMap { it.activities }.associate { it.id to it.name }
        val deltas = MoodCorrelations.factorDeltas(moodActivityPairs, MIN_OCCURRENCES)
        val (up, down) = MoodCorrelations.rankLifts(deltas, TOP_N)
        fun rows(list: List<MoodCorrelations.FactorDelta>) =
            list.mapNotNull { d -> nameById[d.id]?.let { FactorRow(it, d.delta, d.n) } }

        // --- Tracker correlations (daily mean mood vs daily mean tracker value) ---
        val moodByDay = entries.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
            .mapValues { (_, e) -> e.map { it.entry.moodLevel }.average() }
        val logsByTracker = logs.groupBy { it.trackerId }
        val trackerCorrs = trackers.mapNotNull { tracker ->
            val byDay = logsByTracker[tracker.id]
                ?.groupBy { DateUtils.toLocalDate(it.dateTime) }
                ?.mapValues { (_, l) -> l.map { it.value }.average() }
                ?: return@mapNotNull null
            val points = byDay.mapNotNull { (day, value) ->
                moodByDay[day]?.let { MoodCorrelations.DayPoint(it, value) }
            }
            MoodCorrelations.trackerCorrelation(points, MIN_DAYS)
                ?.let { TrackerCorrelationRow(tracker.name, it, points.size) }
        }.sortedByDescending { kotlin.math.abs(it.r) }

        // --- Weekday / time-of-day patterns ---
        val dow = MoodPatterns.byDayOfWeek(
            entries.map { DateUtils.toLocalDate(it.entry.dateTime).dayOfWeek to it.entry.moodLevel },
        )
        val tod = MoodPatterns.byTimeOfDay(
            entries.map { DateUtils.toLocalDateTime(it.entry.dateTime).hour to it.entry.moodLevel },
        )

        // --- Period comparisons (current window vs the one before it) ---
        val today = LocalDate.now()
        fun compareWindow(days: Long): MoodPatterns.PeriodComparison {
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
            return MoodPatterns.periodCompare(cur, prev)
        }

        return InsightsExtrasState(
            topUp = rows(up),
            topDown = rows(down),
            trackerCorrelations = trackerCorrs,
            dayOfWeek = dow,
            timeOfDay = tod,
            weekCompare = compareWindow(7),
            monthCompare = compareWindow(30),
            yearCompare = compareWindow(365),
        )
    }

    private companion object {
        const val MIN_OCCURRENCES = 5
        const val MIN_DAYS = 14
        const val TOP_N = 5
    }
}
