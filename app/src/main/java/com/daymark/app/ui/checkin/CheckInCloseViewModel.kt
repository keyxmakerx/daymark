package com.daymark.app.ui.checkin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.CheckInPlanner
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.TrackerRepository
import com.daymark.app.data.dao.LifeEventDao
import com.daymark.app.data.entity.LifeEvent
import com.daymark.app.stats.CheckInQuestions
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The end of a new check-in: the extra questions the planner chose, one at a time, then the
 * closing screen. Every question can be skipped, the whole of it can be left with one tap, and the
 * closing screen always offers the safety plan (§D1b). Nothing here opens anything by itself except
 * the one thing the person chose ahead of time, the evening check-in that ends in the sky (#540),
 * and that never after Awful or Bad or during a hard time.
 */
@HiltViewModel
class CheckInCloseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val planner: CheckInPlanner,
    private val trackers: TrackerRepository,
    private val entries: EntryRepository,
    private val lifeEvents: LifeEventDao,
) : ViewModel() {

    private val entryId: Long = savedStateHandle.get<String>("entryId")?.toLongOrNull() ?: 0L
    private val mood: Int = savedStateHandle.get<String>("mood")?.toIntOrNull()?.coerceIn(1, 5) ?: 3
    private val fromSky: Boolean = savedStateHandle.get<String>("sky")?.toBooleanStrictOrNull() ?: false

    data class UiState(
        val loaded: Boolean = false,
        val questions: List<CheckInPlanner.Question> = emptyList(),
        /** Which question is showing; at or past the end is the closing screen. */
        val index: Int = 0,
        val offers: Set<CheckInQuestions.Closing> = emptySet(),
        /** Awful or Bad: the closing screen leads with Take a moment and the safety plan. */
        val hardMood: Boolean = false,
        /** True once, when the evening check-in begun in the sky should end with its star landing. */
        val landNow: Boolean = false,
        /** The person just turned a question off; the closing screen says how to turn it back on. */
        val stoppedOne: Boolean = false,
        /** The label of a mark placed from here, so the screen can say it is there. */
        val marked: String? = null,
    ) {
        val current: CheckInPlanner.Question? get() = questions.getOrNull(index)
    }

    private val _state = MutableStateFlow(UiState(hardMood = mood <= CheckInQuestions.SHORT_PATH_MOOD_MAX))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var hardTime = false

    init {
        viewModelScope.launch {
            val plan = planner.plan(mood)
            hardTime = plan.hardTime
            _state.update { it.copy(loaded = true, questions = plan.questions, offers = plan.offers) }
            if (plan.questions.isEmpty()) reachedEnd()
        }
    }

    /** A tracker's question answered with [value], logged now as from the tracker's own screen. */
    fun answer(value: Double) {
        val q = _state.value.current ?: return
        val tracker = q.tracker ?: return
        viewModelScope.launch {
            trackers.log(tracker.id, value, System.currentTimeMillis())
            planner.record(q.subject, answered = true)
            next()
        }
    }

    /** The sleep question's "Log last night": the person is going to the sleep diary. */
    fun answerSleep() {
        val q = _state.value.current ?: return
        viewModelScope.launch {
            planner.record(q.subject, answered = true)
            next()
        }
    }

    fun skip() {
        val q = _state.value.current ?: return
        viewModelScope.launch {
            planner.record(q.subject, answered = false)
            next()
        }
    }

    /** "Stop asking this": off where it was set up, and nothing else asked about it. */
    fun stopAsking() {
        val q = _state.value.current ?: return
        viewModelScope.launch {
            planner.stopAsking(q)
            _state.update { it.copy(stoppedOne = true) }
            next()
        }
    }

    /** "Done for today": the question showing counts as skipped, and the rest are never shown. */
    fun doneForToday() {
        val q = _state.value.current
        viewModelScope.launch {
            if (q != null) planner.record(q.subject, answered = false)
            _state.update { it.copy(index = it.questions.size) }
            reachedEnd()
        }
    }

    fun landed() = _state.update { it.copy(landNow = false) }

    /** "Mark this day": a life event on this check-in's date, in the person's own words (SKY.md §2.2). */
    fun markThisDay(label: String) {
        val text = label.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val millis = entries.getById(entryId)?.entry?.dateTime ?: System.currentTimeMillis()
            lifeEvents.insert(
                LifeEvent(
                    epochDay = DateUtils.toLocalDate(millis).toEpochDay(),
                    label = text,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            _state.update { it.copy(marked = text) }
        }
    }

    private fun next() {
        _state.update { it.copy(index = it.index + 1) }
        if (_state.value.current == null) reachedEnd()
    }

    private fun reachedEnd() {
        if (fromSky && mood > CheckInQuestions.SHORT_PATH_MOOD_MAX && !hardTime) {
            _state.update { it.copy(landNow = true) }
        }
    }
}
