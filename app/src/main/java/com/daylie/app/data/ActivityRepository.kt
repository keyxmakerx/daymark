package com.daylie.app.data

import com.daylie.app.data.dao.ActivityDao
import com.daylie.app.data.entity.ActivityEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityRepository @Inject constructor(
    private val activityDao: ActivityDao,
) {
    fun observeActive(): Flow<List<ActivityEntity>> = activityDao.observeActive()

    fun observeAll(): Flow<List<ActivityEntity>> = activityDao.observeAll()

    suspend fun add(name: String, iconKey: String): Long =
        activityDao.insert(ActivityEntity(name = name.trim(), iconKey = iconKey))

    suspend fun update(activity: ActivityEntity) = activityDao.update(activity)

    suspend fun setArchived(activity: ActivityEntity, archived: Boolean) =
        activityDao.update(activity.copy(archived = archived))

    suspend fun insertAll(activities: List<ActivityEntity>) = activityDao.insertAll(activities)
}
