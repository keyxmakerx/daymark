package com.daymark.app.stats

/**
 * The timing layer, described to itself — what a debug screen shows, as plain data.
 *
 * `docs/FEATURES.md` §13.4 describes a debug-only screen carrying, per feature: the rule, what it
 * reads, its current values, whether it would fire now and if not why, the offers made and how they
 * were answered, how much the gate is holding back, the timing grid, and the phrase pool. This file
 * is all of that as data, so the screen never reaches into [InterruptionBudget] or [TimingGrid]
 * internals and the description cannot drift from the behaviour: every number here is produced by
 * calling the real functions with the real arguments, never by restating their rules in a string.
 *
 * ## What it is, and what it must never become
 *
 * **A description of the rules, never a reading of the person.** Everything below is the app's own
 * behaviour — how often it may speak, when it last spoke, what became of that, which hours it uses.
 * There is no mood, no trend, no entry, no screener, no goal and no person on this screen, and the
 * three states §D1a refuses to distinguish are still not distinguished here: an unanswered hour is
 * shown as an unanswered hour and nothing else is offered about it. A debug screen is exactly where
 * an inference would first be written down as a sentence, so this is the file to keep honest.
 *
 * It follows that [Hold] is a **closed list of five fixed sentences**, not a free string. A "why
 * not" the engine could compose is a "why not" that could one day say something about the person.
 *
 * `docs/DECISIONS.md` §D2 — the gate has no user-facing name and no persona. What is named
 * here are the *features*, which already have names; the thing underneath them is "the rule".
 *
 * Pure and Android-free like the rest of `stats/`: no clock, no persistence. The caller supplies
 * the hour, the time and the rows.
 */
object RuleReadout {

    /** One labelled current value, ready to draw. Both halves are fixed, human-written copy. */
    data class Value(
        val label: String,
        val value: String,
    )

    /**
     * Why a feature would not ask right now. Five fixed sentences and no sixth.
     *
     * Closed on purpose: see the header. They are declared in the order [feature] tests them, which
     * is a standing preference before a timer: the person's own setting first, then their own
     * "stop asking", then the clock, then the hour. Someone who asked the app to stop must never be
     * told it is merely waiting for the clock — that reads as the app having ignored them.
     */
    enum class Hold(val label: String) {
        /** It would ask now. Nothing is in the way. */
        None("Nothing is holding it back"),

        /** The person's own setting for this feature is never. */
        SetToNever("Your setting for this one is “never”"),

        /** The person said stop asking, and only they can lift it. */
        SaidStop("You told this one to stop asking"),

        /** The minimum gap has not elapsed. */
        NotLongEnoughYet("Not long enough since the last one"),

        /** The gate would allow it, but this is not one of the hours the feature uses. */
        NotOneOfItsHours("This is not one of the hours it uses"),
    }

    /** One feature's whole readout. */
    data class Feature(
        val kindKey: String,
        val name: String,
        val rule: String,
        val reads: List<String>,
        val values: List<Value>,
        val declared: SupportOfferFrequency,
        val effective: SupportOfferFrequency,
        val reception: InterruptionBudget.Reception,
        val declaredGapMillis: Long,
        val effectiveGapMillis: Long,
        val lastOfferedAt: Long,
        val asksOnRecord: Int,
        val asksAnswered: Int,
        val wouldAskNow: Boolean,
        val hold: Hold,
        val placement: TimingGrid.Placement,
        val grid: TimingGrid.Grid,
        val openers: List<String>,
    )

    /**
     * The rule, in one fixed sentence. The same rule for every feature — only the numbers differ,
     * which is the point of there being one gate rather than a policy per feature.
     */
    const val RULE: String =
        "It may ask at most as often as you set, less often if its asks are going unanswered, " +
            "and only in the hours it has been answered in before."

    /**
     * Everything the rule reads, listed. This list is the honest answer to "what does it know about
     * me", so it is deliberately exhaustive: if something is not on it, the engine does not see it.
     */
    val READS: List<String> = listOf(
        "Your setting for how often this one may ask",
        "Whether you told it to stop asking",
        "When it last asked",
        "What became of its last few asks — taken up, put off, waved away",
        "The hour and weekday each of those asks was made",
    )

