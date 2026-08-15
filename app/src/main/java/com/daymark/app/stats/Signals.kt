package com.daymark.app.stats

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The "Signals" engine — the small, deterministic rules layer that decides **what's most relevant
 * to surface right now** from a person's own data. It is the single source the three surfaces read
 * from, so the app feels like one thing instead of a pile of separate tools:
 *
 *  - the Today **feed** shows the top few signals as gentle cards,
 *  - the **Insights** page shows the full ranked set (self-ordering),
 *  - the **"what might help"** support screen shows the support-eligible signals.
 *
 * There is intentionally **no AI / no ML / no generated text** here. Every card is a fixed,
 * human-written template with the person's own numbers slotted in — fully reviewable and
 * deterministic. Like the rest of `stats/`, this is free of Android/Room types so it unit-tests on
 * the JVM; the caller (a ViewModel) owns Room and the time zone and feeds in already-computed facts.
 *
 * Everything describes **association, not causation**, and nothing here is diagnostic.
 */
object Signals {

    /** Where a signal is allowed to appear. */
    enum class Surface { Feed, Insights, Support }

    /** The tone/intent of a card, used by the UI to style it (and to keep the feed calm). */
    enum class Category { Support, Celebration, Insight, Nudge, Prompt }

    /**
     * A suggested next step a card can offer. The UI maps these to navigation; the copy itself
     * stays fixed in [Signal]. Kept as a closed set so every action is reviewable.
     */
    sealed interface Action {
        /**
         * Offer to turn a positive factor into a gentle goal. [factor] names the factor for the
         * card copy; the router currently opens a blank goal editor (factor prefill is a planned
         * follow-up).
         */
        data class CreateGoalFromFactor(val factor: String) : Action
        /** Open the daily check-in / mood logger. */
        object LogToday : Action
        /** Open the "take a moment" support space. */
        object OpenSupport : Action
        /** Start a paced-breathing session. */
        object OpenBreathing : Action
        /** Open a CBT thought record. */
        object OpenThoughtRecord : Action
        /** Open free-writing / journal. */
        object OpenJournal : Action
        /** Open a movement / stretch session. */
        object OpenMovement : Action
        /** Open a self-check by name (known names route to that questionnaire; others to the hub). */
        data class TakeCheckin(val name: String) : Action
        /** Open crisis resources. */
        object OpenCrisisResources : Action
    }

    /**
     * One candidate card. [score] ranks relevance (higher first); [surfaces] gates where it may
     * appear; [dismissible] marks gentle suggestions the user can wave away on the feed.
     */
    data class Signal(
        val kind: String,
        val category: Category,
        val score: Double,
        val title: String,
        val body: String,
        val action: Action?,
        val dismissible: Boolean,
        val surfaces: Set<Surface>,
    )

    /** A factor (activity / tracker) and how strongly it associates with mood. */
    data class FactorLift(val name: String, val delta: Double, val n: Int)

    /**
     * Already-computed facts the rules read. The ViewModel derives these from Room (using the
     * existing [MoodStats] / [MoodCorrelations] / [MoodPatterns] helpers) so this stays pure.
     */
    data class Inputs(
        val totalEntries: Int,
        val avgMood: Double?,
        /** Today's mood level 1..5 if the person logged today, else null. */
        val moodTodayLevel: Int?,
        val loggedToday: Boolean,
        val currentStreak: Int,
        val longestStreak: Int,
        /** Strongest positive association past the sample gate, or null. */
        val topLift: FactorLift?,
        /** Strongest negative association past the sample gate, or null. */
        val topDrag: FactorLift?,
        /** This period's average mood vs the previous period, as a percent change, or null. */
        val monthDeltaPct: Double?,
        /** Title of an achievement unlocked just now, or null. */
        val newlyUnlockedAchievement: String?,
        /** Name of a self-check that's due (e.g. "WHO-5"), or null. */
        val dueCheckin: String?,
        /** A note written about this date a year ago, or null. */
        val onThisDayNote: String?,
    )

