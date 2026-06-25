package com.daylie.app.backup

import com.daylie.app.data.dao.ActivityDao
import com.daylie.app.data.dao.EntryDao
import com.daylie.app.data.dao.GoalDao
import com.daylie.app.data.dao.JournalDao
import com.daylie.app.data.entity.ActivityEntity
import com.daylie.app.data.entity.EntryActivityCrossRef
import com.daylie.app.data.entity.Goal
import com.daylie.app.data.entity.JournalEntry
import com.daylie.app.data.entity.MoodEntry
import com.daylie.app.model.Mood
import com.daylie.app.util.DateUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** RFC-4180-style CSV escaping: quote fields containing a comma, quote, or newline. */
internal fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

@Serializable
data class BackupEntry(val id: Long, val dateTime: Long, val moodLevel: Int, val note: String)

@Serializable
data class BackupActivity(val id: Long, val name: String, val iconKey: String, val sortOrder: Int, val archived: Boolean)

@Serializable
data class BackupRef(val entryId: Long, val activityId: Long)

@Serializable
data class BackupJournal(val id: Long, val dateTime: Long, val title: String, val body: String)

@Serializable
data class BackupGoal(
    val id: Long,
    val title: String,
    val activityId: Long?,
    val targetPerWeek: Int,
    val createdAt: Long,
    val archived: Boolean,
)

@Serializable
data class BackupData(
    val version: Int = 3,
    val exportedAt: Long,
    val entries: List<BackupEntry>,
    val activities: List<BackupActivity>,
    val refs: List<BackupRef>,
    // Added in v2. Defaulted so older backups still deserialize.
    val journal: List<BackupJournal> = emptyList(),
    // Added in v3.
    val goals: List<BackupGoal> = emptyList(),
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
    private val goalDao: GoalDao,
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
            goals = goalDao.getAll().map {
                BackupGoal(it.id, it.title, it.activityId, it.targetPerWeek, it.createdAt, it.archived)
            },
        )
        return json.encodeToString(data)
    }

    /** Exports all mood entries as a spreadsheet-friendly CSV. */
    suspend fun exportEntriesCsv(): String {
        val activities = activityDao.getAllOnce().associateBy { it.id }
        val refsByEntry = entryDao.getAllCrossRefs().groupBy { it.entryId }
        val entries = entryDao.getAllEntries().sortedByDescending { it.dateTime }

        val sb = StringBuilder()
        sb.append("date,time,mood,activities,note\n")
        for (e in entries) {
            val names = refsByEntry[e.id].orEmpty()
                .mapNotNull { activities[it.activityId]?.name }
                .joinToString("; ")
            val row = listOf(
                DateUtils.formatDate(e.dateTime),
                DateUtils.formatTime(e.dateTime),
                Mood.fromLevel(e.moodLevel).label,
                names,
                e.note,
            ).joinToString(",") { csvField(it) }
            sb.append(row).append("\n")
        }
        return sb.toString()
    }

    /** How [importFromJson] reconciles a backup with existing data. */
    enum class ImportMode { REPLACE, MERGE }

    /**
     * Imports a backup. [ImportMode.REPLACE] wipes current data first (ids preserved);
     * [ImportMode.MERGE] keeps current data and adds the backup's rows with fresh ids
     * (remapping foreign keys), so nothing collides.
     */
    suspend fun importFromJson(jsonText: String, mode: ImportMode = ImportMode.REPLACE) {
        val data = runCatching { json.decodeFromString<BackupData>(jsonText) }
            .getOrElse { throw IllegalArgumentException("Not a valid Daylie backup file") }
        require(data.version <= CURRENT_VERSION) {
            "This backup was made by a newer version of Daylie (v${data.version}). Please update the app."
        }
        when (mode) {
            ImportMode.REPLACE -> importReplace(data)
            ImportMode.MERGE -> importMerge(data)
        }
    }

    private suspend fun importReplace(data: BackupData) {
        entryDao.deleteAllCrossRefs()
        entryDao.deleteAllEntries()
        activityDao.deleteAll()
        journalDao.deleteAll()
        goalDao.deleteAll()

        activityDao.insertAll(
            data.activities.map { ActivityEntity(it.id, it.name, it.iconKey, it.sortOrder, it.archived) },
        )
        data.entries.forEach { entryDao.insert(MoodEntry(it.id, it.dateTime, it.moodLevel, it.note)) }
        entryDao.insertCrossRefs(data.refs.map { EntryActivityCrossRef(it.entryId, it.activityId) })
        data.journal.forEach { journalDao.insert(JournalEntry(it.id, it.dateTime, it.title, it.body)) }
        data.goals.forEach {
            goalDao.insert(Goal(it.id, it.title, it.activityId, it.targetPerWeek, it.createdAt, it.archived))
        }
    }

    /** Adds backup rows alongside existing data, assigning new ids and remapping links. */
    private suspend fun importMerge(data: BackupData) {
        val activityIdMap = HashMap<Long, Long>()
        data.activities.forEach { a ->
            val newId = activityDao.insert(ActivityEntity(0, a.name, a.iconKey, a.sortOrder, a.archived))
            activityIdMap[a.id] = newId
        }

        val entryIdMap = HashMap<Long, Long>()
        data.entries.forEach { e ->
            val newId = entryDao.insert(MoodEntry(0, e.dateTime, e.moodLevel, e.note))
            entryIdMap[e.id] = newId
        }

        val remappedRefs = data.refs.mapNotNull { ref ->
            val newEntry = entryIdMap[ref.entryId]
            val newActivity = activityIdMap[ref.activityId]
            if (newEntry != null && newActivity != null) {
                EntryActivityCrossRef(newEntry, newActivity)
            } else {
                null
            }
        }
        entryDao.insertCrossRefs(remappedRefs)

        data.journal.forEach { j -> journalDao.insert(JournalEntry(0, j.dateTime, j.title, j.body)) }
        data.goals.forEach { g ->
            goalDao.insert(
                Goal(0, g.title, g.activityId?.let { activityIdMap[it] }, g.targetPerWeek, g.createdAt, g.archived),
            )
        }
    }

    companion object {
        const val CURRENT_VERSION = 3
    }
}
