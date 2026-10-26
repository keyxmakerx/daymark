package com.daymark.app.notifications

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.codeOnly
import com.daymark.app.ui.functionBodyRange
import com.daymark.app.ui.replaceOnce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reminder never stops without a word (#484). Read as text, because alarms and receivers cannot
 * run on a plain JVM. Every check is first shown a planted example in a copy of the real file
 * (CLAUDE.md §5). Runs through `tools/jvm-source-tests.sh`.
 */
class ReminderReliabilitySourceTest {

    private companion object {
        const val DIR = "app/src/main/java/com/daymark/app/notifications/"
        const val RECEIVER = DIR + "ReminderReceiver.kt"
        const val ACTIONS = DIR + "ReminderActionReceiver.kt"
        const val APP = "app/src/main/java/com/daymark/app/DaymarkApp.kt"
        const val HOME_CHECKS = "app/src/main/java/com/daymark/app/ui/home/HomeChecks.kt"

        /** An alarm Doze may hold back for hours: `set(` on an AlarmManager. */
        val HELD_BACK = Regex("""alarmManager\.set\(""")
    }

    private fun code(rel: String) = codeOnly(repoFile(rel).readText())

    @Test
    fun every_fallback_alarm_is_one_doze_lets_fire() {
        val files = listOf("ReminderScheduler.kt", "TrackerCheckInScheduler.kt").map { DIR + it }
        for (f in files) {
            val c = code(f)
            assertTrue("$f has no fallback at all", c.contains("setAndAllowWhileIdle("))
            assertFalse("$f falls back to a plain set()", HELD_BACK.containsMatchIn(c))
        }
        val planted = replaceOnce(code(files[1]), "setAndAllowWhileIdle(", "set(")
        assertTrue(HELD_BACK.containsMatchIn(planted))
    }

    /** Re-arming comes before showing, so nothing showing does can skip it. */
    @Test
    fun a_fired_reminder_is_rearmed_before_it_is_shown() {
        fun rearmsFirst(c: String): Boolean {
            val arm = c.indexOf("scheduler.schedule(reminder)")
            val show = c.indexOf("scheduler.showNotification(")
            return arm >= 0 && show >= 0 && arm < show
        }
        val real = code(RECEIVER)
        assertTrue(rearmsFirst(real))
        val swapped = real
            .replace("if (!later) runCatching { scheduler.schedule(reminder) }", "PLACEHOLDER")
            .replace("scheduler.showNotification(reminder, later = later)", "scheduler.showNotification(reminder, later = later)\nif (!later) scheduler.schedule(reminder)")
            .replace("PLACEHOLDER", "")
        assertFalse(rearmsFirst(swapped))
    }

    @Test
    fun the_app_rearms_every_reminder_when_it_starts() {
        val body = code(APP).let { c -> functionBodyRange(c, "onCreate")?.let { c.substring(it) } }
        assertTrue(body != null && body.contains("reminderRepository.rescheduleAll()"))
        assertFalse(body!!.replace("reminderRepository.rescheduleAll()", "Unit").contains("rescheduleAll"))
    }

    /** Stop asking is recorded for Home's line, and Home's line never switches a reminder back on. */
    @Test
    fun stop_asking_is_said_on_home_and_only_the_person_undoes_it() {
        assertTrue(code(ACTIONS).contains("settings.stoppedReminderIds = settings.stoppedReminderIds + reminder.id"))
        val home = code(HOME_CHECKS)
        val line = functionBodyRange(home, "StoppedReminderLine")?.let { home.substring(it) }
        assertTrue(line != null && line.contains("onOpenReminders"))
        val turnsOn = Regex("""enabled\s*=\s*true|setEnabled|repository\.update""")
        assertEquals(emptyList<String>(), turnsOn.findAll(home).map { it.value }.toList())
        assertTrue(turnsOn.containsMatchIn(home + "\nreminderRepository.update(r.copy(enabled = true))"))
    }
}