    /** What each feature is called. The features have names; the rule underneath them does not. */
    fun name(kind: InterruptionBudget.Kind): String = when (kind) {
        InterruptionBudget.Kind.COMPANION -> "The companion"
        InterruptionBudget.Kind.REMINDER -> "Reminders"
        InterruptionBudget.Kind.ASSIGNMENT -> "Prescribed modules"
        InterruptionBudget.Kind.SUPPORT -> "The support space"
    }

    /** A frequency in plain words, phrased for any feature rather than for the support space. */
    fun frequencyLabel(frequency: SupportOfferFrequency): String = when (frequency) {
        SupportOfferFrequency.Never -> "Never"
        SupportOfferFrequency.OncePerWeek -> "At most once a week"
        SupportOfferFrequency.OncePerDay -> "At most once a day"
        SupportOfferFrequency.EveryTime -> "Every time"
    }

    /**
     * How the asks are landing, in plain words.
     *
     * Every one of these describes the app, not the person — "offers are not being taken up", never
     * "you are not engaging". The difference is the whole of §D1a.
     */
    fun receptionLabel(reception: InterruptionBudget.Reception): String = when (reception) {
        InterruptionBudget.Reception.Open -> "As you set it"
        InterruptionBudget.Reception.Easing -> "One step quieter — some asks were put off or waved away"
        InterruptionBudget.Reception.Quiet -> "Two steps quieter — asks are not being taken up"
        InterruptionBudget.Reception.Closed -> "Silent — you told it to stop"
    }

    /** Why the hours are the hours they are, in plain words. */
    fun basisLabel(basis: TimingGrid.Basis): String = when (basis) {
        TimingGrid.Basis.EveryHour -> "Any hour — this one does not choose the moment, you do"
        TimingGrid.Basis.NoHoursAtAll -> "No hours — it is not asking at all"
        TimingGrid.Basis.TooLittleEvidence ->
            "Spread across the day — too few asks on record to have a view"
        TimingGrid.Basis.AnsweredHours -> "The hours you have answered in before"
        TimingGrid.Basis.NoHourAnswered ->
            "Spread across the day — no hour has ever been answered in"
    }

    /**
     * A span of elapsed time in plain words, rounded down to its largest whole unit.
     *
     * Deliberately coarse. A debug screen showing "2 days" rather than "2 days, 4 hours, 11 minutes"
     * is easier to read and, more to the point, a precise elapsed time is a precise record of when
     * someone last picked up their phone.
     */
    fun describeSpan(millis: Long): String {
        if (millis <= 0L) return "Less than a minute"
        val minutes = millis / 60_000L
        if (minutes < 1L) return "Less than a minute"
        if (minutes < 60L) return plural(minutes, "minute")
        val hours = minutes / 60L
        if (hours < 24L) return plural(hours, "hour")
        return plural(hours / 24L, "day")
    }

    /** The same, for a minimum gap — where zero and "never" are real answers rather than edges. */
    fun describeGap(millis: Long): String = when {
        millis >= InterruptionBudget.NEVER_GAP_MILLIS -> "Never"
        millis <= 0L -> "No minimum"
        else -> describeSpan(millis)
    }

    /**
     * How much quieter the rule is being than the person asked for — the `docs/FEATURES.md` §13.4
     * line "how much the gate is holding back", which is the number that says whether reception is
     * doing anything at all.
     */
    fun heldBackLabel(declaredGapMillis: Long, effectiveGapMillis: Long): String = when {
        declaredGapMillis >= InterruptionBudget.NEVER_GAP_MILLIS -> "Nothing — you set it to never"
        effectiveGapMillis >= InterruptionBudget.NEVER_GAP_MILLIS -> "Everything — it is not asking at all"
        effectiveGapMillis <= declaredGapMillis -> "Nothing"
        else -> describeSpan(effectiveGapMillis - declaredGapMillis) + " longer between asks"
    }

    /**
     * A set of hours as clock times, with runs collapsed — "10:00–12:00, 18:00".
     *
     * [hours] is expected to be [TimingGrid.Placement.hours]; anything ascending and distinct works,
     * and anything else is sorted and de-duplicated first so the label cannot come out nonsense.
     */
    fun hoursLabel(hours: List<Int>): String {
        if (hours.isEmpty()) return "None"
        val sorted = hours.distinct().sorted()
        val parts = ArrayList<String>()
        var start = sorted[0]
        var previous = sorted[0]
        for (index in 1 until sorted.size) {
            val hour = sorted[index]
            if (hour == previous + 1) {
                previous = hour
            } else {
                parts.add(runLabel(start, previous))
                start = hour
                previous = hour
            }
        }
        parts.add(runLabel(start, previous))
        return parts.joinToString(", ")
    }

