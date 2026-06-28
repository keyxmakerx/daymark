package com.daymark.app.ui.assessments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.AssessmentRepository
import com.daymark.app.data.entity.AssessmentResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AssessmentViewModel @Inject constructor(
    private val repository: AssessmentRepository,
) : ViewModel() {

    fun history(key: String): Flow<List<AssessmentResult>> = repository.observeForKey(key)

    fun latestPerKey(): Flow<List<AssessmentResult>> = repository.observeAll()

    fun save(key: String, score: Int, bandLabel: String) {
        viewModelScope.launch { repository.save(key, score, bandLabel, System.currentTimeMillis()) }
    }
}
