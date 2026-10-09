package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A constellation the person drew between their own stars and named (`DECISIONS.md` §D11, #449).
 *
 * The software never groups stars; the only writer of this table is the person's own tap on
 * "Save". Five columns:
 *
 *  - [name] is the person's own words, at most `SkyConstellation.NAME_MAX` characters.
 *  - [madeEpochDay] is the day it was drawn: the day its photo shows.
 *  - [points] is `SkyConstellation.encode`: which memory each point joins, by kind and record id,
 *    and where its star was that day. The positions are what the photo is drawn from, and what the
 *    live sky measures drift against, so neither can move when the sky does.
 *  - [createdAt] is when the row was written, in millis.
 *
 * It lives in the database and not in preferences because the name is the person's writing, and
 * the database is the store that is encrypted at rest.
 */
@Entity(tableName = "sky_constellations")
data class Constellation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val madeEpochDay: Long,
    val points: String,
    val createdAt: Long = 0,
)
