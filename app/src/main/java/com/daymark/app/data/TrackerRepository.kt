package com.daymark.app.data

import com.daymark.app.data.dao.TrackerDao
import com.daymark.app.data.dao.TrackerLogDao
import com.daymark.app.data.entity.Tracker
import com.daymark.app.data.entity.TrackerLog
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackerRepository @Inject constructor(
    private val trackerDao: TrackerDao,
    private val trackerLogDao: TrackerLogDao,
) {
    fun observeActive(): Flow<List<Tracker>> = trackerDao.observeActive()

    fun observeById(id: Long): Flow<Tracker?> = trackerDao.observeById(id)

    fun observeLogs(trackerId: Long): Flow<List<TrackerLog>> = trackerLogDao.observeForTracker(trackerId)

    fun observeAllLogs(): Flow<List<TrackerLog>> = trackerLogDao.observeAll()

    suspend fun add(tracker: Tracker): Long = trackerDao.insert(tracker)

    suspend fun getAll(): List<Tracker> = trackerDao.getAll()

    /** Returns the id of an existing SCALE tracker with [name], creating one (0–10) if absent. */
    suspend fun findOrCreateScale(name: String, max: Int = 10): Long {
        trackerDao.getAll().firstOrNull { it.name == name && !it.archived }?.let { return it.id }
        return trackerDao.insert(
            Tracker(name = name, type = Tracker.SCALE, minValue = 0, maxValue = max, unit = "", sortOrder = 0, archived = false),
        )
    }

    suspend fun update(tracker: Tracker) = trackerDao.update(tracker)

    suspend fun setArchived(tracker: Tracker, archived: Boolean) =
        trackerDao.update(tracker.copy(archived = archived))

    suspend fun log(trackerId: Long, value: Double, dateTime: Long, note: String = ""): Long =
        trackerLogDao.insert(TrackerLog(trackerId = trackerId, dateTime = dateTime, value = value, note = note))

    suspend fun deleteLog(log: TrackerLog) = trackerLogDao.delete(log)
}
