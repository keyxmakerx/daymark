package com.daymark.app.ui.support

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.ui.components.PaperSurface

/**
 * "Take a moment" — the in-the-moment support menu, offered (never forced) after a low mood.
 * Validates FIRST, leads with an opt-out, and presents a calm, low-stimulation menu the user
 * chooses from. Nothing auto-plays; nothing is cheerful at them. Non-diagnostic general wellness.
 */
@Composable
fun SupportScreen(
    onClose: () -> Unit,
    onBreathe: () -> Unit,
    onCrisis: () -> Unit,
    viewModel: SupportViewModel = hiltViewModel(),
) {
    val activity by viewModel.suggestedActivity.collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Take a moment", style = MaterialTheme.typography.headlineSmall)
            Text(
                "However you're feeling right now is okay. There's nothing you have to do.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "If you'd like, you could:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            // Opt-out is always first.
            Option("Not right now", "Close this and go back.", onClose)
            Option("Breathe for a minute", "A slow, calm breathing pace to follow.", onBreathe)
            Option(
                "Do one small thing",
                activity?.let { "Maybe a little $it — even a few minutes can help." }
                    ?: "Something small and kind for yourself — even a few minutes can help.",
                onClose,
            )
            Option("If things feel like too much", "Crisis resources and someone to talk to.", onCrisis)
        }
    }
}

@Composable
private fun Option(title: String, subtitle: String, onClick: () -> Unit) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