    // Gates / thresholds. These constants *are* the rules — tuned to be conservative so cards only
    // appear when there's enough data to mean something.
    private const val LOW_MOOD_MAX = 2          // <= this today => offer support
    private const val LIFT_MIN_DELTA = 0.4      // min mood-delta for a factor to be worth surfacing
    private const val MONTH_UP_PCT = 8.0        // >= this => "steadier month" celebration
    private const val MONTH_DOWN_PCT = -15.0    // <= this => gentle, Insights-only heads-up
    private val STREAK_MILESTONES = setOf(3, 7, 14, 30, 50, 100, 200, 365)

    /**
     * Builds every eligible signal from [inputs], sorted by [Signal.score] descending (ties broken
     * by [Signal.kind] for a stable order). Returns empty until there's at least one entry — an
     * empty app has nothing honest to say.
     */
    fun build(inputs: Inputs, locale: Locale = Locale.getDefault()): List<Signal> {
        if (inputs.totalEntries == 0 || inputs.avgMood == null) return emptyList()
        val out = ArrayList<Signal>()

        // 1. Low mood today -> offer the "take a moment" space (feed), highest priority.
        val low = inputs.moodTodayLevel != null && inputs.moodTodayLevel <= LOW_MOOD_MAX
        if (low) {
            out.add(
                Signal(
                    kind = "support_offer",
                    category = Category.Support,
                    score = 100.0,
                    title = "Want to take a moment?",
                    body = "You said today's been a hard one. There's nothing you have to do — but a few gentle options are here if you want them.",
                    action = Action.OpenSupport,
                    dismissible = true,
                    surfaces = setOf(Surface.Feed),
                ),
            )
        }

        // 2. Haven't logged today yet -> a calm prompt to check in (feed only).
        if (!inputs.loggedToday) {
            out.add(
                Signal(
                    kind = "prompt_log_today",
                    category = Category.Prompt,
                    score = 85.0,
                    title = "How are you, right now?",
                    body = "Tap a face to check in — it only takes a second.",
                    action = Action.LogToday,
                    dismissible = false,
                    surfaces = setOf(Surface.Feed),
                ),
            )
        }

        // 3. Just unlocked an achievement -> a small celebration.
        inputs.newlyUnlockedAchievement?.let { title ->
            out.add(
                Signal(
                    kind = "achievement_unlocked",
                    category = Category.Celebration,
                    score = 72.0,
                    title = "New milestone: $title",
                    body = "A small marker for showing up. Nicely done.",
                    action = null,
                    dismissible = true,
                    surfaces = setOf(Surface.Feed, Surface.Insights),
                ),
            )
        }

        // 4. Streak milestone (a named milestone, or matching your all-time best) -> celebration.
        val streak = inputs.currentStreak
        val milestone = streak in STREAK_MILESTONES || (streak >= 3 && streak == inputs.longestStreak)
        if (milestone) {
            out.add(
                Signal(
                    kind = "streak_milestone",
                    category = Category.Celebration,
                    score = 65.0,
                    title = "$streak-day check-in streak",
                    body = "Showing up is the whole thing. Keep it gentle.",
                    action = null,
                    dismissible = true,
                    surfaces = setOf(Surface.Feed, Surface.Insights),
                ),
            )
        }

        // 5. A self-check is due -> a quiet nudge (not a demand).
        inputs.dueCheckin?.let { name ->
            out.add(
                Signal(
                    kind = "checkin_due",
                    category = Category.Nudge,
                    score = 54.0,
                    title = "$name check-in",
                    body = "A short self-check, whenever you have a quiet minute.",
                    action = Action.TakeCheckin(name),
                    dismissible = true,
                    surfaces = setOf(Surface.Feed, Surface.Insights),
                ),
            )
        }

        // 6. A positive factor stands out -> insight + offer to make it a goal.
        inputs.topLift?.let { lift ->
            if (lift.delta >= LIFT_MIN_DELTA) {
                out.add(
                    Signal(
                        kind = "lift_factor",
                        category = Category.Insight,
                        // base 40, scaled by strength (capped) so stronger patterns float higher.
                        score = 40.0 + (lift.delta * 20.0).coerceAtMost(18.0),
                        title = "\"${lift.name}\" goes with your better days",
                        body = "On days with \"${lift.name}\", your mood averaged higher (association, not cause). Want to make it a gentle goal?",
                        action = Action.CreateGoalFromFactor(lift.name),
                        dismissible = true,
                        surfaces = setOf(Surface.Feed, Surface.Insights),
                    ),
                )
            }
        }

        // 7. On this day -> a memory resurfaced (feed only).
        inputs.onThisDayNote?.let { note ->
            out.add(
                Signal(
                    kind = "on_this_day",
                    category = Category.Insight,
                    score = 44.0,
                    title = "On this day, a year ago",
                    body = "“${note.trim()}”",
                    action = null,
                    dismissible = true,
                    surfaces = setOf(Surface.Feed),
                ),
            )
        }

        // 8. Month-over-month movement. Up = a quiet celebration (both surfaces); a notable dip is
        //    surfaced only on Insights and worded gently — never pushed at you on the feed.
        inputs.monthDeltaPct?.let { pct ->
            if (pct >= MONTH_UP_PCT) {
                out.add(
                    Signal(
                        kind = "month_up",
                        category = Category.Celebration,
                        score = 58.0,
                        title = "A steadier stretch",
                        body = "Your average mood is up ${pct.roundToInt()}% from the period before.",
                        action = null,
                        dismissible = true,
                        surfaces = setOf(Surface.Feed, Surface.Insights),
                    ),
                )
            } else if (pct <= MONTH_DOWN_PCT) {
                out.add(
                    Signal(
                        kind = "month_down",
                        category = Category.Insight,
                        score = 40.0,
                        title = "A harder stretch lately",
                        body = "Your average is down ${abs(pct).roundToInt()}% from the period before. That happens — be kind to yourself.",
                        action = Action.OpenSupport,
                        dismissible = true,
                        surfaces = setOf(Surface.Insights),
                    ),
                )
            }
        }

        // 9. A negative factor worth knowing -> informational, Insights only (kept off the feed so
        //    the feed stays gentle).
        inputs.topDrag?.let { drag ->
            if (drag.delta <= -LIFT_MIN_DELTA) {
                out.add(
                    Signal(
                        kind = "drag_factor",
                        category = Category.Insight,
                        score = 35.0 + (abs(drag.delta) * 15.0).coerceAtMost(14.0),
                        title = "\"${drag.name}\" tends to go with lower days",
                        body = "When \"${drag.name}\" shows up, your mood averaged lower (association, not cause).",
                        action = null,
                        dismissible = true,
                        surfaces = setOf(Surface.Insights),
                    ),
                )
            }
        }

        // 10. Support-screen options ("what might help"). These live only on the Support surface;
        //     a couple are contextual (a movement nudge appears when movement is a known lift).
        out.addAll(supportMenu(inputs.topLift))

        return out.sortedWith(compareByDescending<Signal> { it.score }.thenBy { it.kind })
    }

