package com.daymark.app.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.JournalRepository
import com.daymark.app.export.PdfExportOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Everything the picker screen draws. All of it derived, in one place, from
 * [JournalSelection.resolve] — so the sentence the person reads and the options the report is built
 * from cannot describe different decisions.
 */
data class JournalPickerUiState(
    val rows: List<JournalSelection.Row> = emptyList(),
    val outcome: JournalSelection.Outcome = JournalSelection.NOTHING_CHOSEN,
    val summary: String = "",
    /**
     * The blanket action as the person left it, not as it resolved. The screen needs the raw flag
     * to know which of its two modes to draw: over an empty range the flag resolves to
     * [JournalSelection.Form.NOTHING], and the screen still has to show that the person took the
     * blanket action rather than silently dropping it.
     */
    val wholeRangeChosen: Boolean = false,
) {
    /** Ticking is hidden entirely in blanket mode — see the screen's KDoc for why. */
    val showChecks: Boolean get() = !wholeRangeChosen
}

/**
 * Copies a picker result onto export options. **The single place the three journal fields are
 * set**, and it sets all three together: they encode one decision between them, and a caller that
 * set two of them would produce a report whose pages and whose opt-in sentence disagreed.
 */
fun PdfExportOptions.withJournalChoice(outcome: JournalSelection.Outcome): PdfExportOptions = copy(
    includeInTheirWords = outcome.includeInTheirWords,
    includedJournalEntryIds = outcome.includedIds,
    includeAllJournalInRange = outcome.includeAllInRange,
)

/**
 * Backs the per-entry journal picker.
 *
 * Holds no decisions of its own — the range, the ticks and the blanket flag are three pieces of
 * state and [JournalSelection] turns them into an answer. Nothing is inferred, nominated or
 * pre-ticked: the tick set starts empty and only [toggle] and [chooseWholeRange] ever change it.
 *
 * The range starts **empty** (`0..-1` matches nothing) rather than open, so a host that forgets to
 * call [setRange] lists nothing instead of listing the person's entire journal. The screen calls it
 * from a `LaunchedEffect` on its range arguments, which also re-derives everything when the person
 * changes the date range behind it.
 */
@HiltViewModel
class JournalPickerViewModel @Inject constructor(
    journalRepository: JournalRepository,
) : ViewModel() {

    private data class Range(val fromMillis: Long, val toMillis: Long)

    private val range = MutableStateFlow(Range(0L, -1L))
    private val ticked = MutableStateFlow<Set<Long>>(emptySet())
    private val wholeRange = MutableStateFlow(false)

    val uiState: StateFlow<JournalPickerUiState> = combine(
        // The unfiltered query: the picker needs every entry so it can window them itself, by the
        // same rule the report uses. Reading them here is not disclosure — nothing leaves the
        // device until the person confirms, and the screen's whole job is to show them what they
        // would be sharing.
        journalRepository.observe(""),
        range,
        ticked,
        wholeRange,
    ) { all, r, t, blanket ->
        val candidates = all.map {
            JournalSelection.Candidate(it.id, it.dateTime, it.title, it.body)
        }
        val window = JournalSelection.inRange(candidates, r.fromMillis, r.toMillis)
        val outcome = JournalSelection.resolve(window, t, blanket)
        JournalPickerUiState(
            rows = JournalSelection.rows(window, t),
            outcome = outcome,
            summary = JournalSelection.summary(outcome),
            wholeRangeChosen = blanket,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalPickerUiState())

    /**
     * The window the export was asked for. Ticks are kept across a change of range rather than
     * cleared — narrowing and widening again should not cost the person their choices — and
     * [JournalSelection.prune] keeps the ones outside the current window out of the result.
     */
    fun setRange(fromMillis: Long, toMillis: Long) {
        range.value = Range(fromMillis, toMillis)
    }

    fun toggle(id: Long, on: Boolean) {
        ticked.update { if (on) it + id else it - id }
    }

    /**
     * The blanket action, which is a different consent from ticking everything and is recorded as
     * one. Existing ticks are left untouched so [chooseEntryByEntry] can return the person to
     * exactly where they were.
     */
    fun chooseWholeRange() {
        wholeRange.value = true
    }

    fun chooseEntryByEntry() {
        wholeRange.value = false
    }

    /** Back to the default — nothing included — without leaving the screen. */
    fun clear() {
        ticked.value = emptySet()
        wholeRange.value = false
    }
}
