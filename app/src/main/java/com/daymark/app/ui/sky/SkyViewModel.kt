package com.daymark.app.ui.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.SkyRepository
import com.daymark.app.data.entity.Constellation
import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyConstellation
import com.daymark.app.sky.SkyLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Sky's state: a laid-out sky, the constellations the person drew on it, and which newest star
 * they have already watched being born.
 *
 * No filter, no date window, no "current period", no selection of what to show: those are the
 * shapes a surface grows when it starts having an opinion about which parts of a person's history
 * are worth drawing, and this one does not get to have one.
 */
@HiltViewModel
class SkyViewModel @Inject constructor(
    private val repository: SkyRepository,
) : ViewModel() {

    /**
     * @param loaded false until the first read comes back.
     *
     * It exists so the empty state cannot flash. `NO_RECORDS` renders a calm line saying nothing of
     * yours is here yet, and showing that to someone with ten years of history for the 80 ms before
     * their sky arrives would be a small lie about their own life. Nothing is drawn but the night
     * ground until this is true.
     */
    data class UiState(
        val layout: SkyLayout = SkyLayout.EMPTY,
        /** Only those with at least two stars left to join; see [SkyConstellation.isShown]. */
        val constellations: List<ShownConstellation> = emptyList(),
        /** [SkyRepository.bornIdentity], read with the sky so the opening decides once. */
        val bornIdentity: Long = 0L,
        val loaded: Boolean = false,
    )

    /**
     * One constellation, ready to draw: its points as drawn, and where each point's star is in
     * today's layout (-1 for a memory that is gone).
     */
    class ShownConstellation(
        val id: Long,
        val name: String,
        val madeEpochDay: Long,
        val points: List<SkyConstellation.Point>,
        val resolved: IntArray,
    )

    private val layouts = repository.observeRecords()
        // The seed is the sky's own: derived from the first record, persisted, never re-derived.
        .map { records -> Sky.layout(records, repository.skySeed(records)) }
        // The layout is a sort and a linear pass, which is several frames on a phone for years of
        // records. It runs off the main thread because a surface whose job is to move smoothly
        // must not stutter the moment someone logs a check-in. The seed reads and may write prefs.
        .flowOn(Dispatchers.Default)

    val uiState: StateFlow<UiState> = combine(layouts, repository.observeConstellations()) { layout, rows ->
        UiState(
            layout = layout,
            constellations = rows.mapNotNull { shown(it, layout) },
            bornIdentity = repository.bornIdentity(),
            loaded = true,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Keeps a constellation the person has just drawn and named. */
    fun saveConstellation(name: String, madeEpochDay: Long, points: List<SkyConstellation.Point>) {
        viewModelScope.launch {
            repository.addConstellation(name, madeEpochDay, points, System.currentTimeMillis())
        }
    }

    fun removeConstellation(id: Long) {
        viewModelScope.launch { repository.removeConstellation(id) }
    }

    /** The newest star has been born in front of the person, or they skipped past it. */
    fun markBorn(identity: Long) {
        repository.markBorn(identity)
    }

    private fun shown(row: Constellation, layout: SkyLayout): ShownConstellation? {
        val points = SkyConstellation.decode(row.points)
        val resolved = SkyConstellation.resolve(points, layout)
        if (!SkyConstellation.isShown(resolved)) return null
        return ShownConstellation(row.id, row.name, row.madeEpochDay, points, resolved)
    }
}