    /**
     * The fixed menu of supportive options for the "what might help" screen, lightly contextual on
     * [topLift] (a movement nudge ranks first and personalises its copy when movement-like activity
     * is a known lift). Always available — independent of how much has been logged — so the support
     * space is never empty. Sorted best-first.
     */
    fun supportMenu(topLift: FactorLift?): List<Signal> {
        val s = ArrayList<Signal>()
        val sup = setOf(Surface.Support)
        // A movement nudge ranks first when movement-like activity is a known lift for this person.
        if (topLift != null && topLift.delta >= LIFT_MIN_DELTA) {
            s.add(Signal("support_move", Category.Support, 64.0,
                "Move a little",
                "A short, gentle stretch or a few minutes of \"${topLift.name}\" — no pressure.",
                Action.OpenMovement, false, sup))
        } else {
            s.add(Signal("support_move", Category.Support, 56.0,
                "Move a little", "A short, gentle stretch.", Action.OpenMovement, false, sup))
        }
        s.add(Signal("support_breathe", Category.Support, 60.0,
            "Breathe with me", "A minute of slow, paced breathing.", Action.OpenBreathing, false, sup))
        s.add(Signal("support_thought", Category.Support, 52.0,
            "Untangle a thought", "Look at a tough thought, gently.", Action.OpenThoughtRecord, false, sup))
        s.add(Signal("support_journal", Category.Support, 50.0,
            "Write it out", "Put what's on your mind into words.", Action.OpenJournal, false, sup))
        // Crisis resources are always available, listed last, never dismissible.
        s.add(Signal("support_crisis", Category.Support, 10.0,
            "I could use more support", "Crisis resources and someone to reach.",
            Action.OpenCrisisResources, false, sup))
        return s
    }

