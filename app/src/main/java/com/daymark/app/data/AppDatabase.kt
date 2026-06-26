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
import com.daymark.app.data.dao.TreatmentDao
import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.data.entity.EntryActivityCrossRef
import com.daymark.app.data.entity.Goal
import com.daymark.app.data.entity.JournalEntry
import com.daymark.app.data.entity.MoodEntry
import com.daymark.app.data.entity.SleepLog
import com.daymark.app.data.entity.Treatment

@Database(
    entities = [
        MoodEntry::class, ActivityEntity::class, EntryActivityCrossRef::class,
        JournalEntry::class, Goal::class, SleepLog::class, Treatment::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun activityDao(): ActivityDao
    abstract fun journalDao(): JournalDao
    abstract fun goalDao(): GoalDao
    abstract fun sleepLogDao(): SleepLogDao
    abstract fun treatmentDao(): TreatmentDao

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
        const val NAME = "daylie.db"

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
