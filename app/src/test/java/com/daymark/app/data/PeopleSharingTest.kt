package com.daymark.app.data

import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroup
import com.daymark.app.data.entity.PersonGroupShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one expression that decides whether somebody's name leaves the device, and the group keys it
 * is decided against.
 *
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §2: *"Sharing is one screen listing every person and
 * community, with a default per group (all off) and overrides per item, off even under an
 * accept-all grant."*
 *
 * `PeopleRepository.isShared` is a companion function precisely so this file can exercise it
 * without Room, a device or a mocking framework. It is three tokens long and every one of them is
 * load-bearing.
 */
class PeopleSharingTest {

    private fun person(override: Boolean?, group: PersonGroup = PersonGroup.FRIENDS) =
        Person(id = 1, name = "Sam", groupKey = group.key, sharedOverride = override)

    // ----------------------------------------------------------------------------------------
    // The rule.
    // ----------------------------------------------------------------------------------------

    /** Six combinations, which is all of them: three override states times two group defaults. */
    @Test
    fun `the person's own answer wins, and their group answers only when they have not`() {
        assertFalse("no override, group off", PeopleRepository.isShared(null, false))
        assertTrue("no override, group on", PeopleRepository.isShared(null, true))

        assertTrue("override on, group off", PeopleRepository.isShared(true, false))
        assertTrue("override on, group on", PeopleRepository.isShared(true, true))

        assertFalse("override off, group off", PeopleRepository.isShared(false, false))
        assertFalse("override off, group on", PeopleRepository.isShared(false, true))
    }

    /**
     * The case the whole three-state design exists for, and the one a plausible rewrite breaks.
     *
     * `sharedOverride == true || groupDefault` reads almost identically, passes five of the six
     * rows above, and shares every single person the user had gone to the trouble of excluding from
     * a group they had switched on. This asserts the difference rather than describing it.
     */
    @Test
    fun `an explicit no beats a group default of yes, and the obvious rewrite does not`() {
        assertFalse(PeopleRepository.isShared(false, true))

        // The mutation, written out: the wrong rule, applied to the same inputs.
        fun wrong(override: Boolean?, groupDefault: Boolean) = override == true || groupDefault
        assertTrue("the mutation is not a mutation", wrong(false, true))
        assertTrue(
            "the two rules agree on the case that distinguishes them, so this test proves nothing",
            PeopleRepository.isShared(false, true) != wrong(false, true),
        )
        // ...and it must agree everywhere else, or it would have been caught by any test at all.
        for (override in listOf<Boolean?>(null, true)) {
            for (group in listOf(false, true)) {
                assertTrue(
                    "the mutation also differs at ($override, $group), so it is not the near-miss " +
                        "this case is about",
                    PeopleRepository.isShared(override, group) == wrong(override, group),
                )
            }
        }
    }

    /**
     * A group with no row is off, and a person with no override in a group with no row is off.
     *
     * This is the state of every install and every newly-added person, and it is reached by two
     * fallbacks in a row rather than by anything being written down. Both are asserted, because
     * either one silently defaulting the other way is a name going to a clinician.
     */
    @Test
    fun `a brand new person in a group nobody has touched is not shared`() {
        val defaults = PeopleRepository.groupDefaults(emptyList())
        assertEquals(emptyMap<String, Boolean>(), defaults)
        assertFalse(PeopleRepository.isShared(person(null), defaults))

        // Every group, not just the one above: none of the five may be on out of the box.
        for (group in PersonGroup.entries) {
            assertFalse("$group is shared by default", PeopleRepository.isShared(person(null, group), defaults))
        }
        assertEquals(5, PersonGroup.entries.size)

        // The detector: the same read does return true once a default is actually switched on, so
        // the falses above are a finding and not a function that always says no.
        val on = PeopleRepository.groupDefaults(listOf(PersonGroupShare(PersonGroup.FRIENDS.key, true)))
        assertTrue("detector is broken", PeopleRepository.isShared(person(null, PersonGroup.FRIENDS), on))
        assertFalse("a default leaked across groups", PeopleRepository.isShared(person(null, PersonGroup.FAMILY), on))
    }

    /**
     * A group key this build does not recognise keeps its own default and does not pick up
     * `other`'s.
     *
     * Resolving the key through [PersonGroup.fromKey] before the lookup would land every unknown
     * key on `other` — so a person written by a newer version would inherit a default belonging to
     * a group they are not in, in the direction that can share them.
     */
    @Test
    fun `an unrecognised group key does not inherit the other group's default`() {
        val fromTheFuture = Person(id = 2, name = "The allotment", groupKey = "neighbours", sharedOverride = null)
        val otherIsOn = PeopleRepository.groupDefaults(listOf(PersonGroupShare(PersonGroup.OTHER.key, true)))

        assertFalse("an unknown key picked up other's default", PeopleRepository.isShared(fromTheFuture, otherIsOn))
        // The detector: the key really is unknown, and `other` really is on in this map.
        assertFalse("the key under test is not actually unknown", PersonGroup.isKnown(fromTheFuture.groupKey))
        assertEquals(PersonGroup.OTHER, PersonGroup.fromKey(fromTheFuture.groupKey))
        assertTrue("detector is broken", PeopleRepository.isShared(person(null, PersonGroup.OTHER), otherIsOn))
    }

