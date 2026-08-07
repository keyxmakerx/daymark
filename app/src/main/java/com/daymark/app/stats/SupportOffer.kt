package com.daymark.app.stats

/**
 * How often the app may *interrupt* after a hard day.
 *
 * [SUPPORT_FEATURE_PLAN.md] has always said the support space is "triggered by a low-mood log
 * (offer, never force)". For a while the code forced: every save at Awful or Bad popped the editor
 * and navigated away, gated only by a single on/off. So the real choice was "every single time" or
 * "never" — and "never" also removed the brief pause that made it worth having at all.
 *
 * Two different things are separated here, because they carry very different costs:
 *
 *  - **The affordance** — a quiet action that appears in the editor when a low mood is picked and
 *    simply sits there. Ignoring it is free, so it needs no rate limiting and isn't modelled here.
 *  - **The interruption** — being taken somewhere you didn't ask to go. That one is rationed, and
 *    this is the rule that rations it.
 *
 * The frequency ladder is deliberately short and its steps are fixed constants. No learning, no
 * scoring, no adaptation the person can't see. Pure and Android-free like the rest of `stats/`:
 * the caller owns persistence and the clock.
 *
 * Note the default is [OncePerDay], not [EveryTime]. That default is a **judgement call, not
 * evidence** — published JITAI dosing figures are for other interventions and populations. It is a
 * setting precisely because we don't know the right answer for any given person.
 */
enum class SupportOfferFrequency {
    /** Never interrupt. The editor affordance is still there whenever a low mood is picked. */
    Never,

    /** At most once a day. The default. */
    OncePerDay,

    /** At most once a week, for people who find even a daily offer too much. */
    OncePerWeek,

    /** Every qualifying save — the old behaviour, kept for people who want that reinforcement. */
    EveryTime,
    ;

    companion object {
        val DEFAULT = OncePerDay

        /** Tolerant of unknown/missing stored values so a bad pref can never crash the editor. */
        fun fromKey(key: String?): SupportOfferFrequency =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

object SupportOffer {

    private const val HOUR_MILLIS = 3_600_000L
    private const val DAY_MILLIS = 24 * HOUR_MILLIS
    private const val WEEK_MILLIS = 7 * DAY_MILLIS

    /**
     * Whether the app may interrupt right now.
     *
     * [lastOfferedAt] is when the last interruption happened (0 = never). A clock that jumps
     * backwards — timezone change, manual clock edit — must not lock someone out of the offer
     * forever, so a [lastOfferedAt] in the future is treated as "no record".
     */
    fun shouldInterrupt(
        frequency: SupportOfferFrequency,
        lastOfferedAt: Long,
        nowMillis: Long,
    ): Boolean {
        val minimumGap = when (frequency) {
            SupportOfferFrequency.Never -> return false
            SupportOfferFrequency.EveryTime -> return true
            SupportOfferFrequency.OncePerDay -> DAY_MILLIS
            SupportOfferFrequency.OncePerWeek -> WEEK_MILLIS
        }
        if (lastOfferedAt <= 0L || lastOfferedAt > nowMillis) return true
        return nowMillis - lastOfferedAt >= minimumGap
    }

    /** Plain wording for the setting screen, so the choice reads as a promise about behaviour. */
    fun summary(frequency: SupportOfferFrequency): String = when (frequency) {
        SupportOfferFrequency.Never -> "Never taken there — the button is always in the corner if you want it"
        SupportOfferFrequency.OncePerDay -> "At most once a day"
        SupportOfferFrequency.OncePerWeek -> "At most once a week"
        SupportOfferFrequency.EveryTime -> "Every time you log a hard day"
    }
}
