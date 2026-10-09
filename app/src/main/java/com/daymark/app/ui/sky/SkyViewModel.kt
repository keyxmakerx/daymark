package com.daymark.app.ui.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.SkyRepository
import com.daymark.app.data.entity.Constellation
import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyColours
import com.daymark.app.sky.SkyConstellation
import com.daymark.app.sky.SkyLayout
import com.daymark.app.sky.SkyNebula
import com.daymark.app.sky.SkySeed
import com.daymark.app.sky.SkyRecord
import com.daymark.app.sky.SkyTrackers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * The Sky's state: a laid-out sky, the constellations the person drew on it, which newest star
 * they have already watched being born, and the sky's own colours, nebulae and trackers.
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
        /** The seed the sky grew from. A new one, after "Reset my sky", plays the opening again. */
        val seed: Long = SkySeed.EMPTY_SKY,
        /** The sky's colours, on the person's choice of them ([SkyColours.of]). */
        val look: SkyColours.Look = SkyColours.of(SkySeed.EMPTY_SKY, 0),
        val colourChoice: Int = 0,
        /** The last day the colours can be changed; 0 before the window has started. */
        val colourUntil: Long = 0L,
        val nebulae: List<SkyNebula.Nebula> = emptyList(),
        /** The trackers the person shows, placed by the screen beside today's stars. */
        val trackers: List<SkyTrackers.Source> = emptyList(),
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
        /** [resolved] as the live sky draws it, with put-away memories left out. */
        val inSight: IntArray,
    )

    private class Grown(val layout: SkyLayout, val seed: Long)

    /** Bumped when the seed changes under the records ("Reset my sky"), so the sky is laid out again. */
    private val seedChanges = MutableStateFlow(0)

    /** Bumped when the colours change, which redraws and lays nothing out again. */
    private val colourChanges = MutableStateFlow(0)

    private val grown = combine(repository.observeRecords(), seedChanges) { records, _ -> records }
        // The seed is the sky's own: derived from the first record and persisted, or drawn anew by
        // "Reset my sky", and never re-derived from the records as they change.
        .map { records ->
            val seed = repository.skySeed(records)
            Grown(Sky.layout(records, seed), seed)
        }
        // The layout is a sort and a linear pass, which is several frames on a phone for years of
        // records. It runs off the main thread because a surface whose job is to move smoothly
        // must not stutter the moment someone logs a check-in. The seed reads and may write prefs.
        .flowOn(Dispatchers.Default)

    val uiState: StateFlow<UiState> = combine(
        grown,
        repository.observeConstellations(),
        repository.observeTrackerSources(),
        colourChanges,
    ) { sky, rows, trackers, _ ->
        val layout = sky.layout
        val choice = repository.colourChoice()
        UiState(
            layout = layout,
            constellations = rows.mapNotNull { shown(it, layout) },
            bornIdentity = repository.bornIdentity(),
            loaded = true,
            seed = sky.seed,
            look = SkyColours.of(sky.seed, choice),
            colourChoice = choice,
            // The window starts the first time the person opens a sky with anything in it.
            colourUntil = if (layout.shownCount > 0) {
                repository.openColourWindow(LocalDate.now().toEpochDay())
            } else {
                repository.colourUntil()
            },
            nebulae = SkyNebula.find(layout),
            trackers = trackers,
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

    /** Puts the memories of star [index] away from the sky: hidden, never deleted (§D11). */
    fun putAway(layout: SkyLayout, index: Int) {
        val records = recordsAt(layout, index)
        viewModelScope.launch { repository.putAway(records, LocalDate.now().toEpochDay()) }
    }

    /** Brings the memories of star [index] back to their place. */
    fun bringBack(layout: SkyLayout, index: Int) {
        val records = recordsAt(layout, index)
        viewModelScope.launch { records.forEach { repository.bringBack(it) } }
    }

    fun bringAllBack() {
        viewModelScope.launch { repository.bringAllBack() }
    }

    /** The next of the sky's other colours, while the window is open. */
    fun tryOtherColours() {
        repository.setColourChoice(repository.colourChoice() + 1)
        colourChanges.update { it + 1 }
    }

    /** The sky's own colours again. */
    fun firstColours() {
        repository.setColourChoice(0)
        colourChanges.update { it + 1 }
    }

    /** "Reset my sky": a new seed, and the colours' window again. Every memory stays. */
    fun resetSky() {
        repository.resetSky(LocalDate.now().toEpochDay())
        seedChanges.update { it + 1 }
    }

    /** Takes a tracker out of the sky; its own screen can put it back. */
    fun takeTrackerOut(trackerId: Long) {
        viewModelScope.launch { repository.setTrackerShown(trackerId, false) }
    }

    private fun recordsAt(layout: SkyLayout, index: Int): List<SkyRecord> {
        if (index !in 0 until layout.starCount) return emptyList()
        val kind = layout.kindAt(index)
        return layout.recordIdsAt(index).map { SkyRecord(kind, it, layout.epochDay[index]) }
    }

    private fun shown(row: Constellation, layout: SkyLayout): ShownConstellation? {
        val points = SkyConstellation.decode(row.points)
        val resolved = SkyConstellation.resolve(points, layout)
        if (!SkyConstellation.isShown(resolved)) return null
        return ShownConstellation(
            row.id,
            row.name,
            row.madeEpochDay,
            points,
            resolved,
            SkyConstellation.inSight(resolved, layout),
        )
    }
}
