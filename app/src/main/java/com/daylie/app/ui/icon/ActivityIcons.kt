package com.daylie.app.ui.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/** Maps a stable [ActivityEntity.iconKey] string to a Material icon. */
object ActivityIcons {

    private val map: Map<String, ImageVector> = mapOf(
        "work" to Icons.Filled.Work,
        "family" to Icons.Filled.Home,
        "friends" to Icons.Filled.Group,
        "exercise" to Icons.Filled.DirectionsRun,
        "sleep" to Icons.Filled.Bedtime,
        "food" to Icons.Filled.Restaurant,
        "reading" to Icons.Filled.Book,
        "gaming" to Icons.Filled.SportsEsports,
        "movie" to Icons.Filled.LocalMovies,
        "relax" to Icons.Filled.SelfImprovement,
        "study" to Icons.Filled.School,
        "shopping" to Icons.Filled.ShoppingCart,
        "music" to Icons.Filled.MusicNote,
        "love" to Icons.Filled.Favorite,
        "pets" to Icons.Filled.Pets,
        "art" to Icons.Filled.Brush,
        "star" to Icons.Filled.Star,
    )

    /** All keys offered when creating/editing an activity. */
    val keys: List<String> = map.keys.toList()

    fun forKey(key: String): ImageVector = map[key] ?: Icons.Filled.Star
}
