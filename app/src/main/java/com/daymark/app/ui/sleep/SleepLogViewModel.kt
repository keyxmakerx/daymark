package com.daymark.app.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.SleepRepository
import com.daymark.app.data.entity.SleepLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class SleepLogViewModel @Inject constructor(
    private val repository: SleepRepository,
) : ViewModel() {

    /** Converts picked clock times for "last night" into a stored [SleepLog]. */
    fun save(
        bedHour: Int, bedMinute: Int,
        wakeHour: Int, wakeMinute: Int,
        latencyMin: Int, awakeMin: Int, quality: Int, note: String,
    ) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        var bed = today.atTime(bedHour, bedMinute)
        val wake = today.atTime(wakeHour, wakeMinute)
        // If "into bed" is later in the clock than "woke", the bedtime was the previous evening.
        if (bed.isAfter(wake)) bed = bed.minusDays(1)
        val log = SleepLog(
            night = today.toEpochDay(),
            bedTime = bed.atZone(zone).toInstant().toEpochMilli(),
            wakeTime = wake.atZone(zone).toInstant().toEpochMilli(),
            sleepLatencyMin = latencyMin,
            awakeMin = awakeMin,
            quality = quality,
            note = note.trim(),
        )
        viewModelScope.launch { repository.add(log) }
    }
}
