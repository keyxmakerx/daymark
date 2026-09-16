package com.daymark.app.ui

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four new screens are reachable, and tapping a past entry opens the page rather than the
 * editor.
 *
 * ## Why this is worth a test
 *
 * A route declared in `Destinations.kt` with no `composable(...)` in `DaymarkAppScaffold.kt`
 * compiles perfectly and is dead: nothing can reach it, and nothing fails. The same is true of a
 * route pattern whose argument name does not match the key the view model reads — `personId`
 * against `person_id` gives every page id `0L`, silently, and the screen simply says the person is
 * not there.
 *
 * Neither of those can be caught by the compiler and this module cannot run a navigation test, so
 * the wiring is read as text. Each absence check below is shown a planted example first
 * (`CLAUDE.md` §5).
 */
class PeopleNavigationSourceTest {

    private companion object {
        const val ROUTES = "app/src/main/java/com/daymark/app/ui/navigation/Destinations.kt"
        const val SCAFFOLD = "app/src/main/java/com/daymark/app/ui/DaymarkAppScaffold.kt"
        const val PERSON_VM = "app/src/main/java/com/daymark/app/ui/people/PersonViewModel.kt"
        const val ENTRY_VIEW_VM = "app/src/main/java/com/daymark/app/ui/entry/EntryViewViewModel.kt"

        /** The old wiring: an entry tap opening the editor. */
        const val OLD_ENTRY_TAP = "onEntryClick = { id -> navController.navigate(Routes.entry(id)) }"
    }

    private val routes: String = repoFile(ROUTES).readText()
    private val scaffold: String = repoFile(SCAFFOLD).readText()

    @Test
    fun `both files were found`() {
        assertTrue("Destinations.kt is implausibly short", routes.length > 2000)
        assertTrue("DaymarkAppScaffold.kt is implausibly short", scaffold.length > 10000)
    }

    @Test
    fun `every new route is declared`() {
        for (name in listOf("PEOPLE", "PEOPLE_SHARING", "PERSON", "ENTRY_VIEW")) {
            assertTrue(
                "Routes.$name is gone from Destinations.kt",
                routes.contains("const val $name = "),
            )
        }
        assertTrue("Routes.person(id) is gone", routes.contains("fun person(id: Long)"))
        assertTrue("Routes.entryView(id) is gone", routes.contains("fun entryView(id: Long)"))
    }

    @Test
    fun `every new route is registered in the nav host, or it is unreachable`() {
        val registrations = listOf(
            "composable(Routes.PEOPLE," to "PeopleScreen(",
            "composable(Routes.PEOPLE_SHARING," to "PeopleSharingScreen(",
            "Routes.PERSON_PATTERN," to "PersonScreen(",
            "Routes.ENTRY_VIEW_PATTERN," to "EntryViewScreen(",
        )
        for ((route, screen) in registrations) {
            assertTrue(
                "\"$route\" has no entry in DaymarkAppScaffold's NavHost, so the screen behind it " +
                    "cannot be reached by anything",
                scaffold.contains(route),
            )
            assertTrue("$screen is never composed by the scaffold", scaffold.contains(screen))
        }
    }

    @Test
    fun `the people list has a door into the app`() {
        assertTrue(
            "nothing navigates to Routes.PEOPLE, so the whole feature is unreachable",
            scaffold.contains("navController.navigate(Routes.PEOPLE)"),
        )
        assertTrue(
            "the More hub no longer offers People",
            repoFile("app/src/main/java/com/daymark/app/ui/more/MoreHubScreen.kt")
                .readText()
                .contains("onPeople"),
        )
    }

    /**
     * The argument name in the route pattern is the key the view model reads.
     *
     * A mismatch here does not fail: `savedStateHandle.get<String>("...")` returns null, the id
     * falls back to `0L`, and the screen politely reports that the thing is not there.
     */
    @Test
    fun `each route argument is read under the name the pattern gives it`() {
        assertTrue(
            "PERSON_PATTERN no longer names its argument personId",
            routes.contains("const val PERSON_PATTERN = \"\$PERSON/{personId}\""),
        )
        assertTrue(
            "PersonViewModel does not read personId",
            repoFile(PERSON_VM).readText().contains("savedStateHandle.get<String>(\"personId\")"),
        )
        assertTrue(
            "ENTRY_VIEW_PATTERN no longer names its argument entryId",
            routes.contains("const val ENTRY_VIEW_PATTERN = \"\$ENTRY_VIEW/{entryId}\""),
        )
        assertTrue(
            "EntryViewViewModel does not read entryId",
            repoFile(ENTRY_VIEW_VM).readText().contains("savedStateHandle.get<String>(\"entryId\")"),
        )
        assertTrue(
            "the person route declares no navArgument, so its id never reaches the view model",
            scaffold.contains("navArgument(\"personId\")"),
        )
    }

    /**
     * Tapping a past entry opens the entry page.
     *
     * The editor is one deliberate tap further on. Opening the record of a hard day straight into
     * a form with a delete button in the corner is the wrong first thing to happen, and it is what
     * this app did until now.
     */
    @Test
    fun `tapping a past entry opens the page and not the editor`() {
        assertFalse(
            "an entry tap still opens the editor: \"$OLD_ENTRY_TAP\"",
            scaffold.contains(OLD_ENTRY_TAP),
        )
        val openings = Regex("Routes\\.entryView\\(").findAll(scaffold).count()
        assertTrue(
            "only $openings surfaces open the entry page; Home, All entries, For you, a day, " +
                "search and a person's page all tap through to an entry",
            openings >= 5,
        )
        // Creating a new entry still goes to the editor, which is the point of the editor.
        assertTrue(
            "the new-entry button no longer opens the editor",
            scaffold.contains("navController.navigate(Routes.entry())"),
        )
    }

    @Test
    fun `the old-wiring detector sees a planted example`() {
        val planted = scaffold.replace(
            "onEntryClick = { id -> navController.navigate(Routes.entryView(id)) }",
            OLD_ENTRY_TAP,
        )
        assertTrue("the planted mutation changed nothing, so the check proves nothing", planted != scaffold)
        assertTrue(
            "the check cannot see an entry tap wired back to the editor",
            planted.contains(OLD_ENTRY_TAP),
        )
    }

    @Test
    fun `the registration detector sees a route with no composable`() {
        val planted = scaffold.replace("composable(Routes.PEOPLE_SHARING,", "composable(Routes.NOWHERE,")
        assertTrue("the planted mutation changed nothing", planted != scaffold)
        assertFalse(
            "the check cannot see a route losing its registration",
            planted.contains("composable(Routes.PEOPLE_SHARING,"),
        )
    }
}