    /**
     * Selects the signals eligible for [surface], best first, capped at [limit]. The feed defaults
     * to a small, calm number; pass a larger [limit] for the Insights page.
     */
    fun forSurface(signals: List<Signal>, surface: Surface, limit: Int = Int.MAX_VALUE): List<Signal> =
        signals.asSequence()
            .filter { surface in it.surfaces }
            .take(limit)
            .toList()
}

/* ==============================================================================================
 * Below this line: a second, unrelated vocabulary that happens to share the word "signal".
 *
 * [Signals] above is the card engine — what to *surface* on Home, Insights and the support space.
 * [CompanionSignals] below is the closed eight-fact substrate the companion dialogue *branches on*
 * (docs/COMPANION_DIALOGUE.md). They share no types and neither reads the other. Kept in one file
 * only because the file is named for the word; if that ever reads as a suggestion that they are the
 * same layer, split it — nothing depends on them being co-located.
 * ============================================================================================*/

/**
 * The companion signal vocabulary, device side — the twin of
 * `companion/web/src/lib/companion/signals.ts`.
 *
 * The same eight definitions run on both sides of the product: the web side owns authoring
 * (which signals a clinician may branch on, and the message when they may not), and this side
 * computes the values a dialogue is then evaluated against. **The names, the types and the author
 * partition here must match that file exactly** — they are one vocabulary, not two that resemble
 * each other, and `CompanionSignalsTest` pins every one of them so drift fails a build rather than
 * a person's evening.
 *
 * ## What this is for
 *
 * `docs/COMPANION_DIALOGUE.md` — "The signal vocabulary". These eight facts are the whole substrate
 * a dialogue may branch on. The list is CLOSED on purpose: every signal is a coupling point, and
 * the closed list is what keeps the companion from becoming the beast `docs/DECISIONS_2026-08.md`
 * §D1 warns about. A ninth is a design decision, not a patch, and the test asserting the count is
 * there so that argument cannot be skipped.
 *
 * ## What it is not
 *
 * **Nothing here infers anything about a person, and nothing here is named for a state.**
 * [Values.hardDaysLast7] is a count of days the person logged a check-in in one of the lower bands
 * of the scale *they* picked from — their own data handed back, never a judgement about them
 * (§D1b, reflect-never-label). There is no threshold in this file above which someone becomes a
 * category, because there is no category. A test walks this object's members and fails on any name
 * that reads as a clinical label, since that is the shape the drift would take.
 *
 * **Nothing here can make the app ask more.** These are read-only facts. The decision to interrupt
 * belongs to [InterruptionBudget], whose response to falling reception is monotonic and
 * one-directional (§D1a); computing a value here never widens anyone's budget.
 *
 * **Nothing here is a streak.** [Values.checkInsLast7] counts non-consecutively, which is the form
 * §D6 permits: there is no run to break and nothing to lose.
 *
 * ## Purity
 *
 * Free of Android and Room types like the rest of `stats/`. The caller does the DAO reads, maps
 * them to [Inputs], and owns the clock — so this unit-tests on the JVM with a fixed [Clock] and has
 * no way to reach the person's data on its own.
 */
object CompanionSignals {

    /**
     * Bumped when a signal is added, removed or renamed, and equal to `SIGNAL_VOCABULARY_VERSION`
     * in `signals.ts` — a definition records the version it was authored against so drift fails
     * loudly rather than silently changing behaviour.
     */
    const val VOCABULARY_VERSION: Int = 1

    /** Who may author a dialogue definition, and therefore who may branch on a signal. */
    enum class Author(val key: String) {
        APP("app"),
        THERAPIST("therapist"),
        ;

