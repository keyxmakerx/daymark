package com.daymark.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.daymark.app.data.SettingsRepository
import com.daymark.app.security.PinManager
import com.daymark.app.ui.DaymarkAppScaffold
import com.daymark.app.ui.lock.LockScreen
import com.daymark.app.ui.onboarding.OnboardingScreen
import com.daymark.app.ui.theme.DaymarkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var pinManager: PinManager

    companion object {
        const val EXTRA_PREFILL_MOOD = "prefill_mood"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep private content out of screenshots and the recents thumbnail when locking is on.
        if (settings.lockEnabled && pinManager.isPinSet) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val initialMood = intent?.getIntExtra(EXTRA_PREFILL_MOOD, -1) ?: -1
        setContent {
            val prefs by settings.changes().collectAsState(initial = null)
            // Re-read on any preference change.
            val dynamicColor = prefs?.let { settings.dynamicColor } ?: settings.dynamicColor
            // Only lock when a PIN actually exists (avoids a lock-out with no way in).
            val lockEnabled = (prefs?.let { settings.lockEnabled } ?: settings.lockEnabled) &&
                pinManager.isPinSet

            var unlocked by remember { mutableStateOf(false) }
            // Local state so finishing the wizard advances immediately (the prefs-change
            // flow re-emits the same instance, which doesn't trigger recomposition).
            var onboarded by remember { mutableStateOf(settings.onboardingComplete) }

            // Re-lock whenever the whole app goes to the background.
            if (lockEnabled) {
                DisposableEffect(Unit) {
                    val owner = ProcessLifecycleOwner.get()
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) unlocked = false
                    }
                    owner.lifecycle.addObserver(observer)
                    onDispose { owner.lifecycle.removeObserver(observer) }
                }
            }

            DaymarkTheme(dynamicColor = dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when {
                        !onboarded -> OnboardingScreen(onFinish = { unlocked = true; onboarded = true })
                        lockEnabled && !unlocked -> LockScreen(onUnlocked = { unlocked = true })
                        else -> DaymarkAppScaffold(initialMood = initialMood)
                    }
                }
            }
        }
    }
}