    // ----------------------------------------------------------------------------------------
    // The group keys.
    // ----------------------------------------------------------------------------------------

    /**
     * The five keys, spelled out.
     *
     * Written as literals rather than derived from the enum, because the point of the column being
     * text is that these strings are the stored values on somebody's phone. Renaming one is a
     * migration, not a refactor, and this is where that shows up.
     */
    @Test
    fun `the five group keys are the ones the plan names, and they are stable strings`() {
        assertEquals(
            listOf("friends", "family", "partners", "communities", "other"),
            PersonGroup.entries.map { it.key },
        )
        assertEquals(5, PersonGroup.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `an unknown key reads as other and is reported as unknown`() {
        assertEquals(PersonGroup.OTHER, PersonGroup.DEFAULT)
        assertEquals(PersonGroup.OTHER, PersonGroup.fromKey("colleagues"))
        assertEquals(PersonGroup.OTHER, PersonGroup.fromKey(null))
        assertEquals(PersonGroup.OTHER, PersonGroup.fromKey(""))
        // Case matters: the stored value is whatever was written, and "Friends" is not a key this
        // build wrote. Reading it as FRIENDS would be a guess.
        assertEquals(PersonGroup.OTHER, PersonGroup.fromKey("Friends"))

        assertFalse(PersonGroup.isKnown("colleagues"))
        assertFalse(PersonGroup.isKnown(null))
        assertFalse(PersonGroup.isKnown("Friends"))
        // The detector: isKnown does say yes to the real ones, so the noes above mean something.
        for (group in PersonGroup.entries) assertTrue("$group is not known", PersonGroup.isKnown(group.key))
        assertEquals(PersonGroup.FRIENDS, PersonGroup.fromKey("friends"))
    }

    /**
     * `fromKey` is not the identity on the way back in.
     *
     * Nothing may normalise an unknown key to `"other"` in the database: that turns "written by a
     * newer version" into a permanent loss of the person's own filing, on a downgrade nobody would
     * notice. This asserts the two are different values, which is the fact that makes the rule
     * expressible at all.
     */
    @Test
    fun `reading an unknown key as other does not rewrite it`() {
        val stored = "neighbours"
        assertEquals(PersonGroup.OTHER, PersonGroup.fromKey(stored))
        assertNotEquals(
            "the stored key and the group it displays as are the same string, so nothing here is " +
                "protecting a key a newer version wrote",
            PersonGroup.OTHER.key,
            stored,
        )
        // And a person carrying it keeps it: nothing in the entity's defaults coerces the column.
        assertEquals(stored, Person(id = 3, name = "The allotment", groupKey = stored).groupKey)
    }

    /**
     * The default a new person is created with is *follow my group*, not *no*.
     *
     * Both are off today, so a test that only checked the outcome would pass either way — and the
     * difference only appears later, when somebody turns a group on and expects the people in it to
     * follow. `null` is what makes that work.
     */
    @Test
    fun `a new person starts by following their group rather than by refusing`() {
        val fresh = Person(id = 4, name = "Sam")
        assertNull("a new person starts with an explicit answer rather than following", fresh.sharedOverride)
        assertEquals(PersonGroup.DEFAULT.key, fresh.groupKey)
        assertFalse("a new person is shared", PeopleRepository.isShared(fresh, emptyMap()))

        // The consequence that distinguishes null from false, stated as an assertion.
        val groupOn = mapOf(PersonGroup.DEFAULT.key to true)
        assertTrue("a person following their group did not follow it", PeopleRepository.isShared(fresh, groupOn))
        assertFalse(
            "a person who had said no was dragged in by the group default",
            PeopleRepository.isShared(fresh.copy(sharedOverride = false), groupOn),
        )
    }

    @Test
    fun `groupDefaults reads the stored rows, including an explicit off`() {
        val rows = listOf(
            PersonGroupShare(PersonGroup.FRIENDS.key, true),
            PersonGroupShare(PersonGroup.FAMILY.key, false),
        )
        assertEquals(mapOf("friends" to true, "family" to false), PeopleRepository.groupDefaults(rows))
        // An explicit off and an absent row have to mean the same thing to the rule — the entity
        // requires it, because absence is the state a failed write leaves behind.
        assertFalse(PeopleRepository.isShared(person(null, PersonGroup.FAMILY), PeopleRepository.groupDefaults(rows)))
        assertFalse(PeopleRepository.isShared(person(null, PersonGroup.FAMILY), emptyMap()))
    }
}