        companion object {
            /** Unknown roles resolve to null, never to a permissive default. */
            fun fromKey(key: String?): Author? = entries.firstOrNull { it.key == key }
        }
    }

    /** Shape of the value the host supplies for a signal. */
    enum class Type(val key: String) {
        INT("int"),
        BOOL("bool"),
        ENUM("enum"),
        STRING_ARRAY("stringArray"),
    }

    /**
     * The closed vocabulary. Exactly eight, in the declaration order of `signals.ts`.
     *
     * [authors] is Finding 3's partition: predicates evaluate privately on the device, but the arms
     * of a branch differ in ways a therapist CAN observe (a module delivered, accepted, completed),
     * so an authored branch is an oracle for whatever it tested. Hence the rule — *a predicate may
     * only reference signals its author is already permitted to see.* Therapist-authored dialogue
     * gets [PRESCRIBED_MODULES] (they issued them) and [TIME_OF_DAY] (carries nothing private).
     *
     * [neverDisclosable] is stronger than app-only: app-only is a partition, this is a prohibition
     * that outlives any future widening of that partition by consent.
     */
    enum class Name(
        val key: String,
        val type: Type,
        val authors: Set<Author>,
        val neverDisclosable: Boolean = false,
    ) {
        DAYS_SINCE_LAST_OPEN("daysSinceLastOpen", Type.INT, setOf(Author.APP)),
        DAYS_SINCE_LAST_CHECK_IN("daysSinceLastCheckIn", Type.INT, setOf(Author.APP)),
        CHECK_INS_LAST_7("checkInsLast7", Type.INT, setOf(Author.APP)),
        HARD_DAYS_LAST_7("hardDaysLast7", Type.INT, setOf(Author.APP)),
        HAS_SAFETY_PLAN("hasSafetyPlan", Type.BOOL, setOf(Author.APP), neverDisclosable = true),
        PRESCRIBED_MODULES("prescribedModules", Type.STRING_ARRAY, setOf(Author.APP, Author.THERAPIST)),
        LAST_OFFER_OUTCOME("lastOfferOutcome", Type.ENUM, setOf(Author.APP)),
        TIME_OF_DAY("timeOfDay", Type.ENUM, setOf(Author.APP, Author.THERAPIST)),
        ;

        companion object {
            /** Unknown keys (older content, a typo, a renamed signal) resolve to null. */
            fun fromKey(key: String?): Name? = entries.firstOrNull { it.key == key }
        }
    }

    /** The signals [author] may branch on, in declaration order. */
    fun namesFor(author: Author): List<Name> = Name.entries.filter { author in it.authors }

    /**
     * Whether [author] may branch on [name] — the device-side half of Finding 1's "do both". The
     * web side rejects a bad reference at authoring time with a message someone can act on; this
     * catches content that drifted past it anyway, and it fails closed by construction because an
     * unknown [Name] cannot be constructed.
     */
    fun mayBranchOn(name: Name, author: Author): Boolean = author in name.authors

    /**
     * The values `timeOfDay` may take, matching `TIME_OF_DAY` in `companion/content.ts`. Coarse on
     * purpose: an exact clock reading is a behavioural trace; four buckets are a greeting.
     */
    enum class TimeOfDay(val key: String) {
        MORNING("morning"),
        DAY("day"),
        EVENING("evening"),
        NIGHT("night"),
        ;

        companion object {
            /**
             * @param hour hour-of-day 0..23 in the person's own time zone.
             *
             * The bands are [Greeting]'s, deliberately: the companion saying "evening" while the
             * header says "Good afternoon" is the app disagreeing with itself in front of someone.
             * The late-night band runs to 5am for the same reason it does there — 3am is not
             * "night, still" as an observation about the person, it is just what the clock says.
             */
            fun forHour(hour: Int): TimeOfDay = when (hour) {
                in 5..11 -> MORNING
                in 12..16 -> DAY
                in 17..21 -> EVENING
                else -> NIGHT
            }
        }
    }

