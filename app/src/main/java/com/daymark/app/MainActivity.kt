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
import com.daymark.app.security.AutoLockController
import com.daymark.app.security.PinManager
import com.daymark.app.ui.DaymarkAppScaffold
import com.daymark.app.ui.lock.LockScreen
import com.daymark.app.ui.onboarding.OnboardingScreen
import com.daymark.app.ui.theme.DaymarkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Content-based snapshot of the prefs that drive the theme/lock, so recomposition is reliable. */
private data class ThemePrefs(
    val dynamicColor: Boolean,
    val moodColorOverrides: Map<Int, Int>,
    val moodLabelOverrides: Map<Int, String>,
    val lockEnabled: Boolean,
)

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var pinManager: PinManager
    @Inject lateinit var autoLock: AutoLockController
    @Inject lateinit var moodCustomization: com.daymark.app.data.MoodCustomizationStore

    companion object {
        const val EXTRA_PREFILL_MOOD = "prefill_mood"
        const val EXTRA_OPEN_EDITOR = "open_editor"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialMood = intent?.getIntExtra(EXTRA_PREFILL_MOOD, -1) ?: -1
        val openEditor = intent?.getBooleanExtra(EXTRA_OPEN_EDITOR, false) ?: false
        setContent {
            // Map the prefs-change flow to a *content-based* snapshot. SettingsRepository.changes()
            // re-emits the same SharedPreferences instance, which collectAsState would dedup — so a
            // value object (with structural equality) is what actually drives recomposition when a
            // setting or a mood override changes.
            fun snapshot() = ThemePrefs(
                dynamicColor = settings.dynamicColor,
                moodColorOverrides = moodCustomization.colors(),
                moodLabelOverrides = moodCustomization.labels(),
                lockEnabled = settings.lockEnabled && pinManager.isPinSet,
            )
            val themePrefs by remember { settings.changes().map { snapshot() } }
                .collectAsState(initial = snapshot())
            val dynamicColor = themePrefs.dynamicColor
            val moodColorOverrides = themePrefs.moodColorOverrides
            val moodLabelOverrides = themePrefs.moodLabelOverrides
            // Only lock when a PIN actually exists (avoids a lock-out with no way in).
            val lockEnabled = themePrefs.lockEnabled

            // Keep private content out of screenshots and the recents thumbnail whenever locking
            // is on. Reactive (not onCreate-only) so toggling the lock applies/clears immediately.
            DisposableEffect(lockEnabled) {
                if (lockEnabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                onDispose { }
            }

            var unlocked by remember { mutableStateOf(false) }
            // Local state so finishing the wizard advances immediately (the prefs-change
            // flow re-emits the same instance, which doesn't trigger recomposition).
            var onboarded by remember { mutableStateOf(settings.onboardingComplete) }

            // Re-lock when the app returns from the background, but only after the user's chosen
            // grace period (auto-lock timeout). A file-picker round trip is exempted via the skip.
            if (lockEnabled) {
                DisposableEffect(Unit) {
                    val owner = ProcessLifecycleOwner.get()
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_STOP -> autoLock.onBackgrounded()
                            Lifecycle.Event.ON_START -> {
                                if (autoLock.shouldLockOnForeground(settings.autoLockTimeoutMinutes)) {
                                    unlocked = false
                                }
                            }
                            else -> {}
                        }
                    }
                    owner.lifecycle.addObserver(observer)
                    onDispose { owner.lifecycle.removeObserver(observer) }
                }
            }

            DaymarkTheme(
                dynamicColor = dynamicColor,
                moodColorOverrides = moodColorOverrides,
                moodLabelOverrides = moodLabelOverrides,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when {
                        !onboarded -> OnboardingScreen(onFinish = { unlocked = true; onboarded = true })
                        lockEnabled && !unlocked -> LockScreen(onUnlocked = { unlocked = true })
                        else -> DaymarkAppScaffold(initialMood = initialMood, openEditor = openEditor)
                    }
                }
            }
        }
    }
}
