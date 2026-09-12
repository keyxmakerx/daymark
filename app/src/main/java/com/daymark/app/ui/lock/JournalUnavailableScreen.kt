package com.daymark.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * THE TWO SCREENS NOBODY SHOULD EVER SEE, AND WHAT THEY SAY WHEN SOMEBODY DOES.
 *
 * The journal is encrypted with a key kept in the phone's hardware keystore. A keystore can lose a
 * key — a firmware update that clears the TEE, a factory-reset-protection event, some OEM behaviour
 * nobody documented — and when that happens the entries on this phone cannot be read by anything,
 * ever, by anybody. There is no support line that can help and no server that kept a copy; that is
 * the same no-escrow property the rest of this product rests on, and the copy here must not imply
 * otherwise.
 *
 * WHAT THESE SCREENS REFUSE TO DO.
 *
 * They do not delete anything. An app that tidies away a file it cannot read is an app that decides,
 * on somebody's behalf and without asking, that a year of their journal is over. The unreadable file
 * stays exactly where it is unless a person chooses otherwise, and "leave them as they are and try
 * again later" is offered FIRST and as the ordinary option, because on some devices a key comes back
 * after a restart and because there is no hurry.
 *
 * They do not name a cause, either. From here, a keystore that has lost a key and a keystore that is
 * temporarily unavailable are the same silence. Saying "your phone's secure hardware was reset"
 * would be a guess at somebody's device dressed as a diagnosis — and the rule this app uses is that
 * a refusal names a CONSEQUENCE, not a cause the system cannot know.
 *
 * They do not alarm. No warning colour, no icon, no exclamation mark. Somebody reading this has
 * just opened a mental-health journal and been told they may have lost it; the screen's job is to be
 * calm, say exactly what is true, and get out of the way.
 */

/**
 * Shown while the journal is being opened, and — the first time only — migrated.
 *
 * It says "opening" rather than "upgrading" or "encrypting your entries" because for all but one
 * launch in a person's life it IS just opening, and a sentence that appears once and frightens is
 * worse than a sentence that is always true and dull.
 */
@Composable
fun OpeningJournalScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            "Opening your journal",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

/**
 * A swap that did not finish. Nothing is lost and nothing may be deleted from here.
 *
 * The encrypted copy is sitting beside where the database belongs, and the next launch finishes the
 * rename. So this screen offers exactly one thing — close the app and open it again — and
 * deliberately offers no way to start over, because starting over here would destroy a journal that
 * is intact.
 */
@Composable
fun JournalNeedsRestartScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Daymark could not finish opening your journal", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your entries are safe. Daymark was part-way through moving them and stopped before it " +
                "finished. Close Daymark completely and open it again, and it will pick up where it " +
                "left off.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * The journal is here and this phone can no longer produce the key that opens it.
 *
 * The copy is issue #109's "lost both" wording, minus the clause about the PIN and the recovery
 * code, because neither exists in this state — the key was held by the phone and the phone has lost
 * it. The shape is the part that matters and is kept exactly: what cannot be done, then two
 * choices, of which only the second destroys anything and only a person makes it.
 */
@Composable
fun JournalUnopenableScreen(onStartNewJournal: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("These entries cannot be opened", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Nothing on this phone can open these entries. You can leave them as they are and try " +
                "again later, or start a new journal, which removes them.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Photos you attached to entries were never locked. Starting a new journal does not " +
                "remove them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        // An outlined button, not a filled one. The ordinary thing to do here is close the app and
        // come back later, and that needs no button at all; this is the other one.
        OutlinedButton(
            onClick = { confirming = true },
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        ) {
            Text("Start a new journal")
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Start a new journal?") },
            // The consequence, verbatim, at the point of the click — not on the screen behind it.
            text = {
                Text(
                    "This removes the entries that cannot be opened. They cannot be brought back " +
                        "afterwards. Photos you attached to entries are not removed.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onStartNewJournal() }) {
                    Text("Start a new journal")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Leave them as they are") }
            },
        )
    }
}
