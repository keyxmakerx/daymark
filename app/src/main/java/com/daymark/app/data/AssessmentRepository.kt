package com.daymark.app.data

import com.daymark.app.data.dao.AssessmentDao
import com.daymark.app.data.entity.AssessmentResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssessmentRepository @Inject constructor(
    private val dao: AssessmentDao,
) {
    fun observeForKey(key: String): Flow<List<AssessmentResult>> = dao.observeForKey(key)

    fun observeAll(): Flow<List<AssessmentResult>> = dao.observeAll()

    suspend fun save(key: String, score: Int, bandLabel: String, atMillis: Long) {
        dao.insert(AssessmentResult(key = key, dateTime = atMillis, score = score, bandLabel = bandLabel))
    }
}
