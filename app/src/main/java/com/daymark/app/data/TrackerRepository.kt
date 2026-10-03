package com.daymark.app.data

import com.daymark.app.data.dao.TrackerDao
import com.daymark.app.data.dao.TrackerLogDao
import com.daymark.app.data.entity.Tracker
import com.daymark.app.data.entity.TrackerLog
import com.daymark.app.notifications.TrackerCheckInScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackerRepository @Inject constructor(
    private val trackerDao: TrackerDao,
    private val trackerLogDao: TrackerLogDao,
    private val checkIns: TrackerCheckInScheduler,
) {
    fun observeActive(): Flow<List<Tracker>> = trackerDao.observeActive()

    fun observeById(id: Long): Flow<Tracker?> = trackerDao.observeById(id)

    fun observeLogs(trackerId: Long): Flow<List<TrackerLog>> = trackerLogDao.observeForTracker(trackerId)

    fun observeAllLogs(): Flow<List<TrackerLog>> = trackerLogDao.observeAll()

    suspend fun add(tracker: Tracker): Long {
        val id = trackerDao.insert(tracker)
        checkIns.refresh(tracker.copy(id = id))
        checkIns.redrawWidget()
        return id
    }

    suspend fun getAll(): List<Tracker> = trackerDao.getAll()

    // Serializes find-or-create so two near-simultaneous auto-creates (e.g. Enjoyment + Mastery)
    // can't insert duplicate trackers with the same name.
    private val findOrCreateMutex = kotlinx.coroutines.sync.Mutex()

    /** Returns the id of an existing SCALE tracker with [name], creating one (0–10) if absent. */
    suspend fun findOrCreateScale(name: String, max: Int = 10): Long = findOrCreateMutex.withLock {
        trackerDao.getAll().firstOrNull { it.name == name && !it.archived }?.let { return@withLock it.id }
        trackerDao.insert(
            Tracker(name = name, type = Tracker.SCALE, minValue = 0, maxValue = max, unit = "", sortOrder = 0, archived = false),
        )
    }

    /** Returns the id of an existing NUMERIC tracker with [name], creating one if absent. */
    suspend fun findOrCreateNumeric(name: String, unit: String = ""): Long = findOrCreateMutex.withLock {
        trackerDao.getAll().firstOrNull { it.name == name && !it.archived }?.let { return@withLock it.id }
        trackerDao.insert(
            Tracker(name = name, type = Tracker.NUMERIC, minValue = 0, maxValue = 0, unit = unit, sortOrder = 0, archived = false),
        )
    }

    /** Saves [tracker] and re-arms its check-ins and quick-log notification to match. */
    suspend fun update(tracker: Tracker) {
        trackerDao.update(tracker)
        checkIns.refresh(tracker)
        checkIns.redrawWidget()
    }

    /** An archived tracker asks nothing and shows nothing until it is brought back. */
    suspend fun setArchived(tracker: Tracker, archived: Boolean) = update(tracker.copy(archived = archived))

    suspend fun log(trackerId: Long, value: Double, dateTime: Long, note: String = ""): Long =
        trackerLogDao.insert(TrackerLog(trackerId = trackerId, dateTime = dateTime, value = value, note = note))

    suspend fun deleteLog(log: TrackerLog) = trackerLogDao.delete(log)
}
