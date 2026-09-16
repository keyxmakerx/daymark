package com.daymark.app.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroup

/**
 * "People and communities" — every name the person has written down, grouped for sorting, plus the
 * way in to the sharing screen.
 *
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §2 is the design.
 *
 * ## The rule this whole package is built inside
 *
 * **A person or a community may never reach anything that reads mood.** Nothing in
 * `ui/people` imports the mood model, the mood palette, the mood labels or `stats/`, and nothing
 * here asks a repository a question that could answer with one. That is asserted as an absence,
 * with planted controls on both sides, by `PeopleUiSourceTest`.
 *
 * It is why the entries on a person's page are drawn as a date and their own words and nothing
 * else. A column of mood faces under somebody's name is a mood-with-person statistic — the eye
 * does the correlation whether or not the code does — and the plan's *Not doing* list rules that
 * out by name.
 *
 * ## The empty state asks for nothing
 *
 * No count, no "0 people", nothing shaped like an unfilled slot. It says what the screen is for
 * and stops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenSharing: () -> Unit,
    viewModel: PeopleViewModel = hiltViewModel(),
) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    val active: List<Person> = people.filter { !it.archived }
    val archived: List<Person> = people.filter { it.archived }
    val groups: List<Pair<PersonGroup, List<Person>>> = peopleByGroup(active)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("People and communities") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add a person or community")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "intro") {
                Text(
                    "Whoever you name in an entry, and whatever you write about them. " +
                        "Groups sort this list and nothing else.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
                )
            }

            item(key = "sharing") {
                ListItem(
                    headlineContent = { Text("Sharing with a clinician") },
                    supportingContent = { Text("Everything is off until you turn it on.") },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    },
                    modifier = Modifier.clickable { onOpenSharing() },
                )
                HorizontalDivider()
            }

            if (people.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing here. Add a name from this screen, or from the with picker " +
                            "while you are writing an entry.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                    )
                }
            }

            groups.forEach { entry ->
                item(key = "group_" + entry.first.key) {
                    PeopleSectionLabel(personGroupLabel(entry.first))
                }
                items(entry.second, key = { person -> person.id }) { person ->
                    PersonListRow(person = person, archived = false, onClick = { onOpenPerson(person.id) })
                }
            }

            if (archived.isNotEmpty()) {
                item(key = "archived_header") { PeopleSectionLabel("Archived") }
                items(archived, key = { person -> person.id }) { person ->
                    PersonListRow(person = person, archived = true, onClick = { onOpenPerson(person.id) })
                }
            }
        }
    }

    if (adding) {
        AddPersonDialog(
            onDismiss = { adding = false },
            onConfirm = { name, group ->
                viewModel.add(name, group)
                adding = false
            },
        )
    }
}

/**
 * One name.
 *
 * The "who or what is this to you" line is the supporting text when there is one, and when there
 * is not there is simply nothing there. No placeholder, no "not described yet", nothing that reads
 * as an unfinished form — a name on its own is a whole answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonListRow(
    person: Person,
    archived: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                person.name,
                color = if (archived) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        supportingContent = if (archived) {
            { Text("Archived. Their page and every entry that names them are still here.") }
        } else if (person.whoTheyAre.isNotBlank()) {
            { Text(person.whoTheyAre) }
        } else {
            null
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        },
        modifier = Modifier.clickable { onClick() },
    )
}

/** The group heading, in the same register as the section headings in Settings. */
@Composable
internal fun PeopleSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp),
    )
}
