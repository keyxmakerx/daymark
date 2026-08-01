package com.daymark.app.stats

/**
 * The dial on every suggestion.
 *
 * [Signals] decides what *could* be worth surfacing; this decides what the person has said they
 * want to see. Four fixed, reversible controls — show less, remind later, hide, turn off — applied
 * by rules over stored numbers. Nothing is learned, inferred, or scored by a model: "show less"
 * subtracts a constant, snoozes have fixed lengths, and turning something back on clears every
 * trace so nothing lingers invisibly.
 *
 * Controls are keyed by **group**, not by raw signal kind, so the choices read as plain English
 * ("Self-check reminders") and so turning one thing off silences everything that thing says.
 *
 * Pure and Android-free like the rest of `stats/`: the caller owns persistence and the clock.
 */
object SuggestionControls {

    /** A user-facing family of suggestions, and the [Signals] kinds it covers. */
    data class Group(
        val key: String,
        val title: String,
        val subtitle: String?,
        val kinds: Set<String>,
    )

    /**
     * Every controllable suggestion. The **support menu** on the "what might help" screen is
     * deliberately absent: you open that screen on purpose, so it is never a suggestion pushed at
     * you and there is nothing there to turn off. Crisis resources stay reachable regardless of
     * anything set here.
     */
    val GROUPS: List<Group> = listOf(
        Group(
            key = "check_in",
            title = "Check-in nudges",
            subtitle = "when you haven't logged today",
            kinds = setOf("prompt_log_today"),
        ),
        Group(
            key = "support",
            title = "Support offers",
            subtitle = "after a hard day",
            kinds = setOf("support_offer"),
        ),
        Group(
            key = "milestones",
            title = "Streaks and milestones",
            subtitle = null,
            kinds = setOf("streak_milestone", "achievement_unlocked"),
        ),
        Group(
            key = "self_checks",
            title = "Self-check reminders",
            subtitle = "PHQ-9, GAD-7, WHO-5",
            kinds = setOf("checkin_due"),
        ),
        Group(
            key = "patterns",
            title = "What goes with your mood",
            subtitle = "association, not cause",
            kinds = setOf("lift_factor", "drag_factor"),
        ),
        Group(
            key = "periods",
            title = "Month-to-month changes",
            subtitle = null,
            kinds = setOf("month_up", "month_down"),
        ),
        Group(
            key = "memories",
            title = "On this day",
            subtitle = "what you logged a year ago",
            kinds = setOf("on_this_day"),
        ),
    )

    private val groupKeyByKind: Map<String, String> =
        GROUPS.flatMap { group -> group.kinds.map { it to group.key } }.toMap()

    /** The group a signal kind belongs to, or null if it isn't user-controllable. */
    fun groupKeyOf(kind: String): String? = groupKeyByKind[kind]

    fun groupOf(kind: String): Group? = groupKeyOf(kind)?.let { key -> GROUPS.firstOrNull { it.key == key } }

    /** One group's stored state. All-defaults means "on, never touched". */
    data class State(
        val off: Boolean = false,
        /** Epoch millis before which this group stays quiet; 0 = not snoozed. */
        val snoozedUntil: Long = 0L,
        /** How many times "show less" was used; each step costs [DAMPING_STEP] of score. */
        val damping: Int = 0,
    ) {
        fun isSnoozed(nowMillis: Long): Boolean = snoozedUntil > nowMillis

        /** True when nothing has been changed from the default — used to skip persisting it. */
        val isDefault: Boolean get() = !off && snoozedUntil == 0L && damping == 0
    }

    data class Controls(val byGroup: Map<String, State> = emptyMap()) {
        fun stateOf(groupKey: String): State = byGroup[groupKey] ?: State()
    }

    /** What a card's overflow menu can do. A closed set, so every outcome is reviewable. */
    enum class Action { ShowLess, RemindLater, Hide, TurnOff }

    /** Score subtracted per "show less" step. Demotes a card without silencing it outright. */
    const val DAMPING_STEP = 12.0

    /** "Show less" stops mattering more after this many steps — use "turn off" if you mean off. */
    const val MAX_DAMPING = 3

    private const val HOUR_MILLIS = 3_600_000L
    private const val DAY_MILLIS = 24 * HOUR_MILLIS

    const val REMIND_LATER_MILLIS = 4 * HOUR_MILLIS
    const val SHOW_LESS_MILLIS = 3 * DAY_MILLIS
    const val HIDE_MILLIS = 30 * DAY_MILLIS

    /** Applies one menu [action] to a group's [state]. Snoozes never shorten an existing one. */
    fun apply(state: State, action: Action, nowMillis: Long): State = when (action) {
        Action.ShowLess -> state.copy(
            damping = (state.damping + 1).coerceAtMost(MAX_DAMPING),
            snoozedUntil = maxOf(state.snoozedUntil, nowMillis + SHOW_LESS_MILLIS),
        )
        Action.RemindLater -> state.copy(
            snoozedUntil = maxOf(state.snoozedUntil, nowMillis + REMIND_LATER_MILLIS),
        )
        Action.Hide -> state.copy(
            snoozedUntil = maxOf(state.snoozedUntil, nowMillis + HIDE_MILLIS),
        )
        Action.TurnOff -> state.copy(off = true)
    }

    /**
     * Turning a group back on clears everything holding it back — the off flag, any snooze, and
     * any accumulated "show less". Otherwise a person could turn something on and still not see
     * it, with nothing on screen explaining why.
     */
    fun turnOn(): State = State()

    /**
     * Filters [signals] down to what the person still wants, demotes anything they've asked to see
     * less of, and re-ranks. Signals outside [GROUPS] (the support menu) pass through untouched.
     */
    fun filter(
        signals: List<Signals.Signal>,
        controls: Controls,
        nowMillis: Long,
    ): List<Signals.Signal> = signals
        .mapNotNull { signal ->
            val groupKey = groupKeyOf(signal.kind) ?: return@mapNotNull signal
            val state = controls.stateOf(groupKey)
            when {
                state.off || state.isSnoozed(nowMillis) -> null
                state.damping > 0 -> signal.copy(score = signal.score - state.damping * DAMPING_STEP)
                else -> signal
            }
        }
        .sortedWith(compareByDescending<Signals.Signal> { it.score }.thenBy { it.kind })

    /**
     * Fixed wording for when a snoozed group comes back, e.g. "back in 2h" — or null when it isn't
     * snoozed. Rounds up, so it never promises a card sooner than it will actually appear.
     */
    fun snoozeSummary(state: State, nowMillis: Long): String? {
        if (!state.isSnoozed(nowMillis)) return null
        val remaining = state.snoozedUntil - nowMillis
        return when {
            remaining <= HOUR_MILLIS -> "back within the hour"
            remaining < DAY_MILLIS -> "back in ${ceilDiv(remaining, HOUR_MILLIS)}h"
            remaining <= DAY_MILLIS * 2 -> "back tomorrow"
            else -> "back in ${ceilDiv(remaining, DAY_MILLIS)} days"
        }
    }

    private fun ceilDiv(value: Long, unit: Long): Long = (value + unit - 1) / unit
}
