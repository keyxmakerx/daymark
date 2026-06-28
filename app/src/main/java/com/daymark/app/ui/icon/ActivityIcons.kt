package com.daymark.app.ui.icon

import androidx.annotation.DrawableRes
import com.daymark.app.R

/** Maps a stable [ActivityEntity.iconKey] string to a hand-drawn "paper" vector drawable. */
object ActivityIcons {

    private val map: Map<String, Int> = mapOf(
        "work" to R.drawable.ic_act_work,
        "family" to R.drawable.ic_act_family,
        "friends" to R.drawable.ic_act_friends,
        "exercise" to R.drawable.ic_act_exercise,
        "sleep" to R.drawable.ic_act_sleep,
        "food" to R.drawable.ic_act_food,
        "reading" to R.drawable.ic_act_reading,
        "gaming" to R.drawable.ic_act_gaming,
        "movie" to R.drawable.ic_act_movie,
        "relax" to R.drawable.ic_act_relax,
        "study" to R.drawable.ic_act_study,
        "shopping" to R.drawable.ic_act_shopping,
        "music" to R.drawable.ic_act_music,
        "love" to R.drawable.ic_act_love,
        "pets" to R.drawable.ic_act_pets,
        "art" to R.drawable.ic_act_art,
        "star" to R.drawable.ic_act_star,
    )

    /** All keys offered when creating/editing an activity. */
    val keys: List<String> = map.keys.toList()

    @DrawableRes
    fun forKey(key: String): Int = map[key] ?: R.drawable.ic_act_star
}
