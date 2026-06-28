package com.daymark.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.daymark.app.security.BiometricHelper

@Composable
fun LockScreen(
    onUnlocked: () -> Unit,
    viewModel: LockViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    val activity = context as? FragmentActivity

    // Offer biometric immediately if enabled and available.
    LaunchedEffect(Unit) {
        if (viewModel.biometricEnabled && activity != null && BiometricHelper.canAuthenticate(activity)) {
            BiometricHelper.prompt(activity, onSuccess = onUnlocked, onError = { /* fall back to PIN */ })
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            isError = error,
            supportingText = if (error) ({ Text("Incorrect PIN") }) else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Button(
            onClick = {
                if (viewModel.isLockedOut()) {
                    error = true
                } else if (viewModel.verify(pin)) {
                    onUnlocked()
                } else {
                    error = true
                    pin = ""
                }
            },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text("Unlock")
        }
        if (error && viewModel.isLockedOut()) {
            Text(
                "Too many attempts. Try again in ${viewModel.lockRemainingSeconds()}s.",
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
