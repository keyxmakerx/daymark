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
import com.daymark.app.data.entity.GoalStep
import com.daymark.app.data.entity.JournalEntry
import com.daymark.app.data.entity.LifeEvent
import com.daymark.app.data.entity.AssessmentResult
import com.daymark.app.data.entity.MoodEntry
import com.daymark.app.data.entity.ThoughtRecord
import com.daymark.app.data.entity.Reminder
import com.daymark.app.data.entity.SafetyPlanItem
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
    // Added in v11.
    val cue: String = "",
    val routine: String = "",
    /**
     * A [com.daymark.app.goals.GoalKind.key]. Added in v14.
     *
     * It was missing for one release and the omission was silent in both directions: `BackupGoal`
     * had no field, and `Goal.kind` is defaulted, so every positional `Goal(...)` on the import
     * paths still compiled. A project exported and restored came back as a habit — with a weekly
     * target it never had, an empty board, and no error anywhere to say what had happened.
     *
     * Carried verbatim rather than through [com.daymark.app.goals.GoalKind.fromKey]. The column is
     * text precisely so an unrecognised kind survives being read (see `GoalKind`'s header), and
     * coercing here would turn "a kind this build does not know" into a permanent rewrite to habit.
     */
    val kind: String = "habit",
    /**
     * When the person marked this goal reached, or null. Added in v16.
     *
     * Defaulted like every field added since v1, so a v15 file — one taken before this column
     * existed — still reads, and its goals come back unmarked, which is the truth about them.
     *
     * Carried as the timestamp and never as a boolean. A `reached: Boolean` would round-trip the
     * fact and drop the date, and the date is what the Sky places the star on: restoring a backup
     * would move every star a person had placed to whenever the restore happened. It is also the
     * field nothing on the goals list displays, which is the kind that goes missing without anything
     * looking wrong — the same way `BackupLifeEvent.createdAt` could have.
     */
    val reachedAt: Long? = null,
)

/**
 * One `goal_steps` row. Added in v14, alongside the table.
 *
 * Steps are a person's own writing about what they mean to do, so they belong in the one file that
 * is this app's safety net. They were absent from it for a release, which was worse than an
 * omission: see [BackupData.goalSteps].
 */
