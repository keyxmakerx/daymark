package com.daymark.app.backup

import com.daymark.app.data.entity.EntryPersonCrossRef
import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroupShare
import com.daymark.app.data.entity.PersonNote
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The people half of a backup: the four lists, the rebinding of notes to person ids, and the wiring
 * of both import paths.
 *
 * ## Why people belong in the file at all
 *
 * A person's *name* could be typed again. What they are to somebody — the
 * *"who (or what) is this to you"* line — and two years of dated notes about them could not. Those
 * are in the same category as `lifeEvents`: writing with no second copy anywhere on the device. And
 * the way a table gets dropped from a backup is never a wrong call, it is a missing one, which
 * nothing fails on. [BackupReplaceSourceTest] counts the doors; this checks the format and the
 * calls.
 *
 * ## The one thing that is deliberately *not* round-tripped symmetrically
 *
 * `personGroupShares` is restored by REPLACE and ignored by MERGE, and
 * [theMergePathNeverRaisesAGroupDefault] is the assertion that keeps it that way. A merge adds the
 * file's rows beside data that is already on the phone; raising a group default would share people
 * who have nothing to do with the file being imported, and nothing on any screen would look wrong
 * afterwards. Restoring a whole-device REPLACE is the opposite case — there is nobody left for a
 * default to newly expose.
 *
 * ## What cannot be checked here
 *
 * This module has no mocking framework and no Robolectric, so the suspend import functions cannot
 * be run — [BackupReplaceSourceTest]'s header explains at length. What runs here is the file
 * format, the pure remapping arithmetic, and source assertions over the two import bodies.
 */
class PeopleBackupTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun data(
        people: List<BackupPerson> = emptyList(),
        notes: List<BackupPersonNote> = emptyList(),
        links: List<BackupEntryPerson> = emptyList(),
        shares: List<BackupPersonGroupShare> = emptyList(),
    ) = BackupData(
        version = BackupManager.CURRENT_VERSION,
        exportedAt = 1L,
        entries = emptyList(),
        activities = emptyList(),
        refs = emptyList(),
        people = people,
        personNotes = notes,
        entryPeople = links,
        personGroupShares = shares,
    )

    // ----------------------------------------------------------------------------------------
    // The file format.
    // ----------------------------------------------------------------------------------------

    /**
     * A round trip keeps all three sharing states apart.
     *
     * `null`, `false` and `true` are three different answers and a file that flattens any pair of
     * them changes what leaves the device: `null` read as `false` permanently excludes somebody who
     * had only ever followed their group, and `null` read as `true` shares everybody who had.
     */
    @Test
    fun `the three sharing states survive a round trip as three states`() {
        val before = data(
            people = listOf(
                BackupPerson(1, "Sam", "friends", "the one who calls on Sundays", false, 1_700_000_000_000L, null),
                BackupPerson(2, "Mum", "family", "", false, 1_700_000_000_001L, false),
                BackupPerson(3, "Choir", "communities", "Tuesday nights", true, 1_700_000_000_002L, true),
            ),
        )
        val after = json.decodeFromString(BackupData.serializer(), json.encodeToString(BackupData.serializer(), before))

        assertEquals(before.people, after.people)
        assertNull("a null override came back as something else", after.people[0].sharedOverride)
        assertEquals(false, after.people[1].sharedOverride)
        assertEquals(true, after.people[2].sharedOverride)
        // The three really are distinguishable in this file, which is what the equality above rests
        // on: a serializer that wrote them all the same would satisfy `before == after` too if the
        // three inputs had been equal to begin with.
        assertNotEquals(after.people[0].sharedOverride, after.people[1].sharedOverride)
        assertNotEquals(after.people[1].sharedOverride, after.people[2].sharedOverride)

        // And everything else on the row: the free-text line and the archived flag in particular,
        // since neither is visible from the picker somebody would check after a restore.
        assertEquals("the one who calls on Sundays", after.people[0].whoTheyAre)
        assertEquals("", after.people[1].whoTheyAre)
        assertTrue("an archived person came back unarchived", after.people[2].archived)
    }

    @Test
    fun `notes, entry links and group defaults all survive a round trip`() {
        val before = data(
            people = listOf(BackupPerson(1, "Sam", "friends")),
            notes = listOf(
                BackupPersonNote(10, 1, 1_700_000_000_000L, "We talked about the move."),
                BackupPersonNote(11, 1, 1_710_000_000_000L, "Line with a \"quote\", a comma, and\na newline."),
            ),
            links = listOf(BackupEntryPerson(100, 1), BackupEntryPerson(101, 1)),
            shares = listOf(BackupPersonGroupShare("friends", true), BackupPersonGroupShare("family", false)),
        )
        val after = json.decodeFromString(BackupData.serializer(), json.encodeToString(BackupData.serializer(), before))

        assertEquals(before.personNotes, after.personNotes)
        assertEquals(before.entryPeople, after.entryPeople)
        assertEquals(before.personGroupShares, after.personGroupShares)
        // The awkward note is there on purpose: this is the one field in the file that holds
        // somebody's own writing about another person, and JSON escaping is where writing gets
        // quietly truncated.
        assertTrue(after.personNotes[1].body.contains("\n"))
        assertTrue(after.personNotes[1].body.contains("\"quote\""))
    }

    /**
     * A file written before people existed still reads, and comes back with nobody in it.
     *
     * A person's older backup must never become unreadable because a table was added after they
     * took it — the rule every field added since v1 has followed.
     */
    @Test
    fun `a v16 file reads, and its four people lists default to empty`() {
        val v16 = """
            {
              "version": 16,
              "exportedAt": 1,
              "entries": [],
              "activities": [],
              "refs": []
            }
        """.trimIndent()

        val data = json.decodeFromString(BackupData.serializer(), v16)

        assertEquals(16, data.version)
        assertEquals(emptyList<BackupPerson>(), data.people)
        assertEquals(emptyList<BackupPersonNote>(), data.personNotes)
        assertEquals(emptyList<BackupEntryPerson>(), data.entryPeople)
        assertEquals(emptyList<BackupPersonGroupShare>(), data.personGroupShares)
    }

    @Test
    fun `the format version was bumped for the people`() {
        // A file written by this build carries people. Left at 16 it would claim to be a v16 file,
        // and a v16 reader would accept it and drop every person, note and link without a word.
        assertTrue("CURRENT_VERSION was not bumped past the people-less format", BackupManager.CURRENT_VERSION >= 17)

        val descriptor = BackupData.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
        assertTrue("descriptor did not read", names.contains("entries"))
        for (field in listOf("people", "personNotes", "entryPeople", "personGroupShares")) {
            assertTrue("the backup has no field for $field", names.contains(field))
        }
    }

    /**
     * Every column of every one of the four tables is in the file.
     *
     * Field by field rather than "the backup has a list of them", because the way a table gets
     * half-backed-up is a column added to the entity later and not to its backup class — the same
     * missing-call shape as `offer_records`, one field down instead of one table.
     * `Person.sharedOverride` and `Person.createdAt` are the two at risk here: neither is something
     * a person would look at after a restore to check it came back.
     */
    @Test
    fun `the backup carries every column of all four tables`() {
        for ((entity, backup) in listOf(
            Person::class.java to BackupPerson::class.java,
            PersonNote::class.java to BackupPersonNote::class.java,
            EntryPersonCrossRef::class.java to BackupEntryPerson::class.java,
            PersonGroupShare::class.java to BackupPersonGroupShare::class.java,
        )) {
            assertEquals("${entity.simpleName} and ${backup.simpleName} disagree", fieldNames(entity), fieldNames(backup))
        }

        // Positive control: reflection read something recognisable, not four empty sets compared
        // with four empty sets.
        assertTrue(fieldNames(Person::class.java).containsAll(setOf("name", "groupKey", "whoTheyAre", "sharedOverride")))
        assertEquals(7, fieldNames(Person::class.java).size)
        // And the comparison must be able to fail, or it says nothing about a forgotten column.
        assertNotEquals(fieldNames(Person::class.java), fieldNames(BackupPerson::class.java) - "sharedOverride")
    }

    // ----------------------------------------------------------------------------------------
    // The remapping arithmetic.
    // ----------------------------------------------------------------------------------------

    /**
     * On a merge the notes follow their person to whatever id Room hands back.
     *
     * Getting this wrong does not throw — the wrong id is still a real person — it files what
     * somebody wrote about one person underneath another one. Silent, permanent, and about people.
     */
    @Test
    fun `merge rebinds every note to its person's new id`() {
        val notes = listOf(
            BackupPersonNote(10, 1, 100L, "about Sam"),
            BackupPersonNote(11, 2, 200L, "about Mum"),
            BackupPersonNote(12, 1, 300L, "about Sam again"),
        )
        val remapped = remapPersonNotes(notes, mapOf(1L to 41L, 2L to 42L))

        assertEquals(listOf(41L, 42L, 41L), remapped.map { it.personId })
        // Nothing else moved: the words, the date and the note's own id are carried untouched.
        assertEquals(notes.map { it.body }, remapped.map { it.body })
        assertEquals(notes.map { it.dateTime }, remapped.map { it.dateTime })
        assertEquals(notes.map { it.id }, remapped.map { it.id })
        // The mapping is a real mapping, so "rebound" is a finding and not an identity function.
        assertNotEquals(notes.map { it.personId }, remapped.map { it.personId })
    }

    /**
     * A note whose person is not in the file is dropped rather than inserted.
     *
     * `person_notes.personId` is a live foreign key, so inserting one throws and takes the whole
     * import down over a single orphan line in a hand-edited file. Dropping matches how
     * `remapGoalSteps` and `BackupData.refs` already handle it.
     */
    @Test
    fun `a note whose person is missing from the file is dropped, not inserted`() {
        val notes = listOf(
            BackupPersonNote(10, 1, 100L, "kept"),
            BackupPersonNote(11, 99, 200L, "orphan"),
        )
        val remapped = remapPersonNotes(notes, mapOf(1L to 41L))

        assertEquals(listOf("kept"), remapped.map { it.body })
        // Positive control: the survivor really did go through the same call, so this is a filter
        // and not an empty result.
        assertEquals(1, remapped.size)
        assertEquals(41L, remapped.single().personId)
        assertEquals(emptyList<BackupPersonNote>(), remapPersonNotes(notes, emptyMap()))
    }

    /** REPLACE reinserts each person under their own id, so the map is the identity. */
    @Test
    fun `the replace path maps every person in the file to themselves`() {
        val people = listOf(BackupPerson(7, "Sam"), BackupPerson(9, "Mum"))
        assertEquals(mapOf(7L to 7L, 9L to 9L), replacePersonIdMap(people))
        // And routed through the same dropper, which is the point of it being one function: a note
        // naming somebody the file does not carry is dropped on both paths.
        val notes = listOf(BackupPersonNote(1, 7, 0L, "kept"), BackupPersonNote(2, 8, 0L, "orphan"))
        assertEquals(listOf("kept"), remapPersonNotes(notes, replacePersonIdMap(people)).map { it.body })
        assertEquals(listOf(7L), remapPersonNotes(notes, replacePersonIdMap(people)).map { it.personId })
    }

    // ----------------------------------------------------------------------------------------
    // The wiring, against the source.
    // ----------------------------------------------------------------------------------------

    private val source = repoFile("app/src/main/java/com/daymark/app/backup/BackupManager.kt").readText()

    private fun exportBody(src: String) =
        src.substringAfter("suspend fun exportToJson").substringBefore("suspend fun exportEntriesCsv")

    private fun replaceBody(src: String) =
        src.substringAfter("private suspend fun importReplace").substringBefore("private suspend fun importMerge")

    private fun mergeBody(src: String) = src.substringAfter("private suspend fun importMerge")

    @Test
    fun `the three bodies this file asserts against are real bodies`() {
        // Every claim below is a `contains` on one of these slices; an empty slice would make the
        // negative ones pass vacuously. Bound both ends so a slicer that swallowed the whole file
        // is caught too.
        assertTrue("exportToJson not found", exportBody(source).length in 1000..8000)
        assertTrue("importReplace not found", replaceBody(source).length in 1000..12000)
        assertTrue("importMerge not found", mergeBody(source).length in 1000..12000)
    }

    /**
     * The export reads all four tables.
     *
     * This is the half [BackupReplaceSourceTest]'s door count does not cover: it proves every
     * collaborator is *wiped or rewritten by a restore*, which `personDao.deleteAll()` alone
     * satisfies. A table emptied on restore and never exported is worse than one merely missing —
     * the backup would delete the person's people on the way in.
     */
    @Test
    fun `the export reads people, their notes, their links and the group defaults`() {
        val body = exportBody(source)
        for (call in listOf(
            "personDao.getAll()",
            "personNoteDao.getAll()",
            "entryPersonDao.getAll()",
            "personDao.getAllGroupShares()",
        )) {
            assertTrue("exportToJson never calls $call", body.contains(call))
        }

        // The detector: with one call removed, this check fires rather than finding the same string
        // elsewhere in the file.
        val preFix = source.replace(body, body.replace("personNoteDao.getAll()", "unreached()"))
        assertFalse("detector is broken", exportBody(preFix).contains("personNoteDao.getAll()"))
    }

    /** REPLACE empties all four tables before writing any of them back. */
    @Test
    fun `the replace path empties all four tables before restoring them`() {
        val body = replaceBody(source)
        for (call in listOf(
            "entryPersonDao.deleteAll()",
            "personNoteDao.deleteAll()",
            "personDao.deleteAll()",
            "personDao.deleteAllGroupShares()",
        )) {
            assertTrue("REPLACE never clears via $call", body.contains(call))
        }

        // Children before parents: person_notes cascades off people only while PRAGMA foreign_keys
        // is on, and entry_people has no foreign key at all, so neither can be left to the parent.
        assertTrue(
            "the entry links are cleared after the people they point at",
            body.indexOf("entryPersonDao.deleteAll()") < body.indexOf("personDao.deleteAll()"),
        )
        assertTrue(
            "the notes are cleared after the people they hang off",
            body.indexOf("personNoteDao.deleteAll()") < body.indexOf("personDao.deleteAll()"),
        )
        // People before notes on the way back in, or the live foreign key aborts the restore.
        assertTrue(
            "notes are written before the people they reference",
            body.indexOf("personDao.insert(") < body.indexOf("personNoteDao.insertAll("),
        )

        // The detector: the same reads fail on a replace path that forgot a wipe.
        val preFix = body.replace("personNoteDao.deleteAll()", "")
        assertFalse("mutation did not land", preFix.contains("personNoteDao.deleteAll()"))
        assertTrue("mutation removed the restore too", preFix.contains("personNoteDao.insertAll("))
    }

    /** REPLACE restores the file's own ids; MERGE allocates fresh ones. */
    @Test
    fun `the replace path keeps the file's ids and the merge path allocates fresh ones`() {
        assertTrue("REPLACE does not restore people at all", replaceBody(source).contains("Person(it.id,"))
        assertFalse("REPLACE is allocating fresh person ids", replaceBody(source).contains("Person(0,"))
        assertTrue("REPLACE does not restore notes under their own ids", replaceBody(source).contains("PersonNote(it.id,"))

        // The absences above only mean something if those forms exist in this file and would have
        // been found: they are what MERGE uses, one function down.
        assertTrue("detector is broken — MERGE does not allocate fresh ids either", mergeBody(source).contains("Person(0,"))
        assertTrue("detector is broken", mergeBody(source).contains("PersonNote(0,"))
    }

    /**
     * A merge may not turn sharing on for anybody who was already on the phone.
     *
     * Two halves, and both are absences, so both carry a planted control.
     *
     * 1. The group defaults are not written at all. The file was exported against a different set
     *    of them, and raising one here would share people the file has never heard of.
     * 2. A `null` override becomes an explicit `false`. Null means *follow my group*, and the group
     *    it would be following on this device is not the one it was exported under — so a person
     *    exported from a phone where `friends` was off would arrive on a phone where `friends` is on
     *    and be shared, with nobody choosing it and no screen looking wrong.
     */
    @Test
    fun theMergePathNeverRaisesAGroupDefault() {
        val merge = mergeBody(source)
        val replace = replaceBody(source)

        assertFalse("MERGE writes group sharing defaults", merge.contains("setGroupShares"))
        assertFalse("MERGE writes group sharing defaults", merge.contains("PersonGroupShare("))
        assertTrue("MERGE does not pin an inherited override to off", merge.contains("p.sharedOverride ?: false"))

        // The controls. Both strings exist in this file and the detector does find them — on the
        // REPLACE path, which is where restoring them whole is the safe thing to do.
        assertTrue("detector is broken — nothing writes group shares anywhere", replace.contains("setGroupShares"))
        assertTrue("detector is broken", replace.contains("PersonGroupShare("))
        // And REPLACE carries the override verbatim rather than pinning it, which is the difference
        // between the two paths stated in the direction that can be read off the source.
        assertTrue("REPLACE does not restore the override", replace.contains("it.sharedOverride"))
        assertFalse("REPLACE pins the override, losing the person's choice", replace.contains("it.sharedOverride ?: false"))
    }

    /**
     * MERGE drops an entry link that loses either end, the way the activity cross-refs already do.
     *
     * Asserted against the source because the mapping happens inside a suspend function this module
     * cannot run. Both ends matter: an entry id that survived and a person id that did not would
     * attach a previous life's entry to whoever holds that row id now.
     */
    @Test
    fun `the merge path remaps both ends of an entry link and drops a pair that loses either`() {
        val merge = mergeBody(source)
        assertTrue("MERGE does not remap the entry id", merge.contains("entryIdMap[ref.entryId]"))
        assertTrue("MERGE does not remap the person id", merge.contains("personIdMap[ref.personId]"))
        assertTrue("MERGE does not drop an unmapped pair", merge.contains("mapNotNull"))

        // REPLACE writes them verbatim instead, which is correct there: entry_people has no foreign
        // key, so a pair naming a row the file does not carry is inert rather than fatal.
        assertTrue(
            "REPLACE does not restore the entry links",
            replaceBody(source).contains("EntryPersonCrossRef(it.entryId, it.personId)"),
        )
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
