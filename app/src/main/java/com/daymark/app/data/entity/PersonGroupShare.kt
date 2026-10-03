package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One group's sharing default: *are the people filed under `friends` shared with a clinician
 * unless I said otherwise?*
 *
 * `docs/FEATURES.md` §11.4: *"One screen lists every person and community, with a default per
 * group (all off) and an override per person"*, and sharing stays off even under an accept-all
 * grant. This entity is the "default per group" half; [Person.sharedOverride] is the "override per
 * person" half, and it wins.
 *
 * ## Absent means off, which is why there is no row until somebody switches one on
 *
 * Nothing seeds this table. A group with no row here is not shared, and that is the state every
 * install, every fresh group and every failed write lands in. The reasoning is the same one behind
 * `SafetyPlanItem` having no seeded rows: the safe value has to be the one you get by doing
 * nothing, because doing nothing is what happens when something goes wrong.
 *
 * Put the other way round: there is no code path in which a missing or unreadable row causes
 * somebody's name to be sent to a clinician. The failure mode of this table is that a person who
 * meant to share has to say so again.
 *
 * ## Why it is a table and not a preference
 *
 * It could have been five booleans in `SharedPreferences`. It is a table because it has to move as
 * one piece with the people it governs. A restore writes `people` and this table inside the same
 * transaction, so a phone can never come back up holding the file's group defaults over somebody
 * else's people, or this device's defaults over a restored set — either mismatch turns "shared"
 * on for somebody nobody chose, and nothing on the screen would look wrong.
 *
 * The same argument is why the per-item override is a column on `people` rather than a second table
 * keyed by person: an exclusion that can get separated from the person it excludes is an exclusion
 * that eventually does.
 *
 * ## Keyed by the group's text key, not by an ordinal
 *
 * [groupKey] is a [PersonGroup.key]. A key from a newer version reads back as itself and keeps its
 * setting rather than landing on whichever group happens to hold that ordinal today — the same
 * reasoning as [Person.groupKey], and it matters more here, because the value being misfiled is a
 * decision about what leaves the device.
 */
@Entity(tableName = "person_group_shares")
data class PersonGroupShare(

    /** A [PersonGroup.key]. One row per group, at most, and none until one is switched on. */
    @PrimaryKey val groupKey: String,

    /**
     * The group's default. `false` is both the value a row is created with and the answer given
     * when there is no row at all — see the note above on why those two have to agree.
     */
    val shared: Boolean = false,
)
