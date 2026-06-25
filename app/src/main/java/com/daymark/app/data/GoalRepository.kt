package com.daymark.app.data

import com.daymark.app.data.dao.GoalDao
import com.daymark.app.data.entity.Goal
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoalRepository @Inject constructor(
    private val goalDao: GoalDao,
) {
    fun observeActive(): Flow<List<Goal>> = goalDao.observeActive()

    suspend fun getById(id: Long): Goal? = goalDao.getById(id)

    suspend fun save(goal: Goal): Long =
        if (goal.id == 0L) goalDao.insert(goal) else {
            goalDao.update(goal)
            goal.id
        }

    suspend fun delete(goal: Goal) = goalDao.delete(goal)
}
