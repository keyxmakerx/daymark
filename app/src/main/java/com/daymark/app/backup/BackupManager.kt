package com.daymark.app.backup

import com.daymark.app.data.dao.ActivityDao
import com.daymark.app.data.dao.EntryDao
import com.daymark.app.data.dao.GoalDao
import com.daymark.app.data.dao.JournalDao
import com.daymark.app.data.dao.SleepLogDao
import com.daymark.app.data.dao.TrackerDao
import com.daymark.app.data.dao.TrackerLogDao
import com.daymark.app.data.dao.TreatmentDao
import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.data.entity.EntryActivityCrossRef
import com.daymark.app.data.entity.Goal
import com.daymark.app.data.entity.JournalEntry
import com.daymark.app.data.entity.MoodEntry
import com.daymark.app.data.entity.SleepLog
import com.daymark.app.data.entity.Tracker
import com.daymark.app.data.entity.TrackerLog
import com.daymark.app.data.entity.Treatment
import com.daymark.app.model.Mood
import com.daymark.app.util.DateUtils
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
data class BackupEntry(
    val id: Long,
    val dateTime: Long,
    val moodLevel: Int,
    val note: String,
    // Added in v6: relative filename of an attached photo (bytes live in BackupData.photos).
    val photoPath: String? = null,
)

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
data class BackupSleepLog(
    val id: Long, val night: Long, val bedTime: Long, val wakeTime: Long,
    val sleepLatencyMin: Int, val awakeMin: Int, val quality: Int, val note: String,
)

@Serializable
data class BackupTreatment(val id: Long, val kind: String, val startedAt: Long, val note: String)

@Serializable
data class BackupTracker(
    val id: Long, val name: String, val type: String, val minValue: Int, val maxValue: Int,
    val unit: String, val sortOrder: Int, val archived: Boolean,
)

@Serializable
data class BackupTrackerLog(val id: Long, val trackerId: Long, val dateTime: Long, val value: Double, val note: String)

