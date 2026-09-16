package com.daymark.app.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.BuildConfig
import com.daymark.app.util.DateUtils
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManageActivities: () -> Unit,
    onManageGoals: () -> Unit,
    onManageReminders: () -> Unit,
    onCustomizeMoods: () -> Unit,
    onManageSuggestions: () -> Unit,
    /** Opens the timing debug screen. Only ever called from a debug build — see the "Debug" row. */
    onOpenTimingDebug: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity

    LaunchedEffect(Unit) {
        viewModel.messages.collect { onShowMessage(it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }

    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingImport = uri }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let(viewModel::exportCsvTo) }

    var pdfOptions by remember { mutableStateOf<com.daymark.app.export.PdfExportOptions?>(null) }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> val o = pdfOptions; if (uri != null && o != null) viewModel.exportPdfTo(uri, o) }

    var showPinDialog by remember { mutableStateOf(false) }
    var showPdfDialog by remember { mutableStateOf(false) }
    var showAutoLockMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        SectionHeader("Reminders")
        ListItem(
            headlineContent = { Text("Reminders") },
            supportingContent = {
                Text(
                    when (state.reminderCount) {
                        0 -> "None set"
                        1 -> "1 reminder"
                        else -> "${state.reminderCount} reminders"
                    },
                )
            },
            modifier = Modifier.clickable { onManageReminders() },
        )
        ListItem(
            headlineContent = { Text("Suggestions") },
            supportingContent = { Text("Choose which gentle nudges you want to see") },
            modifier = Modifier.clickable { onManageSuggestions() },
        )

        Divider()
        SectionHeader("Privacy")
        /*
         * WHAT THESE TWO ROWS SAY, AND WHY EACH SENTENCE IS THE ONE IT IS.
         *
         * For most of this app's life the lock row said "On", and the journal was a plaintext
         * SQLite file. Both facts were true at once and nobody reading the first would have guessed
         * the second — a person reading "app lock" on a mental-health journal infers that their
         * entries are locked, and they were not. That gap was this app's largest undisclosed
         * weakness, and it was closed in two steps: first by saying it out loud here, then by
         * removing it.
         *
         * WHAT IS TRUE NOW. The database is encrypted with a random 32-byte key, made on first run
         * for everybody, kept wrapped under a key that lives in the phone's hardware keystore and
         * cannot be copied off the device (DataKeyStore, KeystoreAead). Room opens it through
         * SQLCipher. An image of app-private storage therefore contains an encrypted journal and a
         * wrap nothing in the image can open.
         *
         * WHY THE FIRST ROW IS CONDITIONAL. A person whose migration has not succeeded — or whose
         * device would not hold a key at all — still has a plaintext file, and the app still works,
         * because refusing to open would cost them their journal over a problem that recovers
         * itself. For them the encrypted sentence would be false, so it is not shown. The state is
         * read off JournalEncryptionGate rather than assumed; see SettingsUiState.entriesEncrypted.
         *
         * WHY THE PHOTOS CLAUSE IS THERE AND WHY IT IS NOT AN APOLOGY. Entry photos are ordinary
         * JPEGs in filesDir/entry_photos (PhotoStore) and nothing in this work touched them. Saying
         * "encrypted" over a journal whose pictures are sitting in the open would be exactly the
         * inference this row exists to stop, so the exception is named in the same breath as the
         * claim — one clause, flat, no warning, no promise about when.
         *
         * WHAT IS DELIBERATELY NOT SAID. Nothing about exports. A backup, a CSV or a PDF is a plain
         * file the person asked for and put where they chose; folding it into a sentence about what
         * the app does to its own storage would either overclaim or turn a settings row into a
         * lecture. The export rows say it where it belongs.
         *
         * WHY THE PIN ROW NO LONGER CLAIMS ANYTHING ABOUT THE FILE. It says what the PIN does — it
         * guards the screen — and then the thing a person actually needs to know, which is that
         * forgetting it does not lose their entries. The key is held by the phone, not made from
         * the PIN.
         *
         * WHAT COMES NEXT, AND WHAT THIS ROW WILL HAVE TO SAY THEN. Issue #109 also specifies a PIN
         * wrap and a written-down recovery code, at which point the key stops being available to the
         * app without the person, and the copy becomes the issue's wording under A: "Your entries
         * are locked with a key made from this PIN... If you forget the PIN, only your recovery code
         * opens them." That sentence is FALSE TODAY and must not be written here until the wrap it
         * describes is actually armed — and arming it is a decision about reminders and lost PINs
         * that belongs to the maintainer, not to whoever next edits this file. Rewrite these
         * sentences when that lands; do not quietly shorten either of them back to "On".
         */
        ListItem(
            headlineContent = { Text("Your entries on this device") },
            supportingContent = {
                Text(
                    if (state.entriesEncrypted) {
                        "Encrypted with a key only this phone holds, so copying its storage does " +
                            "not read them. Photos attached to entries are not covered."
                    } else {
                        // The honest sentence for a device where the migration has not succeeded,
                        // or where the keystore would not hold a key. The app works; the claim
                        // above would be false, so it is not made.
                        "Not encrypted on this device. Daymark tries again each time you open it."
                    },
                )
            },
        )
        ListItem(
            headlineContent = { Text("App lock (PIN)") },
            supportingContent = {
                Text(
                    "The PIN guards the screen. It is not what your entries are encrypted with, " +
                        "so forgetting it does not lose them.",
                )
            },
            trailingContent = {
                Switch(
                    checked = state.lockEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) showPinDialog = true else viewModel.disableLock()
                    },
                )
            },
        )
        if (state.lockEnabled) {
            ListItem(
                headlineContent = { Text("Unlock with biometrics") },
                supportingContent = { Text("Confirm your fingerprint/face to turn this on") },
                trailingContent = {
                    Switch(
                        checked = state.biometricEnabled,
                        onCheckedChange = { enable ->
                            if (!enable) {
                                viewModel.setBiometricEnabled(false)
                            } else if (activity == null) {
                                onShowMessage("Biometrics unavailable")
                            } else if (!com.daymark.app.security.BiometricHelper.canAuthenticate(activity)) {
                                onShowMessage("No biometrics enrolled on this device")
                            } else {
                                // Only enable after a successful biometric check, so we know it works.
                                com.daymark.app.security.BiometricHelper.prompt(
                                    activity = activity,
                                    onSuccess = { viewModel.setBiometricEnabled(true) },
                                    onError = { onShowMessage("Biometric check failed — not enabled") },
                                )
                            }
                        },
                    )
                },
            )
            ListItem(
                headlineContent = { Text("Auto-lock") },
                supportingContent = { Text(autoLockLabel(state.autoLockTimeoutMinutes)) },
                trailingContent = {
                    androidx.compose.foundation.layout.Box {
                        TextButton(onClick = { showAutoLockMenu = true }) {
                            Text(autoLockLabel(state.autoLockTimeoutMinutes))
                        }
                        DropdownMenu(
                            expanded = showAutoLockMenu,
                            onDismissRequest = { showAutoLockMenu = false },
                        ) {
                            AUTO_LOCK_OPTIONS.forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text(autoLockLabel(minutes)) },
                                    onClick = {
                                        viewModel.setAutoLockTimeout(minutes)
                                        showAutoLockMenu = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }

        Divider()
        SectionHeader("Data")
        ListItem(
            headlineContent = { Text("Manage activities") },
            modifier = Modifier.clickable { onManageActivities() },
        )
        ListItem(
            headlineContent = { Text("Goals") },
            supportingContent = { Text("Weekly habit goals") },
            modifier = Modifier.clickable { onManageGoals() },
        )
        ListItem(
            headlineContent = { Text("Export backup") },
            supportingContent = { Text("Unencrypted JSON — keep it somewhere safe") },
            modifier = Modifier.clickable {
                viewModel.prepareForFilePicker(); exportLauncher.launch("daymark-backup.json")
            },
        )
        ListItem(
            headlineContent = { Text("Restore backup") },
            supportingContent = { Text("Replace or merge from a JSON file") },
            modifier = Modifier.clickable {
                viewModel.prepareForFilePicker(); importLauncher.launch(arrayOf("application/json", "text/*"))
            },
        )
        ListItem(
            headlineContent = { Text("Export as CSV") },
            supportingContent = { Text("Unencrypted spreadsheet of all entries") },
            modifier = Modifier.clickable {
                viewModel.prepareForFilePicker(); csvLauncher.launch("daymark-entries.csv")
            },
        )
        ListItem(
            headlineContent = { Text("Export PDF for therapist") },
            supportingContent = { Text("A printable mood report with an authenticity stamp") },
            modifier = Modifier.clickable { showPdfDialog = true },
        )

        Divider()
        SectionHeader("Appearance")
        ListItem(
            headlineContent = { Text("Customize moods") },
            supportingContent = { Text("Rename and recolor the five mood levels") },
            modifier = Modifier.clickable { onCustomizeMoods() },
        )
        ListItem(
            headlineContent = { Text("Dynamic color") },
            supportingContent = { Text("Use wallpaper-based colors (Android 12+)") },
            trailingContent = {
                Switch(checked = state.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
            },
        )

        Divider()
        SectionHeader("About")
        ListItem(
            headlineContent = { Text("Daymark") },
            supportingContent = { Text("Open-source mood tracker · all data stays on your device") },
        )

        // DEBUG BUILDS ONLY, and this is one of three checks rather than the only one.
        //
        // `BuildConfig.DEBUG` is false in every release variant, so this whole section is dead code
        // the minifier drops; the route it opens is registered behind the same flag in
        // DaymarkAppScaffold, and DebugTimingScreen re-checks it on entry. Removing any one of the
        // three still leaves the screen unreachable in a release build, which is the point of there
        // being three: this is the one surface in the app that lays the decision engine's whole
        // state out at once.
        if (BuildConfig.DEBUG) {
            Divider()
            SectionHeader("Debug")
            ListItem(
                headlineContent = { Text("Why it asks") },
                supportingContent = {
                    Text("The timing rules, what they read, and what each one would do right now")
                },
                modifier = Modifier.clickable { onOpenTimingDebug() },
            )
        }
    }

    if (showPinDialog) {
        PinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin ->
                viewModel.setPin(pin)
                showPinDialog = false
            },
        )
    }

    if (showPdfDialog) {
        PdfOptionsDialog(
            onDismiss = { showPdfDialog = false },
            onExport = { options ->
                pdfOptions = options
                showPdfDialog = false
                viewModel.prepareForFilePicker()
                pdfLauncher.launch("daymark-report.pdf")
            },
        )
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Restore backup") },
            text = { Text("Replace all current data, or merge the backup's entries alongside what you have?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importFrom(uri, com.daymark.app.backup.BackupManager.ImportMode.MERGE)
                    pendingImport = null
                }) { Text("Merge") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.importFrom(uri, com.daymark.app.backup.BackupManager.ImportMode.REPLACE)
                    pendingImport = null
                }) { Text("Replace all") }
            },
        )
    }
}

