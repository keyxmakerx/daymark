package com.daymark.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.daymark.app.ui.components.MoodFaceIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var step by remember { mutableIntStateOf(0) }
    val lastStep = 3

    fun finish() {
        viewModel.complete()
        onFinish()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Skip-everything affordance + simple progress dots.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(lastStep + 1) { i ->
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .height(6.dp)
                                .width(if (i == step) 18.dp else 6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (i == step) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                ),
                        )
                    }
                }
                if (step < lastStep) {
                    TextButton(onClick = { finish() }) { Text("Skip") }
                }
            }

            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboardingStep",
                modifier = Modifier.weight(1f),
            ) { s ->
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (s) {
                        0 -> Welcome()
                        1 -> ReminderStep(
                            onEnable = { h, m -> viewModel.enableReminder(h, m); step = 2 },
                            onSkip = { step = 2 },
                        )
                        2 -> LockStep(
                            onSetPin = { pin -> viewModel.setPin(pin); step = 3 },
                            onSkip = { step = 3 },
                        )
                        else -> Done()
                    }
                }
            }

            if (step == 0) {
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
            } else if (step == lastStep) {
                Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Start using Daymark") }
            }
        }
    }
}

@Composable
private fun Welcome() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        MoodFaceIcon(level = 5, size = 80.dp)
        Spacer(16.dp)
        Text("Welcome to Daymark", style = MaterialTheme.typography.headlineMedium)
        Spacer(10.dp)
        Text(
            "Mark how each day feels, note why, and watch patterns emerge over time.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(8.dp)
        Text(
            "Everything stays on your device — no accounts, no cloud, no tracking.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderStep(onEnable: (Int, Int) -> Unit, onSkip: () -> Unit) {
    val tpState = rememberTimePickerState(initialHour = 21, initialMinute = 0, is24Hour = false)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onEnable(tpState.hour, tpState.minute)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("A gentle daily nudge", style = MaterialTheme.typography.headlineSmall)
        Spacer(8.dp)
        Text(
            "Pick a time to be reminded to check in. You can change or turn this off later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(20.dp)
        TimePicker(state = tpState)
        Spacer(16.dp)
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onEnable(tpState.hour, tpState.minute)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enable reminder") }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
    }
}

@Composable
private fun LockStep(onSetPin: (String) -> Unit, onSkip: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length in 4..8 && pin == confirm

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Lock your journal", style = MaterialTheme.typography.headlineSmall)
        Spacer(8.dp)
        Text(
            "Optionally protect Daymark with a PIN (and biometrics, if your device supports it).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(16.dp)
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.all(Char::isDigit) && it.length <= 8) pin = it },
            label = { Text("PIN (4–8 digits)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(8.dp)
        OutlinedTextField(
            value = confirm,
            onValueChange = { if (it.all(Char::isDigit) && it.length <= 8) confirm = it },
            label = { Text("Confirm PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(16.dp)
        Button(onClick = { onSetPin(pin) }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
            Text("Set PIN & continue")
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("No lock for now") }
    }
}

@Composable
private fun Done() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        MoodFaceIcon(level = 4, size = 80.dp)
        Spacer(16.dp)
        Text("You're all set", style = MaterialTheme.typography.headlineMedium)
        Spacer(10.dp)
        Text(
            "Tap the + on Home whenever you want to log how you feel.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Spacer(size: Dp) {
    Spacer(modifier = Modifier.height(size))
}
