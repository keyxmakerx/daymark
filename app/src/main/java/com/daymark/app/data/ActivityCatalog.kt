package com.daymark.app.data

/** One browsable suggestion in the activity library. [iconKey] reuses an existing
 *  hand-drawn icon from [com.daymark.app.ui.icon.ActivityIcons] (unknown keys fall back
 *  to the generic star), so the catalog ships without new drawables. */
data class CatalogActivity(val name: String, val iconKey: String)

/** A named group of catalog suggestions shown as one section in the library. */
data class CatalogCategory(val title: String, val items: List<CatalogActivity>)

/**
 * A bundled, offline library of ~120 suggested activities grouped into categories, browsable
 * from the Activities screen ("Add from library"). Pure static data — no DB, no network.
 * Names that already exist in the user's list are filtered out when adding.
 */
object ActivityCatalog {

    private fun a(name: String, iconKey: String) = CatalogActivity(name, iconKey)

    val categories: List<CatalogCategory> = listOf(
        CatalogCategory(
            "Health & Fitness",
            listOf(
                a("Exercise", "exercise"), a("Walk", "exercise"), a("Run", "exercise"),
                a("Gym", "exercise"), a("Yoga", "relax"), a("Cycling", "exercise"),
                a("Swimming", "exercise"), a("Sports", "exercise"), a("Stretching", "relax"),
                a("Dancing", "exercise"), a("Hiking", "exercise"), a("Steps", "exercise"),
            ),
        ),
        CatalogCategory(
            "Food & Drink",
            listOf(
                a("Eat", "food"), a("Cook", "food"), a("Coffee", "food"), a("Tea", "food"),
                a("Water", "food"), a("Healthy meal", "food"), a("Junk food", "food"),
                a("Alcohol", "food"), a("Baking", "food"), a("Takeout", "food"), a("Snack", "food"),
            ),
        ),
        CatalogCategory(
            "Sleep & Rest",
            listOf(
                a("Sleep", "sleep"), a("Nap", "sleep"), a("Early night", "sleep"),
                a("Slept well", "sleep"), a("Slept poorly", "sleep"), a("Lie-in", "sleep"),
                a("Rest", "relax"),
            ),
        ),
        CatalogCategory(
            "Social",
            listOf(
                a("Friends", "friends"), a("Family", "family"), a("Party", "friends"),
                a("Date", "love"), a("Call", "friends"), a("Texting", "friends"),
                a("Met someone new", "friends"), a("Hangout", "friends"), a("Video call", "friends"),
            ),
        ),
        CatalogCategory(
            "Love",
            listOf(
                a("Partner", "love"), a("Quality time", "love"), a("Romance", "love"),
                a("Intimacy", "love"),
            ),
        ),
        CatalogCategory(
            "Work & Study",
            listOf(
                a("Work", "work"), a("Study", "study"), a("Reading", "reading"),
                a("Writing", "study"), a("Exam", "study"), a("Meeting", "work"),
                a("Project", "work"), a("Homework", "study"), a("Online course", "study"),
                a("Email", "work"), a("Deadline", "work"),
            ),
        ),
        CatalogCategory(
            "Hobbies & Leisure",
            listOf(
                a("Gaming", "gaming"), a("Movies", "movie"), a("TV", "movie"),
                a("Music", "music"), a("Podcast", "music"), a("Art", "art"),
                a("Drawing", "art"), a("Photography", "art"), a("Crafts", "art"),
                a("Instrument", "music"), a("Singing", "music"), a("Gardening", "relax"),
            ),
        ),
        CatalogCategory(
            "Chores & Errands",
            listOf(
                a("Cleaning", "star"), a("Laundry", "star"), a("Shopping", "shopping"),
                a("Groceries", "shopping"), a("Dishes", "star"), a("Errands", "shopping"),
                a("Bills", "star"), a("Repairs", "star"), a("Tidying", "star"),
            ),
        ),
        CatalogCategory(
            "Self-care & Mind",
            listOf(
                a("Relax", "relax"), a("Meditation", "relax"), a("Mindfulness", "relax"),
                a("Journaling", "reading"), a("Therapy", "star"), a("Bath", "relax"),
                a("Skincare", "star"), a("Breathing", "relax"), a("Self-care", "relax"),
            ),
        ),
        CatalogCategory(
            "Pets",
            listOf(
                a("Pets", "pets"), a("Walk the dog", "pets"), a("Play with pet", "pets"),
                a("Vet", "pets"), a("Feed pet", "pets"),
            ),
        ),
        CatalogCategory(
            "Places & Travel",
            listOf(
                a("Travel", "star"), a("Commute", "star"), a("Nature", "star"),
                a("Park", "star"), a("Beach", "star"), a("Restaurant", "food"),
                a("Café", "food"), a("Outdoors", "star"), a("Road trip", "star"),
            ),
        ),
        CatalogCategory(
            "Health",
            listOf(
                a("Doctor", "star"), a("Dentist", "star"), a("Medication", "star"),
                a("Felt sick", "star"), a("Self-check", "star"),
            ),
        ),
    )
}
