package com.daymark.app.export

import com.daymark.app.data.dao.EntryDao
import com.daymark.app.data.dao.JournalDao
import com.daymark.app.stats.MoodStats
import com.daymark.app.util.DateUtils
import java.security.MessageDigest
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class ReportEntry(
    val dateTime: Long,
    val moodLevel: Int,
    val note: String,
    val activityNames: List<String>,
)

data class ReportJournalEntry(val dateTime: Long, val title: String, val body: String)

data class ReportActivityStat(val name: String, val averageMood: Double, val count: Int)

data class TrendPoint(val date: LocalDate, val value: Double?)

/** Everything the PDF generator needs, plus an authenticity hash over the source data. */
data class ReportData(
    val patientName: String,
    val rangeLabel: String,
    val generatedAtMillis: Long,
    val daysInRange: Int,
    val daysLogged: Int,
    val totalEntries: Int,
    val averageMood: Double?,
    val currentStreak: Int,
    val longestStreak: Int,
    val moodCounts: Map<Int, Int>,
    val trend: List<TrendPoint>,
    val activityStats: List<ReportActivityStat>,
    val entries: List<ReportEntry>,
    val journal: List<ReportJournalEntry>,
    val sha256Hex: String,
)

@Singleton
class ReportDataBuilder @Inject constructor(
    private val entryDao: EntryDao,
    private val journalDao: JournalDao,
) {
    suspend fun build(options: PdfExportOptions, nowMillis: Long): ReportData {
        val ewas = entryDao.getBetween(options.fromMillis, options.toMillis)
        val reportEntries = ewas.map { e ->
            ReportEntry(
                dateTime = e.entry.dateTime,
                moodLevel = e.entry.moodLevel,
                note = if (options.includeNotes) e.entry.note else "",
                activityNames = e.activities.map { it.name },
            )
        }

        val journal = if (options.includeJournal) {
            journalDao.getAll()
                .filter { it.dateTime in options.fromMillis..options.toMillis }
                .sortedBy { it.dateTime }
                .map { ReportJournalEntry(it.dateTime, it.title, it.body) }
        } else {
            emptyList()
        }

        val levels = ewas.map { it.entry.moodLevel }
        val days = ewas.map { DateUtils.toLocalDate(it.entry.dateTime) }.toSet()
        val today = DateUtils.toLocalDate(nowMillis)

        // Per-activity average mood with names + counts.
        val pairs = ewas.map { it.entry.moodLevel to it.activities.map { a -> a.id } }
        val averages = MoodStats.activityAverages(pairs)
        val nameById = ewas.flatMap { it.activities }.associate { it.id to it.name }
        val counts = mutableMapOf<Long, Int>()
        pairs.forEach { (_, ids) -> ids.distinct().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val activityStats = averages.entries
            .mapNotNull { (id, avg) -> nameById[id]?.let { ReportActivityStat(it, avg, counts[id] ?: 0) } }
            .sortedByDescending { it.averageMood }

        // Daily trend across the range.
        val from = DateUtils.toLocalDate(options.fromMillis)
        val to = DateUtils.toLocalDate(options.toMillis)
        val byDay = ewas.groupBy { DateUtils.toLocalDate(it.entry.dateTime) }
        val daysInRange = (java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt() + 1).coerceAtLeast(1)
        val trend = (0 until daysInRange).map { offset ->
            val day = from.plusDays(offset.toLong())
            TrendPoint(day, byDay[day]?.map { it.entry.moodLevel }?.average())
        }

        return ReportData(
            patientName = options.patientName,
            rangeLabel = options.rangeLabel,
            generatedAtMillis = nowMillis,
            daysInRange = daysInRange,
            daysLogged = days.size,
            totalEntries = ewas.size,
            averageMood = MoodStats.averageMood(levels),
            currentStreak = MoodStats.currentStreak(days, today),
            longestStreak = MoodStats.longestStreak(days),
            moodCounts = MoodStats.moodCounts(levels),
            trend = trend,
            activityStats = activityStats,
            entries = reportEntries,
            journal = journal,
            sha256Hex = sha256Hex(canonicalPayload(options.fromMillis, options.toMillis, reportEntries, journal)),
        )
    }

    companion object {
        /**
         * A deterministic, order-stable string over the report's source data, used for the
         * authenticity hash. Excludes the volatile "generated at" timestamp on purpose so the
         * same data always hashes the same.
         */
        fun canonicalPayload(
            fromMillis: Long,
            toMillis: Long,
            entries: List<ReportEntry>,
            journal: List<ReportJournalEntry>,
        ): String {
            val sb = StringBuilder()
            sb.append("daymark-report-v1\n")
            sb.append("range:").append(fromMillis).append('-').append(toMillis).append('\n')
            entries.sortedWith(compareBy({ it.dateTime }, { it.moodLevel })).forEach { e ->
                sb.append("E|").append(e.dateTime).append('|').append(e.moodLevel)
                    .append('|').append(e.note.replace("\n", " "))
                    .append('|').append(e.activityNames.sorted().joinToString(","))
                    .append('\n')
            }
            journal.sortedBy { it.dateTime }.forEach { j ->
                sb.append("J|").append(j.dateTime).append('|').append(j.title.replace("\n", " "))
                    .append('|').append(j.body.replace("\n", " ")).append('\n')
            }
            return sb.toString()
        }

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
