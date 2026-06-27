package com.daymark.app.data

import com.daymark.app.data.dao.EntryDao
import com.daymark.app.data.entity.EntryWithActivities
import com.daymark.app.data.entity.MoodEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EntryRepository @Inject constructor(
    private val entryDao: EntryDao,
) {
    fun observeAll(): Flow<List<EntryWithActivities>> = entryDao.observeAll()

    fun observeBetween(from: Long, to: Long): Flow<List<EntryWithActivities>> =
        entryDao.observeBetween(from, to)

    suspend fun getById(id: Long): EntryWithActivities? = entryDao.getById(id)

    /** Full-text-ish search across entry notes. Blank query returns nothing. */
    fun search(query: String): Flow<List<EntryWithActivities>> = entryDao.search(query)

    suspend fun count(): Int = entryDao.count()

    /** Creates or updates an entry and its activity links in one operation. */
    suspend fun save(entry: MoodEntry, activityIds: List<Long>): Long {
        val id = if (entry.id == 0L) {
            entryDao.insert(entry)
        } else {
            entryDao.update(entry)
            entry.id
        }
        entryDao.setActivities(id, activityIds)
        return id
    }

    suspend fun delete(entry: MoodEntry) {
        entryDao.clearCrossRefs(entry.id)
        entryDao.delete(entry)
    }
}
