package com.daymark.app.ui.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.SkyRepository
import com.daymark.app.sky.Sky
import com.daymark.app.sky.SkyLayout
import com.daymark.app.sky.SkySeed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The Sky's state: a laid-out sky and the seed its background is drawn from.
 *
 * There is nothing else in it. No filter, no date window, no "current period", no selection of what
 * to show — those are the shapes a surface grows when it starts having an opinion about which parts
 * of a person's history are worth drawing, and this one does not get to have one.
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
        val fieldSeed: Long = SkySeed.EMPTY_SKY,
        val loaded: Boolean = false,
    )

    val uiState: StateFlow<UiState> = repository.observeRecords()
        .map { records ->
            UiState(
                layout = Sky.layout(records),
                fieldSeed = repository.fieldSeed(records),
                loaded = true,
            )
        }
        // The layout is a sort and a linear pass — measured at 15–29 ms for 5,393 records on a
        // plain JVM (`docs/SKY.md` §0.2), which is several frames on a phone. It runs off the main
        // thread because a surface whose entire job is to scroll smoothly must not stutter the
        // moment someone logs a check-in. `fieldSeed` is here too: it reads and may write prefs.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())
}
