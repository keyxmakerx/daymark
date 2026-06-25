package com.daylie.app.data

import com.daylie.app.data.dao.JournalDao
import com.daylie.app.data.entity.JournalEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JournalRepository @Inject constructor(
    private val journalDao: JournalDao,
) {
    /** All entries, or those matching [query] when it is non-blank. */
    fun observe(query: String): Flow<List<JournalEntry>> =
        if (query.isBlank()) journalDao.observeAll() else journalDao.search(query.trim())

    suspend fun getById(id: Long): JournalEntry? = journalDao.getById(id)

    suspend fun count(): Int = journalDao.count()

    suspend fun save(entry: JournalEntry): Long =
        if (entry.id == 0L) journalDao.insert(entry) else {
            journalDao.update(entry)
            entry.id
        }

    suspend fun delete(entry: JournalEntry) = journalDao.delete(entry)
}
