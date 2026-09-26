package com.daymark.app.sync

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.util.DateUtils
import com.daymark.synccrypto.PairingVerdict
import com.daymark.synccrypto.PhoneWords
import java.time.LocalDate

/**
 * Settings → Sync with your server (#432), in the `sync` flavour only. One step at a time: the server's
 * address and the pairing code (or the pairing text pasted into the address); the six words to compare
 * with the web while the phone waits for the confirmation there; the sync passphrase; and then Send a
 * copy now, with the time the server last took one.
 *
 * Every sentence the phone says about the server is one of [PhoneWords], shown in the plain ink: a
 * refusal says what did and did not happen, and nothing here is coloured as a success. The code and the
 * passphrase live in this screen's memory only, never in its saved state, and the passphrase field is
 * cleared the moment it is used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSyncScreen(
    onBack: () -> Unit,
    viewModel: ServerSyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var confirmForget by remember { mutableStateOf(false) }

    // A code that went through is spent: it leaves the field once its words are on the screen.
    LaunchedEffect(state.stage) {
        if (state.stage == ServerSyncStage.COMPARING) code = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync with your server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            Text(
                text = "Copies of your journal are encrypted on this phone before they leave it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            when (state.stage) {
                ServerSyncStage.LOADING -> Unit
                ServerSyncStage.NOT_PAIRED, ServerSyncStage.REDEEMING -> PairingFields(
                    address = address,
                    code = code,
                    busy = state.stage == ServerSyncStage.REDEEMING,
                    onAddress = { address = it; viewModel.clearMessage() },
                    onCode = { code = it; viewModel.clearMessage() },
                    onPair = { viewModel.pair(address, code) },
                )
                ServerSyncStage.COMPARING -> WordsToCompare(words = state.words, onCancel = viewModel::cancelPairing)
                ServerSyncStage.NEEDS_PASSPHRASE, ServerSyncStage.OPENING -> {
                    ServerAddress(state.address)
                    PassphraseField(
                        passphrase = passphrase,
                        busy = state.stage == ServerSyncStage.OPENING,
                        onPassphrase = { passphrase = it; viewModel.clearMessage() },
                        onOpen = {
                            viewModel.unlock(passphrase)
                            passphrase = ""
                        },
                    )
                }
                ServerSyncStage.READY, ServerSyncStage.SENDING -> {
                    ServerAddress(state.address)
                    Button(
                        onClick = viewModel::sendNow,
                        enabled = state.stage == ServerSyncStage.READY,
                    ) {
                        Text(if (state.stage == ServerSyncStage.SENDING) "Sending a copy…" else "Send a copy now")
                    }
                }
            }

            state.message?.let { words ->
                Spacer(Modifier.height(16.dp))
                Text(words, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }

            val paired = state.stage == ServerSyncStage.NEEDS_PASSPHRASE || state.stage == ServerSyncStage.OPENING ||
                state.stage == ServerSyncStage.READY || state.stage == ServerSyncStage.SENDING
            if (paired) {
                state.lastSentAt?.let { at ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        PhoneWords.sent(timeOf(at)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.lineage?.let { name ->
                    Text(
                        PhoneWords.named(name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                TextButton(
                    onClick = { confirmForget = true },
                    enabled = state.stage == ServerSyncStage.NEEDS_PASSPHRASE || state.stage == ServerSyncStage.READY,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Forget this server")
                }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this server?") },
            text = {
                Text(
                    "This phone forgets the server and its key, and sends no more copies. The web lists " +
                        "this phone until you remove it there. Copies already sent stay on the server.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    viewModel.forget()
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep") } },
        )
    }
}

/** The address and the code, or the pairing text in the address, which makes the code field unneeded. */
@Composable
private fun PairingFields(
    address: String,
    code: String,
    busy: Boolean,
    onAddress: (String) -> Unit,
    onCode: (String) -> Unit,
    onPair: () -> Unit,
) {
    val pastedText = address.trimStart().startsWith(PairingVerdict.QR_SCHEME)
    Text(
        "Make a pairing code on the web. Type the server's address and the code it shows, or paste the " +
            "pairing text into the address.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = address,
        onValueChange = onAddress,
        label = { Text("Server address") },
        placeholder = { Text("https://") },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    )
    if (!pastedText) {
        OutlinedTextField(
            value = code,
            onValueChange = onCode,
            label = { Text("Pairing code") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
    Button(
        onClick = onPair,
        enabled = !busy && address.isNotBlank() && (pastedText || code.isNotBlank()),
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Text(if (busy) "Pairing…" else "Pair")
    }
}

/** The six words, numbered, while the phone waits for the web's confirmation. */
@Composable
private fun WordsToCompare(words: List<String>, onCancel: () -> Unit) {
    words.forEachIndexed { index, word ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )
            Text(word, style = MaterialTheme.typography.headlineSmall)
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(PhoneWords.COMPARE_THE_WORDS, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        PhoneWords.WAITING,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
}

/** The passphrase that opens the key on the server: Argon2id runs over it, which takes seconds. */
@Composable
private fun PassphraseField(
    passphrase: String,
    busy: Boolean,
    onPassphrase: (String) -> Unit,
    onOpen: () -> Unit,
) {
    OutlinedTextField(
        value = passphrase,
        onValueChange = onPassphrase,
        label = { Text("Sync passphrase") },
        supportingText = { Text(PhoneWords.OPENING_TAKES_TIME) },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    )
    Button(
        onClick = onOpen,
        enabled = !busy && passphrase.isNotEmpty(),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Text(if (busy) "Opening the key…" else "Open the key")
    }
}

/** Which server this phone is paired with. */
@Composable
private fun ServerAddress(address: String?) {
    if (address == null) return
    Text("Your server", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(address, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp))
}

/** A time as the person reads times: the time alone today, the date and the time before that. */
private fun timeOf(millis: Long): String =
    if (DateUtils.toLocalDate(millis) == LocalDate.now()) {
        DateUtils.formatTime(millis)
    } else {
        "${DateUtils.formatDate(millis)}, ${DateUtils.formatTime(millis)}"
    }
