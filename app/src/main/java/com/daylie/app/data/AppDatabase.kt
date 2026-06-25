package com.daylie.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.daylie.app.data.dao.ActivityDao
import com.daylie.app.data.dao.EntryDao
import com.daylie.app.data.dao.JournalDao
import com.daylie.app.data.entity.ActivityEntity
import com.daylie.app.data.entity.EntryActivityCrossRef
import com.daylie.app.data.entity.JournalEntry
import com.daylie.app.data.entity.MoodEntry

@Database(
    entities = [MoodEntry::class, ActivityEntity::class, EntryActivityCrossRef::class, JournalEntry::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun activityDao(): ActivityDao
    abstract fun journalDao(): JournalDao

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

        val DEFAULT_ACTIVITIES = listOf(
            "Work" to "work",
            "Family" to "family",
            "Friends" to "friends",
            "Exercise" to "exercise",
            "Sleep" to "sleep",
            "Eat healthy" to "food",
            "Reading" to "reading",
            "Gaming" to "gaming",
            "Movies" to "movie",
            "Relax" to "relax",
            "Study" to "study",
            "Shopping" to "shopping",
        )
    }
}