    /**
     * One feature's readout, computed by asking the real engine the real questions.
     *
     * [recent] is this kind's rows as [InterruptionBudget] takes them and [timed] is the same rows
     * as [TimingGrid] takes them; they are separate arguments because the two engines want different
     * projections of the ledger and neither should be handed the other's.
     *
     * [hour] is the hour of day the screen is being drawn at, in the person's own zone, and
     * [nowMillis] the clock — both from the caller, because nothing in `stats/` may read either.
     */
    fun feature(
        kind: InterruptionBudget.Kind,
        declared: SupportOfferFrequency,
        recent: List<InterruptionBudget.Offer>,
        saidStop: Boolean,
        timed: List<TimingGrid.Ask>,
        hour: Int,
        nowMillis: Long,
        candidateHours: List<Int> = TimingGrid.DEFAULT_CANDIDATE_HOURS,
    ): Feature {
        val reception = InterruptionBudget.receptionOf(kind, recent, saidStop)
        val effective = InterruptionBudget.effectiveFrequency(declared, reception)
        val lastAt = InterruptionBudget.lastOfferedAt(kind, recent)
        val budgetAllows = InterruptionBudget.shouldInterrupt(declared, reception, lastAt, nowMillis)
        val placement = TimingGrid.allocate(
            kind = kind.key,
            asks = timed,
            hoursWanted = TimingGrid.hoursFor(effective),
            candidateHours = candidateHours,
        )
        val wouldAsk = TimingGrid.mayAskNow(budgetAllows, placement, hour)
        val hold = when {
            wouldAsk -> Hold.None
            declared == SupportOfferFrequency.Never -> Hold.SetToNever
            saidStop || reception == InterruptionBudget.Reception.Closed -> Hold.SaidStop
            !budgetAllows -> Hold.NotLongEnoughYet
            else -> Hold.NotOneOfItsHours
        }

        var asksOnRecord = 0
        var asksAnswered = 0
        for (reading in placement.readings) {
            asksOnRecord += reading.asks
            asksAnswered += reading.answered
        }

        val declaredGap = InterruptionBudget.minimumGapMillis(declared)
        val effectiveGap = InterruptionBudget.minimumGapMillis(effective)
        val values: List<Value> = listOf(
            Value("Your setting", frequencyLabel(declared)),
            Value("In force now", frequencyLabel(effective)),
            Value("How its asks are landing", receptionLabel(reception)),
            Value("Shortest gap you allow", describeGap(declaredGap)),
            Value("Shortest gap in force", describeGap(effectiveGap)),
            Value("Held back by", heldBackLabel(declaredGap, effectiveGap)),
            Value("Last asked", if (lastAt <= 0L) "It has not asked" else describeSpan(nowMillis - lastAt) + " ago"),
            Value("Asks on record", asksOnRecord.toString()),
            Value("Of those, answered", asksAnswered.toString()),
            Value("Hours it uses", hoursLabel(placement.hours)),
            Value("Why those hours", basisLabel(placement.basis)),
        )

        return Feature(
            kindKey = kind.key,
            name = name(kind),
            rule = RULE,
            reads = READS,
            values = values,
            declared = declared,
            effective = effective,
            reception = reception,
            declaredGapMillis = declaredGap,
            effectiveGapMillis = effectiveGap,
            lastOfferedAt = lastAt,
            asksOnRecord = asksOnRecord,
            asksAnswered = asksAnswered,
            wouldAskNow = wouldAsk,
            hold = hold,
            placement = placement,
            grid = TimingGrid.grid(kind.key, timed),
            openers = PhrasePool.pool(PhrasePool.bandForHour(hour)),
        )
    }

    private fun plural(count: Long, unit: String): String =
        count.toString() + " " + unit + (if (count == 1L) "" else "s")

    private fun runLabel(start: Int, end: Int): String =
        if (start == end) clockLabel(start) else clockLabel(start) + "–" + clockLabel(end)

    private fun clockLabel(hour: Int): String =
        (if (hour < 10) "0" else "") + hour.toString() + ":00"
}
