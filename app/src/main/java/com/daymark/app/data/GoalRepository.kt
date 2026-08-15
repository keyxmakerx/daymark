package com.daymark.app.data

import com.daymark.app.data.dao.GoalDao
import com.daymark.app.data.entity.Goal
import com.daymark.app.data.entity.GoalStep
import com.daymark.app.data.entity.toRow
import com.daymark.app.goals.GoalBoard
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoalRepository @Inject constructor(
    private val goalDao: GoalDao,
    database: AppDatabase,
) {
    /**
     * Taken off the database rather than injected as its own binding.
     *
     * `di/AppModule.kt` has a `@Provides` per DAO and this one is missing, because AppModule is
     * outside the set of files this change was allowed to touch. `AppDatabase` is itself a
     * `@Singleton` binding, so this compiles and behaves identically — a DAO is a cached object on
     * the database, not a new instance per call. It should still be given the `provideGoalStepDao`
     * line for consistency with the other thirteen, at which point this becomes a constructor
     * parameter like [goalDao].
     */
    private val stepDao = database.goalStepDao()

    fun observeActive(): Flow<List<Goal>> = goalDao.observeActive()

    suspend fun getById(id: Long): Goal? = goalDao.getById(id)

    suspend fun save(goal: Goal): Long =
        if (goal.id == 0L) goalDao.insert(goal) else {
            goalDao.update(goal)
            goal.id
        }

    suspend fun delete(goal: Goal) = deleteById(goal.id)

    /**
     * Deletes a goal and everything belonging to it.
     *
     * The steps go first and by hand, even though `goal_steps` cascades. A step is a person's own
     * writing, deleting a goal is them asking for it to go, and an orphan row is that writing kept
     * — so this does not rest on `PRAGMA foreign_keys` being on in whatever context the call
     * happens to run in. Deleting steps that a cascade would have deleted costs one statement;
     * finding out years later that a pragma was off costs the guarantee.
     */
    suspend fun deleteById(id: Long) {
        if (id == 0L) return
        stepDao.deleteForGoal(id)
        goalDao.deleteById(id)
    }

    // --- Steps (project goals) ---

    fun observeSteps(goalId: Long): Flow<List<GoalStep>> = stepDao.observeForGoal(goalId)

    fun observeAllSteps(): Flow<List<GoalStep>> = stepDao.observeAll()

    suspend fun getSteps(goalId: Long): List<GoalStep> = stepDao.getForGoal(goalId)

    /**
     * Writes a goal's board back as a whole: delete this goal's steps, insert [steps] in the order
     * given.
     *
     * **Replace rather than diff, deliberately.** A per-row diff was the obvious alternative and was
     * rejected: nothing anywhere references a `goal_steps.id`, so the ids are free to change, and
     * the failure mode of a wrong diff is a step silently not written — a line of the person's own
     * writing lost with no error. A replace has one failure mode and it is loud. The cost is that
     * row ids churn on every save and the autoincrement counter climbs, which nothing reads.
     *
     * [GoalStep.createdAt] and [GoalStep.completedAt] survive because the board carries them
     * through, so a replace is not a reset.
     */
    suspend fun replaceSteps(goalId: Long, steps: List<GoalBoard.Step>) {
        if (goalId == 0L) return
        stepDao.deleteForGoal(goalId)
        if (steps.isNotEmpty()) stepDao.insertAll(steps.map { it.toRow(goalId) })
    }
}
