package com.daymark.app.data

import com.daymark.app.data.dao.ReminderDao
import com.daymark.app.data.entity.Reminder
import com.daymark.app.notifications.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for reminders: persists them and keeps the [ReminderScheduler]'s alarms
 * in sync. Also performs a one-time import of the legacy single-reminder preference so people who
 * upgrade keep their existing reminder.
 */
@Singleton
class ReminderRepository @Inject constructor(
    private val dao: ReminderDao,
    private val scheduler: ReminderScheduler,
    private val settings: SettingsRepository,
) {
    fun observeAll(): Flow<List<Reminder>> = dao.observeAll()

    suspend fun get(id: Long): Reminder? = dao.getById(id)

    suspend fun add(hour: Int, minute: Int, label: String = ""): Long {
        val id = dao.insert(Reminder(hour = hour, minute = minute, enabled = true, label = label))
        dao.getById(id)?.let { scheduler.schedule(it) }
        return id
    }

    suspend fun update(reminder: Reminder) {
        dao.update(reminder)
        if (reminder.enabled) scheduler.schedule(reminder) else scheduler.cancel(reminder.id)
    }

    suspend fun delete(reminder: Reminder) {
        scheduler.cancel(reminder.id)
        dao.delete(reminder)
    }

    /** Re-arms every reminder (after a reboot or a backup restore). */
    suspend fun rescheduleAll() {
        dao.getAll().forEach { if (it.enabled) scheduler.schedule(it) else scheduler.cancel(it.id) }
    }

    /** Cancels the alarm for every current reminder (used before a REPLACE import wipes the table,
     *  so alarms for ids that won't exist afterwards don't linger). */
    suspend fun cancelAllAlarms() {
        dao.getAll().forEach { scheduler.cancel(it.id) }
    }

    /**
     * Moves the old single-reminder preference into the reminders table once. Idempotent via a
     * flag, so it runs only on the first launch after upgrading.
     */
    suspend fun migrateLegacyReminderIfNeeded() {
        if (settings.legacyReminderMigrated) return
        if (settings.reminderEnabled && dao.getAll().isEmpty()) {
            add(settings.reminderHour, settings.reminderMinute)
        }
        settings.legacyReminderMigrated = true
    }
}
