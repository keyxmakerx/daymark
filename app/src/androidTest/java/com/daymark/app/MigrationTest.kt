package com.daymark.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daymark.app.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the Room migrations preserve user data and produce the expected schema. Covers every
 * hop for which an exported start schema exists (3 → 14). The 1.json / 2.json start schemas predate
 * `exportSchema`, so MIGRATION_1_2 and MIGRATION_2_3 can't be validated here — they are retained
 * for correctness and must never be deleted (see docs/ARCHITECTURE.md).
 *
 * **Instrumented test — and nothing in CI runs it today.** It needs a device or emulator, and no
 * workflow invokes `connectedAndroidTest`; `build.yml` runs `test` + `assembleDebug` only. So a
 * green CI badge says nothing about migrations, and these assertions only run when someone runs
 * them locally. (An earlier version of this comment claimed it ran in CI. It did not.)
 *
 * The exported schemas reach this test as **androidTest assets**, wired by hand in
 * `app/build.gradle.kts` via `sourceSets.getByName("androidTest") { assets.srcDir(...) }`. Without
 * that wiring `MigrationTestHelper` cannot find them and every test here fails at runtime with
 * "Cannot find the schema file in the assets folder" — which is exactly the state this file was in
 * before v13 was added.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val allMigrations = arrayOf(
        AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
        AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
        AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10,
        AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13,
        AppDatabase.MIGRATION_13_14,
    )

    @Test
    fun migrate7To8_preservesEntries_andAddsPhotoColumn() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO mood_entries (id, dateTime, moodLevel, note) VALUES (1, 1000, 4, 'before')",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 8, true, AppDatabase.MIGRATION_7_8).use { db ->
            db.query("SELECT note, photoPath FROM mood_entries WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("before", c.getString(0))
                assertTrue("photoPath should be null after migration", c.isNull(1))
            }
        }
    }

    @Test
    fun migrate8To9_createsRemindersTable() {
        helper.createDatabase(TEST_DB, 8).close()
        helper.runMigrationsAndValidate(TEST_DB, 9, true, AppDatabase.MIGRATION_8_9).use { db ->
            // The reminders table now exists and accepts a row.
            db.execSQL(
                "INSERT INTO reminders (id, hour, minute, enabled, label) VALUES (1, 8, 30, 1, 'AM')",
            )
            db.query("SELECT hour, minute, label FROM reminders WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(8, c.getInt(0))
                assertEquals(30, c.getInt(1))
                assertEquals("AM", c.getString(2))
            }
        }
    }

    @Test
    fun migrate9To10_createsAssessmentTable() {
        helper.createDatabase(TEST_DB, 9).close()
        helper.runMigrationsAndValidate(TEST_DB, 10, true, AppDatabase.MIGRATION_9_10).use { db ->
            db.execSQL(
                "INSERT INTO assessment_results (id, key, dateTime, score, bandLabel) " +
                    "VALUES (1, 'phq9', 1000, 7, 'Mild')",
            )
            db.query("SELECT key, score, bandLabel FROM assessment_results WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("phq9", c.getString(0))
                assertEquals(7, c.getInt(1))
                assertEquals("Mild", c.getString(2))
            }
        }
    }

    @Test
    fun migrate10To11_addsGoalCueRoutine_preservesGoals() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            db.execSQL(
                "INSERT INTO goals (id, title, activityId, targetPerWeek, createdAt, archived) " +
                    "VALUES (1, 'Walk', NULL, 3, 100, 0)",
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, 11, true, AppDatabase.MIGRATION_10_11).use { db ->
            db.query("SELECT title, cue, routine FROM goals WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Walk", c.getString(0))
                assertEquals("", c.getString(1))
                assertEquals("", c.getString(2))
            }
        }
    }

    @Test
    fun migrate12To13_createsSafetyPlanTable_andKeepsCommasIntact() {
        helper.createDatabase(TEST_DB, 12).close()
        helper.runMigrationsAndValidate(TEST_DB, 13, true, AppDatabase.MIGRATION_12_13).use { db ->
            db.execSQL(
                "INSERT INTO safety_plan_items (id, section, position, text, detail) " +
                    "VALUES (1, 'things_that_help', 0, 'Call Sam, then walk', '')",
            )
            db.execSQL(
                "INSERT INTO safety_plan_items (id, section, position, text, detail) " +
                    "VALUES (2, 'people', 0, 'Sam', 'sister')",
            )
            // The comma is the whole reason this is a child table rather than a CSV column: a
            // free-text safety-plan line must survive storage byte-for-byte.
            db.query("SELECT text, detail FROM safety_plan_items WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Call Sam, then walk", c.getString(0))
                assertEquals("", c.getString(1))
            }
            db.query("SELECT text, detail FROM safety_plan_items WHERE id = 2").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Sam", c.getString(0))
                assertEquals("sister", c.getString(1))
            }
        }
    }

    @Test
    fun migrate13To14_createsOfferRecordsTable_withBothIndices() {
        helper.createDatabase(TEST_DB, 13).close()
        helper.runMigrationsAndValidate(TEST_DB, 14, true, AppDatabase.MIGRATION_13_14).use { db ->
            db.execSQL(
                "INSERT INTO offer_records (id, kind, offeredAt, outcome) " +
                    "VALUES (1, 'companion', 1000, 'dismissed')",
            )
            db.query("SELECT kind, offeredAt, outcome FROM offer_records WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("companion", c.getString(0))
                assertEquals(1000L, c.getLong(1))
                assertEquals("dismissed", c.getString(2))
            }
            // runMigrationsAndValidate already compares against 14.json, but name the indices
            // explicitly: the ledger's two reads (narrow by kind, order by offeredAt) both depend
            // on them, and an index silently missing from a hand-written migration is exactly the
            // kind of drift this file exists to catch.
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'offer_records' " +
                    "ORDER BY name",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("index_offer_records_kind", c.getString(0))
                assertTrue(c.moveToNext())
                assertEquals("index_offer_records_offeredAt", c.getString(0))
            }
        }
    }

    @Test
    fun migrateAll_from3_toLatest() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO mood_entries (id, dateTime, moodLevel, note) VALUES (7, 5000, 5, 'kept')",
            )
        }
        // Keep this target in step with AppDatabase's @Database(version = …) on every schema bump —
        // running to a stale version silently stops exercising the newest migrations.
        helper.runMigrationsAndValidate(TEST_DB, 14, true, *allMigrations).use { db ->
            db.query("SELECT note FROM mood_entries WHERE id = 7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("kept", c.getString(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
