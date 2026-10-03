package com.daymark.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.daymark.app.MainActivity
import com.daymark.app.data.dao.TrackerDao
import com.daymark.app.data.entity.Tracker
import com.daymark.app.notifications.TrackerCheckInReceiver
import com.daymark.app.notifications.TrackerCheckInScheduler
import com.daymark.app.ui.theme.Hairline
import com.daymark.app.ui.theme.HairlineDark
import com.daymark.app.ui.theme.InkSoft
import com.daymark.app.ui.theme.InkSoftDark
import com.daymark.app.ui.theme.InkText
import com.daymark.app.ui.theme.InkTextDark
import com.daymark.app.ui.theme.PaperBg
import com.daymark.app.ui.theme.PaperBgDark
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The trackers' quick-log widget: every active tracker, one tap from the home screen. Tapping a
 * tracker opens it to log; a yes/no tracker logs straight from its Yes and No. It never asks
 * anything and never shows a value or a count, so a phone left on a table says nothing about how
 * anyone has been.
 */
class TrackerWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun trackerDao(): TrackerDao
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // A journal this phone cannot open yet (locked, or its key lost) leaves the widget with
        // nothing to list; it then only opens the app, which says what happened.
        val trackers = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
                .trackerDao().getAll().filter { !it.archived }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        }.getOrDefault(emptyList())
        provideContent { Content(context, trackers) }
    }

    @Composable
    private fun Content(context: Context, trackers: List<Tracker>) {
        val ink = ColorProvider(day = InkText, night = InkTextDark)
        val soft = ColorProvider(day = InkSoft, night = InkSoftDark)
        val chip = ColorProvider(day = Hairline, night = HairlineDark)
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(day = PaperBg, night = PaperBgDark))
                .padding(12.dp),
        ) {
            Text("Log a tracker", style = TextStyle(fontSize = 15.sp, color = ink))
            Spacer(GlanceModifier.height(8.dp))
            if (trackers.isEmpty()) {
                Text(
                    "Open Daymark to add or see your trackers.",
                    style = TextStyle(fontSize = 13.sp, color = soft),
                    modifier = GlanceModifier.clickable(actionStartActivity(open(context, -1L))),
                )
            } else {
                LazyColumn {
                    items(trackers, itemId = { it.id }) { tracker ->
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tracker.name,
                                style = TextStyle(fontSize = 14.sp, color = ink),
                                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(open(context, tracker.id))),
                            )
                            if (tracker.type == Tracker.BOOLEAN) {
                                Answer(context, tracker.id, 1.0, "Yes", chip, ink)
                                Spacer(GlanceModifier.width(6.dp))
                                Answer(context, tracker.id, 0.0, "No", chip, ink)
                            } else {
                                Answer(context, tracker.id, null, "Log", chip, ink)
                            }
                        }
                    }
                }
            }
        }
    }

    /** One button: logs [value] for a yes/no tracker, or opens the tracker when [value] is null. */
    @Composable
    private fun Answer(
        context: Context,
        trackerId: Long,
        value: Double?,
        label: String,
        chip: androidx.glance.unit.ColorProvider,
        ink: androidx.glance.unit.ColorProvider,
    ) {
        val action = if (value == null) {
            actionStartActivity(open(context, trackerId))
        } else {
            actionSendBroadcast(
                Intent(context, TrackerCheckInReceiver::class.java)
                    .setAction(TrackerCheckInReceiver.ACTION_LOG)
                    .putExtra(TrackerCheckInScheduler.EXTRA_TRACKER_ID, trackerId)
                    .putExtra(TrackerCheckInScheduler.EXTRA_VALUE, value),
            )
        }
        Box(
            modifier = GlanceModifier.background(chip).padding(horizontal = 12.dp, vertical = 6.dp).clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TextStyle(fontSize = 13.sp, color = ink))
        }
    }

    private fun open(context: Context, trackerId: Long): Intent =
        Intent(context, MainActivity::class.java).apply {
            if (trackerId > 0L) putExtra(MainActivity.EXTRA_OPEN_TRACKER, trackerId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    companion object {
        /** Redraws every placed tracker widget, after a tracker is added, changed or archived. */
        suspend fun redraw(context: Context) {
            runCatching { TrackerWidget().updateAll(context) }
        }
    }
}

class TrackerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrackerWidget()
}
