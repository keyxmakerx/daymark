package com.daymark.app.notifications

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A locked phone says only "Daymark", and nothing on it writes to the journal.
 *
 * A tracker's or a reminder's name ("Urges", "Took meds") is private, and a Yes from the lock screen
 * or the home screen would log past the app lock. So every notification Daymark builds hides its words
 * behind a public version through `NotificationPrivacy.lockedAway`, every notification button needs
 * the phone unlocked through `NotificationPrivacy.unlockedAction`, and the trackers widget reads the
 * app lock before it names anything.
 *
 * ## How it reads
 *
 * Every production Kotlin file, through [codeOnly], so a comment cannot fake a call. Each check is
 * shown a planted example made from the real files.
 *
 * ## What it will not catch
 *
 * A notification built some way that never names `NotificationCompat.Builder` or `Notification.Builder`
 * (a `RemoteViews` template, a library), or a public version whose words are a tracker's name. The
 * public version is built in one place, `NotificationPrivacy.kt`, from `R.string.app_name` only.
 */
class NotificationPrivacySourceTest {

    // repoFile finds files only, so the tree is reached from one file known to be in it.
    private val main: File = repoFile("app/src/main/java/com/daymark/app/DaymarkApp.kt").parentFile.parentFile.parentFile.parentFile
    private val files: Map<String, String> = main.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(main).path to codeOnly(it.readText()) }

    /** Builders that are not the public version itself, each with the chain that follows it. */
    private fun unlockedBuilders(code: String): List<String> =
        Regex("""(?:NotificationCompat|Notification)\.Builder\(""").findAll(code)
            .map { code.substring(it.range.first, minOf(code.length, it.range.first + 400)) }
            .filter { !it.contains(".setVisibility(NotificationCompat.VISIBILITY_PUBLIC)") }
            .filter { !it.contains(".lockedAway(") }
            .toList()

    private fun bareActions(code: String): Int = Regex("""\.addAction\(\s*0\s*,""").findAll(code).count()

    @Test
    fun `every notification hides its words on the lock screen`() {
        val builders = files.mapValues { unlockedBuilders(it.value) }.filterValues { it.isNotEmpty() }
        assertEquals("notifications that would show their words on a locked phone", emptyMap<String, List<String>>(), builders)
        // The files that post check-ins were read, and each builds through lockedAway.
        for (name in listOf("ReminderScheduler.kt", "TrackerCheckInScheduler.kt")) {
            val code = files.entries.single { it.key.endsWith(name) }.value
            assertTrue("$name builds no notification", code.contains("NotificationCompat.Builder("))
            assertTrue("$name does not hide its words", code.contains(".lockedAway("))
        }
        // Positive control: the check sees a builder that skips it.
        val code = files.entries.single { it.key.endsWith("TrackerCheckInScheduler.kt") }.value
        val planted = code.replaceFirst(".lockedAway(context, CHECKIN_CHANNEL_ID)", "")
        assertTrue("mutation did not land", planted != code)
        assertEquals(1, unlockedBuilders(planted).size)
    }

    @Test
    fun `the lock screen version says only the app's name`() {
        val privacy = files.entries.single { it.key.endsWith("notifications/NotificationPrivacy.kt") }.value
        val public = privacy.substringAfter(".setPublicVersion(").substringBefore(".build()")
        assertTrue(public.contains(".setContentTitle(context.getString(R.string.app_name))"))
        assertEquals("the public version sets one line of words", 1, Regex("""\.setContent\w+\(""").findAll(public).count())
    }

    @Test
    fun `every notification button needs the phone unlocked`() {
        val bare = files.mapValues { bareActions(it.value) }.filterValues { it > 0 }
        assertEquals("buttons a locked phone could press", emptyMap<String, Int>(), bare)
        val privacy = files.entries.single { it.key.endsWith("notifications/NotificationPrivacy.kt") }.value
        assertTrue(privacy.contains(".setAuthenticationRequired(true)"))
        // The quick-log Yes is one of them.
        val tracker = files.entries.single { it.key.endsWith("TrackerCheckInScheduler.kt") }.value
        assertTrue(tracker.contains("unlockedAction(context.getString(R.string.tracker_log_yes)"))
        // Positive control: a bare button is seen.
        assertEquals(1, bareActions("$tracker\n.addAction(0, title, intent)"))
    }

    @Test
    fun `the widget names nothing while the app lock is on`() {
        val widget = files.entries.single { it.key.endsWith("widget/TrackerWidget.kt") }.value
        val glance = widget.substringAfter("override suspend fun provideGlance(").substringBefore("provideContent")
        // The lock is read first, an unreadable setting counts as locked, and a locked widget loads no tracker.
        assertTrue(glance.contains("runCatching { deps.settings().lockEnabled }.getOrDefault(true)"))
        assertTrue(Regex("""if \(locked\) \{\s*emptyList\(\)""").containsMatchIn(glance))
        assertTrue(glance.indexOf("lockEnabled") < glance.indexOf("trackerDao()"))
        // And the app redraws it when the lock changes.
        val app = files.entries.single { it.key.endsWith("DaymarkApp.kt") }.value
        assertTrue(Regex("""lockEnabled \}\.distinctUntilChanged\(\)\.collect \{ TrackerWidget\.redraw""").containsMatchIn(app))
    }

    @Test
    fun `the walk saw the whole app`() {
        assertTrue(files.size > 100)
        assertTrue(File(main, "com/daymark/app/notifications/NotificationPrivacy.kt").isFile)
    }
}
