package com.daymark.app.ui.movement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.TrackerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MovementSessionViewModel @Inject constructor(
    private val trackerRepository: TrackerRepository,
) : ViewModel() {

    /** Logs a finished session's duration to a "Movement minutes" tracker (auto-created), so it
     *  shows up against mood in Insights. */
    fun logSession(seconds: Int) {
        if (seconds <= 0) return
        val minutes = ((seconds + 59) / 60).coerceAtLeast(1)
        viewModelScope.launch {
            val t = trackerRepository.findOrCreateNumeric(TRACKER_NAME, "min")
            trackerRepository.log(t, minutes.toDouble(), System.currentTimeMillis())
        }
    }

    private companion object {
        const val TRACKER_NAME = "Movement minutes"
    }
}