@Serializable
data class BackupData(
    val version: Int = 6,
    val exportedAt: Long,
    val entries: List<BackupEntry>,
    val activities: List<BackupActivity>,
    val refs: List<BackupRef>,
    // Added in v2. Defaulted so older backups still deserialize.
    val journal: List<BackupJournal> = emptyList(),
    // Added in v3.
    val goals: List<BackupGoal> = emptyList(),
    // Added in v4.
    val sleepLogs: List<BackupSleepLog> = emptyList(),
    val treatments: List<BackupTreatment> = emptyList(),
    // Added in v5.
    val trackers: List<BackupTracker> = emptyList(),
    val trackerLogs: List<BackupTrackerLog> = emptyList(),
    // Added in v6: entry photos as filename -> base64-encoded JPEG bytes, kept in the one file.
    val photos: Map<String, String> = emptyMap(),
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
    private val sleepLogDao: SleepLogDao,
    private val treatmentDao: TreatmentDao,
    private val trackerDao: TrackerDao,
    private val trackerLogDao: TrackerLogDao,
    private val photoStore: com.daymark.app.data.PhotoStore,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun exportToJson(nowMillis: Long): String {
        val allEntries = entryDao.getAllEntries()
        // Embed each referenced photo's bytes as base64 so the backup stays a single portable file.
        val photos = allEntries.mapNotNull { it.photoPath }.distinct()
            .mapNotNull { path -> photoStore.readBytes(path)?.let { path to encodeBase64(it) } }
            .toMap()
        val data = BackupData(
            version = CURRENT_VERSION,
            exportedAt = nowMillis,
            entries = allEntries.map { BackupEntry(it.id, it.dateTime, it.moodLevel, it.note, it.photoPath) },
            photos = photos,
            activities = activityDao.getAllOnce().map {
                BackupActivity(it.id, it.name, it.iconKey, it.sortOrder, it.archived)
            },
            refs = entryDao.getAllCrossRefs().map { BackupRef(it.entryId, it.activityId) },
            journal = journalDao.getAll().map { BackupJournal(it.id, it.dateTime, it.title, it.body) },
            goals = goalDao.getAll().map {
                BackupGoal(it.id, it.title, it.activityId, it.targetPerWeek, it.createdAt, it.archived)
            },
            sleepLogs = sleepLogDao.getAll().map {
                BackupSleepLog(it.id, it.night, it.bedTime, it.wakeTime, it.sleepLatencyMin, it.awakeMin, it.quality, it.note)
            },
            treatments = treatmentDao.getAll().map { BackupTreatment(it.id, it.kind, it.startedAt, it.note) },
            trackers = trackerDao.getAll().map {
                BackupTracker(it.id, it.name, it.type, it.minValue, it.maxValue, it.unit, it.sortOrder, it.archived)
            },
            trackerLogs = trackerLogDao.getAll().map { BackupTrackerLog(it.id, it.trackerId, it.dateTime, it.value, it.note) },
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
            .getOrElse { throw IllegalArgumentException("Not a valid Daymark backup file") }
        require(data.version <= CURRENT_VERSION) {
            "This backup was made by a newer version of Daymark (v${data.version}). Please update the app."
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
        sleepLogDao.deleteAll()
        treatmentDao.deleteAll()

        // Restore photos under their original filenames (entries reference them by name).
        photoStore.clearAll()
        data.photos.forEach { (name, b64) -> runCatching { photoStore.writeBytes(name, decodeBase64(b64)) } }

        activityDao.insertAll(
            data.activities.map { ActivityEntity(it.id, it.name, it.iconKey, it.sortOrder, it.archived) },
        )
        data.entries.forEach { entryDao.insert(MoodEntry(it.id, it.dateTime, it.moodLevel, it.note, it.photoPath)) }
        entryDao.insertCrossRefs(data.refs.map { EntryActivityCrossRef(it.entryId, it.activityId) })
        data.journal.forEach { journalDao.insert(JournalEntry(it.id, it.dateTime, it.title, it.body)) }
        data.goals.forEach {
            goalDao.insert(Goal(it.id, it.title, it.activityId, it.targetPerWeek, it.createdAt, it.archived))
        }
        data.sleepLogs.forEach {
            sleepLogDao.insert(SleepLog(it.id, it.night, it.bedTime, it.wakeTime, it.sleepLatencyMin, it.awakeMin, it.quality, it.note))
        }
        data.treatments.forEach { treatmentDao.insert(Treatment(it.id, it.kind, it.startedAt, it.note)) }
        trackerDao.deleteAll()
        trackerLogDao.deleteAll()
        data.trackers.forEach {
            trackerDao.insert(Tracker(it.id, it.name, it.type, it.minValue, it.maxValue, it.unit, it.sortOrder, it.archived))
        }
        data.trackerLogs.forEach { trackerLogDao.insert(TrackerLog(it.id, it.trackerId, it.dateTime, it.value, it.note)) }
    }

    /** Adds backup rows alongside existing data, assigning new ids and remapping links. */
    private suspend fun importMerge(data: BackupData) {
        val activityIdMap = HashMap<Long, Long>()
        data.activities.forEach { a ->
            val newId = activityDao.insert(ActivityEntity(0, a.name, a.iconKey, a.sortOrder, a.archived))
            activityIdMap[a.id] = newId
        }

        // Write the backup's photos under fresh names when they'd collide with existing files,
        // building old-name -> stored-name so merged entries point at the right file.
        val photoNameMap = HashMap<String, String>()
        data.photos.forEach { (name, b64) ->
            val target = if (photoStore.exists(name)) photoStore.freshName() else name
            runCatching { photoStore.writeBytes(target, decodeBase64(b64)) }
                .onSuccess { photoNameMap[name] = target }
        }

        val entryIdMap = HashMap<Long, Long>()
        data.entries.forEach { e ->
            val photo = e.photoPath?.let { photoNameMap[it] ?: it }
            val newId = entryDao.insert(MoodEntry(0, e.dateTime, e.moodLevel, e.note, photo))
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
        // Sleep logs and treatments have no foreign keys, so merge is a plain insert with fresh ids.
        data.sleepLogs.forEach { s ->
            sleepLogDao.insert(SleepLog(0, s.night, s.bedTime, s.wakeTime, s.sleepLatencyMin, s.awakeMin, s.quality, s.note))
        }
        data.treatments.forEach { t -> treatmentDao.insert(Treatment(0, t.kind, t.startedAt, t.note)) }

        val trackerIdMap = HashMap<Long, Long>()
        data.trackers.forEach { t ->
            val newId = trackerDao.insert(Tracker(0, t.name, t.type, t.minValue, t.maxValue, t.unit, t.sortOrder, t.archived))
            trackerIdMap[t.id] = newId
        }
        data.trackerLogs.forEach { l ->
            trackerIdMap[l.trackerId]?.let { newTrackerId ->
                trackerLogDao.insert(TrackerLog(0, newTrackerId, l.dateTime, l.value, l.note))
            }
        }
    }

    private fun encodeBase64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decodeBase64(text: String): ByteArray =
        android.util.Base64.decode(text, android.util.Base64.NO_WRAP)

    companion object {
        const val CURRENT_VERSION = 6
    }
}
