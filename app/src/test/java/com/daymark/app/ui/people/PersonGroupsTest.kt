package com.daymark.app.ui.people

import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grouping the picker and the lists sort by — the only thing a group does.
 *
 * `docs/FEATURES.md` §11.1: *"Groups (friends, family, partners, communities, other) sort the
 * picker and the list, and do nothing else."*
 *
 * `PersonGroups.kt` has no Compose in it precisely so this can be an ordinary unit test: the
 * ordering, the omission of empty groups and the fallback for a key this build does not know are
 * all exercised here rather than asserted by reading the file.
 */
class PersonGroupsTest {

    private fun person(id: Long, name: String, key: String): Person =
        Person(id = id, name = name, groupKey = key)

    @Test
    fun `the order is friends, family, partners, communities, other`() {
        assertEquals(
            listOf("friends", "family", "partners", "communities", "other"),
            PersonGroupOrder.map { it.key },
        )
    }

    @Test
    fun `every group has a name, and none of them counts or judges anything`() {
        val labels = PersonGroupOrder.map { personGroupLabel(it) }
        assertEquals(listOf("Friends", "Family", "Partners", "Communities", "Other"), labels)
        for (label in labels) {
            assertFalse("a group heading carries a number: $label", label.any { it.isDigit() })
        }
    }

    @Test
    fun `people are split into their groups, in that order`() {
        val people = listOf(
            person(1, "Choir", PersonGroup.COMMUNITIES.key),
            person(2, "Sam", PersonGroup.FRIENDS.key),
            person(3, "Mum", PersonGroup.FAMILY.key),
            person(4, "Ada", PersonGroup.FRIENDS.key),
        )
        val grouped = peopleByGroup(people)
        assertEquals(
            listOf(PersonGroup.FRIENDS, PersonGroup.FAMILY, PersonGroup.COMMUNITIES),
            grouped.map { it.first },
        )
        assertEquals(listOf("Sam", "Ada"), grouped[0].second.map { it.name })
        assertEquals(listOf("Mum"), grouped[1].second.map { it.name })
        assertEquals(listOf("Choir"), grouped[2].second.map { it.name })
    }

    /**
     * A group nobody is in is not drawn at all.
     *
     * `CLAUDE.md` §4 — a gap in someone's data is never drawn as a failure — and an empty
     * "Partners" heading on somebody's screen is exactly that shape.
     */
    @Test
    fun `a group with nobody in it is left out entirely`() {
        val grouped = peopleByGroup(listOf(person(1, "Sam", PersonGroup.FRIENDS.key)))
        assertEquals(listOf(PersonGroup.FRIENDS), grouped.map { it.first })
        assertTrue(
            "an empty group was emitted",
            grouped.none { it.second.isEmpty() },
        )
    }

    @Test
    fun `nobody is lost when the list is empty`() {
        assertEquals(emptyList<Pair<PersonGroup, List<Person>>>(), peopleByGroup(emptyList()))
    }

    /**
     * A key written by a newer version of the app is **shown** under "Other" and is **not**
     * rewritten.
     *
     * `PersonGroup` explains both halves: of the five, only "Other" is safe to be wrong in, because
     * it asserts nothing about somebody real; and normalising the stored string would turn "filed
     * by a newer version" into a permanent loss of the person's own choice on the first downgrade.
     */
    @Test
    fun `a group key this build does not know is shown under Other and left alone`() {
        val unknown = person(1, "Book club", "housemates")
        val grouped = peopleByGroup(listOf(unknown))

        assertEquals(listOf(PersonGroup.OTHER), grouped.map { it.first })
        assertEquals("Other", personGroupLabelForKey(unknown.groupKey))
        assertEquals(
            "the stored key was rewritten by display code",
            "housemates",
            grouped[0].second[0].groupKey,
        )
        assertFalse("an unknown key was reported as known", PersonGroup.isKnown(unknown.groupKey))
    }

    @Test
    fun `an unknown key and a real Other are drawn in the same section`() {
        val grouped = peopleByGroup(
            listOf(
                person(1, "Book club", "housemates"),
                person(2, "Neighbour", PersonGroup.OTHER.key),
            ),
        )
        assertEquals(1, grouped.size)
        assertEquals(listOf("Book club", "Neighbour"), grouped[0].second.map { it.name })
    }
}
