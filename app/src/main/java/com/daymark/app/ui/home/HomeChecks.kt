package com.daymark.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.daymark.app.data.CrisisStore
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.support.CrisisLineFields
import com.daymark.app.ui.support.rememberCrisisLineDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * The questions Home asks once, of people who set Daymark up before onboarding asked them. Each is
 * the same question onboarding asks, with the same answers, and any answer ends it.
 */
@HiltViewModel
class HomeChecksViewModel @Inject constructor(
    private val crisisStore: CrisisStore,
) : ViewModel() {

    /** The line to ask about, or null when nothing is owed (#482). */
    private val _crisisCheck = MutableStateFlow(crisisStore.takeIf { it.needsCheck }?.get())
    val crisisCheck: StateFlow<CrisisStore.Resource?> = _crisisCheck.asStateFlow()

    fun keepCrisisLine() {
        crisisStore.confirm()
        _crisisCheck.value = null
    }

    fun saveCrisisLine(label: String, contact: String, number: String?, takesTexts: Boolean) {
        crisisStore.save(label, contact, number, takesTexts)
        _crisisCheck.value = null
    }

    fun skipCrisisLine() {
        crisisStore.markChecked()
        _crisisCheck.value = null
    }
}

/**
 * "Is this the right crisis line where you live?", once. The line has been on the crisis screen all
 * along; this asks the people who never saw onboarding's step whether it is theirs, because the
 * default is a US number and the screen's Call button waits until someone says it is (#482).
 */
@Composable
fun CrisisLineCheck(
    current: CrisisStore.Resource,
    onKeep: () -> Unit,
    onSave: (label: String, contact: String, number: String?, takesTexts: Boolean) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var changing by remember { mutableStateOf(false) }
    val draft = rememberCrisisLineDraft(null)
    PaperSurface(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your crisis line", style = MaterialTheme.typography.titleMedium)
            Text(
                "Daymark keeps one crisis line on your phone. Is this the right one where you live?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(current.label, style = MaterialTheme.typography.titleSmall)
            Text(current.contact, style = MaterialTheme.typography.bodyLarge)
            if (changing) {
                CrisisLineFields(draft)
                Button(
                    onClick = { onSave(draft.label, draft.contact, draft.number, draft.takesTexts) },
                    enabled = draft.complete,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save") }
            } else {
                Button(onClick = onKeep, modifier = Modifier.fillMaxWidth()) { Text("This one is right") }
                TextButton(onClick = { changing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Use a different line")
                }
            }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
            Text(
                "Asked once. You can change it any time on the crisis screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
