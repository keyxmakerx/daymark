package com.daymark.app.backup

import com.daymark.app.data.entity.GoalStep
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `goal_steps` half of a backup: that the steps are in the file at all, and that an import
 * attaches each one to the goal it was written under.
 *
 * ## What went wrong
 *
 * `goal_steps` arrived with the schema's first foreign key — `ON DELETE CASCADE` onto `goals` — and
 * Room runs with `PRAGMA foreign_keys = ON`. `importReplace` calls `goalDao.deleteAll()`. So from
 * the day the table existed, restoring *any* backup emptied every project's board through the
 * cascade and put nothing back, because no backup file contained a step. A person restoring the
 * backup they took a minute ago got their goals returned and their steps deleted, with no error.
 * `BackupGoal` also had no `kind`, and `Goal.kind` is defaulted, so the positional `Goal(...)` calls
 * on both import paths still compiled and a project came back as a habit.
 *
 * ## Why the arithmetic is tested and the DAO calls are not
 *
 * This module has no mocking framework and no Robolectric (see [BackupReplaceSourceTest]), so the
 * suspend import functions cannot be run here. The part that can be wrong quietly is the id
 * rebinding, and it lives in [remapGoalSteps] and [replaceGoalIdMap] — free of Android and Room,
 * like `export/ReportLayout.kt` and `stats/InterruptionBudget.kt` — so it is called directly below.
 * The ordering and the wiring are held by [BackupReplaceSourceTest] instead.
 */
class GoalStepBackupTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun step(id: Long, goalId: Long, title: String) =
        BackupGoalStep(id, goalId, title, "todo", 0, 1_700_000_000_000L, null)

    private fun goal(id: Long, title: String, kind: String = "habit") =
        BackupGoal(id, title, null, 3, 0L, false, kind = kind)

    // --- MERGE: the ids in the file are not the ids in the database ---

    /**
     * A merge inserts each goal as `Goal(0, ...)` and takes the id Room allocates, so the file's
     * `goalId` is stale by the time the steps are written. The steps here are listed in the
     * opposite order to the goals, which is what separates a real remap from the two ways of
     * getting it wrong that both happen to look right when the orders agree.
     */
    @Test
    fun `each step follows its own goal through the id map, not its position`() {
        val goalIdMap = mapOf(1L to 41L, 2L to 42L)
        val steps = listOf(
            step(20, 2L, "book the appointment"),
            step(21, 1L, "draft chapter one"),
            step(22, 1L, "re-read it"),
        )

        val remapped = remapGoalSteps(steps, goalIdMap)

        assertEquals(
            listOf("book the appointment" to 42L, "draft chapter one" to 41L, "re-read it" to 41L),
            remapped.map { it.title to it.goalId },
        )
    }

    /**
     * The assertion above has to be able to fail, and the two failures worth naming are the ones a
     * hurried implementation actually produces: pairing steps to goals by position, and forgetting
     * to remap at all. Both are silent — the resulting `goalId` is a real goal either way — so this
     * shows the expectation distinguishes them rather than agreeing with everything.
     */
    @Test
    fun `the expectation rejects the two wrong remaps, so it is not agreeing with everything`() {
        val goalIdMap = mapOf(1L to 41L, 2L to 42L)
        val steps = listOf(step(20, 2L, "book the appointment"), step(21, 1L, "draft chapter one"))

        val right = remapGoalSteps(steps, goalIdMap).map { it.goalId }
        val zippedByPosition = steps.mapIndexed { i, _ -> goalIdMap.values.toList()[i] }
        val neverRemapped = steps.map { it.goalId }

        assertEquals(listOf(42L, 41L), right)
        assertNotEquals("zip-by-position would pass this suite", zippedByPosition, right)
        assertNotEquals("leaving goalId alone would pass this suite", neverRemapped, right)
    }

    @Test
    fun `steps written under one goal are not spread across two`() {
        val remapped = remapGoalSteps(
            listOf(step(1, 5L, "a"), step(2, 5L, "b"), step(3, 5L, "c")),
            mapOf(5L to 77L, 6L to 78L),
        )
        assertEquals(3, remapped.size)
        assertEquals(listOf(77L), remapped.map { it.goalId }.distinct())
    }

    @Test
    fun `everything except goalId comes through the remap untouched`() {
        val original = BackupGoalStep(9, 3L, "sand the shelf", "doing", 2, 1_234L, 5_678L)
        val remapped = remapGoalSteps(listOf(original), mapOf(3L to 30L)).single()

        assertEquals(30L, remapped.goalId)
        assertEquals(original, remapped.copy(goalId = original.goalId))
    }

    /**
     * `goal_steps.goalId` references `goals(id)` with the pragma on, so a step whose goal is not in
     * the file throws on insert and aborts the whole import — over one orphan line in a file that
     * is untrusted input and can be hand-edited. Dropping it matches how `BackupData.refs` already
     * treats a cross-ref to a row that is not there.
     */
    @Test
    fun `a step whose goal is absent is dropped instead of reaching the foreign key`() {
        val steps = listOf(step(1, 1L, "kept"), step(2, 404L, "orphan"))

        // The detector first: with 404 mapped, the same step is kept, so the drop below is the
        // absence rule firing and not this input being unusable.
        assertEquals(2, remapGoalSteps(steps, mapOf(1L to 1L, 404L to 9L)).size)

        val remapped = remapGoalSteps(steps, mapOf(1L to 1L))
        assertEquals(listOf("kept"), remapped.map { it.title })
    }

    @Test
    fun `a backup with no goals at all imports no steps rather than throwing`() {
        assertEquals(emptyList<BackupGoalStep>(), remapGoalSteps(listOf(step(1, 1L, "x")), emptyMap()))
        assertEquals(emptyList<BackupGoalStep>(), remapGoalSteps(emptyList(), mapOf(1L to 2L)))
    }

    // --- REPLACE: the ids are the file's own ---

    @Test
    fun `the replace map is the identity, so a restored step keeps its goalId and its row id`() {
        val goals = listOf(goal(1, "Write the book", "project"), goal(2, "Exercise"))
        val idMap = replaceGoalIdMap(goals)
        assertEquals(mapOf(1L to 1L, 2L to 2L), idMap)

        val remapped = remapGoalSteps(listOf(step(7, 1L, "draft"), step(8, 404L, "orphan")), idMap)
        assertEquals(listOf(7L to 1L), remapped.map { it.id to it.goalId })
    }

    /**
     * [replaceGoalIdMap] is only correct because REPLACE reinserts each goal under the id the file
     * gives it. If that ever became a fresh-id insert like MERGE's, the identity map would keep
     * pointing steps at the ids in the file and file every board under whatever goal happened to
     * land on that id — so the claim is checked against the source rather than assumed.
     */
    @Test
    fun `the replace path reinserts goals with their original ids and the merge path does not`() {
        val src = repoFile("app/src/main/java/com/daymark/app/backup/BackupManager.kt").readText()
        val replaceBody = src.substringAfter("private suspend fun importReplace")
            .substringBefore("private suspend fun importMerge")
        val mergeBody = src.substringAfter("private suspend fun importMerge")

        // Both slices must be real bodies, or every claim below is made about an empty string.
        assertTrue("importReplace not found", replaceBody.length in 1000..12000)
        assertTrue("importMerge not found", mergeBody.length in 1000..12000)

        assertTrue(
            "REPLACE no longer keeps the backup's goal ids, so replaceGoalIdMap is now a lie and " +
                "every restored step is filed under whatever goal landed on that id",
            replaceBody.contains("goalDao.insert(Goal(it.id,"),
        )
        assertFalse("REPLACE is allocating fresh goal ids", replaceBody.contains("Goal(0,"))
        // The absence above only means something if `Goal(0,` is a form that exists in this file
        // and would have been found: it is what MERGE uses, three functions down.
        assertTrue("detector is broken — MERGE does not allocate fresh ids either", mergeBody.contains("Goal(0,"))
    }

    // --- The file format ---

    /**
     * A v13 file has no `goalSteps` key and its goals have no `kind`. Both must default, or the
     * first thing this fix does to a person is make their existing backup unreadable.
     */
    @Test
    fun `a v13 backup with no steps and no kind still deserializes`() {
        val v13 = """
            {
              "version": 13,
              "exportedAt": 1700000000000,
              "entries": [],
              "activities": [],
              "refs": [],
              "goals": [
                {"id":1,"title":"Exercise","activityId":null,"targetPerWeek":3,
                 "createdAt":0,"archived":false,"cue":"","routine":""}
              ]
            }
        """.trimIndent()

        val data = json.decodeFromString(BackupData.serializer(), v13)

        assertEquals(13, data.version)
        assertEquals(emptyList<BackupGoalStep>(), data.goalSteps)
        assertEquals("habit", data.goals.single().kind)
    }

    @Test
    fun `steps and kind survive a round trip through the file`() {
        val before = BackupData(
            version = BackupManager.CURRENT_VERSION,
            exportedAt = 1L,
            entries = emptyList(),
            activities = emptyList(),
            refs = emptyList(),
            goals = listOf(goal(1, "Write the book", "project"), goal(2, "Exercise")),
            goalSteps = listOf(
                BackupGoalStep(7, 1L, "draft chapter one", "doing", 0, 1_234L, null),
                BackupGoalStep(8, 1L, "re-read it", "done", 1, 1_235L, 9_999L),
            ),
        )

        val after = json.decodeFromString(BackupData.serializer(), json.encodeToString(before))

        assertEquals(before.goalSteps, after.goalSteps)
        assertEquals(listOf("project", "habit"), after.goals.map { it.kind })
        // completedAt is nullable on both sides; a round trip that flattened it to 0 would read as
        // "this step is done and was finished at the epoch".
        assertEquals(null, after.goalSteps[0].completedAt)
        assertEquals(9_999L, after.goalSteps[1].completedAt)
    }

    /**
     * Every column of `goal_steps` is in the file.
     *
     * Named field by field rather than "the backup has a steps list", because the way this table
     * gets half-backed-up is a column added to [GoalStep] later and not to [BackupGoalStep] — the
     * same missing-call shape as `offer_records`, one field down instead of one table.
     */
    @Test
    fun `the backup carries every column of a goal step`() {
        val entityFields = fieldNames(GoalStep::class.java)
        val backupFields = fieldNames(BackupGoalStep::class.java)

        // Positive control: reflection must have read something recognisable, or the set
        // comparison below is empty-equals-empty.
        assertTrue("reflection read nothing", entityFields.containsAll(setOf("goalId", "title", "state")))
        assertEquals(entityFields, backupFields)

        // And the comparison must be able to fail, or it says nothing about a forgotten column.
        assertNotEquals(entityFields, backupFields - "position")
    }

    @Test
    fun `the format version was bumped for the steps`() {
        // v13 was the safety plan; steps and kind are v14. A file written without the bump would
        // claim to be v13 and a v13 reader would accept it and drop the steps silently.
        assertTrue("CURRENT_VERSION was not bumped past the stepless format", BackupManager.CURRENT_VERSION >= 14)

        val names = BackupData.serializer().descriptor.let { d ->
            (0 until d.elementsCount).map { d.getElementName(it) }
        }
        assertTrue("descriptor did not read", names.contains("entries"))
        assertTrue("the backup has no field for goal steps", names.contains("goalSteps"))
    }

    /**
     * Declared instance field names of a Kotlin data class, order-independent.
     *
     * Statics and `$`-prefixed fields are dropped: the Compose and serialization plugins add their
     * own (`$stable`, `Companion`) and comparing those would make this fail on a plugin upgrade
     * rather than on a forgotten column.
     */
    private fun fieldNames(cls: Class<*>): Set<String> =
        cls.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
}
