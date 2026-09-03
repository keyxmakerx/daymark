package com.daymark.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.daymark.app.security.BiometricHelper
import kotlinx.coroutines.delay

@Composable
fun LockScreen(
    onUnlocked: () -> Unit,
    viewModel: LockViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    // True only while the last attempt was wrong and no lockout is in force; a lockout replaces
    // the "Incorrect PIN" line rather than stacking on top of it.
    var error by remember { mutableStateOf(false) }
    var lockedOut by remember { mutableStateOf(viewModel.isLockedOut()) }
    var secondsLeft by remember {
        mutableIntStateOf(if (viewModel.isLockedOut()) viewModel.lockRemainingSeconds() else 0)
    }

    val activity = context as? FragmentActivity

    // Offer biometric immediately if enabled and available.
    LaunchedEffect(Unit) {
        if (viewModel.biometricEnabled && activity != null && BiometricHelper.canAuthenticate(activity)) {
            BiometricHelper.prompt(activity, onSuccess = onUnlocked, onError = { /* fall back to PIN */ })
        }
    }

    // While locked out, re-read the remaining time once a second so the line on screen counts
    // down; when the lockout lapses, clear it so the next attempt is judged afresh.
    LaunchedEffect(lockedOut) {
        if (!lockedOut) return@LaunchedEffect
        while (viewModel.isLockedOut()) {
            secondsLeft = viewModel.lockRemainingSeconds()
            delay(1_000L)
        }
        secondsLeft = 0
        lockedOut = false
    }

    fun attemptUnlock() {
        if (viewModel.isLockedOut()) {
            error = false
            lockedOut = true
        } else if (viewModel.verify(pin)) {
            onUnlocked()
        } else {
            pin = ""
            // A wrong attempt can be the one that starts a lockout; then the countdown speaks.
            if (viewModel.isLockedOut()) {
                error = false
                lockedOut = true
            } else {
                error = true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp))
        Text("Daymark is locked", style = MaterialTheme.typography.titleLarge)
        Text(
            "Enter your PIN to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        OutlinedTextField(
            value = pin,
            onValueChange = {
                if (it.all(Char::isDigit) && it.length <= 8) { pin = it; error = false }
            },
            label = { Text("PIN") },
            isError = error && !lockedOut,
            supportingText = if (error && !lockedOut) ({ Text("Incorrect PIN") }) else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { attemptUnlock() }),
        )
        Button(
            onClick = { attemptUnlock() },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text("Unlock")
        }
        if (lockedOut) {
            Text(
                "Too many attempts. Try again in ${secondsLeft}s.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (viewModel.biometricEnabled && activity != null) {
            OutlinedButton(
                onClick = {
                    BiometricHelper.prompt(activity, onSuccess = onUnlocked, onError = {})
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Use biometrics")
            }
        }
    }
}
