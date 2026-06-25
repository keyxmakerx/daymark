package com.daylie.app.backup

import com.daylie.app.data.dao.ActivityDao
import com.daylie.app.data.dao.EntryDao
import com.daylie.app.data.dao.JournalDao
import com.daylie.app.data.entity.ActivityEntity
import com.daylie.app.data.entity.EntryActivityCrossRef
import com.daylie.app.data.entity.JournalEntry
import com.daylie.app.data.entity.MoodEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupEntry(val id: Long, val dateTime: Long, val moodLevel: Int, val note: String)

@Serializable
data class BackupActivity(val id: Long, val name: String, val iconKey: String, val sortOrder: Int, val archived: Boolean)

@Serializable
data class BackupRef(val entryId: Long, val activityId: Long)

@Serializable
data class BackupJournal(val id: Long, val dateTime: Long, val title: String, val body: String)

@Serializable
data class BackupData(
    val version: Int = 2,
    val exportedAt: Long,
    val entries: List<BackupEntry>,
    val activities: List<BackupActivity>,
    val refs: List<BackupRef>,
    // Added in v2. Defaulted so older (v1) backups still deserialize.
    val journal: List<BackupJournal> = emptyList(),
)

/**
 * Exports/imports the entire local database as JSON. This is the user's only safety
 * net in a local-only app, so it round-trips every table.
 */
@Singleton
class BackupManager @Inject constructor(
    private val entryDao: EntryDao,
    private val activityDao: ActivityDao,
    private val journalDao: JournalDao,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun exportToJson(nowMillis: Long): String {
        val data = BackupData(
            exportedAt = nowMillis,
            entries = entryDao.getAllEntries().map { BackupEntry(it.id, it.dateTime, it.moodLevel, it.note) },
            activities = activityDao.getAllOnce().map {
                BackupActivity(it.id, it.name, it.iconKey, it.sortOrder, it.archived)
            },
            refs = entryDao.getAllCrossRefs().map { BackupRef(it.entryId, it.activityId) },
            journal = journalDao.getAll().map { BackupJournal(it.id, it.dateTime, it.title, it.body) },
        )
        return json.encodeToString(data)
    }

    /** Replaces all current data with the backup's contents. */
    suspend fun importFromJson(jsonText: String) {
        val data = json.decodeFromString<BackupData>(jsonText)

        entryDao.deleteAllCrossRefs()
        entryDao.deleteAllEntries()
        activityDao.deleteAll()
        journalDao.deleteAll()

        activityDao.insertAll(
            data.activities.map { ActivityEntity(it.id, it.name, it.iconKey, it.sortOrder, it.archived) },
        )
        data.entries.forEach { entryDao.insert(MoodEntry(it.id, it.dateTime, it.moodLevel, it.note)) }
        entryDao.insertCrossRefs(data.refs.map { EntryActivityCrossRef(it.entryId, it.activityId) })
        data.journal.forEach { journalDao.insert(JournalEntry(it.id, it.dateTime, it.title, it.body)) }
    }
}
