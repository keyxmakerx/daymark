package com.daymark.app.ui.insights

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.export.YearKeepsakeRenderer
import com.daymark.app.stats.YearReview
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

data class ReviewYearState(
    val year: Int,
    /** date -> average mood level (1..5) for days with entries, that year. */
    val dayMoods: Map<LocalDate, Double> = emptyMap(),
)

/** Provides one year's per-day mood means for the "Review my year" walkthrough. */
@HiltViewModel
class ReviewYearViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    entryRepository: EntryRepository,
    private val keepsakeRenderer: YearKeepsakeRenderer,
) : ViewModel() {

    private val year: Int =
        savedStateHandle.get<String>("year")?.toIntOrNull() ?: LocalDate.now().year

    /** Renders the keepsake and writes it as a PNG to [uri]; returns true on success. Off-main. */
    suspend fun saveKeepsake(uri: Uri, review: YearReview.Review, moodArgb: IntArray): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val bmp = keepsakeRenderer.render(review, moodArgb)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: return@withContext false
                true
            } catch (e: Exception) {
                false
            }
        }

    val uiState: StateFlow<ReviewYearState> = run {
        val from = DateUtils.startOfDay(LocalDate.of(year, 1, 1))
        val to = DateUtils.endOfDay(LocalDate.of(year, 12, 31))
        entryRepository.observeBetween(from, to).map { entries ->
            ReviewYearState(
                year = year,
                dayMoods = entries.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
                    .mapValues { (_, list) -> list.map { it.entry.moodLevel }.average() },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewYearState(year))
    }
}
