package com.daymark.app.ui.sleep

/**
 * Self-authored, license-clean sleep self-checks ("screeners"). These are NOT validated
 * clinical instruments and NOT a diagnosis — they collect well-known, non-proprietary risk
 * factors / symptoms in our own wording (e.g. apnea risk factors rather than the licensed
 * STOP-Bang text; the Ferri single-question idea for restless legs). Every result is framed as
 * "signs worth discussing with a clinician," and a low result NEVER says "you're fine."
 */

data class ScreenerOption(val label: String, val points: Int)

data class ScreenerQuestion(val text: String, val options: List<ScreenerOption>)

/** A scoring band. The highest band whose [minScore] is met applies. */
data class Band(val minScore: Int, val label: String, val message: String)

data class Screener(
    val key: String,
    val title: String,
    val subtitle: String,
    val intro: String,
    val questions: List<ScreenerQuestion>,
    val bands: List<Band>,
    /** Optional source/attribution line (used by bundled validated questionnaires). */
    val citation: String = "",
) {
    fun bandFor(score: Int): Band = bands.last { score >= it.minScore }
}

object SleepScreeners {

    private const val NOT_DX =
        "This is a self-check, not a diagnosis — it can't rule anything in or out. " +
            "If you're concerned about your sleep, a clinician can help."

    private fun yn(extraNotSure: Boolean = false): List<ScreenerOption> = buildList {
        add(ScreenerOption("No", 0))
        if (extraNotSure) add(ScreenerOption("Not sure", 1))
        add(ScreenerOption("Yes", if (extraNotSure) 2 else 1))
    }

    val APNEA = Screener(
        key = "apnea",
        title = "Sleep apnea signs",
        subtitle = "Breathing-related risk factors",
        intro = "A few questions about signs commonly linked to sleep apnea. Some of the strongest " +
            "signs (like someone noticing you stop breathing) don't involve snoring at all.",
        questions = listOf(
            ScreenerQuestion(
                "Has anyone ever noticed you stop breathing, gasp, or choke during sleep?",
                listOf(ScreenerOption("No", 0), ScreenerOption("Not sure", 1), ScreenerOption("Yes", 2)),
            ),
            ScreenerQuestion(
                "Do you snore loudly — louder than talking, or audible through a closed door?",
                yn(),
            ),
            ScreenerQuestion(
                "Do you often feel sleepy or worn out during the day, even after enough time in bed?",
                listOf(ScreenerOption("No", 0), ScreenerOption("Sometimes", 1), ScreenerOption("Often", 2)),
            ),
            ScreenerQuestion(
                "Do you have high blood pressure (or take medication for it)?",
                yn(),
            ),
            ScreenerQuestion("Are you over 50?", yn()),
            ScreenerQuestion(
                "Is your collar / neck size large (about 16 in / 41 cm or more)?",
                yn(extraNotSure = false),
            ),
        ),
        bands = listOf(
            Band(0, "Few signs today", "We didn't pick up many of the common signs today. $NOT_DX"),
            Band(3, "Some signs", "You noted some signs associated with sleep apnea. $NOT_DX"),
            Band(5, "Several signs", "You noted several signs associated with sleep apnea — " +
                "especially worth raising with a doctor or asking about a sleep study. $NOT_DX"),
        ),
    )

    val RLS = Screener(
        key = "rls",
        title = "Restless legs",
        subtitle = "The hallmark evening symptom",
        intro = "One main question — it captures the hallmark feeling of restless legs syndrome.",
        questions = listOf(
            ScreenerQuestion(
                "When you relax in the evening or try to sleep, do you get unpleasant, restless " +
                    "feelings in your legs that ease when you move or walk?",
                listOf(ScreenerOption("No", 0), ScreenerOption("Sometimes", 1), ScreenerOption("Yes, often", 2)),
            ),
            ScreenerQuestion(
                "Do these feelings make it harder to fall or stay asleep?",
                yn(),
            ),
        ),
        bands = listOf(
            Band(0, "Hallmark sign not noted", "We didn't note the hallmark feeling today. $NOT_DX"),
            Band(2, "Hallmark sign present", "That restless, movement-relieved leg feeling is the " +
                "hallmark of restless legs syndrome — worth mentioning to a clinician. $NOT_DX"),
        ),
    )

    val INSOMNIA = Screener(
        key = "insomnia",
        title = "Insomnia signs",
        subtitle = "Falling & staying asleep",
        intro = "A short check on how your sleep has been over roughly the last month.",
        questions = listOf(
            ScreenerQuestion(
                "How long does it take you to fall asleep?",
                listOf(
                    ScreenerOption("No problem", 0), ScreenerOption("Slightly delayed", 1),
                    ScreenerOption("Quite delayed", 2), ScreenerOption("Very delayed", 3),
                ),
            ),
            ScreenerQuestion(
                "Waking up during the night?",
                listOf(
                    ScreenerOption("None", 0), ScreenerOption("Minor", 1),
                    ScreenerOption("Considerable", 2), ScreenerOption("Serious", 3),
                ),
            ),
            ScreenerQuestion(
                "Waking earlier than you'd like?",
                listOf(
                    ScreenerOption("No", 0), ScreenerOption("A little", 1),
                    ScreenerOption("Somewhat", 2), ScreenerOption("A lot", 3),
                ),
            ),
            ScreenerQuestion(
                "Is your daytime functioning affected by poor sleep?",
                listOf(
                    ScreenerOption("Not at all", 0), ScreenerOption("A little", 1),
                    ScreenerOption("Somewhat", 2), ScreenerOption("A lot", 3),
                ),
            ),
            ScreenerQuestion(
                "How often has this happened lately?",
                listOf(
                    ScreenerOption("Rarely", 0), ScreenerOption("Some nights", 1), ScreenerOption("Most nights", 2),
                ),
            ),
        ),
        bands = listOf(
            Band(0, "Few signs today", "Your answers show few insomnia signs today. $NOT_DX"),
            Band(4, "Some signs", "Your answers show some signs of disrupted sleep. $NOT_DX"),
            Band(9, "Several signs", "Your answers show several signs consistent with insomnia — " +
                "if this keeps up, a clinician can help. $NOT_DX"),
        ),
    )

    val all: List<Screener> = listOf(APNEA, RLS, INSOMNIA)

    fun byKey(key: String): Screener? = all.firstOrNull { it.key == key }
}