    /**
     * The values `lastOfferOutcome` may take — the reception ledger's `outcome` column, and the
     * same four keys as `LAST_OFFER_OUTCOME` in `companion/content.ts`.
     *
     * Spelled out rather than read from `data.entity.OfferOutcome` so this file stays free of Room
     * types; `CompanionSignalsTest` asserts the two lists are identical, which is the check that
     * would otherwise be a comment nobody runs.
     */
    val OFFER_OUTCOME_KEYS: List<String> = listOf("accepted", "dismissed", "snoozed", "stop")

    /**
     * Mood levels at or below this are the lower bands of the fixed 1..5 scale — Awful and Bad, the
     * words the person picked from (`model/Mood.kt`). The same boundary the support action uses.
     *
     * It is a boundary on a scale, not a boundary on a person: what is above and below it are two
     * groups of *entries*, and nothing in this file joins them up into a statement about anyone.
     */
    const val LOWER_BAND_MAX_LEVEL: Int = 2

    /** The window "last 7" means: today and the six days before it, in the person's own zone. */
    const val WINDOW_DAYS: Int = 7

    /** One check-in, reduced to the two fields the vocabulary needs. */
    data class CheckIn(
        /** Epoch millis of the moment the entry is *for* (`MoodEntry.dateTime`). */
        val atMillis: Long,
        /** 1..5, worst to best (`model/Mood.kt`). A level outside that range counts as neither. */
        val moodLevel: Int,
    )

    /**
     * Everything the eight signals are derived from, already read. The caller owns the DAOs; this
     * is the whole of what it has to hand over, and it is deliberately less than the app knows.
     */
    data class Inputs(
        /** When the person last opened Daymark. Null — or 0, the "never" sentinel — on a new install. */
        val lastOpenedAtMillis: Long? = null,
        /** Their check-ins. Any order; only the last [WINDOW_DAYS] days and the newest one are read. */
        val checkIns: List<CheckIn> = emptyList(),
        /** Whether they have written a safety plan of their own. Not whether they need one. */
        val hasSafetyPlan: Boolean = false,
        /** Ids of modules currently assigned to them *and accepted by them*. */
        val prescribedModuleIds: List<String> = emptyList(),
        /** The `outcome` of the newest reception-ledger row, or null when nothing has been offered. */
        val lastOfferOutcomeKey: String? = null,
    )

