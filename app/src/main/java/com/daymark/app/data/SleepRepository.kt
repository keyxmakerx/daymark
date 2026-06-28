package com.daymark.app.data

import com.daymark.app.data.dao.SleepLogDao
import com.daymark.app.data.entity.SleepLog
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SleepRepository @Inject constructor(
    private val sleepLogDao: SleepLogDao,
) {
    fun observeAll(): Flow<List<SleepLog>> = sleepLogDao.observeAll()

    suspend fun add(log: SleepLog): Long = sleepLogDao.insert(log)

    suspend fun delete(log: SleepLog) = sleepLogDao.delete(log)
}
