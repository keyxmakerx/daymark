package com.daymark.app.ui.assessments

import com.daymark.app.ui.sleep.Band
import com.daymark.app.ui.sleep.Screener
import com.daymark.app.ui.sleep.ScreenerOption
import com.daymark.app.ui.sleep.ScreenerQuestion

/**
 * Bundled, free-to-reproduce wellbeing questionnaires. These are the ONLY place the app reproduces
 * standardized instrument wording verbatim — it is legally clear to do so for these three:
 *  - PHQ-9 and GAD-7: developed by Drs Spitzer, Williams, Kroenke & colleagues; Pfizer states no
 *    permission is required to reproduce, translate, display or distribute them.
 *  - WHO-5 Well-Being Index: © World Health Organization; free to use with attribution for
 *    non-commercial purposes.
 * Every other questionnaire in the app stays self-authored. Do NOT add licensed instruments here
 * (ISI, Epworth/ESS, STOP-Bang, PSQI, PANAS, WEMWBS, or ACT measures all require a license).
 *
 * Results are scores over time, framed strictly as non-diagnostic self-checks. PHQ-9 item 9
 * (thoughts of self-harm) gets special, gentle handling in the UI — never a risk "verdict".
 */
object Assessments {

    private const val NOT_DX =
        "This is a self-check, not a diagnosis. Scores can change day to day; what matters most is " +
            "the trend over time, ideally discussed with a clinician."

    /** Index of the self-harm item in PHQ-9 (0-based) — surfaces crisis resources in the UI. */
    const val PHQ9_KEY = "phq9"
    const val PHQ9_SELF_HARM_INDEX = 8

    private val FREQ_0_3 = listOf(
        ScreenerOption("Not at all", 0),
        ScreenerOption("Several days", 1),
        ScreenerOption("More than half the days", 2),
        ScreenerOption("Nearly every day", 3),
    )

    val PHQ9 = Screener(
        key = PHQ9_KEY,
        title = "PHQ-9 (mood)",
        subtitle = "Depressive symptoms over 2 weeks",
        intro = "Over the last 2 weeks, how often have you been bothered by any of the following problems?",
        citation = "PHQ-9 — Spitzer, Williams, Kroenke & colleagues. Free to reproduce (Pfizer).",
        questions = listOf(
            "Little interest or pleasure in doing things",
            "Feeling down, depressed, or hopeless",
            "Trouble falling or staying asleep, or sleeping too much",
            "Feeling tired or having little energy",
            "Poor appetite or overeating",
            "Feeling bad about yourself — or that you are a failure or have let yourself or your family down",
            "Trouble concentrating on things, such as reading the newspaper or watching television",
            "Moving or speaking so slowly that other people could have noticed? Or the opposite — being so " +
                "fidgety or restless that you have been moving around a lot more than usual",
            "Thoughts that you would be better off dead, or of hurting yourself in some way",
        ).map { ScreenerQuestion(it, FREQ_0_3) },
        bands = listOf(
            Band(0, "Minimal", "Scores in this range ($NOT_DX)"),
            Band(5, "Mild", "A mild range. $NOT_DX"),
            Band(10, "Moderate", "A moderate range — many people find it helpful to talk this over with a clinician. $NOT_DX"),
            Band(15, "Moderately high", "A moderately high range — consider reaching out to a clinician. $NOT_DX"),
            Band(20, "High", "A high range — please consider talking with a clinician or a support line. $NOT_DX"),
        ),
    )

    val GAD7 = Screener(
        key = "gad7",
        title = "GAD-7 (anxiety)",
        subtitle = "Anxiety symptoms over 2 weeks",
        intro = "Over the last 2 weeks, how often have you been bothered by the following problems?",
        citation = "GAD-7 — Spitzer, Kroenke, Williams, Löwe. Free to reproduce (Pfizer).",
        questions = listOf(
            "Feeling nervous, anxious, or on edge",
            "Not being able to stop or control worrying",
            "Worrying too much about different things",
            "Trouble relaxing",
            "Being so restless that it is hard to sit still",
            "Becoming easily annoyed or irritable",
            "Feeling afraid, as if something awful might happen",
        ).map { ScreenerQuestion(it, FREQ_0_3) },
        bands = listOf(
            Band(0, "Minimal", "A minimal range. $NOT_DX"),
            Band(5, "Mild", "A mild range. $NOT_DX"),
            Band(10, "Moderate", "A moderate range — talking with a clinician may help. $NOT_DX"),
            Band(15, "High", "A high range — consider reaching out to a clinician or support line. $NOT_DX"),
        ),
    )

    private val WHO5_OPTIONS = listOf(
        ScreenerOption("All of the time", 5),
        ScreenerOption("Most of the time", 4),
        ScreenerOption("More than half the time", 3),
        ScreenerOption("Less than half the time", 2),
        ScreenerOption("Some of the time", 1),
        ScreenerOption("At no time", 0),
    )

    const val WHO5_KEY = "who5"

    val WHO5 = Screener(
        key = WHO5_KEY,
        title = "WHO-5 (wellbeing)",
        subtitle = "Positive wellbeing over 2 weeks",
        intro = "Please say, for each of the five statements, which is closest to how you have been " +
            "feeling over the last two weeks. (Higher scores mean better wellbeing.)",
        citation = "WHO-5 Well-Being Index — © World Health Organization. Free for non-commercial use.",
        questions = listOf(
            "I have felt cheerful and in good spirits",
            "I have felt calm and relaxed",
            "I have felt active and vigorous",
            "I woke up feeling fresh and rested",
            "My daily life has been filled with things that interest me",
        ).map { ScreenerQuestion(it, WHO5_OPTIONS) },
        // Raw 0–25 (×4 = 0–100). Lower = lower wellbeing.
        bands = listOf(
            Band(0, "Low wellbeing", "A lower range — it may be worth a wellbeing check-in or talking with a clinician. $NOT_DX"),
            Band(14, "Moderate–good wellbeing", "A healthier range. $NOT_DX"),
        ),
    )

    val ALL = listOf(PHQ9, GAD7, WHO5)

    fun byKey(key: String): Screener? = ALL.firstOrNull { it.key == key }

    /** WHO-5 is reported as a 0–100 percentage (raw × 4). */
    fun displayScore(key: String, rawScore: Int): String =
        if (key == WHO5_KEY) "${rawScore * 4} / 100" else rawScore.toString()
}
