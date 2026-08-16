package com.daymark.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.daymark.app.data.dao.ActivityDao
import com.daymark.app.data.dao.EntryDao
import com.daymark.app.data.dao.GoalDao
import com.daymark.app.data.dao.JournalDao
import com.daymark.app.data.dao.SleepLogDao
import com.daymark.app.data.dao.TrackerDao
import com.daymark.app.data.dao.TrackerLogDao
import com.daymark.app.data.dao.TreatmentDao
import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.data.entity.AssessmentResult
import com.daymark.app.data.entity.EntryActivityCrossRef
import com.daymark.app.data.entity.Goal
import com.daymark.app.data.entity.JournalEntry
import com.daymark.app.data.entity.MoodEntry
import com.daymark.app.data.entity.Reminder
import com.daymark.app.data.entity.SleepLog
import com.daymark.app.data.entity.Tracker
import com.daymark.app.data.entity.TrackerLog
import com.daymark.app.data.entity.Treatment

@Database(
    entities = [
        MoodEntry::class, ActivityEntity::class, EntryActivityCrossRef::class,
        JournalEntry::class, Goal::class, SleepLog::class, Treatment::class,
        Tracker::class, TrackerLog::class, Reminder::class, AssessmentResult::class,
        com.daymark.app.data.entity.ThoughtRecord::class,
        com.daymark.app.data.entity.SafetyPlanItem::class,
        com.daymark.app.data.entity.OfferRecord::class,
        com.daymark.app.data.entity.GoalStep::class,
        com.daymark.app.data.entity.LifeEvent::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun activityDao(): ActivityDao
    abstract fun journalDao(): JournalDao
    abstract fun goalDao(): GoalDao
    abstract fun sleepLogDao(): SleepLogDao
    abstract fun treatmentDao(): TreatmentDao
    abstract fun trackerDao(): TrackerDao
    abstract fun trackerLogDao(): TrackerLogDao
    abstract fun reminderDao(): com.daymark.app.data.dao.ReminderDao
    abstract fun assessmentDao(): com.daymark.app.data.dao.AssessmentDao
    abstract fun thoughtRecordDao(): com.daymark.app.data.dao.ThoughtRecordDao
    abstract fun safetyPlanDao(): com.daymark.app.data.dao.SafetyPlanDao
    abstract fun offerRecordDao(): com.daymark.app.data.dao.OfferRecordDao
    abstract fun goalStepDao(): com.daymark.app.data.dao.GoalStepDao
    abstract fun lifeEventDao(): com.daymark.app.data.dao.LifeEventDao

    /** Seeds a sensible set of starter activities on first install. */
    class SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            DEFAULT_ACTIVITIES.forEachIndexed { index, (name, icon) ->
                db.execSQL(
                    "INSERT INTO activities (name, iconKey, sortOrder, archived) VALUES (?, ?, ?, 0)",
                    arrayOf(name, icon, index),
                )
            }
        }
    }

    companion object {
        const val NAME = "daymark.db"

        /** v2 adds the standalone journal table; existing data is preserved. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `journal_entries` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`dateTime` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_journal_entries_dateTime` " +
                        "ON `journal_entries` (`dateTime`)",
                )
            }
        }

        /** v3 adds the goals table; existing data is preserved. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goals` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`activityId` INTEGER, " +
                        "`targetPerWeek` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`archived` INTEGER NOT NULL)",
                )
            }
        }

        /** v4 renames the default "Eat healthy" activity to "Eat" (only if still unedited). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE activities SET name = 'Eat' WHERE name = 'Eat healthy'")
            }
        }

        /** v5 adds the manual sleep-diary table; existing data is preserved. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sleep_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`night` INTEGER NOT NULL, " +
                        "`bedTime` INTEGER NOT NULL, " +
                        "`wakeTime` INTEGER NOT NULL, " +
                        "`sleepLatencyMin` INTEGER NOT NULL, " +
                        "`awakeMin` INTEGER NOT NULL, " +
                        "`quality` INTEGER NOT NULL, " +
                        "`note` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sleep_logs_night` ON `sleep_logs` (`night`)",
                )
            }
        }

        /** v6 adds the treatments table (for before/after self-tracking); data is preserved. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `treatments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`note` TEXT NOT NULL)",
                )
            }
        }

        /** v7 adds custom trackers + their logs; existing data is preserved. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trackers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`minValue` INTEGER NOT NULL, " +
                        "`maxValue` INTEGER NOT NULL, " +
                        "`unit` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, " +
                        "`archived` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tracker_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`trackerId` INTEGER NOT NULL, " +
                        "`dateTime` INTEGER NOT NULL, " +
                        "`value` REAL NOT NULL, " +
                        "`note` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tracker_logs_trackerId` ON `tracker_logs` (`trackerId`)",
                )
            }
        }

        /** v8 adds an optional photo attachment to mood entries; existing data is preserved. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mood_entries ADD COLUMN photoPath TEXT")
            }
        }

        /** v9 adds the reminders table (multiple daily reminders); existing data is preserved. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`hour` INTEGER NOT NULL, " +
                        "`minute` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL)",
                )
            }
        }

        /** v10 adds the assessment_results table (PHQ-9/GAD-7/WHO-5 history); data is preserved. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `assessment_results` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`key` TEXT NOT NULL, " +
                        "`dateTime` INTEGER NOT NULL, " +
                        "`score` INTEGER NOT NULL, " +
                        "`bandLabel` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_assessment_results_key` ON `assessment_results` (`key`)",
                )
            }
        }

        /** v11 adds optional if-then (cue/routine) fields to goals; existing data is preserved. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE goals ADD COLUMN cue TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE goals ADD COLUMN routine TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v12 adds the thought_records table (CBT thought records); existing data is preserved. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `thought_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`dateTime` INTEGER NOT NULL, " +
                        "`situation` TEXT NOT NULL, " +
                        "`automaticThought` TEXT NOT NULL, " +
                        "`evidenceFor` TEXT NOT NULL, " +
                        "`evidenceAgainst` TEXT NOT NULL, " +
                        "`balancedThought` TEXT NOT NULL, " +
                        "`moodBefore` INTEGER NOT NULL, " +
                        "`moodAfter` INTEGER NOT NULL, " +
                        "`distortions` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_thought_records_dateTime` ON `thought_records` (`dateTime`)",
                )
            }
        }

        /**
         * v13 adds the safety-plan items table; existing data is preserved. One row per line the
         * person writes — deliberately not a CSV column, since safety-plan text may contain commas.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `safety_plan_items` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`section` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`detail` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_safety_plan_items_section` " +
                        "ON `safety_plan_items` (`section`)",
                )
            }
        }

        /**
         * v14 adds the reception ledger (`offer_records`); existing data is preserved.
         *
         * Three columns and a primary key, and that is deliberately the whole table — see
         * [com.daymark.app.data.entity.OfferRecord]. It records that the app asked and how that
         * landed, never anything about the person, so there is no free-text column here and no
         * migration may ever add one.
         *
         * Both indices exist because the two questions the arbiter asks are "when did this kind of
         * offer last happen?" and "how did the recent ones land?" — `kind` narrows, `offeredAt`
         * orders.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offer_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`offeredAt` INTEGER NOT NULL, " +
                        "`outcome` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_offer_records_kind` " +
                        "ON `offer_records` (`kind`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_offer_records_offeredAt` " +
                        "ON `offer_records` (`offeredAt`)",
                )
            }
        }

        /**
         * v15 makes a goal one of two shapes and gives the project shape its steps. Existing data is
         * preserved, and existing rows keep their existing meaning — that is what the `DEFAULT
         * 'habit'` is for, and why the column is added rather than the table rewritten.
         *
         * The `kind` column is `TEXT NOT NULL DEFAULT 'habit'`, matching `Goal.kind`'s
         * `@ColumnInfo(defaultValue = "habit")`, exactly as the v11 cue/routine columns did.
         *
         * `goal_steps` carries **the schema's first foreign key**. The `ON UPDATE NO ACTION ON DELETE
         * CASCADE` clause and its ordering are Room's own generated form, because Room compares this
         * table's SQL against what it would have written and a difference in wording — not just in
         * meaning — fails the migration test. `index_goal_steps_goalId` is the index `GoalStep`
         * declares; it is also what stops Room warning that a foreign key's child column is
         * unindexed, so it is not optional.
         *
         * Foreign keys are enforced only while `PRAGMA foreign_keys` is on. Room sets it; a raw
         * `SupportSQLiteDatabase` does not have to. The cascade is therefore not the only thing
         * standing between a deleted goal and orphaned steps — see [com.daymark.app.data.entity.GoalStep].
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE goals ADD COLUMN kind TEXT NOT NULL DEFAULT 'habit'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goal_steps` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`goalId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`completedAt` INTEGER, " +
                        "FOREIGN KEY(`goalId`) REFERENCES `goals`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_goal_steps_goalId` " +
                        "ON `goal_steps` (`goalId`)",
                )
            }
        }

        /**
         * v16 adds `life_events` — the person's own marks on their history. Existing data is
         * preserved; nothing else in the schema is touched.
         *
         * The SQL is Room's own generated form for
         * [com.daymark.app.data.entity.LifeEvent], column for column and in declaration order,
         * because `runMigrationsAndValidate` compares this table against what Room would have
         * written and a difference in wording fails the comparison even when the meaning matches.
         * `epochDay` is `INTEGER NOT NULL` — a *day number*, not a timestamp, following
         * `sleep_logs.night`.
         *
         * `index_life_events_epochDay` is the index the entity declares, and it is not decoration:
         * every read of this table is by date. An index present on the entity and missing from the
         * migration is the drift this file has shipped before — it does not fail at runtime, it
         * fails the schema comparison on someone else's change.
         *
         * No foreign key and no cascade. A life event belongs to nothing else; it is not a child of
         * a goal, an entry or a journal page, and it must survive the deletion of everything around
         * it.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `life_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`epochDay` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_life_events_epochDay` " +
                        "ON `life_events` (`epochDay`)",
                )
            }
        }

        /**
         * v17 gives a goal a way to be reached: `goals.reachedAt`, epoch millis, NULL until the
         * person says so. Existing data is preserved and no existing column is touched.
         *
         * One `ALTER TABLE ... ADD COLUMN`, nullable, with no `DEFAULT` — the form
         * [com.daymark.app.data.entity.Goal] declares (`val reachedAt: Long?`, no `@ColumnInfo`) and
         * the same shape [MIGRATION_7_8] used for `mood_entries.photoPath`. A `NOT NULL DEFAULT 0`
         * column would have been the cue/routine shape from v11 and would have been wrong here:
         * every goal on every phone would have come out of this migration claiming it was reached at
         * the epoch, and the Sky would have drawn a star on 1 January 1970 for each one.
         *
         * **It does not backfill from `archived`, and it never may.** Archiving is giving up on a
         * goal or setting it aside; a migration that read those rows as reached would hand the
         * person a wall of stars for the things they let go of. `Goal.reachedAt` and
         * `com.daymark.app.goals.GoalReached` both carry the longer version of this.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE goals ADD COLUMN reachedAt INTEGER")
            }
        }

        val DEFAULT_ACTIVITIES = listOf(
            "Work" to "work",
            "Family" to "family",
            "Friends" to "friends",
            "Exercise" to "exercise",
            "Sleep" to "sleep",
            "Eat" to "food",
            "Reading" to "reading",
            "Gaming" to "gaming",
            "Movies" to "movie",
            "Relax" to "relax",
            "Study" to "study",
            "Shopping" to "shopping",
        )
    }
}
