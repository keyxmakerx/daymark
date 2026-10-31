package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What the person chose for one star of their sky (`DECISIONS.md` §D11): a name of its own (#517),
 * a meteor shower on its day each year (#516), and a place it belongs to (#518). One row per
 * memory, keyed as [SkyPutAway] is: [kind] is `SkyKind.key` and [recordId] the memory's row.
 *
 * Every field is the person's own act, and nothing writes one for them: no name is suggested, no
 * day is picked for a shower, no memory is put in a place. A row with nothing chosen is deleted
 * rather than kept (`SkyStarDao`).
 *
 * **Deleting the memory deletes this row**, by triggers on each memory's own table
 * ([com.daymark.app.data.AppDatabase.SKY_STAR_TRIGGERS]), so a deleted memory's name leaves no trace.
 */
@Entity(tableName = "sky_stars", primaryKeys = ["kind", "recordId"])
data class SkyStar(
    val kind: String,
    val recordId: Long,
    /** The person's own name for this star, or null. At most `SkyStars.NAME_MAX` characters. */
    val name: String? = null,
    /** True when the person chose a meteor shower for this star's day each year. */
    val shower: Boolean = false,
    /** The [SkyPlace] the person put this memory in, or null. */
    val placeId: Long? = null,
)

/**
 * A place the person named (#518): home, a grandparent's house, a city. Only what they typed; no
 * device location is read for it, ever (`DECISIONS.md` §D11). It becomes a ringed planet among the
 * memories they put in it.
 */
@Entity(tableName = "sky_places")
data class SkyPlace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = 0,
)