@Serializable
data class BackupGoalStep(
    val id: Long,
    val goalId: Long,
    val title: String,
    val state: String,
    val position: Int,
    val createdAt: Long,
    val completedAt: Long? = null,
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
data class BackupReminder(val id: Long, val hour: Int, val minute: Int, val enabled: Boolean, val label: String)

@Serializable
data class BackupAssessment(val id: Long, val key: String, val dateTime: Long, val score: Int, val bandLabel: String)

@Serializable
data class BackupThoughtRecord(
    val id: Long, val dateTime: Long, val situation: String, val automaticThought: String,
    val evidenceFor: String, val evidenceAgainst: String, val balancedThought: String,
    val moodBefore: Int, val moodAfter: Int, val distortions: String,
)

@Serializable
data class BackupSafetyPlanItem(
    val id: Long, val section: String, val position: Int, val text: String, val detail: String,
)

/**
 * One `life_events` row. Added in v15, alongside the table.
 *
 * All four columns, including [createdAt], which nothing displays. A backup that dropped it would
 * silently rewrite every restored row's creation time to zero, and the field a person cannot see is
 * the one whose loss nothing reports.
 *
 * [epochDay] is a day number (`LocalDate.toEpochDay()`), not millis — see
 * [com.daymark.app.data.entity.LifeEvent]. It is carried across verbatim, so a backup restored on a
 * phone in another timezone puts the event back on the day the person chose.
 */
@Serializable
data class BackupLifeEvent(
    val id: Long,
    val epochDay: Long,
    val label: String,
    val createdAt: Long = 0,
)

@Serializable
data class BackupData(
    /**
     * The format version. [BackupManager.CURRENT_VERSION] is the authoritative number — it is what
     * [BackupManager.exportToJson] stamps on every file it writes and what
     * [BackupManager.importFromJson] validates against — and this default is only reached by a file
     * with no `version` key at all,
     * which no version of this app has ever written.
     *
     * So it deliberately does not track `CURRENT_VERSION`: raising it would have a file that never
     * declared a version assert it was written by the newest build, which is the one claim the
     * `data.version <= CURRENT_VERSION` guard exists to be able to disbelieve. Reading an
     * undeclared version as an old one costs nothing, because every field added since is defaulted.
     */
    val version: Int = 12,
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
    // Added in v7.
    val reminders: List<BackupReminder> = emptyList(),
    // Added in v8: per-level mood label/colour overrides (kept in prefs, not the DB).
    val moodLabels: Map<Int, String> = emptyMap(),
    val moodColors: Map<Int, Int> = emptyMap(),
    // Added in v9.
    val assessments: List<BackupAssessment> = emptyList(),
    // Added in v10: achievement unlock times (id -> epoch millis), kept in prefs.
    val achievements: Map<String, Long> = emptyMap(),
    // Added in v12.
    val thoughtRecords: List<BackupThoughtRecord> = emptyList(),
    // Added in v13: the safety plan. Local-only like the rest — this is the backup, not a share.
    val safetyPlan: List<BackupSafetyPlanItem> = emptyList(),
    /**
     * The steps of every project goal. Added in v14.
     *
     * **This list is why a restore was destroying data, not merely losing it.** `goal_steps` has the
     * schema's first foreign key, `ON DELETE CASCADE` onto `goals`, and Room runs with
     * `PRAGMA foreign_keys = ON`. `importReplace` calls `goalDao.deleteAll()`, so from the moment
     * the table existed, restoring *any* backup — including one taken a minute earlier on the same
     * phone — emptied every project's board through the cascade and put nothing back, because the
     * file had no steps in it to put back. Nothing failed, and the goals themselves reappeared, so
     * the boards looked like they had never been written.
     */
    val goalSteps: List<BackupGoalStep> = emptyList(),
    /**
     * The marks the person placed on their own history. Added in v15.
     *
     * Defaulted like everything else added since v1, so a v14 file still reads — a person's older
     * backup must never become unreadable because a table was added after they took it.
     *
     * This list is the one in the file that cannot be reconstructed from anything else. A check-in,
     * a journal entry and a project step are all recoverable in spirit from the person's own memory
     * of what they did; a life event is a decision they made once about what mattered, in their own
     * words, and there is no second copy of it anywhere on the device.
     */
    val lifeEvents: List<BackupLifeEvent> = emptyList(),
)

/**
 * The backup's steps rebound to the goal ids an import actually used, dropping any whose goal is
 * not in [goalIdMap].
 *
 * **Why this is a function and not two lines inside the import.** On the MERGE path each goal is
 * inserted as `Goal(0, ...)` and takes whatever id Room hands back, so every `goalId` in the file is
 * stale before the steps are written. Getting the rebinding wrong does not throw — the wrong id is
 * still a real goal — it files one person's writing about what they mean to do underneath a
 * different goal. That failure is silent and permanent, so the arithmetic lives where a test can
 * call it, next to `csvField` and for the same reason.
 *
 * **Why an unmapped step is dropped rather than kept.** `goal_steps.goalId` is a live foreign key
 * now, so inserting a step whose goal is absent throws and takes the whole import down over one
 * orphan line. Dropping matches how [BackupData.refs] already handles a cross-ref to a missing row.
 * [BackupGoalStep.id] is carried through untouched; each import path decides what id to write.
 */
internal fun remapGoalSteps(
    steps: List<BackupGoalStep>,
    goalIdMap: Map<Long, Long>,
): List<BackupGoalStep> =
    steps.mapNotNull { step -> goalIdMap[step.goalId]?.let { step.copy(goalId = it) } }

/**
 * The map [remapGoalSteps] needs on the REPLACE path: every goal in the file, to itself.
 *
 * REPLACE reinserts each goal with its original id (`goalDao.insert(Goal(it.id, ...))`), so a step's
 * `goalId` still names the right row and the identity is honest rather than a stand-in. Routing
 * REPLACE through the same function as MERGE is the point: the orphan-dropping rule is what keeps a
 * hand-edited file from throwing mid-import, and two copies of it would drift.
 */
internal fun replaceGoalIdMap(goals: List<BackupGoal>): Map<Long, Long> =
    goals.associate { it.id to it.id }

/**
 * Exports/imports the entire local database as JSON. This is the user's only safety
 * net in a local-only app, so it round-trips every table a person would miss.
 *
 * One table is outside that rule on purpose: the reception ledger (`offer_records`) is neither
 * exported nor restored, and a REPLACE import empties it. The end of [importReplace] says why —
 * worth reading before adding a field for it in the name of completeness.
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
    private val reminderDao: com.daymark.app.data.dao.ReminderDao,
    private val reminderRepository: com.daymark.app.data.ReminderRepository,
    private val photoStore: com.daymark.app.data.PhotoStore,
    private val moodCustomization: com.daymark.app.data.MoodCustomizationStore,
    private val assessmentDao: com.daymark.app.data.dao.AssessmentDao,
    private val achievementsStore: com.daymark.app.data.AchievementsStore,
    private val thoughtRecordDao: com.daymark.app.data.dao.ThoughtRecordDao,
    private val safetyPlanDao: com.daymark.app.data.dao.SafetyPlanDao,
    private val lifeEventDao: com.daymark.app.data.dao.LifeEventDao,
    // The reception ledger, held only to be able to empty it on a REPLACE — see importReplace.
    // Deliberately the repository and not `OfferRecordDao`: the repository is the seam that decides
    // what may be read out of that table, and a backup path has no business reading rows at all.
    private val offerLedger: com.daymark.app.data.OfferLedgerRepository,
    database: com.daymark.app.data.AppDatabase,
) {
    /**
     * Taken off the database rather than injected, the same way `GoalRepository` takes it.
     *
     * `di/AppModule.kt` has a `@Provides` per DAO and has none for this one, and that file is
     * outside the set this change was allowed to touch. `AppDatabase` is itself a `@Singleton`
     * binding and a DAO is a cached object on it, so this behaves identically to a constructor
     * parameter. When `provideGoalStepDao` is added it should become one — and note that
     * `BackupReplaceSourceTest` had to learn to read this shape, because a collaborator held here
     * instead of in the constructor is a door its count could not see.
     */
    private val goalStepDao = database.goalStepDao()

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
                BackupGoal(it.id, it.title, it.activityId, it.targetPerWeek, it.createdAt, it.archived, it.cue, it.routine, it.kind, it.reachedAt)
            },
            goalSteps = goalStepDao.getAll().map {
                BackupGoalStep(it.id, it.goalId, it.title, it.state, it.position, it.createdAt, it.completedAt)
            },
            sleepLogs = sleepLogDao.getAll().map {
                BackupSleepLog(it.id, it.night, it.bedTime, it.wakeTime, it.sleepLatencyMin, it.awakeMin, it.quality, it.note)
            },
            treatments = treatmentDao.getAll().map { BackupTreatment(it.id, it.kind, it.startedAt, it.note) },
            trackers = trackerDao.getAll().map {
                BackupTracker(it.id, it.name, it.type, it.minValue, it.maxValue, it.unit, it.sortOrder, it.archived)
            },
            trackerLogs = trackerLogDao.getAll().map { BackupTrackerLog(it.id, it.trackerId, it.dateTime, it.value, it.note) },
            reminders = reminderDao.getAll().map { BackupReminder(it.id, it.hour, it.minute, it.enabled, it.label) },
            moodLabels = moodCustomization.labels(),
            moodColors = moodCustomization.colors(),
            assessments = assessmentDao.getAll().map { BackupAssessment(it.id, it.key, it.dateTime, it.score, it.bandLabel) },
            achievements = achievementsStore.all(),
            thoughtRecords = thoughtRecordDao.getAll().map {
                BackupThoughtRecord(it.id, it.dateTime, it.situation, it.automaticThought, it.evidenceFor,
                    it.evidenceAgainst, it.balancedThought, it.moodBefore, it.moodAfter, it.distortions)
            },
            safetyPlan = safetyPlanDao.getAll().map {
                BackupSafetyPlanItem(it.id, it.section, it.position, it.text, it.detail)
            },
            lifeEvents = lifeEventDao.getAll().map {
                BackupLifeEvent(it.id, it.epochDay, it.label, it.createdAt)
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
                moodCustomization.labelFor(e.moodLevel) ?: Mood.fromLevel(e.moodLevel).label,
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
        // Re-arm alarms for whatever reminder set we now hold.
        reminderRepository.rescheduleAll()
        // Restore mood label/colour overrides. REPLACE starts clean; MERGE overlays.
        if (mode == ImportMode.REPLACE) moodCustomization.reset()
        data.moodLabels.forEach { (lvl, label) -> moodCustomization.setLabel(lvl, label) }
        data.moodColors.forEach { (lvl, color) -> moodCustomization.setColor(lvl, color) }
        achievementsStore.restore(data.achievements)
    }

    private suspend fun importReplace(data: BackupData) {
        // Cancel alarms for the reminders we're about to wipe, so stale ids don't keep firing.
        reminderRepository.cancelAllAlarms()
        entryDao.deleteAllCrossRefs()
        entryDao.deleteAllEntries()
        activityDao.deleteAll()
        journalDao.deleteAll()
        // Steps before goals, though the cascade would take them anyway: the cascade needs
        // `PRAGMA foreign_keys` on, which Room sets and a raw SupportSQLiteDatabase does not have
        // to, and `GoalRepository.deleteById` deletes them by hand for the same reason. A restore
        // that left orphan steps standing would attach a previous life's writing to whatever goal
        // ids the backup happens to reuse below.
        goalStepDao.deleteAll()
        goalDao.deleteAll()
        sleepLogDao.deleteAll()
        treatmentDao.deleteAll()

        // Restore photos under their original filenames (entries reference them by name).
        photoStore.clearAll()
        data.photos.forEach { (name, b64) -> runCatching { photoStore.writeBytes(name, decodeBase64(b64)) } }

        activityDao.insertAll(
            data.activities.map { ActivityEntity(it.id, it.name, it.iconKey, it.sortOrder, it.archived) },
        )
        data.entries.forEach {
            entryDao.insert(MoodEntry(it.id, it.dateTime, it.moodLevel, it.note, safePhotoName(it.photoPath)))
        }
        entryDao.insertCrossRefs(data.refs.map { EntryActivityCrossRef(it.entryId, it.activityId) })
        data.journal.forEach { journalDao.insert(JournalEntry(it.id, it.dateTime, it.title, it.body)) }
        data.goals.forEach {
            goalDao.insert(Goal(it.id, it.title, it.activityId, it.targetPerWeek, it.createdAt, it.archived, it.cue, it.routine, it.kind, it.reachedAt))
        }
        // After the goals, never before: `goal_steps.goalId` references `goals(id)` and the pragma
        // is on, so a step written ahead of its goal throws and aborts the restore.
        goalStepDao.insertAll(
            remapGoalSteps(data.goalSteps, replaceGoalIdMap(data.goals)).map {
                GoalStep(it.id, it.goalId, it.title, it.state, it.position, it.createdAt, it.completedAt)
            },
        )
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
        reminderDao.deleteAll()
        data.reminders.forEach { reminderDao.insert(Reminder(it.id, it.hour, it.minute, it.enabled, it.label)) }
        assessmentDao.deleteAll()
        data.assessments.forEach {
            assessmentDao.insert(AssessmentResult(it.id, it.key, it.dateTime, it.score, it.bandLabel))
        }
        thoughtRecordDao.deleteAll()
        data.thoughtRecords.forEach {
            thoughtRecordDao.insert(ThoughtRecord(it.id, it.dateTime, it.situation, it.automaticThought,
                it.evidenceFor, it.evidenceAgainst, it.balancedThought, it.moodBefore, it.moodAfter, it.distortions))
        }
        safetyPlanDao.deleteAll()
        data.safetyPlan.forEach {
            safetyPlanDao.insert(SafetyPlanItem(it.id, it.section, it.position, it.text, it.detail))
        }
        // Life events keep the ids the file gives them, like every other REPLACE insert. They have
        // no foreign key in either direction, so nothing needs remapping — and nothing else points
        // at a life event, which is why an id collision here cannot misfile anything.
        lifeEventDao.deleteAll()
        data.lifeEvents.forEach {
            lifeEventDao.insert(LifeEvent(it.id, it.epochDay, it.label, it.createdAt))
        }

        /*
         * The reception ledger is emptied here, and it is the one table with no matching restore
         * because it is not in the backup file at all.
         *
         * WHAT WAS WRONG. "Replace all current data" wiped twelve tables and left `offer_records`
         * standing — BackupManager did not even hold anything that could reach it. That table is
         * supposed to hold the app's behaviour and never the person (see OfferRecord's header), but
         * a `kind="support"` row is written only when the entry just saved was at mood 1 or 2
         * (`EntryEditorViewModel.save`, `LOW_MOOD_MAX`). So the rows that survived were a dated list
         * of this person's worst days, in a table whose name reads like telemetry, on the one
         * operation someone performs precisely because they want the old data gone. Restore an empty
         * backup onto a phone and the journal was blank while the ledger still said which days.
         *
         * WHY IT IS NOT EXPORTED EITHER. Round-tripping it would put those same dates into a
         * plaintext file that leaves the device — and buy nothing: the ledger is working state with
         * a sixty-day retention (OfferLedgerRepository.RETENTION_DAYS), not history, and everything
         * a person would miss is already in `entries`. A field for it here would also be the first
         * step towards the ledger looking like data worth keeping, which is the direction
         * `docs/DECISIONS_2026-08.md` §D1a exists to block.
         *
         * WHAT A PERSON WILL NOTICE. A standing "stop asking" is stored as a STOP row in this table
         * (OfferLedgerRepository.saidStop), so clearing it lifts that preference and the support
         * offer may start asking again after a replace-import. That is the one direction the engine
         * may never move on its own — but this is the person's own erase, which §D1a allows and
         * OfferLedgerRepository.clear names as its expected caller. Leaving the row to preserve the
         * preference would mean keeping a dated row about them, which is the worse trade.
         */
        offerLedger.clear()
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
            // `photoNameMap[it] ?: it` falls through to the backup's own string when no photo blob
            // matched — which is exactly the case the guarded writeBytes door never sees.
            val photo = safePhotoName(e.photoPath?.let { photoNameMap[it] ?: it })
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
        val goalIdMap = HashMap<Long, Long>()
        // `reachedAt` is carried across verbatim, like `createdAt` beside it. It is a moment the
        // person named, not a link to anything, so there is nothing to remap — and re-stamping it
        // with the import time would silently move every star on the Sky to the day of the merge.
        data.goals.forEach { g ->
            val newId = goalDao.insert(
                Goal(0, g.title, g.activityId?.let { activityIdMap[it] }, g.targetPerWeek, g.createdAt, g.archived, g.cue, g.routine, g.kind, g.reachedAt),
            )
            goalIdMap[g.id] = newId
        }
        // After the goals for the foreign key, and through the map because the ids just changed.
        goalStepDao.insertAll(
            remapGoalSteps(data.goalSteps, goalIdMap).map {
                GoalStep(0, it.goalId, it.title, it.state, it.position, it.createdAt, it.completedAt)
            },
        )
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
        // Reminders have no foreign keys, so merge is a plain insert with fresh ids.
        data.reminders.forEach { r -> reminderDao.insert(Reminder(0, r.hour, r.minute, r.enabled, r.label)) }
        data.assessments.forEach { a ->
            assessmentDao.insert(AssessmentResult(0, a.key, a.dateTime, a.score, a.bandLabel))
        }
        data.thoughtRecords.forEach { t ->
            thoughtRecordDao.insert(ThoughtRecord(0, t.dateTime, t.situation, t.automaticThought,
                t.evidenceFor, t.evidenceAgainst, t.balancedThought, t.moodBefore, t.moodAfter, t.distortions))
        }
        // Safety-plan items have no foreign keys. Positions are recomputed rather than copied, so a
        // merged plan appends after what's already there instead of interleaving two orderings.
        data.safetyPlan.sortedBy { it.position }.forEach { s ->
            safetyPlanDao.insert(
                SafetyPlanItem(0, s.section, safetyPlanDao.nextPosition(s.section), s.text, s.detail),
            )
        }
        // Life events take fresh row ids, like every other MERGE insert, so a row from the file
        // cannot land on top of one already on this phone. `epochDay` and `label` are
        // carried across unchanged: a merge must not move the date or edit the words. Duplicates are
        // left alone rather than de-duplicated by (day, label) — two people can be remembered on one
        // date, and the same line written twice is the person's to delete, not this code's to judge.
        data.lifeEvents.forEach { e ->
            lifeEventDao.insert(LifeEvent(0, e.epochDay, e.label, e.createdAt))
        }
    }

    /**
     * A photo name from a backup, or null if it is not one this app could have written.
     *
     * A backup file is untrusted input — it can be hand-edited, or handed to someone by a person
     * who wants to see what happens. `photoPath` was the one field that reached the database
     * without being checked: the photo *blobs* went through `PhotoStore.writeBytes`, which
     * validates the name, but an entry naming a photo the backup does not include skipped that door
     * entirely and was written verbatim. `../../databases/daymark.db` then sat in the entry until
     * an ordinary swipe-delete resolved it and unlinked the journal.
     *
     * Dropping to null is the right failure: a name we cannot serve is a missing photo, and the
     * entry — the writing, which is the part that matters — is kept and imported.
     */
    private fun safePhotoName(relPath: String?): String? =
        relPath?.takeIf { com.daymark.app.data.PhotoStore.isSafeName(it) }

    private fun encodeBase64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decodeBase64(text: String): ByteArray =
        android.util.Base64.decode(text, android.util.Base64.NO_WRAP)

    companion object {
        // v16 adds `BackupGoal.reachedAt`, defaulted, so a v15 file still reads. The bump is what
        // stops a file written by this build from claiming to be v15: a v15 reader would accept it
        // and drop the mark — and the date under it — without saying so. Same reason v15 was bumped
        // for `lifeEvents`, which a v14 reader would have dropped the same way.
        const val CURRENT_VERSION = 16
    }
}
