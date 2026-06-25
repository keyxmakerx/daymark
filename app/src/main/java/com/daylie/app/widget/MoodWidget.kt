package com.daylie.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.daylie.app.MainActivity

/** A quick-log home-screen widget: tap a mood to open a new entry with it preselected. */
class MoodWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content(context) }
    }

    @Composable
    private fun Content(context: Context) {
        // Muted paper mood palette (awful → rad), matching the app theme.
        val moods = listOf(
            1 to (Color(0xFFAE5747) to "Awful"),
            2 to (Color(0xFFC27C46) to "Bad"),
            3 to (Color(0xFFC6A24E) to "Meh"),
            4 to (Color(0xFF8FA268) to "Good"),
            5 to (Color(0xFF5E8A66) to "Rad"),
        )
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(Color(0xFFF4EFE6))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "How are you?",
                style = TextStyle(fontSize = 15.sp, color = ColorProvider(Color(0xFF2A2722))),
            )
            Spacer(GlanceModifier.height(10.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                moods.forEach { (level, pair) ->
                    val (color, label) = pair
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .height(48.dp)
                            .padding(3.dp)
                            .background(color)
                            .clickable(actionStartActivity(launchIntent(context, level))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = TextStyle(fontSize = 11.sp, color = ColorProvider(Color(0xFFFCFAF5))),
                        )
                    }
                }
            }
        }
    }

    private fun launchIntent(context: Context, level: Int): Intent =
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_PREFILL_MOOD, level)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
}
