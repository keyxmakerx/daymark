package com.daymark.app.data

import com.daymark.app.data.dao.SleepLogDao
import com.daymark.app.data.dao.TrackerDao
import com.daymark.app.data.dao.TrackerLogDao
import com.daymark.app.data.entity.OfferKind
import com.daymark.app.data.entity.OfferOutcome
import com.daymark.app.data.entity.Tracker
import com.daymark.app.data.entity.rhythmChoice
import com.daymark.app.notifications.ReminderScheduler
import com.daymark.app.stats.CheckInQuestions
import com.daymark.app.stats.PhrasePool
import com.daymark.app.stats.TrackerRhythm
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gathers what the pure planner (`stats/CheckInQuestions.kt`) needs for the check-in just saved,
 * and writes the ledger line when a question is answered or skipped.
 *
 * Each feature's own rule stays here, beside its data, as §D1 asks: a tracker is a candidate when
 * the person chose "In my check-in" for it; the sleep question is a candidate in the morning, for
 * someone who used the sleep diary in the last two weeks, while last night is not in it. What goes
 * on to the planner is a subject, whether it is already answered today, and its own rows. Nothing
 * about the entry is read here but its mood level, and only for the planner's one rule that a hard
 * mood ends the check-in.
 */
@Singleton
class CheckInPlanner @Inject constructor(
    private val trackerDao: TrackerDao,
    private val trackerLogDao: TrackerLogDao,
    private val sleepLogDao: SleepLogDao,
    private val offerLedger: OfferLedgerRepository,
    private val settings: SettingsRepository,
    private val reminders: ReminderScheduler,
) {

    /** One question to ask: a tracker's, or the sleep diary's when [tracker] is null. */
    data class Question(val subject: Long, val tracker: Tracker?)

    data class Plan(
        val questions: List<Question>,
        val offers: Set<CheckInQuestions.Closing>,
        val hardTime: Boolean,
    ) {
        /** Nothing to ask and nothing to offer: the check-in ends where it always did. */
        val isEmpty: Boolean get() = questions.isEmpty() && offers.isEmpty()
    }

    suspend fun plan(
        moodLevel: Int,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Plan {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val hardTime = settings.hardTimeOn(nowMillis)

        val trackers = trackerDao.getAll()
            .filter { !it.archived && it.rhythmChoice.inCheckIn }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val candidates = mutableListOf<CheckInQuestions.Candidate>()
        trackers.forEach { tracker ->
            candidates += CheckInQuestions.Candidate(
                subject = tracker.id,
                answeredToday = trackerLogDao.countBetween(tracker.id, startOfToday, nowMillis + 1) > 0,
                rows = offerLedger.checkInRows(OfferKind.QUESTION, tracker.id),
            )
        }
        sleepCandidate(today, nowMillis, zone)?.let { candidates += it }

        val moment = CheckInQuestions.Moment(
            moodLevel = moodLevel,
            hardTime = hardTime,
            allowed = settings.checkInQuestionsAllowed,
            nowMillis = nowMillis,
            startOfTodayMillis = startOfToday,
        )
        val byId = trackers.associateBy { it.id }
        val questions = CheckInQuestions.plan(moment, candidates).map { Question(it, byId[it]) }
        val offers = CheckInQuestions.closingOffers(moodLevel, hardTime, reminders.paceNow(nowMillis))
        return Plan(questions, offers, hardTime)
    }

    /**
     * The sleep diary's question, when it applies: the morning half of the day, the diary used in
     * the last [SLEEP_RECENT_DAYS] days, and the person has not turned the question off. Last night
     * already in the diary is "answered today", so it is never asked for.
     */
    private suspend fun sleepCandidate(today: LocalDate, nowMillis: Long, zone: ZoneId): CheckInQuestions.Candidate? {
        if (!settings.sleepQuestionOn) return null
        val hour = Instant.ofEpochMilli(nowMillis).atZone(zone).hour
        if (PhrasePool.bandForHour(hour) != PhrasePool.Band.Morning) return null
        val nights = sleepLogDao.getAll().map { it.night }
        val todayDay = today.toEpochDay()
        if (nights.none { it in (todayDay - SLEEP_RECENT_DAYS)..todayDay }) return null
        return CheckInQuestions.Candidate(
            subject = CheckInQuestions.SLEEP_SUBJECT,
            answeredToday = todayDay in nights,
            rows = offerLedger.checkInRows(OfferKind.QUESTION, CheckInQuestions.SLEEP_SUBJECT),
        )
    }

    /** One ledger line for a question that was shown: answered, or skipped. */
    suspend fun record(subject: Long, answered: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        offerLedger.record(
            kind = OfferKind.QUESTION,
            outcome = if (answered) OfferOutcome.ACCEPTED else OfferOutcome.DISMISSED,
            offeredAtMillis = nowMillis,
            responded = answered,
            subject = subject,
        )
    }

    /** "Stop asking this": the tracker goes back to "When it happens", or the sleep question goes off. */
    suspend fun stopAsking(question: Question) {
        val tracker = question.tracker
        if (tracker == null) {
            settings.sleepQuestionOn = false
        } else {
            trackerDao.getById(tracker.id)?.let {
                trackerDao.update(it.copy(rhythm = TrackerRhythm.Rhythm.WHEN_IT_HAPPENS.key))
            }
        }
    }

    private companion object {
        const val SLEEP_RECENT_DAYS = 14L
    }
}
