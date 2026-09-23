package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Somebody — or something — the person shares their life with: a friend, a family member, a
 * partner, a choir, a support group.
 *
 * `docs/FEATURES.md` §11.1 is its design. Seven columns, and as with [LifeEvent] the fields that
 * are *not* here are most of the point.
 *
 * ## One table, not two
 *
 * A community is a [Person] with [groupKey] = `"communities"`. [PersonGroup] carries the long
 * version of why; the short one is that an entry's *with* is a name, and a name that could live in
 * either of two tables is a name half the code will forget to look for in the second.
 *
 * ## The rule this table exists inside
 *
 * **A person or a community must never reach any code that reads mood.** `docs/FEATURES.md` §11.2:
 * *"Correlations, patterns and the cards they produce cannot receive a person or a community,
 * groups included."* "Meetup went well" as an *activity* is the person's own choice and stays in
 * statistics; *Sam* does not, and neither does *family*.
 *
 * This entity holds up its end of that by what it is not joined to. `entry_people` is read through
 * its own DAO (`EntryPersonDao`), which returns entry **ids** and never a mood level, and
 * `PeopleRepository` never joins to `mood_entries`. So there is no query in this layer that can
 * produce a (person, mood) pair for a correlation to be run over — someone would have to write one.
 * The matching guard on the other side is `stats/`'s own signatures; see `PeopleRepository`.
 *
 * ## What is not here
 *
 * - **No status, and no dates about the relationship.** No "close / distant", no "met in 2019", no
 *   "last seen". `docs/FEATURES.md` §11.1 rules these out, and a group is not a relationship
 *   model. A closeness field is the app grading somebody's relationships back at them.
 * - **No photo** (`docs/FEATURES.md` §11.1). A mental-health diary that holds pictures of the
 *   people in someone's life is a different and much heavier object, and the people in the
 *   pictures never agreed to be in it.
 * - **No contact details.** Not a phone number, not a handle, not an email. Nothing here is a way
 *   to reach anyone; this is the person's own note about who somebody is to them.
 * - **No mood, no rating, no score.** Nothing measures a relationship.
 * - **No sort order.** The picker sorts by group then name. A manual ranking of the people in
 *   someone's life is a list nobody should be asked to put in order.
 *
 * ## [whoTheyAre] is free text and stays free text
 *
 * `docs/FEATURES.md` §11.1: *"a "who (or what) is this to you" line ... It is all free text in the
 * person's words. There is no status field ... The app stores it and shows it; it never reads
 * it."* It is never parsed, never matched against a vocabulary, never used to infer a group, and
 * never fed to a rule. It is one line the person wrote; the app's whole job with it is to keep it
 * and show it back.
 *
 * ## [archived] hides, it does not delete
 *
 * `docs/FEATURES.md` §11.1: *"Archive hides someone from the picker. Their page, notes and entries
 * stay as they are."* Archiving takes a name out of the *with* picker and nothing else. Every entry
 * that already names them still names them, every note is still there, and their page still opens.
 * This matters more here than it does for an activity: people leave, and a person who tidies a
 * name out of a picker has not asked for the record of the years they were in it to be rewritten.
 */
@Entity(tableName = "people")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** The name the person typed. Never parsed, never matched, never normalised. */
    val name: String,

    /**
     * A [PersonGroup.key]. Text, never an ordinal — [PersonGroup] says why, and why an unknown key
     * is kept verbatim rather than normalised to `"other"` on the way in.
     */
    val groupKey: String = PersonGroup.DEFAULT.key,

    /**
     * The *"who (or what) is this to you"* line, in the person's own words.
     *
     * Empty by default and allowed to stay empty. Nothing prompts for it twice and nothing treats a
     * blank one as incomplete — a name on its own is a whole answer.
     */
    val whoTheyAre: String = "",

    /** Hidden from the picker. Entries and notes are untouched — see the note above. */
    val archived: Boolean = false,

    /**
     * When the row was written, in epoch millis.
     *
     * Not a date *about* the relationship — it is the date the person added a card to their own
     * app, and it must never be displayed as though it were when they met. It exists so a restore
     * can rebuild the order names were added in, and so two people with the same name have a
     * tiebreak that is not the row id alone. `LifeEvent.createdAt` carries the same warning.
     */
    val createdAt: Long = 0,

    /**
     * This person's own answer to *is this shared with a clinician*, or `null` for *whatever the
     * group says*.
     *
     * **Three states, not two, and the third one is the feature.** `docs/FEATURES.md` §11.4 asks
     * for *"a default per group (all off) and an override per person"*. A plain `Boolean` cannot
     * hold "I have not chosen for this one" apart from "I chose no": a group default later turned
     * on would either move everybody who had never been touched (if `false` meant unset) or move
     * nobody ever again (if it meant chosen). `null` says *follow the group*, and it is what every
     * new row starts as.
     *
     * **Every one of the three resolves to off until somebody says otherwise.** `null` inherits a
     * group default that is itself absent-means-off ([PersonGroupShare]), so a brand-new person in
     * a brand-new group is not shared. Sharing is off by default for everything, and there is no
     * path through this column that turns it on without an explicit act.
     *
     * It lives on the person's own row rather than in a side table because it must travel with
     * them: through a backup, through a restore, through an export. A person's exclusion held
     * somewhere else is a person who becomes shared the first time the two halves get out of step,
     * and that failure is invisible from this side of the screen — the phone would look right.
     *
     * The resolution rule is one expression and it lives in `PeopleRepository.isShared`, where a
     * test can call it without a database.
     */
    val sharedOverride: Boolean? = null,
)
