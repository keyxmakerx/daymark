package com.daymark.app.ui.settings

import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.MoodCustomizationStore
import com.daymark.app.data.SettingsRepository
import com.daymark.app.model.Mood
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One row in the customize-moods screen: the level plus its resolved label/colour. */
data class MoodLevelUi(
    val level: Int,
    val label: String,
    val colorArgb: Int,
    val labelOverridden: Boolean,
    val colorOverridden: Boolean,
)

@HiltViewModel
class CustomizeMoodsViewModel @Inject constructor(
    private val store: MoodCustomizationStore,
    settings: SettingsRepository,
) : ViewModel() {

    // The store shares SettingsRepository's prefs, so changes() fires when overrides change.
    val levels: StateFlow<List<MoodLevelUi>> = settings.changes()
        .map { buildLevels() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildLevels())

    private fun buildLevels(): List<MoodLevelUi> = (5 downTo 1).map { lvl ->
        val mood = Mood.fromLevel(lvl)
        MoodLevelUi(
            level = lvl,
            label = store.labelFor(lvl) ?: mood.label,
            colorArgb = store.colorFor(lvl) ?: mood.color.toArgb(),
            labelOverridden = store.labelFor(lvl) != null,
            colorOverridden = store.colorFor(lvl) != null,
        )
    }

    /** Blank label resets that level to its default name. */
    fun setLabel(level: Int, label: String) = store.setLabel(level, label.ifBlank { null })

    fun setColor(level: Int, argb: Int) = store.setColor(level, argb)

    fun resetLevel(level: Int) {
        store.setLabel(level, null)
        store.setColor(level, null)
    }

    fun resetAll() = store.reset()
}