    /**
     * The eight values, computed.
     *
     * A null is "there is no such fact yet", which is an ordinary state and not an error: on a new
     * install nobody has ever opened the app before now and nobody has checked in. It is kept
     * distinct from zero because they say opposite things — "0 days since" is *today*, and telling
     * someone on their first evening that it has been a while would be both wrong and unkind.
     */
    data class Values(
        /** Whole days since the person last opened Daymark. Null when they never have. */
        val daysSinceLastOpen: Int?,
        /** Whole days since the person last recorded a check-in. Null when there are none. */
        val daysSinceLastCheckIn: Int?,
        /** How many check-ins they recorded in the last [WINDOW_DAYS] days. Not consecutive. */
        val checkInsLast7: Int,
        /**
         * How many of the last [WINDOW_DAYS] days carry a check-in they logged in a lower mood
         * band. 0..[WINDOW_DAYS], because it counts days and not entries — three entries on one
         * day are one day. A count of what they logged, never a judgement about them.
         */
        val hardDaysLast7: Int,
        /** Whether they have written a safety plan of their own. */
        val hasSafetyPlan: Boolean,
        /** Ids of the modules assigned to them and accepted by them. */
        val prescribedModules: List<String>,
        /** How they answered the last thing the app offered. Null when nothing has been offered. */
        val lastOfferOutcome: String?,
        /** Coarse part of the day on the device clock. Always present; carries nothing private. */
        val timeOfDay: TimeOfDay,
    ) {
        /**
         * The pseudo-answers a dialogue is evaluated against, keyed by [Name.key] — the bridge
         * described in `docs/COMPANION_DIALOGUE.md`: `Answers` is a plain map, so a signal is
         * injected as an answer and `{ ref: 'hardDaysLast7', op: 'gte', value: 3 }` evaluates with
         * no engine changes.
         *
         * **A signal with no value is omitted, not defaulted.** The evaluator fails an absent ref
         * closed, so an omission drifts the dialogue toward its fallback line — the most ordinary
         * thing to say — where a stand-in number would silently answer a question about a person
         * that nobody has the answer to.
         *
         * [hasSafetyPlan] goes in as a real Boolean. The authored predicate asks `gte 1` because
         * the DSL has no boolean literal and its evaluator coerces numerically, so both a boolean
         * and a 0/1 host read correctly; the failure this avoids is in the direction of not showing
         * someone the plan they wrote for themselves.
         */
        fun asAnswers(): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            daysSinceLastOpen?.let { out[Name.DAYS_SINCE_LAST_OPEN.key] = it }
            daysSinceLastCheckIn?.let { out[Name.DAYS_SINCE_LAST_CHECK_IN.key] = it }
            out[Name.CHECK_INS_LAST_7.key] = checkInsLast7
            out[Name.HARD_DAYS_LAST_7.key] = hardDaysLast7
            out[Name.HAS_SAFETY_PLAN.key] = hasSafetyPlan
            out[Name.PRESCRIBED_MODULES.key] = prescribedModules
            lastOfferOutcome?.let { out[Name.LAST_OFFER_OUTCOME.key] = it }
            out[Name.TIME_OF_DAY.key] = timeOfDay.key
            return out
        }
    }

    /**
     * Compute all eight from [inputs] and [clock], which supplies both the instant and the person's
     * time zone. Total: there is no input for which this throws, because a new install and a device
     * whose clock is wrong are both ordinary rather than exceptional.
     *
     * **A stored timestamp later than now reads as now.** Clocks go backwards — a time-zone change,
     * a manual edit, a restored backup — and the alternatives are worse than clamping: a negative
     * "days since" is a number no copy is written for, and dropping the entry would quietly
     * discount something the person logged. This is the same treatment `SupportOffer` gives a
     * future `lastOfferedAt`, and it errs toward the app being quieter rather than louder.
     */
    fun compute(inputs: Inputs, clock: Clock): Values {
        val zone = clock.zone
        val nowMillis = clock.millis()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate()
        val windowStart = today.minusDays((WINDOW_DAYS - 1).toLong())

        // Clamped, so a future timestamp reads as today rather than as a negative age.
        fun dayOf(millis: Long): LocalDate =
            Instant.ofEpochMilli(minOf(millis, nowMillis)).atZone(zone).toLocalDate()

        // Coerced because this number ends up inside a sentence someone reads. The clamp above
        // already makes it non-negative; the range guard is for the absurd stored value — a
        // corrupt row, a restored backup — that would otherwise overflow Int and come back as a
        // number with the wrong sign entirely.
        fun daysSince(millis: Long): Int =
            ChronoUnit.DAYS.between(dayOf(millis), today).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        val lastOpenedAt = inputs.lastOpenedAtMillis?.takeIf { it > 0L }
        val lastCheckInAt = inputs.checkIns.maxOfOrNull { it.atMillis }

        val inWindow = inputs.checkIns.filter { dayOf(it.atMillis) >= windowStart }
        val lowerBandDays = inWindow
            .filter { it.moodLevel in 1..LOWER_BAND_MAX_LEVEL }
            .map { dayOf(it.atMillis) }
            .toSet()

        return Values(
            daysSinceLastOpen = lastOpenedAt?.let { daysSince(it) },
            daysSinceLastCheckIn = lastCheckInAt?.let { daysSince(it) },
            checkInsLast7 = inWindow.size,
            hardDaysLast7 = lowerBandDays.size,
            hasSafetyPlan = inputs.hasSafetyPlan,
            // Distinct and blank-free: a duplicate id would let `includes` be true twice over and a
            // blank one is not a module, and neither is worth a branch behaving oddly over.
            prescribedModules = inputs.prescribedModuleIds.filter { it.isNotBlank() }.distinct(),
            // An outcome key this version does not recognise is dropped rather than passed through,
            // so a predicate on it fails closed instead of comparing against a string nobody wrote.
            lastOfferOutcome = inputs.lastOfferOutcomeKey?.takeIf { it in OFFER_OUTCOME_KEYS },
            timeOfDay = TimeOfDay.forHour(now.hour),
        )
    }
}
