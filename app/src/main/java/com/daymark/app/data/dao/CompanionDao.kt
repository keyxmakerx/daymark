package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.daymark.app.data.entity.AcceptedAssignment
import com.daymark.app.data.entity.GamePlan
import com.daymark.app.data.entity.GamePlanItem

/**
 * Reads and writes the six tables that hold what a clinician sends through the Companion and what
 * the owner does with it: `game_plans`, `game_plan_items`, `game_plan_progress`, `assignments`,
 * `instrument_results` and `task_results` (#177).
 *
 * "Companion" here is the server, its consoles and the relationship channels
 * (`docs/COMPANION_ASSIGNMENTS.md`), not the in-app companion presence of `docs/DECISIONS.md` §D1b.
 *
 * ## Deliberately small
 *
 * This holds what taking an item in needs — storing an accepted game plan or assignment, and the
 * highest version already accepted, which decides whether a new one is newer — and the erase that
 * "Replace all" runs. Every other query is added with the code that calls it, so nothing here is a
 * question somebody could later ask of these tables without having to write it first.
 *
 * ## Insert-only for what a clinician signed
 *
 * `game_plans`, `game_plan_items` and `assignments` hold signed content. They have no `@Update`
 * here, and their inserts use [OnConflictStrategy.ABORT], so storing a version that is already
 * stored fails instead of replacing what was verified and accepted the first time.
 */
@Dao
interface CompanionDao {

    // --- Game plans ---

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGamePlan(plan: GamePlan)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGamePlanItems(items: List<GamePlanItem>)

    /**
     * Takes one accepted game-plan version into the app: the plan and its items, whole or not at all.
     *
     * The plan goes first because `game_plan_items` references it and the foreign key is enforced.
     * Every item must belong to this version: the foreign key would refuse an item naming a version
     * that is not stored, but not one naming a different version that is, so that is checked here,
     * before anything is written.
     */
    @Transaction
    suspend fun acceptGamePlan(plan: GamePlan, items: List<GamePlanItem>) {
        require(items.all { it.lineageId == plan.lineageId && it.version == plan.version }) {
            "every item must belong to the plan version being accepted"
        }
        insertGamePlan(plan)
        if (items.isNotEmpty()) insertGamePlanItems(items)
    }

    /**
     * The highest version of [lineageId] already accepted, or null if none is. A version at or below
     * it is not newer and must not be offered again as though it were.
     */
    @Query("SELECT MAX(version) FROM game_plans WHERE lineageId = :lineageId")
    suspend fun highestAcceptedGamePlanVersion(lineageId: String): Long?

    // --- Assignments ---

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssignment(assignment: AcceptedAssignment)

    /** The highest version of [lineageId] already accepted, or null if none is. */
    @Query("SELECT MAX(version) FROM assignments WHERE lineageId = :lineageId")
    suspend fun highestAcceptedAssignmentVersion(lineageId: String): Long?

    // --- Erasing ---

    @Query("DELETE FROM game_plan_items")
    suspend fun deleteAllGamePlanItems()

    @Query("DELETE FROM game_plans")
    suspend fun deleteAllGamePlans()

    @Query("DELETE FROM game_plan_progress")
    suspend fun deleteAllGamePlanProgress()

    @Query("DELETE FROM assignments")
    suspend fun deleteAllAssignments()

    @Query("DELETE FROM instrument_results")
    suspend fun deleteAllInstrumentResults()

    @Query("DELETE FROM task_results")
    suspend fun deleteAllTaskResults()

    /**
     * Empties all six tables, for "Replace all current data" (`BackupManager.importReplace`).
     *
     * Items before plans, though the cascade would take them: the cascade needs `PRAGMA foreign_keys`
     * on, which Room sets and a raw `SupportSQLiteDatabase` does not have to, and `GoalStep`,
     * `PersonNote` and their repositories delete children by hand for the same reason.
     * `CompanionSchemaTest` checks that this names every Companion table and nothing else.
     */
    @Transaction
    suspend fun deleteAll() {
        deleteAllGamePlanItems()
        deleteAllGamePlans()
        deleteAllGamePlanProgress()
        deleteAllAssignments()
        deleteAllInstrumentResults()
        deleteAllTaskResults()
    }
}
