package com.daymark.app.ui.activation

/**
 * Behavioral activation: doing more meaningful or enjoyable activities to lift mood — one of the
 * best-supported self-help skills for low mood. Suggestions are our own, generic, and split into
 * "pleasure" and "mastery" (a sense of accomplishment). Framed as a skill, never as treatment.
 */
object BehavioralActivation {

    const val ENJOYMENT_TRACKER = "Enjoyment"
    const val MASTERY_TRACKER = "Mastery"

    const val INTRO =
        "Doing a little more of what matters — even small things — tends to lift mood over time. " +
            "Pick something small, do it, then note how it felt. A self-help skill, not treatment."

    data class Suggestion(val name: String, val kind: String)

    val SUGGESTIONS = listOf(
        Suggestion("Go for a short walk", "Pleasure"),
        Suggestion("Step outside for fresh air", "Pleasure"),
        Suggestion("Message a friend", "Pleasure"),
        Suggestion("Listen to a favourite song", "Pleasure"),
        Suggestion("Make a warm drink", "Pleasure"),
        Suggestion("Stretch for a few minutes", "Pleasure"),
        Suggestion("Tidy one small area", "Mastery"),
        Suggestion("Do one item on your list", "Mastery"),
        Suggestion("Cook something simple", "Mastery"),
        Suggestion("Water the plants", "Mastery"),
        Suggestion("Take a shower", "Mastery"),
        Suggestion("Reply to one message", "Mastery"),
    )
}
