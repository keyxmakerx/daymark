package com.daymark.app.data

import com.daymark.app.data.dao.SafetyPlanDao
import com.daymark.app.data.entity.SafetyPlanItem
import com.daymark.app.data.entity.SafetyPlanSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety plan's storage rules.
 *
 * The one that matters most is the comma case — the whole reason this is a child table rather than
 * a CSV column. `ThoughtRecord.distortions` stores a list as CSV, and copying that trick here would
 * have silently shredded any safety-plan line a person wrote a comma into.
 */
class SafetyPlanRepositoryTest {

    private fun repo(dao: FakeSafetyPlanDao = FakeSafetyPlanDao()) =
        SafetyPlanRepository(dao) to dao

    @Test
    fun `blank text is never stored`() = runTest {
        val (repo, dao) = repo()
        assertNull(repo.add(SafetyPlanSection.THINGS_THAT_HELP, ""))
        assertNull(repo.add(SafetyPlanSection.THINGS_THAT_HELP, "   "))
        assertNull(repo.add(SafetyPlanSection.THINGS_THAT_HELP, "\n\t "))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `text is trimmed on the way in`() = runTest {
        val (repo, dao) = repo()
        repo.add(SafetyPlanSection.WARNING_SIGNS, "  Can't sleep  ")
        assertEquals("Can't sleep", dao.rows.single().text)
    }

    @Test
    fun `a comma in a line survives intact — the reason this is not a CSV column`() = runTest {
        val (repo, dao) = repo()
        repo.add(SafetyPlanSection.THINGS_THAT_HELP, "Call Sam, then walk")
        assertEquals("Call Sam, then walk", dao.rows.single().text)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `detail is kept for people and dropped everywhere else`() = runTest {
        val (repo, dao) = repo()
        repo.add(SafetyPlanSection.PEOPLE, "Sam", "sister")
        repo.add(SafetyPlanSection.WARNING_SIGNS, "Withdrawing", "should be ignored")

        val person = dao.rows.first { it.section == SafetyPlanSection.PEOPLE.key }
        val sign = dao.rows.first { it.section == SafetyPlanSection.WARNING_SIGNS.key }
        assertEquals("sister", person.detail)
        assertEquals("", sign.detail)
    }

    @Test
    fun `positions append within a section and are independent across sections`() = runTest {
        val (repo, dao) = repo()
        repo.add(SafetyPlanSection.WARNING_SIGNS, "One")
        repo.add(SafetyPlanSection.WARNING_SIGNS, "Two")
        repo.add(SafetyPlanSection.THINGS_THAT_HELP, "Walk outside")

        val signs = dao.rows.filter { it.section == SafetyPlanSection.WARNING_SIGNS.key }
        assertEquals(listOf(0, 1), signs.map { it.position })
        // A new section starts at 0 rather than continuing the global count.
        assertEquals(0, dao.rows.single { it.section == SafetyPlanSection.THINGS_THAT_HELP.key }.position)
    }

    @Test
    fun `the plan groups by section, and an unknown section key is skipped not crashed`() = runTest {
        val (repo, dao) = repo()
        repo.add(SafetyPlanSection.PEOPLE, "Sam", "sister")
        repo.add(SafetyPlanSection.WARNING_SIGNS, "Racing thoughts")
        // A row from a newer version, or a hand-edited backup.
        dao.insert(SafetyPlanItem(0, "some_future_section", 0, "???", ""))

        val plan = repo.observePlan().first()
        assertEquals(setOf(SafetyPlanSection.PEOPLE, SafetyPlanSection.WARNING_SIGNS), plan.keys)
        assertEquals("Sam", plan.getValue(SafetyPlanSection.PEOPLE).single().text)
    }

    @Test
    fun `an empty table means no plan yet, not an empty plan`() = runTest {
        val (repo, _) = repo()
        assertTrue(repo.observePlan().first().isEmpty())
    }
}

/** In-memory stand-in for Room, so these rules can be tested without a device. */
private class FakeSafetyPlanDao : SafetyPlanDao {
    val rows = mutableListOf<SafetyPlanItem>()
    private val stream = MutableStateFlow<List<SafetyPlanItem>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<SafetyPlanItem>> = stream

    override suspend fun nextPosition(section: String): Int =
        (rows.filter { it.section == section }.maxOfOrNull { it.position } ?: -1) + 1

    override suspend fun insert(item: SafetyPlanItem): Long {
        val stored = item.copy(id = nextId++)
        rows += stored
        publish()
        return stored.id
    }

    override suspend fun update(item: SafetyPlanItem) {
        rows.replaceAll { if (it.id == item.id) item else it }
        publish()
    }

    override suspend fun delete(item: SafetyPlanItem) {
        rows.removeAll { it.id == item.id }
        publish()
    }

    override suspend fun getAll(): List<SafetyPlanItem> = rows.toList()

    override suspend fun deleteAll() {
        rows.clear()
        publish()
    }

    private fun publish() {
        stream.value = rows.sortedWith(compareBy({ it.section }, { it.position }))
    }
}
