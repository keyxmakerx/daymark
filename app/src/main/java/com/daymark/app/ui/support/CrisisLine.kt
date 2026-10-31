package com.daymark.app.ui.support

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.daymark.app.data.CrisisLineDirectory
import com.daymark.app.data.CrisisStore
import com.daymark.app.ui.components.DaymarkSwitch
import com.daymark.app.ui.components.SentenceCaps
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The fields that set a crisis line, the same in onboarding, on Home's one-time check and on the
 * crisis screen, so the three can never ask for different things. The number is its own field
 * because the Call and Text buttons dial it exactly; the contact line stays free text for whatever
 * else the person wants to remember ("Call 116 123, free, any time") (#482).
 */
@Composable
fun CrisisLineFields(
    label: String,
    onLabel: (String) -> Unit,
    contact: String,
    onContact: (String) -> Unit,
    number: String,
    onNumber: (String) -> Unit,
    takesTexts: Boolean,
    onTakesTexts: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = label, onValueChange = onLabel, label = { Text("Name") },
            keyboardOptions = SentenceCaps, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = contact, onValueChange = onContact, label = { Text("How to reach them") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = number, onValueChange = onNumber, label = { Text("Number to call (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (CrisisStore.dialable(number) != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("This line takes texts", modifier = Modifier.weight(1f))
                DaymarkSwitch(checked = takesTexts, onCheckedChange = onTakesTexts)
            }
        }
    }
}

/** Hoisted state for [CrisisLineFields], starting from [from] when the person is changing a line. */
class CrisisLineDraft(from: CrisisStore.Resource?) {
    var label by mutableStateOf(from?.label.orEmpty())
    var contact by mutableStateOf(from?.contact.orEmpty())
    var number by mutableStateOf(from?.number.orEmpty())
    var takesTexts by mutableStateOf(from?.takesTexts ?: false)
    val complete: Boolean get() = label.isNotBlank() && contact.isNotBlank()
}

@Composable
fun rememberCrisisLineDraft(from: CrisisStore.Resource?): CrisisLineDraft = remember { CrisisLineDraft(from) }

/**
 * Fills the draft from a line in the bundled list (#521). It fills in the fields and nothing else:
 * the person still reads them, can change any of them, and saves as if they had typed them.
 */
fun CrisisLineDraft.fillFrom(line: CrisisLineDirectory.Line) {
    label = line.label
    contact = line.contact
    number = line.number
    takesTexts = line.takesTexts
}

@Composable
fun CrisisLineFields(draft: CrisisLineDraft) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Pick from a list of national lines")
        }
        CrisisLineFields(
            label = draft.label, onLabel = { draft.label = it },
            contact = draft.contact, onContact = { draft.contact = it },
            number = draft.number, onNumber = { draft.number = it },
            takesTexts = draft.takesTexts, onTakesTexts = { draft.takesTexts = it },
        )
    }
    if (picking) {
        CrisisLinePicker(
            onPick = { draft.fillFrom(it); picking = false },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The bundled list, in its own fixed order. Nothing about the phone (region, SIM, language,
 * location) chooses, sorts or highlights an entry: the person finds their country (FEATURES §7.3).
 * The list says when it was last checked, because a number can change after it was written down.
 */
@Composable
private fun CrisisLinePicker(onPick: (CrisisLineDirectory.Line) -> Unit, onDismiss: () -> Unit) {
    val checkedOn = LocalDate.parse(CrisisLineDirectory.CHECKED_ON).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("National crisis lines") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (line in CrisisLineDirectory.lines) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(line) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(line.country, style = MaterialTheme.typography.titleSmall)
                        Text(
                            line.label + " · " + line.contact,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "This list was last checked on " + checkedOn + ". " +
                        "You can change anything it fills in before you save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Call, and Text where the line takes texts. Each opens the person's own phone or messages app
 * with the number filled in (`ACTION_DIAL`, `ACTION_SENDTO`): neither needs a permission, neither
 * places a call or sends anything, and the person presses call themselves (FEATURES §8). Shown only
 * for a confirmed line with a number, so nobody is handed a one-tap number they did not choose.
 */
@Composable
fun CrisisCallButtons(resource: CrisisStore.Resource, modifier: Modifier = Modifier) {
    val number = resource.number ?: return
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(false) }
    fun open(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            unavailable = true
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Call $number") }
        if (resource.takesTexts) {
            OutlinedButton(
                onClick = { open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Text $number") }
        }
        if (unavailable) {
            Text(
                "This phone has no app that can open the number. It is written above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
