package com.daylie.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.daylie.app.data.dao.ActivityDao
import com.daylie.app.data.dao.EntryDao
import com.daylie.app.data.entity.ActivityEntity
import com.daylie.app.data.entity.EntryActivityCrossRef
import com.daylie.app.data.entity.MoodEntry

@Database(
    entities = [MoodEntry::class, ActivityEntity::class, EntryActivityCrossRef::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun activityDao(): ActivityDao

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
