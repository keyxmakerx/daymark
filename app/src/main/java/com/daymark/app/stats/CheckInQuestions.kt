package com.daymark.app.stats

/**
 * What a check-in asks after the mood, and what its closing screen offers: the rules engine's
 * choice of extra questions (`docs/DECISIONS.md` §D1) and of the two sky offers (#538).
 *
 * Pure and Android-free, like the rest of `stats/`: no Room types, no clock, no time zone. The
 * caller hands in the moment, the person's settings, and each question's own ledger rows, and owns
 * the wording. `tools/jvm-tests.sh stats` runs `CheckInQuestionsTest`.
 *
 * ## Where a question comes from
 *
 * Only from something the person set up: a tracker they chose to have asked in their check-in, or
 * the sleep diary they use. A feature hands in a [Candidate], a subject and its rows and nothing
 * else, so this file never learns what a tracker measures or what anyone wrote, and no question can
 * carry a guess about how someone is (§D1a). The order of the candidates is the feature's own, and
 * is kept.
 *
 * ## The rule everything else hangs on
 *
 * **A hard mood can make the check-in shorter, and nothing can make it longer than the person set.**
 * After Awful or Bad ([SHORT_PATH_MOOD_MAX]) there is no extra question and no sky offer: the
 * check-in ends on its own closing screen. While the person has said they are having a hard time
 * (#537) there is no extra question either. Both are the engine quietening itself, which §D1a
 * allows; neither can add anything.
 *
 * ## Easing off, per question
 *
 * Each question is its own check-in in [CheckInEngine]'s sense, with [InterruptionBudget.Kind.QUESTION]
 * rows for its subject alone. Skipped twice running, it rests: asked every other day, then further
 * apart, with no cap, and never off by itself. Answered, it is back to every check-in at once. It is
 * asked at most once a day, and not at all on a day it is already answered elsewhere. Only the
 * person turns a question off, where they set it up.
 *
 * ## The tests hold it to
 *
 *  1. no input puts more questions in a check-in than the person allowed;
 *  2. a lower mood never adds a question, and the hard-time switch never adds one;
 *  3. skipping never brings a question back sooner;
 *  4. no sequence of answers or silences adds a sky offer that a fresh ledger would not show.
 */
object CheckInQuestions {

    /** How many extra questions a check-in may ask until the person says otherwise. */
    const val DEFAULT_ALLOWED = 1

    /** The range the person can choose from. Three is enough for a check-in to stay short. */
    val ALLOWED = 0..3

    /** Awful (1) and Bad (2): the mood levels that end the check-in straight away. */
    const val SHORT_PATH_MOOD_MAX = 2

    /** The [Candidate.subject] of the sleep diary's question. Tracker ids are always positive. */
    const val SLEEP_SUBJECT = -1L

    private const val DAY_MILLIS = 24 * 3_600_000L

    /**
     * One question a feature would like in today's check-in.
     *
     * @param subject a tracker's id, or [SLEEP_SUBJECT].
     * @param answeredToday already answered today by some other route: the tracker was logged, or
     *   last night is in the sleep diary. A question that would ask for what is already there is
     *   never asked.
     * @param rows this question's own [InterruptionBudget.Kind.QUESTION] rows. Rows of any other kind
     *   or subject are ignored, so one question's skips never quiet another.
     */
    data class Candidate(
        val subject: Long,
        val answeredToday: Boolean,
        val rows: List<InterruptionBudget.Offer>,
    )

    /**
     * The check-in being closed.
     *
     * @param moodLevel the mood just given, 1..5.
     * @param hardTime the person's own "I'm having a hard time" switch is on.
     * @param allowed the person's setting, clamped into [ALLOWED].
     * @param startOfTodayMillis local midnight, from the caller, who owns the zone.
     */
    data class Moment(
        val moodLevel: Int,
        val hardTime: Boolean,
        val allowed: Int,
        val nowMillis: Long,
        val startOfTodayMillis: Long,
    )

    /** The subjects to ask about, in the order the features gave them. */
    fun plan(moment: Moment, candidates: List<Candidate>): List<Long> {
        if (moment.moodLevel <= SHORT_PATH_MOOD_MAX || moment.hardTime) return emptyList()
        val allowed = moment.allowed.coerceIn(ALLOWED)
        if (allowed == 0) return emptyList()
        return candidates
            .distinctBy { it.subject }
            .filter { mayAsk(it, moment) }
            .take(allowed)
            .map { it.subject }
    }

    /** Whether one question may be asked now, on its own rows alone. */
    fun mayAsk(candidate: Candidate, moment: Moment): Boolean {
        if (candidate.answeredToday) return false
        val mine = candidate.rows.filter { it.kind == InterruptionBudget.Kind.QUESTION.key }
        if (mine.any { it.offeredAt in moment.startOfTodayMillis..moment.nowMillis }) return false
        val pace = CheckInEngine.paceOf(
            kind = InterruptionBudget.Kind.QUESTION,
            recent = mine,
            saidStop = false,
            countFrom = 0L,
            // A question rides along with a check-in, so its own schedule is at most once a day.
            setSpacingMillis = DAY_MILLIS,
        )
        val last = mine.maxOfOrNull { it.offeredAt } ?: 0L
        return CheckInEngine.shouldPost(pace, last, moment.nowMillis)
    }

    /** The two things a check-in's closing screen may offer beside Close (#538). */
    enum class Closing {
        /** The new star's birth in the Sky, then straight back. */
        SEE_IT_LAND,

        /** A plain link to the Sky. */
        YOUR_SKY,
    }

    /**
     * What the closing screen offers. The engine can only take these away: after Awful or Bad,
     * whenever the person's check-ins have eased off ([checkInsPace] is anything but as set, which
     * one answered check-in puts right), and "See it land" while the hard-time switch is on. It
     * never adds one, and nothing here sends anyone to the Sky; the person taps.
     */
    fun closingOffers(moodLevel: Int, hardTime: Boolean, checkInsPace: CheckInEngine.Pace): Set<Closing> {
        if (moodLevel <= SHORT_PATH_MOOD_MAX) return emptySet()
        if (checkInsPace != CheckInEngine.Pace.AsSet) return emptySet()
        return if (hardTime) setOf(Closing.YOUR_SKY) else setOf(Closing.SEE_IT_LAND, Closing.YOUR_SKY)
    }
}
