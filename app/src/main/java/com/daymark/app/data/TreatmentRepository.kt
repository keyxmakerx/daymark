package com.daymark.app.data

import com.daymark.app.data.dao.TreatmentDao
import com.daymark.app.data.entity.Treatment
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TreatmentRepository @Inject constructor(
    private val treatmentDao: TreatmentDao,
) {
    fun observeAll(): Flow<List<Treatment>> = treatmentDao.observeAll()

    fun observeById(id: Long): Flow<Treatment?> = treatmentDao.observeById(id)

    suspend fun add(treatment: Treatment): Long = treatmentDao.insert(treatment)

    suspend fun delete(treatment: Treatment) = treatmentDao.delete(treatment)
}
