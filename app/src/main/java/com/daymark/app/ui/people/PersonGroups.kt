package com.daymark.app.ui.people

import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroup

/**
 * How the five groups are ordered and named on screen, and nothing else.
 *
 * `docs/FEATURES.md` §11.1: *"Groups (friends, family, partners, communities, other) sort the
 * picker and the list, and do nothing else."* **Nothing else** is the whole of this file's brief.
 * A group orders a list and gives a heading. It is not read by anything that decides, suggests,
 * counts or draws anything else, and it never reaches a rule about mood.
 *
 * Plain Kotlin with no Compose in it, so the ordering and the fallback are exercised by an ordinary
 * unit test rather than believed.
 */

/** The order `docs/FEATURES.md` §11.1 gives; every list and picker in `ui/people` uses it. */
val PersonGroupOrder: List<PersonGroup> = listOf(
    PersonGroup.FRIENDS,
    PersonGroup.FAMILY,
    PersonGroup.PARTNERS,
    PersonGroup.COMMUNITIES,
    PersonGroup.OTHER,
)

/** The heading shown above a group. A noun, never a count and never a judgement. */
fun personGroupLabel(group: PersonGroup): String = when (group) {
    PersonGroup.FRIENDS -> "Friends"
    PersonGroup.FAMILY -> "Family"
    PersonGroup.PARTNERS -> "Partners"
    PersonGroup.COMMUNITIES -> "Communities"
    PersonGroup.OTHER -> "Other"
}

/**
 * The same heading for a stored key.
 *
 * A key this build does not recognise is *displayed* as "Other" — `PersonGroup.fromKey`'s own
 * rule, and its KDoc gives the reason: of the five, "Other" is the only one that is safe to be
 * wrong in, because it asserts nothing about somebody real. Nothing here rewrites the stored
 * string.
 */
fun personGroupLabelForKey(groupKey: String): String = personGroupLabel(PersonGroup.fromKey(groupKey))

/**
 * Splits [people] into the groups that actually have somebody in them, in [PersonGroupOrder].
 *
 * An empty group is left out rather than drawn as an empty heading. A person's screen must never
 * show a slot with nothing in it: `CLAUDE.md` §4 — *a gap in someone's data is never drawn as a
 * failure* — and an empty "Family" heading is exactly that shape.
 *
 * Within a group the order is whatever the caller was handed; `PersonDao` already sorts by group
 * then name, case-insensitively.
 */
fun peopleByGroup(people: List<Person>): List<Pair<PersonGroup, List<Person>>> =
    PersonGroupOrder.mapNotNull { group ->
        val members: List<Person> = people.filter { PersonGroup.fromKey(it.groupKey) == group }
        if (members.isEmpty()) null else group to members
    }
