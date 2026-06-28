package com.daymark.app.data

import com.daymark.app.data.dao.ThoughtRecordDao
import com.daymark.app.data.entity.ThoughtRecord
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThoughtRecordRepository @Inject constructor(
    private val dao: ThoughtRecordDao,
) {
    fun observeAll(): Flow<List<ThoughtRecord>> = dao.observeAll()

    suspend fun getById(id: Long): ThoughtRecord? = dao.getById(id)

    suspend fun save(record: ThoughtRecord): Long = dao.insert(record)

    suspend fun delete(record: ThoughtRecord) = dao.delete(record)
}
