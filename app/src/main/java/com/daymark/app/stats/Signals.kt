package com.daymark.app.stats

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