/**
 * The two length rules in this dialog used to be the literal `3..8`, written out here and again in
 * the onboarding step, connected to nothing. Both are now [com.daymark.app.security.PinPolicy], and
 * `PinManager.setChosenPin` refuses anything the policy rejects — so this dialog is a courtesy to
 * the person, not the enforcement.
 */
@Composable
private fun PinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = com.daymark.app.security.PinPolicy.accepts(pin) && pin == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column {
                Text(
                    com.daymark.app.security.PinPolicy.HELP,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (com.daymark.app.security.PinPolicy.stillTypeable(it)) pin = it },
                    label = { Text(com.daymark.app.security.PinPolicy.LABEL) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (com.daymark.app.security.PinPolicy.stillTypeable(it)) confirm = it },
                    label = { Text("Confirm PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(pin) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PdfOptionsDialog(
    onDismiss: () -> Unit,
    onExport: (com.daymark.app.export.PdfExportOptions) -> Unit,
) {
    var days by remember { mutableStateOf(90) } // 0 = all time
    var notes by remember { mutableStateOf(true) }
    var charts by remember { mutableStateOf(true) }
    var journal by remember { mutableStateOf(false) }
    val ranges = listOf(30 to "Last 30 days", 90 to "Last 90 days", 365 to "Last 12 months", 0 to "All time")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export PDF report") },
        text = {
            Column {
                Text("Date range", style = MaterialTheme.typography.labelLarge)
                ranges.forEach { (d, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { days = d },
                    ) {
                        RadioButton(selected = days == d, onClick = { days = d })
                        Text(label)
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("Include notes", notes) { notes = it }
                ToggleRow("Include charts", charts) { charts = it }
                ToggleRow("Include all journal entries in range", journal) { journal = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val now = System.currentTimeMillis()
                val from = if (days == 0) 0L else now - days.toLong() * 86_400_000L
                val label = ranges.first { it.first == days }.second
                onExport(
                    com.daymark.app.export.PdfExportOptions(
                        fromMillis = from,
                        toMillis = now,
                        rangeLabel = label,
                        includeNotes = notes,
                        includeCharts = charts,
                        includeInTheirWords = journal,
                        includeAllJournalInRange = journal,
                    ),
                )
            }) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp),
    )
}

private val AUTO_LOCK_OPTIONS = listOf(0, 1, 5, 15)

private fun autoLockLabel(minutes: Int): String = when (minutes) {
    0 -> "Immediately"
    1 -> "After 1 minute"
    else -> "After $minutes minutes"
}
