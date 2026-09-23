package com.daymark.app.data.entity

/**
 * Which drawer of the picker a [Person] sits in.
 *
 * `docs/FEATURES.md` §11.1: *"Groups (friends, family, partners, communities, other) sort the
 * picker and the list, and do nothing else."* That sentence is the whole specification, and
 * **nothing else** is the part worth holding. A group sorts a list. It is not a relationship
 * model, it is not a fact about the person on the other end of it, and nothing in the app may read
 * it as one.
 *
 * ## A community is not a second kind of thing
 *
 * There is one table and one entity, [Person], with a group on it — not a `Person` table and a
 * `Community` table. `docs/FEATURES.md` §11.1 makes them one tag kind, and the reason is
 * practical rather than tidy: an entry says *with*, and what it is with is a name the person typed.
 * Splitting the storage would mean every screen, every picker and every cross-reference had to ask
 * which of two tables a name came from, and the first place that forgot to ask would quietly drop
 * half of them.
 *
 * [COMMUNITIES] is therefore a group key like any other. A choir, a support group, a Sunday league
 * and a housemate all live in one list.
 *
 * ## Why the column is text and not an enum ordinal
 *
 * Stored as [key], the way `Tracker.type`, `Treatment.kind`, `OfferRecord.kind` and
 * `com.daymark.app.goals.GoalKind` already are. An ordinal is a number whose meaning lives in the
 * declaration order of a Kotlin file: insert a group in the middle and every row on every phone
 * silently changes group. Text cannot do that.
 *
 * It also means **a key this build does not recognise survives being read**. A backup from a newer
 * version, or a row a later version wrote, reads back as its own string; [fromKey] decides what to
 * *display* it as, here, where it is testable, rather than in a type converter that would throw.
 * [Person.groupKey] keeps the original string either way, so downgrading and upgrading again does
 * not rewrite the person's choice.
 *
 * ## Why the unknown case resolves to [OTHER]
 *
 * [OTHER] is the group that claims nothing. An unrecognised key shown as "other" says *this one is
 * not filed*, which is true; shown as "family" or "partners" it would assert a relationship the
 * person never chose, on their own screen, about somebody real. Of the five, only [OTHER] is safe
 * to be wrong in.
 */
enum class PersonGroup(val key: String) {

    FRIENDS("friends"),

    FAMILY("family"),

    PARTNERS("partners"),

    /** Choirs, teams, congregations, support groups — a *what* rather than a *who*. */
    COMMUNITIES("communities"),

    /** The group that asserts nothing, and what an unreadable key resolves to. */
    OTHER("other"),
    ;

    companion object {

        /** What a person is filed under when nothing says otherwise. */
        val DEFAULT: PersonGroup = OTHER

        /** Never throws: an unrecognised key is [DEFAULT]. See the note above on why. */
        fun fromKey(key: String?): PersonGroup = entries.firstOrNull { it.key == key } ?: DEFAULT

        /**
         * Whether [key] is one this build knows.
         *
         * Distinct from [fromKey] on purpose. [fromKey] answers *what do I draw*, and it always
         * answers something; this answers *did I understand it*, which is what a caller needs
         * before it rewrites the stored string. Nothing may normalise an unknown key to `"other"`
         * in the database — that would turn "written by a newer version" into a permanent loss of
         * the person's own choice.
         */
        fun isKnown(key: String?): Boolean = entries.any { it.key == key }
    }
}
