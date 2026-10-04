package com.daymark.app.sync

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.synccrypto.ClinicianCeremony.EndReason
import com.daymark.synccrypto.ClinicianCeremony.Phase
import com.daymark.synccrypto.ClinicianKeys

/**
 * Settings → Sync with your server → Clinicians (#174): the clinicians this phone has added, and for
 * one at a time, their invitation from link to approval. The design is on #174 and the words are
 * [ClinicianWords].
 *
 * WHAT THE SCREEN KEEPS TRUE:
 *  - The sign-in key is shown once, in monospace, with Copy only, and the clip is marked sensitive.
 *  - The link has Share and Copy, and the share carries the link and nothing else.
 *  - The code is large, keeps its dash, and is never selectable, copyable or shared.
 *  - While the key or the code is on screen the window is secure: no screenshot, no recents thumbnail.
 *  - "Check for a reply" is the only read, and only a tap makes it.
 *  - Plain ink throughout: no colour of its own, and nothing drawn as a success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CliniciansScreen(
    onBack: () -> Unit,
    viewModel: CliniciansViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back: () -> Unit = {
        when {
            state.added != null -> viewModel.dropShownToken()
            state.open != null -> viewModel.close()
            else -> onBack()
        }
    }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.open?.name ?: ClinicianWords.TITLE) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            val added = state.added
            val open = state.open
            when {
                state.loading -> Unit
                added != null -> SignInKey(added, onGiven = viewModel::tokenGiven)
                open != null -> Invitation(open, viewModel)
                else -> ClinicianList(state, viewModel)
            }
        }
    }
}

@Composable
private fun ClinicianList(state: CliniciansUiState, viewModel: CliniciansViewModel) {
    if (state.unreadable) {
        Text(ClinicianWords.UNREADABLE, style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (state.clinicians.isEmpty()) {
        Text(ClinicianWords.NONE_YET, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.clinicians.forEach { row ->
        ListItem(
            headlineContent = { Text(row.name) },
            modifier = Modifier.clickable(enabled = state.ready) { viewModel.open(row.id) },
        )
    }
    if (!state.ready) {
        Text(ClinicianWords.ROW_LOCKED, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        return
    }
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    var name by remember { mutableStateOf("") }
    Text(ClinicianWords.ADD, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; viewModel.clearNameRefused() },
        label = { Text(ClinicianWords.NAME_LABEL) },
        supportingText = { Text(if (state.nameRefused) ClinicianWords.NAME_REFUSED else ClinicianWords.NAME_HINT) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
    Button(
        onClick = { viewModel.add(name); name = "" },
        enabled = name.isNotBlank(),
        modifier = Modifier.padding(top = 8.dp),
    ) { Text(ClinicianWords.ADD) }
}

/** The sign-in key, once. */
@Composable
private fun SignInKey(added: NewClinician, onGiven: () -> Unit) {
    SecureWhileShown()
    val context = LocalContext.current
    Text(ClinicianWords.TOKEN_TITLE, style = MaterialTheme.typography.titleLarge)
    Text(ClinicianWords.TOKEN_BODY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        added.inboxToken,
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 16.dp),
    )
    OutlinedButton(onClick = { copy(context, added.inboxToken, sensitive = true) }) { Text(ClinicianWords.COPY) }
    Text(
        ClinicianWords.threeThings(added.name),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )
    Button(onClick = onGiven, modifier = Modifier.padding(top = 16.dp)) { Text(ClinicianWords.TOKEN_DONE) }
}

