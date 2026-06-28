package com.daymark.app.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.AchievementsStore
import com.daymark.app.data.AssessmentRepository
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.entity.AssessmentResult
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.stats.Achievements
import com.daymark.app.stats.MoodCorrelations
import com.daymark.app.stats.MoodPatterns
import com.daymark.app.stats.MoodStats
import com.daymark.app.stats.Signals
import com.daymark.app.ui.assessments.Assessments
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Derives [Signals.Inputs] from locally-stored data and runs the [Signals] rules engine, exposing
 * the ranked list of cards. This is the first place the engine becomes visible: the Insights screen
 * reads the [Signals.Surface.Insights]-eligible signals from here.
 *
 * Like the other view-models it owns Room and the time zone, and hands the pure engine
 * already-computed facts.
 *
 * Note: deriving the achievement signal also idempotently records newly-earned achievement unlock
 * times (the same sticky write the Achievements screen performs) so a genuine new unlock can be
 * celebrated app-wide. It never writes anything else and nothing leaves the device.
 */
@HiltViewModel
class SignalsViewModel @Inject constructor(
    entryRepository: EntryRepository,
    assessmentRepository: AssessmentRepository,
    private val achievementsStore: AchievementsStore,
) : ViewModel() {

    val signals: StateFlow<List<Signals.Signal>> = combine(
        entryRepository.observeAll(),
        assessmentRepository.observeAll(),
    ) { entries, assessments ->
        Signals.build(buildInputs(entries, assessments, System.currentTimeMillis()))
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The "what might help" support menu, ordered & lightly personalised (movement rises when it's
     * a known lift). Deliberately separate from [signals]: it has no side effects (no achievement
     * write) and is always available even with no history, so the support space is never empty.
     */
    val supportSignals: StateFlow<List<Signals.Signal>> = entryRepository.observeAll()
        .map { entries -> Signals.supportMenu(topFactors(entries).first) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Strongest positive and negative factor associations past the sample gate (lift to drag). */
    private fun topFactors(
        entries: List<EntryWithActivities>,
    ): Pair<Signals.FactorLift?, Signals.FactorLift?> {
        val pairs = entries.map { it.entry.moodLevel to it.activities.map { a -> a.id } }
        val nameById = entries.flatMap { it.activities }.associate { it.id to it.name }
        val (up, down) = MoodCorrelations.rankLifts(
            MoodCorrelations.factorDeltas(pairs, MIN_OCCURRENCES), topN = 1,
        )
        fun lift(d: MoodCorrelations.FactorDelta?) =
            d?.let { nameById[it.id]?.let { name -> Signals.FactorLift(name, it.delta, it.n) } }
        return lift(up.firstOrNull()) to lift(down.firstOrNull())
    }

    private fun buildInputs(
        entries: List<EntryWithActivities>,
        assessments: List<AssessmentResult>,
        nowMillis: Long,
    ): Signals.Inputs {
        if (entries.isEmpty()) {
            return Signals.Inputs(
                totalEntries = 0, avgMood = null, moodTodayLevel = null, loggedToday = false,
                currentStreak = 0, longestStreak = 0,
                topLift = null, topDrag = null, monthDeltaPct = null,
                newlyUnlockedAchievement = null, dueCheckin = null, onThisDayNote = null,
            )
        }
        val today = LocalDate.now()
        val levels = entries.map { it.entry.moodLevel }
        val byDay = entries.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
        val days = byDay.keys
        val longestStreak = MoodStats.longestStreak(days)

        val todayLevels = byDay[today]?.map { it.entry.moodLevel }
        val moodTodayLevel = todayLevels?.average()?.roundToInt()?.coerceIn(1, 5)

        // Strongest positive / negative factor associations (past the sample gate).
        val (topLift, topDrag) = topFactors(entries)

        return Signals.Inputs(
            totalEntries = entries.size,
            avgMood = levels.average(),
            moodTodayLevel = moodTodayLevel,
            loggedToday = days.contains(today),
            currentStreak = MoodStats.currentStreak(days, today),
            longestStreak = longestStreak,
            topLift = topLift,
            topDrag = topDrag,
            monthDeltaPct = periodDeltaPct(entries, today, 30),
            newlyUnlockedAchievement = recentlyUnlockedAchievement(entries, assessments, longestStreak, nowMillis),
            dueCheckin = dueCheckin(entries.size, assessments, nowMillis),
            onThisDayNote = onThisDayNote(byDay, today),
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
        // Gate like every other input: a month card shouldn't fire off a near-empty window.
        val cmp = MoodPatterns.periodCompare(cur, prev)
        if (cmp.currentCount < MIN_PERIOD_ENTRIES || cmp.previousCount < MIN_PERIOD_ENTRIES) return null
        return cmp.deltaPct
    }

    /**
     * Marks any newly-earned achievements as unlocked (sticky, idempotent — same as the
     * Achievements screen) and, only when **exactly one** was earned by this call, returns its title
     * to celebrate. The single-crossing rule is deliberate: a genuine milestone is crossed one at a
     * time, whereas the first run for a pre-existing user records *many* already-earned badges at
     * once — those must be stamped but never surprise-celebrated as "new". Returns null otherwise.
     */
    private fun recentlyUnlockedAchievement(
        entries: List<EntryWithActivities>,
        assessments: List<AssessmentResult>,
        longestStreak: Int,
        nowMillis: Long,
    ): String? {
        val inputs = Achievements.Inputs(
            totalEntries = entries.size,
            longestStreak = longestStreak,
            distinctActivities = entries.flatMap { it.activities.map { a -> a.id } }.distinct().size,
            checkInsTaken = assessments.size,
        )
        val justUnlocked = achievementsStore.markUnlocked(Achievements.evaluate(inputs), nowMillis)
        val singleNew = justUnlocked.singleOrNull() ?: return null
        return Achievements.CATALOG.firstOrNull { it.id == singleNew }?.title
    }

    /**
     * Suggests the gentle weekly WHO-5 wellbeing check when the person has enough history and
     * hasn't taken it in the last week. Returns the short name, or null.
     */
    private fun dueCheckin(totalEntries: Int, assessments: List<AssessmentResult>, nowMillis: Long): String? {
        if (totalEntries < MIN_ENTRIES_FOR_CHECKIN) return null
        val lastWho5 = assessments.filter { it.key == Assessments.WHO5_KEY }.maxOfOrNull { it.dateTime }
        return if (lastWho5 == null || nowMillis - lastWho5 >= WEEK_MS) "WHO-5" else null
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
        const val MIN_PERIOD_ENTRIES = 5
        const val MIN_ENTRIES_FOR_CHECKIN = 14
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