/** One clinician's invitation, as far as it has gone. */
@Composable
private fun Invitation(open: OpenClinician, viewModel: CliniciansViewModel) {
    val context = LocalContext.current
    var confirmStop by remember { mutableStateOf(false) }
    val idle = !open.busy
    val name = open.name

    when (val phase = open.phase) {
        null -> if (idle) {
            OutlinedButton(onClick = viewModel::lookAgain) { Text(ClinicianWords.LOOK_AGAIN) }
        }
        Phase.Idle -> {
            Text(ClinicianWords.TWO_CHANNELS, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = viewModel::startInvitation, enabled = idle, modifier = Modifier.padding(top = 16.dp)) {
                Text(ClinicianWords.INVITE)
            }
        }
        is Phase.Invited -> {
            val link = phase.invite.link
            if (link != null) {
                Text(ClinicianWords.LINK_LABEL, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(link, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                Row {
                    OutlinedButton(onClick = { share(context, link) }) { Text(ClinicianWords.SHARE) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { copy(context, link, sensitive = true) }) { Text(ClinicianWords.COPY) }
                }
            } else {
                Text(ClinicianWords.LINK_GONE, style = MaterialTheme.typography.bodyMedium)
            }
            Text(ClinicianWords.TWO_CHANNELS, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
            Button(onClick = viewModel::showCode, enabled = idle, modifier = Modifier.padding(top = 16.dp)) {
                Text(ClinicianWords.SHOW_CODE)
            }
            Tries(phase.attemptsLeft, phase.failCount)
            StopButton(idle) { confirmStop = true }
        }
        is Phase.Waiting -> {
            SecureWhileShown()
            Text(ClinicianWords.CODE_LABEL, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(phase.code.display, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp))
            Text(ClinicianWords.CODE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ClinicianWords.WAITING, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
            RunButtons(idle, viewModel)
            Tries(phase.attemptsLeft, phase.failCount)
            StopButton(idle) { confirmStop = true }
        }
        is Phase.Resumed -> {
            Text(ClinicianWords.WAITING_RESUMED, style = MaterialTheme.typography.bodyMedium)
            RunButtons(idle, viewModel)
            Tries(phase.attemptsLeft, phase.failCount)
            StopButton(idle) { confirmStop = true }
        }
        is Phase.Mismatch -> {
            Text(ClinicianWords.MISMATCH_TITLE, style = MaterialTheme.typography.titleMedium)
            Tries(phase.attemptsLeft, phase.failCount)
            AlertDialog(
                onDismissRequest = { if (idle) viewModel.keepOpen() },
                title = { Text(ClinicianWords.MISMATCH_TITLE) },
                text = { Text(ClinicianWords.mismatchBody(name)) },
                confirmButton = {
                    Column {
                        TextButton(onClick = viewModel::keepOpen, enabled = idle) { Text(ClinicianWords.KEEP_OPEN) }
                        TextButton(onClick = viewModel::newCode, enabled = idle) { Text(ClinicianWords.NEW_CODE) }
                        TextButton(onClick = viewModel::stop, enabled = idle) { Text(ClinicianWords.STOP) }
                    }
                },
            )
        }
        is Phase.Answered -> {
            when (val keys = phase.keys) {
                is ClinicianKeys.Check.OtherPerson -> {
                    Text(ClinicianWords.NOT_APPROVED, style = MaterialTheme.typography.titleMedium)
                    Text(ClinicianWords.sameKeysAsOther(name, keys.displayName), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    StopButton(idle) { confirmStop = true }
                }
                ClinicianKeys.Check.Unreadable -> {
                    Text(ClinicianWords.NOT_RECORDED, style = MaterialTheme.typography.bodyMedium)
                    StopButton(idle) { confirmStop = true }
                }
                ClinicianKeys.Check.Supersedes -> {
                    Text(ClinicianWords.replaceTitle(name), style = MaterialTheme.typography.titleMedium)
                    TheirName(phase.offer.displayName)
                    ClinicianWords.replaceBody(name).forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (open.words != ClinicianWords.CONTRADICTED) {
                        Button(onClick = viewModel::approve, enabled = idle, modifier = Modifier.padding(top = 16.dp)) {
                            Text(ClinicianWords.REPLACE_APPROVE)
                        }
                    }
                    TextButton(onClick = viewModel::notNow, enabled = idle) { Text(ClinicianWords.NOT_NOW) }
                }
                ClinicianKeys.Check.First, ClinicianKeys.Check.Unchanged -> {
                    Text(ClinicianWords.ANSWERED_TITLE, style = MaterialTheme.typography.titleMedium)
                    Text(ClinicianWords.ANSWERED_BODY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    TheirName(phase.offer.displayName)
                    if (open.words != ClinicianWords.CONTRADICTED) {
                        Button(onClick = viewModel::approve, enabled = idle, modifier = Modifier.padding(top = 16.dp)) {
                            Text(ClinicianWords.APPROVE)
                        }
                    }
                    StopButton(idle) { confirmStop = true }
                }
            }
        }
        is Phase.Approved -> {
            Text(ClinicianWords.APPROVED_TITLE, style = MaterialTheme.typography.titleMedium)
            Text(ClinicianWords.APPROVED_BODY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            if (phase.replaced) {
                Text(ClinicianWords.REPLACED_BODY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
            if (phase.run != null) {
                OutlinedButton(onClick = viewModel::takeBack, enabled = idle, modifier = Modifier.padding(top = 16.dp)) {
                    Text(ClinicianWords.TAKE_BACK)
                }
            }
            StopButton(idle) { confirmStop = true }
        }
        is Phase.Ended -> {
            when (phase.reason) {
                EndReason.CAPPED -> {
                    Text(ClinicianWords.CAPPED_TITLE, style = MaterialTheme.typography.titleMedium)
                    Text(ClinicianWords.CAPPED_BODY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
                EndReason.REPORTED -> Text(ClinicianWords.STOPPED_BODY, style = MaterialTheme.typography.bodyMedium)
                EndReason.EXPIRED -> Text(ClinicianWords.EXPIRED_BODY, style = MaterialTheme.typography.bodyMedium)
                EndReason.CANCELLED -> Text(ClinicianWords.ENDED_CANCELLED, style = MaterialTheme.typography.bodyMedium)
                EndReason.SUPERSEDED -> Text(ClinicianWords.ENDED_SUPERSEDED, style = MaterialTheme.typography.bodyMedium)
            }
            when (phase.reason) {
                EndReason.CANCELLED, EndReason.SUPERSEDED ->
                    OutlinedButton(onClick = viewModel::lookAgain, enabled = idle, modifier = Modifier.padding(top = 16.dp)) {
                        Text(ClinicianWords.LOOK_AGAIN)
                    }
                else ->
                    Button(onClick = viewModel::startInvitation, enabled = idle, modifier = Modifier.padding(top = 16.dp)) {
                        Text(ClinicianWords.FRESH)
                    }
            }
        }
    }

    open.words?.let { words ->
        Text(words, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(ClinicianWords.STOP) },
            text = { Text(ClinicianWords.STOP_BODY) },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; viewModel.stop() }) { Text(ClinicianWords.STOP) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(ClinicianWords.KEEP) } },
        )
    }
}

@Composable
private fun RunButtons(idle: Boolean, viewModel: CliniciansViewModel) {
    Button(onClick = viewModel::checkForReply, enabled = idle, modifier = Modifier.padding(top = 16.dp)) {
        Text(ClinicianWords.CHECK)
    }
    OutlinedButton(onClick = viewModel::newCode, enabled = idle, modifier = Modifier.padding(top = 8.dp)) {
        Text(ClinicianWords.NEW_CODE)
    }
    Text(
        ClinicianWords.NEW_CODE_HINT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun TheirName(entered: String) {
    Text(
        ClinicianWords.NAME_THEY_ENTERED,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )
    Text(entered, style = MaterialTheme.typography.bodyLarge)
}

/** The attempts line, and any wrong links tried: counts, never warnings. */
@Composable
private fun Tries(attemptsLeft: Int, failCount: Long) {
    Text(
        ClinicianWords.attemptsLeft(attemptsLeft),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )
    if (failCount > 0) {
        Text(ClinicianWords.wrongLinkTried(failCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ClinicianWords.WRONG_LINK_ADVICE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StopButton(idle: Boolean, onStop: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    HorizontalDivider()
    TextButton(onClick = onStop, enabled = idle, modifier = Modifier.padding(top = 8.dp)) { Text(ClinicianWords.STOP) }
}

/**
 * Keeps the window out of screenshots and the recents thumbnail while it is composed, and puts the
 * window back as it found it: an app lock that had already set the flag keeps it.
 */
@Composable
private fun SecureWhileShown() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val already = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!already) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Copies [text]; [sensitive] keeps it out of the clipboard's on-screen preview. */
private fun copy(context: Context, text: String, sensitive: Boolean) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("", text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    clipboard.setPrimaryClip(clip)
}

/** Shares the link and nothing else. */
private fun share(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
    val chooser = Intent.createChooser(send, null)
    if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
